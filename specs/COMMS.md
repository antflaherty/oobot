# Communications Layer Specification

Object Oriented Robotics — MVP

**Status:** current. Describes the implemented sim-side `Transport` abstraction
and its `WebSocketTransport` implementation (`simulator/server/src/types.ts`,
`transport.ts`). Connection limits, tool state and sequencing follow
`WIRE_PROTOCOL.md` and `docs/DECISIONS.md` D-2/D-4.

## 1. Purpose

This document defines the encapsulated transport interface in the **sim
server** so the transport can be swapped later (e.g. for physical hardware)
without changing the sim engine. The Java robot has a separate, conventional
client stack (`WsConnection` with OkHttp + Jackson) and does not implement this
TypeScript interface.

The communication layer sits between:

- the **simulation engine** (owner of world state, timestep, actuator state and
  publishing), and
- the **concrete transport** (`WebSocketTransport` in the MVP).

Robot code never touches sockets directly. The wire envelope
(`{ type, seq, simTick, payload }`, NDJSON framing — see
`specs/WIRE_PROTOCOL.md`) is unchanged regardless of the transport.

## 2. Transport interface (sim side)

### 2.1 Interface contract

`sim.ts` depends only on this abstraction. The authoritative definition is
`simulator/server/src/types.ts`; the shape is:

```typescript
type Channel = "robot" | "visualiser";
type ConnectionId = string;

interface IncomingEnvelope {
  type: string;        // e.g. "componentCommand"
  seq: number;         // per-connection sender sequence
  simTick?: number;    // sim tick the message relates to (validated by the sim)
  payload: unknown;    // message-specific data
}

interface OutgoingEnvelope {
  type: string;        // e.g. "componentSnapshot", "worldSnapshot"
  seq: number;         // filled in by the transport, per connection
  simTick: number;
  payload: unknown;
}

interface Handlers {
  onConnect: (connectionId: ConnectionId, channel: Channel) => void;
  onMessage: (connectionId: ConnectionId, channel: Channel,
              envelope: IncomingEnvelope) => void;
  onDisconnect: (connectionId: ConnectionId, channel: Channel) => void;
}

interface Transport {
  listen(options: TransportOptions): Promise<void>;  // resolves once bound
  setHandlers(handlers: Handlers): void;
  send(connectionId: ConnectionId, envelope: OutgoingEnvelope): void;
  broadcast(channel: "visualiser", envelope: OutgoingEnvelope): void;
  close(connectionId: ConnectionId): void;
  shutdown(): Promise<void>;                         // bounded
}

interface TransportOptions {
  robotPort: number;      // ROBOT_PORT
  visualiserPort: number; // VISUALISER_PORT
}
```

`onMessage` receives the **channel** as well as the connection id: commands are
accepted only when the channel is `"robot"` and the connection is the active
robot slot (§5.1).

### 2.2 Implementations

| Implementation | Status | Channel(s) | Notes |
|----------------|--------|------------|-------|
| `WebSocketTransport` | Implemented | Robot + visualiser | One Node.js process, two ports |
| `PhysicalHardwareTransport` | Deferred idea | Robot (physical) | Would implement the same interface |

`index.ts` constructs exactly one `Transport` (currently
`WebSocketTransport`) and passes it to `SimEngine`; `sim.ts` imports only the
interface. Swapping transports would require changing that construction, not
the sim engine or robot code.

### 2.3 Ownership

| Concern | Owner |
|---------|-------|
| Wire envelope and message schemas | Sim + `WIRE_PROTOCOL.md` |
| NDJSON framing, 64 KiB inbound line cap | Transport |
| Accepting connections, assigning ids, routing by channel | Transport |
| Bind/origin checks, per-connection outbound `seq`, drop-oldest queue | Transport |
| Bounded shutdown (close all sockets, cap `server.close()` wait) | Transport |
| Persistent wheel/tool state, active-robot slot, D-4 seq/tick checks | Sim |
| Safe-stop (zero wheels, clear tools) on active-robot close / sim shutdown (D-2 layer A) | Sim |
| Explicit zero command written before Java socket close (D-2 layer B) | Java bridge (`SimRobot` / `WsConnection`) |
| Publishing `worldSnapshot` to visualisers | Sim (calls `transport.broadcast`) |
| Never blocking the sim tick on I/O | Both — all send paths are non-blocking |

## 3. WebSocket server setup (sim server)

A single Node.js process hosts both channels:

- robot channel: `ws://localhost:ROBOT_PORT` (default 8080);
- visualiser channel: `ws://localhost:VISUALISER_PORT` (default 8081);
- both ports validated as integers in 1–65535 (`ROBOT_PORT` /
  `VISUALISER_PORT`).

Both servers bind **`0.0.0.0`**, not hostname `localhost`: IPv4-only clients
(the Java socket) cannot reliably connect to an IPv6-only `localhost` bind on
macOS. This is not "localhost-only at the socket layer". Instead, each upgrade
is checked for an allowed `Origin`: no header is allowed (Java), and an
http(s) URL whose host is `localhost`, `127.0.0.1`, `::1` or `[::1]` is
allowed; `Origin: null`, other schemes and other hosts are closed with code
1008. This is browser-oriented defence in depth, not a network firewall.

`listen()` creates both servers, registers `connection`/`error` handlers, then
awaits both `listening` events. A bind failure (e.g. `EADDRINUSE`) rejects with
the original error, any bound sibling server is closed, and the entry point
exits non-zero immediately. There is no silent half-bound state.

Per-connection outgoing state:

- `seq` starts at 0 for each connection and is assigned on send, so two
  visualiser tabs receive independent monotonic sequences. The caller's
  envelope (including its payload) is cloned and never mutated.
- At most one unpublished snapshot is pending. A newer envelope replaces it
  (drop-oldest). `ws.send` callbacks drive the queue; the sim tick never waits.
- A failed send logs and closes that client; other clients are unaffected.

### 3.1 Outline (illustrative, not a second implementation)

```typescript
async listen({ robotPort, visualiserPort }) {
  robotServer = new WebSocketServer({ host: "0.0.0.0", port: robotPort });
  visualiserServer = new WebSocketServer({ host: "0.0.0.0", port: visualiserPort });
  // connection handlers check Origin, then accept(ws, channel)
  // error handlers log post-startup errors
  await Promise.all([waitForListening(robotServer), waitForListening(visualiserServer)]);
  // on rejection: close any bound sibling, rethrow original error
}
```

`TransportConnection` owns the inbound text buffer, the depth-1 outgoing slot
and the per-connection outgoing counter; see `transport.ts` for the exact code.

### 3.2 Shutdown

`shutdown()` terminates all tracked connections plus any sockets still in
`server.clients` (including rejected-origin sockets), clears the connection map
and closes both servers. Each `server.close()` callback races a 2 s timer so an
unresponsive peer cannot stall process exit. The sim calls `stopMotors()` before
shutdown; Java writes its zero-before-close frame independently (D-2; see
`WIRE_PROTOCOL.md` §7 and §9.2 below).

## 4. Connection lifecycle

### 4.1 Accept

1. The TCP/WebSocket upgrade is accepted on the appropriate port; `Origin` is
   checked and an invalid origin is rejected.
2. A unique `connectionId` is assigned and a `TransportConnection` is created.
3. `onConnect(connectionId, channel)` is invoked. On the robot channel the sim
   claims the single robot slot, resets D-4 tracking and closes any previous
   robot connection; on the visualiser channel the connection is simply added
   to the broadcast set.
4. The connection is logged with channel and id.

### 4.2 Message

1. Inbound text is buffered until `\n`; a segment longer than 64 KiB (including
   a trailing partial line) is logged and the socket is hard-closed.
2. Each complete line is parsed into an envelope. Malformed or non-object JSON
   and envelopes missing `type`/`seq` are logged and ignored.
3. `onMessage(connectionId, channel, envelope)` is called with the real channel.
4. Only `componentCommand` from the active robot connection is considered; all
   other messages are logged and ignored by the sim.

### 4.3 Close

1. The connection is removed from the map / subscriber set.
2. `onDisconnect(connectionId, channel)` is invoked.
3. **Robot channel:** if this was the active robot slot, the slot is cleared,
   D-4 state is reset and the sim safe-stops (D-2 layer A). A stale robot's
   disconnect is logged and ignored so it cannot stop the active robot.
4. **Visualiser channel:** no retry or buffering in the sim. A reconnecting
   visualiser is accepted as a brand-new connection and receives the next
   published snapshot; the client itself resets stale render state (D-5).

## 5. Robot channel message handling

The robot channel is persistent and bidirectional: one active Java controller
at a time.

### 5.1 Receive `componentCommand` (robot → sim)

For each inbound envelope, in this order:

1. Require `channel === "robot"` and `connectionId === activeRobotConnectionId`;
   otherwise log and ignore before touching state.
2. Require `seq` and `simTick` to be finite non-negative integers (reject
   `NaN`, `Infinity`, fractions, negatives, missing values).
3. Require `seq > lastAppliedSeq` (duplicates/out-of-order ignored).
4. Require `lastSeenTick === null || simTick >= lastSeenTick - 1` (one tick of
   reordering slack; older commands are stale).
5. Require `payload.commands` to be an array of `{ id, value }` with finite
   numeric values.

On acceptance only, update `lastAppliedSeq = seq` and
`lastSeenTick = max(lastSeenTick ?? simTick, simTick)`, then apply wheel values
(clamped to `[-1, 1]`) and tool values (exactly `0` = inactive or `1` = active;
anything else is ignored) to persistent state. Unknown/non-wheel/non-tool ids
are logged and ignored individually. A new active robot connection resets both
tracking values; rejected commands never touch wheels or tools. High-frequency
reject events are counted and reported with the 5 s achieved-Hz log (plus
`SimEngine.getState()`), not logged per command.

### 5.2 Publish `componentSnapshot` (sim → robot)

Every sim tick the sim builds one snapshot with one reading per manifest
proximity sensor and sends it to the active robot connection via
`transport.send(...)` (non-blocking). If no robot is connected, the send is a
no-op. The snapshot carries the sim tick it describes; the Java bridge resets
sensor values on disconnect/reconnect/tick rollback and exposes
`ProximitySensor.latestSimTick()` for staleness checks (`specs/SENSORS.md`).

## 6. Publish/subscribe to visualiser clients

The visualiser channel (`VISUALISER_PORT`) is **unidirectional**: sim →
visualiser `worldSnapshot` only. No `userControl` messages are sent or handled.

### 6.1 Publish without waiting

Every sim tick the sim calls
`transport.broadcast("visualiser", worldSnapshotEnvelope)`. The envelope has
`payload.robot: { x, y, heading, shape?, toolStates? }` (shape in world units;
one `{ id, active }` entry per declared tool) and `payload.obstacles: [...]`.
The sim does not wait for acknowledgement or for a visualiser to drain its
socket.

### 6.2 Drop-oldest backpressure

Each visualiser connection has one pending outbound slot: a newer snapshot
replaces an unpublished one. The sim tick continues at its own pace; rendering
lag never slows the sim.

### 6.3 Disconnected visualisers

A disconnected visualiser is removed immediately. The sim has no retry or
snapshot replay; the browser client reconnects on its own timer and resets all
derived state on every open and on tick rollback (D-5).

## 7. Error handling contract

| Situation | Contract |
|-----------|----------|
| Malformed / non-object JSON, missing `type`/`seq` | Log and ignore; no crash, no disconnect on bad input alone. |
| Inbound line > 64 KiB | Log, discard buffer, terminate that connection; an active robot close triggers sim safe stop. |
| Disallowed `Origin` (`null`, non-http(s), non-loopback host) | Reject upgrade with close code 1008; log. |
| Port already in use / bind error | `listen()` rejects with the original error; partially bound siblings close; process exits non-zero fast. |
| `componentCommand` from wrong channel/connection | Log and ignore; D-4 state and actuators untouched. |
| Duplicate/stale/invalid `componentCommand` | Log/count and ignore; no wheel/tool state change. |
| Invalid tool value (not exactly `0` or `1`) | Log/count and ignore; tool state unchanged. |
| Active robot socket closes or process dies | Sim zeroes motors and clears tools (D-2 layer A); Java had tried to write its wheels-only zero frame first (layer B). |
| Stale robot socket closes | Log and ignore; active robot keeps running. |
| Tick rollback / reconnect seen by Java | Invalidate sensor readings (distance → `Infinity`, tick → 0). |
| Send failure to one visualiser | Log and close that client; sim continues. |
| Hung-but-connected process | Deferred beyond MVP: no heartbeat/timeout; WebSocket close is the only close trigger. |
| Shutdown with an unresponsive peer | Sockets are terminated and server close waits are capped at ~2 s. |

There is no formal error message type on the wire.

## 8. Future swap to physical hardware

### 8.1 What would need to change

Implement a transport behind the same `Transport` interface:

- `listen()` opens the physical bus instead of WebSocket ports.
- `send()`/`broadcast()` frame commands for the driver; `onMessage` parses
  device frames into envelopes.
- Attach/detach maps to the connection lifecycle; safe-stop on detach.

### 8.2 What stays the same

- Envelope: `{ type, seq, simTick, payload }`; NDJSON where a text transport is
  used; `simTick` as freshness source.
- Message types: `componentCommand`, `componentSnapshot`, `worldSnapshot`.
- Student API: behaviours use `Mover`, `ProximitySensor` and `Tool` from their
  `Brain`; they never know which bridge/transport is underneath.

This is a rationale for the abstraction, not an implemented transport.

## 9. Sequence descriptions

### 9.1 Robot connects and drives (happy path)

```
Robot (Java)                    Transport                    Sim
    |                               |                          |
    |--- WebSocket connect (ROBOT_PORT, no Origin) ------------>|
    |                               |-- onConnect(id,"robot") ->|
    |                               |                          | claim slot, reset D-4 state
    |--- componentCommand {seq,tick,commands} ----------------->|
    |                               |-- onMessage(id,channel,env)|
    |                               |                          | validate channel/seq/tick (D-4)
    |                               |                          | apply wheel/tool values (persistent)
    |<-- componentSnapshot {seq,tick,readings} -----------------|
    |            (one batched command per ~20 Hz behaviour tick; snapshots every ~60 Hz)
```

### 9.2 Robot disconnects → two-layer safe stop (D-2)

```
Robot (Java)                    Transport                    Sim
    |                               |                          |
    |-- stop behaviour tick                                    |
    |-- write componentCommand: every wheel id = 0.0            |
    |   (still open: send -> flush) --------------------------->|
    |-- clear open, close socket ------------------------------>|
    |                               |-- onDisconnect ---------->|
    |                               |                          | zero wheels, clear tools; clear slot
```

The final zero frame is wheels-only; the sim clears tool states locally and
Java force-resyncs its persistent tool state after reconnect. If the Java
process dies without writing the frame, the close event alone still triggers
layer A. A displaced/stale robot's close does not stop the active robot.

### 9.3 Visualiser connects and receives snapshots

```
Visualiser (browser)            Transport                    Sim
    |--- WebSocket connect (VISUALISER_PORT, allowed Origin) -->|
    |                               |-- onConnect(id,"visualiser")>
    |                               |                          | add to broadcast set
    |<-- worldSnapshot {robot,toolStates,obstacles} (per tick) -|
    |                               |                          | drop-oldest if tab is behind
    |--- socket closes ---------------------------------------->|
    |                               |-- onDisconnect ---------->| remove from set
    |-- client resets state and reconnects on its own timer (D-5)
```

## 10. Deferred beyond MVP

- Heartbeat/timeout for hung-process detection.
- Generic sensor lookup in the Java `Brain` (fixed ids per D-3).
- `userControl` on the visualiser channel; any other visualiser → sim traffic.
- Binary framing; multi-robot support; formal error protocol.
- `PhysicalHardwareTransport`.
