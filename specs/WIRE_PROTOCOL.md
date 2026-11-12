# Wire Protocol Specification

Object Oriented Robotics — MVP

**Status:** accepted (see `DESIGN_REVIEW.md` Section 1, items 1.1–1.5, 2.1, 5.1)

## 1. Purpose

This document defines the wire protocol between the simulation engine, the
Java robot controller, and the browser visualiser. It is the fixed contract
that lets the three components be built independently (Phase 1.2, 2.x and 3.x
can proceed in parallel once this is agreed).

The protocol is intentionally minimal: language-independent JSON over local
WebSockets, one robot, no discovery, no error protocol beyond "log and ignore
malformed input".

## 2. Transport: two WebSocket channels

Communication uses **two separate WebSocket ports** from a single sim server
process. The channel/port implies who is talking — there is no
`clientType` or `clientId` field in messages.

| Channel | URL | Direction | Carries |
|---------|-----|-----------|---------|
| Robot channel | `ws://localhost:ROBOT_PORT` | Bidirectional | `motorCommand` (robot → sim), `sensorSnapshot` (sim → robot) |
| Visualiser channel | `ws://localhost:VISUALISER_PORT` | Primarily unidirectional | `worldSnapshot` (sim → visualiser); `userControl` (visualiser → sim) deferred |

- The robot controller (Java process) connects to the **robot channel**.
- The visualiser (browser) connects to the **visualiser channel**.
- One robot only — robot IDs are implicit and absent from the protocol (see `DESIGN_REVIEW.md` 5.3). `robotId` can be added when multi-robot becomes a real goal.

## 3. Message framing: NDJSON

All messages use **newline-delimited JSON (NDJSON)**:

- Exactly one JSON object per line.
- Lines are terminated by `\n`.
- The TCP/WebSocket stream has no message boundaries, so the newline is the
  delimiter. A receiver buffers bytes until a newline, then parses one
  complete JSON object.
- Malformed JSON: log and ignore (never crash or disconnect on bad input).

## 4. Baseline message envelope

Every message on either channel carries the same baseline envelope:

```json
{
  "type": "...",
  "seq": 0,
  "simTick": 0,
  "payload": { ... }
}
```

| Field | Meaning |
|-------|---------|
| `type` | Message kind, e.g. `motorCommand`, `sensorSnapshot`, `worldSnapshot`, `userControl`. |
| `seq` | Per-connection sequence number, starting at 0, incremented by the sender on each message it sends. Used for deduplication and ordering on that connection. |
| `simTick` | Simulation logical tick number the message relates to. The **source of truth** for sequencing and staleness. |
| `payload` | Message-specific data (see section 6). |

### Wall-clock timestamps

`simTick` — not wall-clock — is the sequence source of truth. Wall-clock
timestamps may accompany messages for debugging only (e.g. an optional field
inside `payload` or logged on receipt). Staleness is **never** judged by
wall-clock drift across processes; it is judged by `simTick` difference.

## 5. Clock & sequencing

- The sim owns the authoritative logical clock (`simTick`), advancing one
  tick per sim step (target ~60 Hz).
- Every snapshot carries the `simTick` of the world state it describes.
- The robot echoes the last tick it acted on in its `motorCommand` messages
  (optional but useful for debugging staleness).
- A receiver judges freshness by comparing `simTick` values, never by
  comparing wall-clock times between the robot and sim processes.

## 6. Message types

### 6.1 Robot channel

#### `motorCommand` — robot → sim

Issued by the robot controller through the Java framework API
(`Motor.setSpeed(...)`). Motors use **persistent actuator state**: they keep
running at the last commanded speed until overridden. The sim never waits
for the robot; if the robot is slow, the sim reuses the previous command.

Example:

```json
{ "type": "motorCommand", "seq": 7, "simTick": 142, "payload": { "left": 1.0, "right": 0.5 } }
```

Payload fields are engine-defined (typical: per-motor power in `[-1, 1]`).
Exact payload schema is fixed when the sim server is scaffolded (Phase 2.4).

#### `sensorSnapshot` — sim → robot

Published by the sim on each tick (or as configured). The robot reads the
latest available value on its behaviour loop (~20 Hz).

For **MVP iteration 1 there are no sensors**, so `sensorSnapshot` carries no
meaningful readings yet — its payload is empty/placeholder. It remains part
of the protocol so iteration 2 can add readings without a protocol change.

Example (iteration 1):

```json
{ "type": "sensorSnapshot", "seq": 33, "simTick": 142, "payload": {} }
```

### 6.2 Visualiser channel

#### `worldSnapshot` — sim → visualiser

Published by the sim so the visualiser can render the world. The sim
publishes without waiting for the visualiser; if a visualiser falls behind,
the oldest unpublished snapshot for that client is dropped (keep only
latest). Disconnected visualisers are removed from the subscriber list — no
retry, no buffering.

Example:

```json
{ "type": "worldSnapshot", "seq": 142, "simTick": 142, "payload": { "robot": { "x": 2.4, "y": -1.1, "heading": 0.78 } } }
```

Payload fields are engine-defined (typical: robot pose, obstacle list).
Exact schema is fixed when the sim server is scaffolded (Phase 2.3).

#### `userControl` — visualiser → sim (deferred)

Primarily unidirectional channel; `userControl` (e.g. start/pause/reset)
is **not part of MVP** and is deferred to a later iteration. No payload
schema is defined yet.

## 7. Safe-stop on disconnect

- If the robot's WebSocket closes (Java process dies, socket closes, or
  controller disconnects), the sim immediately transitions actuators to a
  safe state: **motors stop**.
- Persistent actuator state means motors run until overridden — so a clean
  disconnect must explicitly stop them; there is no reliance on commands
  expiring.
- **Heartbeat/timeout for hung-but-connected processes is deferred beyond
  the MVP.** A TCP socket can stay open while a process is dead; manual
  intervention covers that for now.

## 8. Sensor model

The sim maintains the **latest sensor value + `simTick` in a single slot**,
overwriting it each tick:

- No queue.
- No polling.
- If the sim runs at 60 Hz and the robot reads at 20 Hz, ~2/3 of samples are
  silently dropped by being overwritten; the robot always sees the most
  recent reading.
- `simTick` is on every reading so a behaviour can check staleness later if
  needed, but no maximum-staleness check is required for MVP.

For MVP iteration 1 there are no sensors, so `sensorSnapshot` carries no
readings yet (see section 6.1).

## 9. Summary table: message types per channel

| Channel | Message | Direction | simTick on every message? | MVP iteration 1 | Notes |
|---------|---------|-----------|---------------------------|-----------------|-------|
| Robot (`ROBOT_PORT`) | `motorCommand` | robot → sim | Yes | ✅ | Persistent actuator state; last command reused if robot is slow |
| Robot (`ROBOT_PORT`) | `sensorSnapshot` | sim → robot | Yes | ✅ (empty payload) | No readings yet; single-slot sensor model deferred to iteration 2 |
| Visualiser (`VISUALISER_PORT`) | `worldSnapshot` | sim → visualiser | Yes | ✅ | Drop-oldest backpressure; no blocking on visualiser |
| Visualiser (`VISUALISER_PORT`) | `userControl` | visualiser → sim | — | ❌ deferred | Start/pause/reset etc.; future iteration |

## 10. Deferred beyond MVP

- Heartbeat/timeout for hung-process detection (safe-stop relies on WebSocket
  close only).
- Binary framing (revisit if NDJSON parsing proves a bottleneck).
- `userControl` messages on the visualiser channel.
- Robot IDs / multi-robot support.
- Formal error protocol (malformed JSON is logged and ignored).
- World file format and static obstacles (iteration 2).
- Sensor readings in `sensorSnapshot` (iteration 2).
