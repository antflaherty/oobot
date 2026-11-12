# Communications Layer Specification

Object Oriented Robotics — MVP

**Status:** accepted (see `DESIGN_REVIEW.md` items 1.1, 2.1, 4.2, 6.5)

## 1. Purpose

This document defines the **encapsulated transport interface** in the sim
server so the transport can be swapped later (e.g. for physical hardware)
without touching robot code or the wire protocol.

The communication layer sits between:

- the **simulation engine** (owner of world state, timestep, publishing), and
- the **concrete transport** (WebSocket server in the MVP; physical hardware
  transport deferred).

Robot code never touches sockets directly. The Java framework API talks to
the sim through this encapsulated interface, and the wire protocol envelope
(`{ type, seq, simTick, payload }`, NDJSON framing — see `specs/WIRE_PROTOCOL.md`)
is unchanged regardless of which transport implementation is active.

## 2. Transport interface (sim side)

### 2.1 Interface contract

The sim depends only on this abstraction. Everything transport-specific
(WebSocket frames, TCP buffering, serial ports, motor driver protocols)
lives behind it.

```typescript
// --- Types shared across transports ---------------------------------------

// Opaque identifier for one transport connection.
type ConnectionId = string;

// One complete message received from the client side.
interface IncomingEnvelope {
  type: string;        // e.g. "motorCommand", "userControl"
  seq: number;         // per-connection sender sequence
  simTick?: number;    // sim logical tick the message relates to
  payload: unknown;    // message-specific data
}

// One complete message to send to the client side.
interface OutgoingEnvelope {
  type: string;        // e.g. "sensorSnapshot", "worldSnapshot"
  seq: number;
  simTick: number;
  payload: unknown;
}

// Callback the sim registers to be notified when a client connects.
type OnConnect = (connectionId: ConnectionId, channel: Channel) => void;

// Callback the sim registers to be notified when a parsed message arrives.
type OnMessage = (connectionId: ConnectionId, envelope: IncomingEnvelope) => void;

// Callback the sim registers to be notified when a client disconnects.
type OnDisconnect = (connectionId: ConnectionId, channel: Channel) => void;

type Channel = "robot" | "visualiser";

// --- The interface the sim programs against --------------------------------

interface Transport {
  // Start listening on the configured port(s). Called once at sim startup.
  listen(options: TransportOptions): Promise<void>;

  // Register lifecycle callbacks. The sim wires these up before listen().
  setHandlers(handlers: {
    onConnect: OnConnect;
    onMessage: OnMessage;
    onDisconnect: OnDisconnect;
  }): void;

  // Send one envelope to one specific connection. Non-blocking; fire and
  // forget — the transport owns framing, buffering, and backpressure.
  send(connectionId: ConnectionId, envelope: OutgoingEnvelope): void;

  // Send one envelope to every subscriber on a channel (visualiser fan-out).
  broadcast(channel: "visualiser", envelope: OutgoingEnvelope): void;

  // Close one connection and clean up its state.
  close(connectionId: ConnectionId): void;

  // Shut the transport down (stop accepting, close all connections).
  shutdown(): Promise<void>;
}

interface TransportOptions {
  robotPort: number;      // ROBOT_PORT
  visualiserPort: number; // VISUALISER_PORT
}
```

### 2.2 Implementations

| Implementation | Status | Channel(s) | Notes |
|----------------|--------|------------|-------|
| `WebSocketTransport` | MVP | Robot + visualiser | Single Node.js `ws`/`net` server process, two ports |
| `PhysicalHardwareTransport` | Deferred | Robot (physical) | Same interface; serial/CAN/motor-driver behind it |

The sim constructs exactly one `Transport` and never imports a concrete
socket library. Swapping transports means constructing a different
implementation behind the same `Transport` interface — robot code and the
wire protocol are untouched.

### 2.3 What the sim owns vs. what the transport owns

| Concern | Owner |
|---------|-------|
| Wire protocol envelope (`{ type, seq, simTick, payload }`) | Sim + wire protocol spec |
| NDJSON framing (buffer until `\n`, parse one JSON object) | Transport |
| Accepting connections, assigning connection IDs | Transport |
| Routing by channel (port) | Transport |
| Publishing `worldSnapshot` to visualiser subscribers | Sim (calls `transport.broadcast`) |
| Storing latest `motorCommand` as persistent actuator state | Sim |
| Safe-stop motors on robot disconnect | Sim (reacts to `onDisconnect` on robot channel) |
| Drop-oldest backpressure per visualiser | Transport (see section 4) |
| Never blocking the sim tick on I/O | Both — transport send/broadcast are non-blocking |

## 3. WebSocket server setup (sim server)

A single Node.js server process hosts both WebSocket channels:

- Robot channel: `ws://localhost:ROBOT_PORT`
- Visualiser channel: `ws://localhost:VISUALISER_PORT`

The port implies who is talking — there is no `clientType` field in
messages (see `DESIGN_REVIEW.md` 1.1 / WIRE_PROTOCOL section 2).

### 3.1 Server pseudocode

```typescript
class WebSocketTransport implements Transport {
  private robotServer: WebSocketServer;    // listens on ROBOT_PORT
  private visualiserServer: WebSocketServer; // listens on VISUALISER_PORT
  private connections = new Map<ConnectionId, TransportConnection>();
  private handlers?: { onConnect, onMessage, onDisconnect };

  async listen({ robotPort, visualiserPort }: TransportOptions) {
    this.robotServer = createWebSocketServer(robotPort);
    this.visualiserServer = createWebSocketServer(visualiserPort);

    this.robotServer.on("connection", (ws) =>
      this.accept(ws, "robot")
    );
    this.visualiserServer.on("connection", (ws) =>
      this.accept(ws, "visualiser")
    );
  }

  private accept(ws: WebSocket, channel: Channel) {
    const connectionId = generateId();
    const conn = new TransportConnection(ws, connectionId, channel);
    this.connections.set(connectionId, conn);

    // NDJSON framing: buffer bytes until newline, then parse one object.
    conn.on("line", (line: string) => {
      let envelope: IncomingEnvelope;
      try {
        envelope = JSON.parse(line) as IncomingEnvelope;
      } catch (err) {
        // Malformed JSON: log and ignore. Never crash or disconnect.
        log.warn(`Malformed JSON from ${connectionId}: ${line}`);
        return;
      }
      this.handlers?.onMessage(connectionId, envelope);
    });

    conn.on("close", () => {
      this.connections.delete(connectionId);
      this.handlers?.onDisconnect(connectionId, channel);
    });

    this.handlers?.onConnect(connectionId, channel);
  }

  send(connectionId: ConnectionId, envelope: OutgoingEnvelope) {
    const conn = this.connections.get(connectionId);
    if (!conn) return; // already gone
    conn.send(JSON.stringify(envelope) + "\n"); // non-blocking
  }

  broadcast(channel: "visualiser", envelope: OutgoingEnvelope) {
    const line = JSON.stringify(envelope) + "\n";
    for (const conn of this.connections.values()) {
      if (conn.channel === channel) conn.send(line); // non-blocking, drop-oldest handled per-conn
    }
  }

  close(connectionId: ConnectionId) {
    this.connections.get(connectionId)?.ws.close();
  }

  async shutdown() {
    for (const conn of this.connections.values()) conn.ws.close();
    this.robotServer.close();
    this.visualiserServer.close();
  }
}
```

`TransportConnection` owns one raw WebSocket, an incoming NDJSON buffer
(partial reads buffered until `\n`), and an outgoing send path.

## 4. Connection lifecycle

### 4.1 Accept

When a new client connects:

1. The transport accepts the WebSocket upgrade on the appropriate port.
2. Assign a unique `connectionId`.
3. Add the connection to the subscriber list (visualiser channel) or to the
   robot-handler map (robot channel).
4. Invoke `onConnect(connectionId, channel)`.
5. Log the connection (channel + connection ID).

### 4.2 Message

For every line received on a connection:

1. Buffer incoming bytes until a newline (partial reads are held, not
   parsed).
2. Parse the NDJSON line into `{ type, seq, simTick, payload }`.
3. Malformed JSON: log and ignore — never crash, never disconnect.
4. Route by `type` to the sim's handler via `onMessage(connectionId,
   envelope)`.
5. The channel (port) already implies sender identity; no `clientType`
   lookup is needed.

### 4.3 Close

When a connection closes:

1. Remove the connection from the subscriber list or robot-handler map.
2. Invoke `onDisconnect(connectionId, channel)`.
3. **Robot channel:** sim transitions actuators to safe state — motors stop
   (persistent actuator state means a clean disconnect must explicitly stop
   them; see section 5.1).
4. **Visualiser channel:** remove from the publisher list — no retry, no
   buffering, no reconnect logic. A reconnecting visualiser is accepted as
   a brand-new connection and receives the latest snapshot on the next
   publish cycle.

## 5. Robot channel message handling

The robot channel (`ROBOT_PORT`) is persistent and bidirectional: one Java
controller process connects and stays connected for the life of the run.

### 5.1 Receive `motorCommand` (robot → sim)

On each `motorCommand` envelope:

1. Validate the envelope (well-formed JSON with expected `type`).
2. Store it as the **latest command** — persistent actuator state.
3. Motors keep running at the last commanded speed until a new command
   overrides it.
4. The sim never waits for the robot: if the robot is slow to respond, the
   sim reuses the previous command on the next tick.

### 5.2 Publish `sensorSnapshot` (sim → robot)

On each sim tick (or as configured):

1. Read the latest sensor value + `simTick` from the single sensor slot
   (no queue, no polling — overwritten each tick).
2. Send `sensorSnapshot` to the connected robot connection (non-blocking).
3. For MVP iteration 1 there are no sensors, so the payload is empty/
   placeholder; the message type remains in the protocol for iteration 2.

## 6. Publish/subscribe to visualiser clients

The visualiser channel (`VISUALISER_PORT`) is primarily unidirectional:
sim → visualiser (`worldSnapshot`).

### 6.1 Publish without waiting

Every sim tick, the sim calls `transport.broadcast("visualiser",
worldSnapshotEnvelope)`. The sim does not wait for any visualiser to
acknowledge or drain its socket.

### 6.2 Drop-oldest backpressure

If a visualiser falls behind (slow consumer, browser tab backgrounded, full
TCP send buffer), the transport drops the oldest unpublished snapshot for
that client and keeps only the latest:

- Per visualiser connection: at most one unpublished `worldSnapshot` is
  queued. A new snapshot replaces the unpublished one.
- The sim tick continues at its own pace; rendering lag never slows the
  sim.

### 6.3 Disconnected visualisers

- A disconnected visualiser is removed from the subscriber list immediately.
- No retry, no buffering of missed snapshots, no reconnect logic.
- A visualiser that reconnects is a new connection; it receives the latest
  snapshot on the next publish cycle.
- The sim never blocks on a visualiser; sim and visualiser are fully
  decoupled.

## 7. Error handling contract

| Situation | Contract |
|-----------|----------|
| Malformed JSON on any line | Log and ignore. Never crash, never disconnect the client. |
| Partial reads (stream has no message boundaries) | NDJSON buffering: accumulate bytes until `\n`, then parse one complete JSON object. |
| Controller crash mid-behaviour | WebSocket close fires → `onDisconnect` on robot channel → safe stop motors immediately. |
| Visualiser reconnect mid-simulation | Accept new connection; send latest snapshot on next publish cycle; sim never blocks. |
| Hung-but-connected process (socket open, process dead) | Deferred beyond MVP — no heartbeat/timeout for now; WebSocket close is the only safe-stop trigger. |
| Send failure to one visualiser | That client is dropped from the subscriber list; sim continues. |

No formal error protocol exists beyond "log everything for debugging."
There are no error message types on the wire.

## 8. Future swap to physical hardware

### 8.1 What would need to change

Implement `PhysicalHardwareTransport` behind the same `Transport`
interface:

- `listen()` opens the physical bus (serial/CAN/motor driver) instead of
  WebSocket ports.
- `send()`/`broadcast()` frame commands for the physical motor driver;
  `onMessage` parses physical sensor frames into `IncomingEnvelope`s.
- Connection lifecycle maps to physical device attach/detach rather than
  WebSocket open/close.
- Safe-stop on detach: stop motors when the physical link goes away.

The sim constructs `PhysicalHardwareTransport` instead of
`WebSocketTransport`; nothing else in the sim changes.

### 8.2 What stays the same

- Wire protocol envelope: `{ type, seq, simTick, payload }`.
- Message types: `motorCommand`, `sensorSnapshot`, `worldSnapshot`.
- NDJSON framing semantics (where a text transport is used).
- Sim logical tick as sequence source of truth.
- Java framework API: robot code calls `Motor.setSpeed(...)` and reads
  `sensorSnapshot` through the framework; it never knows whether the
  backend is WebSocket or physical hardware.
- Robot code never touches sockets directly in either case.

## 9. Sequence descriptions

### 9.1 Robot connects and drives (happy path)

```
Robot (Java)                          Transport                      Sim
   |                                    |                              |
   |--- WebSocket connect (ROBOT_PORT) --->|                          |
   |                                    | assign connectionId         |
   |                                    |-- onConnect(id, "robot") -->|
   |                                    |                              |
   |--- motorCommand {type,seq,tick} -->|                              |
   |                                    |-- onMessage(id, env) ------->|
   |                                    |                              | store latest command (persistent actuator state)
   |                                    |                              | apply motor speeds to world model
   |                                    |                              | read latest sensor slot
   |<--- sensorSnapshot {..} -----------|                              | send latest sensor value + simTick
   |                                    |                              |
   | (repeats each behaviour tick ~20 Hz)                                                |
```

### 9.2 Robot disconnects → safe stop

```
Robot (Java)                          Transport                      Sim
   |                                    |                              |
   |--- process dies / socket closes -->|                              |
   |                                    |-- onDisconnect(id, "robot")->|
   |                                    |                              | stop motors (safe stop)
   |                                    |                              | persistent actuator state cleared
```

### 9.3 Visualiser connects and receives snapshots

```
Visualiser (browser)                  Transport                      Sim
   |                                    |                              |
   |--- WebSocket connect (VIS_PORT)--->|                              |
   |                                    | assign connectionId         |
   |                                    |-- onConnect(id, "visualiser")->|
   |                                    |                              | add to visualiser subscriber list
   |                                    |                              |
   |                                    |                              | each sim tick:
   |<--- worldSnapshot {..} ------------|                              |-- broadcast("visualiser", env) -->
   |<--- worldSnapshot {..} ------------|                              |   (non-blocking, drop-oldest if lagging)
   |                                    |                              |
   |--- tab closes / socket closes ----->|                              |
   |                                    |-- onDisconnect(id, "visualiser")->
   |                                    |                              | remove from subscriber list (no retry, no buffering)
```

## 10. Deferred beyond MVP

- Heartbeat/timeout for hung-process detection (safe-stop relies on WebSocket
  close only).
- Binary framing (revisit only if NDJSON parsing proves a bottleneck).
- `userControl` messages on the visualiser channel.
- Robot IDs / multi-robot support.
- Formal error protocol (malformed JSON is logged and ignored).
- `PhysicalHardwareTransport` implementation.
