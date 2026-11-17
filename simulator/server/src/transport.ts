// WebSocket transport — MVP implementation of the encapsulated Transport
// interface (specs/COMMS.md §2, §3).
//
// A single Node.js process hosts two WebSocket servers:
//   - robot channel      on ROBOT_PORT  (bidirectional: componentCommand in, componentSnapshot out)
//   - visualiser channel on VISUALISER_PORT (unidirectional out: worldSnapshot)
// The port implies who is talking — there is no clientType field in messages.
//
// NDJSON framing: one JSON object per \n-terminated line. Each connection
// buffers incoming bytes until a newline, then parses exactly one JSON object.
// Malformed JSON is logged and ignored — never crashes. A line exceeding
// MAX_LINE_LENGTH is logged and the connection is dropped so a peer that never
// sends a newline cannot grow the buffer without bound.
//
// Backpressure on the visualiser channel: per visualiser connection at most
// one unpublished snapshot is queued; a newer snapshot replaces it (drop
// oldest). Sends are non-blocking; the sim tick never waits on I/O.

import { IncomingMessage } from "http";
import { WebSocketServer, WebSocket } from "ws";
import {
  Channel,
  Handlers,
  IncomingEnvelope,
  OutgoingEnvelope,
  Transport,
  TransportOptions,
} from "./types";

function log(...args: unknown[]): void {
  const stamp = new Date().toISOString().slice(11, 19);
  console.log(`[ws-transport] ${stamp}`, ...args);
}

// Maximum characters retained for one inbound NDJSON line. Real commands are
// tiny; this cap only exists so a peer that streams bytes without a newline
// cannot grow our buffer without bound.
const MAX_LINE_LENGTH = 64 * 1024;

// Upper bound for shutdown(): a peer that ignores the WebSocket closing
// handshake must not be able to stall process exit.
const SHUTDOWN_TIMEOUT_MS = 2000;

const ALLOWED_ORIGIN_HOSTS = new Set([
  "localhost",
  "127.0.0.1",
  "::1",
  "[::1]", // WHATWG URL keeps the brackets on IPv6 hostnames
]);

function generateConnectionId(): string {
  return crypto.randomUUID();
}

// Non-browser clients (the Java robot framework) send no Origin header at all
// and stay allowed. An explicit `Origin: null` is rejected, as is any scheme
// other than http/https or a hostname that is not localhost/loopback. This is
// defence in depth on top of the loopback socket bind.
function isAllowedOrigin(origin: string | undefined): boolean {
  if (origin === undefined) return true;
  if (!origin || origin === "null") return false;
  try {
    const url = new URL(origin);
    return (
      (url.protocol === "http:" || url.protocol === "https:") &&
      ALLOWED_ORIGIN_HOSTS.has(url.hostname)
    );
  } catch {
    return false;
  }
}

// Resolve once the underlying server has actually bound its port. Bind errors
// (e.g. EADDRINUSE) reject with the original Error so callers keep its `code`.
function waitForListening(server: WebSocketServer): Promise<void> {
  return new Promise((resolve, reject) => {
    const onListening = () => {
      server.off("error", onError);
      resolve();
    };
    const onError = (err: Error) => {
      server.off("listening", onListening);
      reject(err);
    };
    server.once("listening", onListening);
    server.once("error", onError);
  });
}

function isJsonObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

// Clone the payload one level so a queued envelope never aliases the caller's
// object. Combined with the fresh envelope in TransportConnection.send(), this
// lets broadcast() hand the same envelope to several connections without their
// per-connection seq values overwriting one another.
function freshPayload(payload: unknown): unknown {
  if (Array.isArray(payload)) return payload.slice();
  if (isJsonObject(payload)) return { ...payload };
  return payload;
}

function parseIncoming(line: string): IncomingEnvelope | null {
  let parsed: unknown;
  try {
    parsed = JSON.parse(line);
  } catch {
    log("malformed JSON line ignored:", line.slice(0, 120));
    return null;
  }
  if (!isJsonObject(parsed)) {
    log("non-object JSON line ignored:", line.slice(0, 120));
    return null;
  }
  const { type, seq, simTick, payload } = parsed;
  if (typeof type !== "string" || typeof seq !== "number") {
    log("envelope missing type/seq, ignored:", line.slice(0, 120));
    return null;
  }
  return { type, seq, simTick: typeof simTick === "number" ? simTick : undefined, payload: payload ?? {} };
}

// One raw WebSocket connection: NDJSON inbound buffer, depth-1 outbound slot
// (drop-oldest backpressure), per-connection outgoing sequence counter.
class TransportConnection {
  public readonly connectionId: string;
  public readonly channel: Channel;
  public readonly ws: WebSocket;

  private buffer = "";
  private outgoingSeq = 0;
  private pending: OutgoingEnvelope | null = null; // depth-1 drop-oldest slot
  private sending = false;
  private closed = false;
  private closing = false;

  public onLine?: (line: string) => void;
  public onClose?: () => void;

  constructor(ws: WebSocket, connectionId: string, channel: Channel) {
    this.ws = ws;
    this.connectionId = connectionId;
    this.channel = channel;

    ws.on("message", (data: Buffer | string) => {
      if (this.closed || this.closing) return;
      this.buffer += data.toString();
      // Check the length of every segment (including a trailing partial line)
      // before dispatching it, so a never-terminated line cannot grow forever.
      for (;;) {
        const idx = this.buffer.indexOf("\n");
        const segmentLength = idx < 0 ? this.buffer.length : idx;
        if (segmentLength > MAX_LINE_LENGTH) {
          this.dropForOversizedLine();
          return;
        }
        if (idx < 0) break;
        const line = this.buffer.slice(0, idx);
        this.buffer = this.buffer.slice(idx + 1);
        if (line.trim().length > 0 && this.onLine) {
          this.onLine(line);
        }
      }
    });

    ws.on("close", () => {
      if (this.closed) return;
      this.closed = true;
      this.onClose?.();
    });

    ws.on("error", (err: Error) => {
      log(`websocket error on ${channel}/${connectionId}:`, err.message);
      this.closeSocket();
    });
  }

  // Drop a peer that sent a line longer than MAX_LINE_LENGTH. `closing` stops
  // further message processing, but we deliberately leave `closed` false so
  // the 'close' event still reaches onClose (and safe-stops an active robot).
  private dropForOversizedLine(): void {
    if (this.closing || this.closed) return;
    this.closing = true;
    this.buffer = "";
    log(
      `oversized inbound line (max ${MAX_LINE_LENGTH} chars) on ` +
        `${this.channel}/${this.connectionId}; closing`,
    );
    this.terminateSocket();
  }

  closeSocket(): void {
    try {
      this.ws.close();
    } catch {
      // ignore
    }
  }

  // Hard-close: destroy the socket without waiting for a closing handshake.
  terminateSocket(): void {
    try {
      this.ws.terminate();
    } catch {
      // ignore
    }
  }

  // Send one envelope to this connection. Non-blocking, fire and forget.
  // Never mutate the caller's envelope: a clone (with a fresh payload) carries
  // this connection's own seq, so fan-out to N connections yields N independent
  // monotonic sequences.
  send(envelope: OutgoingEnvelope): void {
    if (this.closed || this.closing || this.ws.readyState !== WebSocket.OPEN) return;
    const outgoing: OutgoingEnvelope = {
      ...envelope,
      seq: this.outgoingSeq++,
      payload: freshPayload(envelope.payload),
    };
    this.pending = outgoing; // drop-oldest: newest replaces unpublished
    if (!this.sending) {
      this.flush();
    }
  }

  private flush(): void {
    if (this.closed || this.closing || !this.pending || this.sending) return;
    const envelope = this.pending;
    this.pending = null;
    this.sending = true;

    const line = JSON.stringify(envelope) + "\n";
    this.ws.send(line, (err: Error | undefined) => {
      this.sending = false;
      if (err) {
        log(`send failed to ${this.channel}/${this.connectionId}:`, err.message);
        this.closeSocket();
        return;
      }
      this.flush(); // send next pending snapshot if one arrived meanwhile
    });
  }
}

export class WebSocketTransport implements Transport {
  private robotServer: WebSocketServer | null = null;
  private visualiserServer: WebSocketServer | null = null;
  private connections = new Map<string, TransportConnection>();
  private handlers: Handlers | null = null;

  async listen(options: TransportOptions): Promise<void> {
    // Bind to IPv4 (0.0.0.0) so both IPv4-only clients (e.g. Java's
    // Socket.connect("localhost") which may not fall back from ::1) and
    // dual-stack clients can connect. Binding to "localhost" on macOS
    // results in IPv6-only listening, which breaks IPv4-only clients.
    this.robotServer = new WebSocketServer({ host: "0.0.0.0", port: options.robotPort });
    this.visualiserServer = new WebSocketServer({ host: "0.0.0.0", port: options.visualiserPort });

    this.robotServer.on("connection", (ws: WebSocket, request: IncomingMessage) => {
      if (!isAllowedOrigin(request.headers.origin)) {
        log("rejected websocket connection: origin not allowed");
        ws.close(1008, "origin not allowed");
        return;
      }
      this.accept(ws, "robot");
    });
    this.visualiserServer.on("connection", (ws: WebSocket, request: IncomingMessage) => {
      if (!isAllowedOrigin(request.headers.origin)) {
        log("rejected websocket connection: origin not allowed");
        ws.close(1008, "origin not allowed");
        return;
      }
      this.accept(ws, "visualiser");
    });

    // Log errors that happen after startup too; waitForListening installs a
    // short-lived 'error' listener for bind failures and rejects with the
    // original Error (preserving EADDRINUSE and friends) for main() to handle.
    this.robotServer.on("error", (err: Error) =>
      log("robot channel server error:", err.message)
    );
    this.visualiserServer.on("error", (err: Error) =>
      log("visualiser channel server error:", err.message)
    );

    const bound = await Promise.allSettled([
      waitForListening(this.robotServer),
      waitForListening(this.visualiserServer),
    ]);
    const failure = bound.find((result) => result.status === "rejected");
    if (failure) {
      // Do not leave a half-open transport behind if only one port bound.
      if (bound[0].status === "fulfilled") this.robotServer.close();
      if (bound[1].status === "fulfilled") this.visualiserServer.close();
      this.robotServer = null;
      this.visualiserServer = null;
      throw failure.reason;
    }

    log(
      `listening: robot channel on ws://localhost:${options.robotPort}, ` +
        `visualiser channel on ws://localhost:${options.visualiserPort}`
    );
  }

  setHandlers(handlers: Handlers): void {
    this.handlers = handlers;
  }

  private accept(ws: WebSocket, channel: Channel): void {
    const connectionId = generateConnectionId();
    const conn = new TransportConnection(ws, connectionId, channel);
    this.connections.set(connectionId, conn);

    conn.onLine = (line: string) => {
      const envelope = parseIncoming(line);
      if (envelope && this.handlers) {
        this.handlers.onMessage(connectionId, channel, envelope);
      }
    };

    conn.onClose = () => {
      this.connections.delete(connectionId);
      if (this.handlers) {
        this.handlers.onDisconnect(connectionId, channel);
      }
      log(`disconnected: ${channel}/${connectionId}`);
    };

    log(`connected: ${channel}/${connectionId}`);
    if (this.handlers) {
      this.handlers.onConnect(connectionId, channel);
    }
  }

  send(connectionId: string, envelope: OutgoingEnvelope): void {
    const conn = this.connections.get(connectionId);
    if (!conn) return; // already gone — never block the sim
    conn.send(envelope);
  }

  broadcast(channel: "visualiser", envelope: OutgoingEnvelope): void {
    let count = 0;
    for (const conn of this.connections.values()) {
      if (conn.channel === channel) {
        conn.send(envelope);
        count++;
      }
    }
    // fire-and-forget fan-out; drop-oldest per connection is handled inside
    // TransportConnection. Nothing here blocks the sim tick.
    void count;
  }

  close(connectionId: string): void {
    this.connections.get(connectionId)?.closeSocket();
  }

  async shutdown(): Promise<void> {
    // Terminate rather than politely close: a peer that never completes the
    // WebSocket closing handshake would otherwise keep server.close() waiting
    // forever. Include server.clients so rejected-origin sockets are covered.
    for (const conn of this.connections.values()) {
      conn.terminateSocket();
    }
    this.connections.clear();
    for (const server of [this.robotServer, this.visualiserServer]) {
      if (server) {
        for (const client of server.clients) client.terminate();
      }
    }

    const closeServer = (server: WebSocketServer | null): Promise<void> => {
      if (!server) return Promise.resolve();
      return new Promise<void>((resolve) => {
        let settled = false;
        const finish = () => {
          if (settled) return;
          settled = true;
          clearTimeout(timer);
          resolve();
        };
        // Hard upper bound: even a stuck socket cannot stall shutdown/exit.
        const timer = setTimeout(finish, SHUTDOWN_TIMEOUT_MS);
        server.close(finish);
      });
    };

    await Promise.all([
      closeServer(this.robotServer),
      closeServer(this.visualiserServer),
    ]);
    this.robotServer = null;
    this.visualiserServer = null;
    log("transport shut down");
  }
}
