// Protocol ownership and sequence-integrity tests (S1.2).
//
// SimEngine behaviour is exercised with an in-process fake Transport so no sim
// tick timer or socket is needed for command handling. One focused test uses a
// real WebSocketTransport to verify independent per-connection seq values.

import assert from "node:assert/strict";
import { createServer } from "node:net";
import { test } from "node:test";
import { WebSocket } from "ws";

import {
  Channel,
  Handlers,
  IncomingEnvelope,
  OutgoingEnvelope,
  Transport,
  TransportOptions,
} from "../src/types";
import { RobotDescription } from "../src/robotDescription";
import { SimEngine } from "../src/sim";
import { WebSocketTransport } from "../src/transport";

const DESCRIPTION: RobotDescription = {
  schemaVersion: 2,
  name: "test-two-wheel",
  components: [
    { id: "left-wheel", type: "wheel", angle: Math.PI / 2, offset: 0.25 },
    { id: "right-wheel", type: "wheel", angle: -Math.PI / 2, offset: 0.25 },
    { id: "front-sensor", type: "proximitySensor", angle: 0, offset: 0 },
  ],
};

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function waitFor(predicate: () => boolean, timeoutMs = 2000): Promise<void> {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    if (predicate()) return;
    await sleep(5);
  }
  throw new Error(`condition not met within ${timeoutMs}ms`);
}

// ---- Fake transport ------------------------------------------------------

class FakeTransport implements Transport {
  handlers: Handlers | null = null;
  readonly connections = new Map<string, Channel>();
  readonly sent: Array<{ connectionId: string; envelope: OutgoingEnvelope }> = [];
  readonly closed: string[] = [];
  private nextId = 0;

  listen(_options: TransportOptions): Promise<void> {
    return Promise.resolve();
  }
  setHandlers(handlers: Handlers): void {
    this.handlers = handlers;
  }
  send(connectionId: string, envelope: OutgoingEnvelope): void {
    this.sent.push({ connectionId, envelope: { ...envelope } });
  }
  broadcast(channel: "visualiser", envelope: OutgoingEnvelope): void {
    for (const [id, ch] of this.connections) {
      if (ch === channel) this.send(id, envelope);
    }
  }
  close(connectionId: string): void {
    this.closed.push(connectionId);
  }
  async shutdown(): Promise<void> {}

  connect(channel: Channel): string {
    const id = `${channel}-${this.nextId++}`;
    this.connections.set(id, channel);
    this.handlers?.onConnect(id, channel);
    return id;
  }
  disconnect(connectionId: string): void {
    const channel = this.connections.get(connectionId);
    this.connections.delete(connectionId);
    if (channel) this.handlers?.onDisconnect(connectionId, channel);
  }
  message(connectionId: string, envelope: IncomingEnvelope, channel?: Channel): void {
    const ch = channel ?? this.connections.get(connectionId);
    if (!ch) throw new Error(`unknown connection ${connectionId}`);
    this.handlers?.onMessage(connectionId, ch, envelope);
  }
  snapshotRecipients(): Set<string> {
    return new Set(
      this.sent
        .filter((entry) => entry.envelope.type === "componentSnapshot")
        .map((entry) => entry.connectionId),
    );
  }
}

// Start to register handlers, then stop immediately to clear the tick timer.
// Handler callbacks remain live, so command handling can be driven directly.
function startedEngine(transport: Transport): SimEngine {
  const engine = new SimEngine(transport, DESCRIPTION);
  engine.start();
  engine.stop();
  return engine;
}

function componentCommand(
  seq: number,
  simTick: number | undefined,
  commands: Array<{ id: string; value: number }>,
): IncomingEnvelope {
  return { type: "componentCommand", seq, simTick, payload: { commands } };
}

// ---- Acceptance criterion: visualiser commands are ignored and logged -----

test("componentCommand from a visualiser connection is ignored and logged", () => {
  const transport = new FakeTransport();
  const engine = startedEngine(transport);
  transport.connect("robot");
  const viz = transport.connect("visualiser");

  const logs: string[] = [];
  const originalLog = console.log;
  console.log = (...args: unknown[]) => {
    logs.push(args.map((arg) => String(arg)).join(" "));
  };
  try {
    transport.message(
      viz,
      componentCommand(0, 5, [{ id: "left-wheel", value: 1 }]),
      "visualiser",
    );
  } finally {
    console.log = originalLog;
  }

  assert.deepEqual(engine.getState().command, { left: 0, right: 0 });
  assert.ok(
    logs.some(
      (line) =>
        line.includes("ignoring componentCommand") && line.includes("visualiser"),
    ),
    `expected the visualiser command to be logged as ignored, got:\n${logs.join("\n")}`,
  );
});

// ---- Acceptance criterion: second robot cannot drive / receive snapshots --

test("single robot slot: a displaced robot cannot drive or receive componentSnapshots", async () => {
  const transport = new FakeTransport();
  const engine = new SimEngine(transport, DESCRIPTION);
  engine.start();
  try {
    const robotA = transport.connect("robot");
    transport.message(robotA, componentCommand(0, 10, [{ id: "left-wheel", value: 1 }]));
    assert.equal(engine.getState().command.left, 1);

    const robotB = transport.connect("robot");
    assert.ok(transport.closed.includes(robotA), "the previous robot must be closed");

    // A is displaced: its command must not touch actuators, even with a fresh seq.
    transport.message(robotA, componentCommand(1, 11, [{ id: "left-wheel", value: -1 }]));
    assert.equal(engine.getState().command.left, 1, "displaced robot must not drive");

    // B is active: D-4 state was reset, so seq 0 is accepted again.
    transport.message(robotB, componentCommand(0, 11, [{ id: "right-wheel", value: 1 }]));
    assert.equal(engine.getState().command.right, 1);

    // Let a few real ticks run so componentSnapshots are published.
    await waitFor(() => transport.snapshotRecipients().size > 0);
    const recipients = transport.snapshotRecipients();
    assert.ok(recipients.has(robotB), "active robot must receive snapshots");
    assert.ok(!recipients.has(robotA), "displaced robot must not receive snapshots");
  } finally {
    engine.stop();
  }
});

// ---- Acceptance criterion: stale robot disconnect must not safe-stop ------

test("stale robot disconnect does not stop the active robot", () => {
  const transport = new FakeTransport();
  const engine = startedEngine(transport);
  const robotA = transport.connect("robot");
  transport.message(robotA, componentCommand(0, 10, [{ id: "left-wheel", value: 1 }]));
  assert.equal(engine.getState().command.left, 1);

  const robotB = transport.connect("robot");
  transport.message(robotB, componentCommand(0, 10, [{ id: "left-wheel", value: 0.5 }]));
  assert.equal(engine.getState().command.left, 0.5);

  transport.disconnect(robotA); // stale — A is no longer the active slot
  assert.equal(engine.getState().command.left, 0.5, "stale disconnect must not stop motors");

  transport.disconnect(robotB); // active — safe stop
  assert.deepEqual(
    engine.getState().command,
    { left: 0, right: 0 },
    "active disconnect must safe-stop",
  );
});

// ---- D-4: duplicate / stale / invalid commands never touch actuators ------

test("D-4: duplicate, out-of-order, stale and invalid commands are ignored", () => {
  const transport = new FakeTransport();
  const engine = startedEngine(transport);
  const robot = transport.connect("robot");

  const drive = (seq: number, simTick: number | undefined, left: number): void => {
    transport.message(robot, componentCommand(seq, simTick, [{ id: "left-wheel", value: left }]));
  };

  drive(1, 5, 1);
  assert.equal(engine.getState().command.left, 1);

  drive(1, 6, -1); // duplicate seq
  assert.equal(engine.getState().command.left, 1);
  drive(0, 7, -1); // out-of-order seq
  assert.equal(engine.getState().command.left, 1);
  drive(2, 3, -1); // simTick older than lastSeenTick - 1
  assert.equal(engine.getState().command.left, 1);

  drive(2, 4, -1); // exactly one tick older is accepted
  assert.equal(engine.getState().command.left, -1);

  for (const bad of [
    { seq: Number.NaN, tick: 6 },
    { seq: 3, tick: Number.POSITIVE_INFINITY },
    { seq: 3.5, tick: 6 },
    { seq: -1, tick: 6 },
    { seq: 3, tick: -1 },
    { seq: 3, tick: 1.5 },
  ]) {
    drive(bad.seq, bad.tick, 1);
  }
  transport.message(robot, componentCommand(4, undefined, [{ id: "left-wheel", value: 1 }]));
  assert.equal(engine.getState().command.left, -1, "invalid seq/simTick must not drive");

  drive(5, 6, 1); // a valid command after all rejections still works
  assert.equal(engine.getState().command.left, 1);
});

// ---- Acceptance criterion: two visualisers get independent seq values -----

function freePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const server = createServer();
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      if (!address || typeof address === "string") {
        server.close();
        reject(new Error("could not obtain an ephemeral port"));
        return;
      }
      server.close(() => resolve(address.port));
    });
  });
}

async function connectClient(port: number, timeoutMs = 2000): Promise<WebSocket> {
  const start = Date.now();
  let lastError: Error | undefined;
  while (Date.now() - start < timeoutMs) {
    try {
      return await new Promise<WebSocket>((resolve, reject) => {
        const ws = new WebSocket(`ws://127.0.0.1:${port}`);
        ws.once("open", () => resolve(ws));
        ws.once("error", reject);
      });
    } catch (err) {
      lastError = err instanceof Error ? err : new Error(String(err));
      await sleep(10);
    }
  }
  throw new Error(`could not connect to ws://127.0.0.1:${port}: ${lastError?.message}`);
}

test("two visualisers receive independent monotonic seq values", async () => {
  const robotPort = await freePort();
  const visualiserPort = await freePort();
  const transport = new WebSocketTransport();
  await transport.listen({ robotPort, visualiserPort });

  const clients: WebSocket[] = [];
  try {
    clients.push(await connectClient(visualiserPort));
    clients.push(await connectClient(visualiserPort));

    // The client 'open' event can precede registration in the server's
    // connection map by a tick; wait for both server-side connections.
    const serverConnections = (
      transport as unknown as { connections: Map<string, unknown> }
    ).connections;
    await waitFor(() => serverConnections.size === 2);

    const received: number[][] = [[], []];
    clients.forEach((ws, index) => {
      ws.on("message", (data: Buffer | string) => {
        for (const line of data.toString().split("\n")) {
          if (line.trim().length === 0) continue;
          received[index].push(JSON.parse(line).seq);
        }
      });
    });

    // Broadcast the exact same envelope objects to both clients: the transport
    // must clone per connection rather than overwriting a shared `seq` field.
    const envelopes: OutgoingEnvelope[] = [0, 1, 2].map((simTick) => ({
      type: "worldSnapshot",
      seq: 99, // sentinel — the transport replaces this per connection
      simTick,
      payload: { robot: { x: simTick, y: 0, heading: 0 }, obstacles: [] },
    }));
    // Wait for each snapshot to be delivered before sending the next. The
    // depth-1 drop-oldest slot intentionally discards intermediates when
    // broadcasts pile up, so pacing keeps the expected sequence unambiguous.
    for (let i = 0; i < envelopes.length; i++) {
      transport.broadcast("visualiser", envelopes[i]);
      await waitFor(() => received[0].length >= i + 1 && received[1].length >= i + 1);
    }
    assert.deepEqual(received[0], [0, 1, 2]);
    assert.deepEqual(received[1], [0, 1, 2]);
    assert.ok(
      envelopes.every((envelope) => envelope.seq === 99),
      "the transport must not mutate the caller's envelope",
    );
  } finally {
    for (const ws of clients) ws.terminate();
    await transport.shutdown();
  }
});
