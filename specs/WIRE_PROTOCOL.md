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
| Robot channel | `ws://localhost:ROBOT_PORT` | Bidirectional | `componentCommand` (robot → sim), `componentSnapshot` (sim → robot) |
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
| `type` | Message kind, e.g. `componentCommand`, `componentSnapshot`, `worldSnapshot`, `userControl`. |
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
- The robot echoes the last tick it acted on in its `componentCommand` messages
  (optional but useful for debugging staleness).
- A receiver judges freshness by comparing `simTick` values, never by
  comparing wall-clock times between the robot and sim processes.

## 6. Message types

### 6.1 Robot channel

Message names are **id-addressed**: commands and readings carry component
`id`s from the shared manifest (`specs/ROBOT_DESCRIPTION.md`,
`robots/default.json`) instead of fixed left/right/front/right slots.
`motorCommand` and `sensorSnapshot` are removed (breaking change). The
envelope and NDJSON framing are unchanged.

#### `componentCommand` — robot → sim

Issued by the robot controller through the Java framework API. Components use
**persistent actuator state**: wheels keep their last commanded value until
overridden. The sim never waits for the robot; if the robot is slow, the sim
reuses the previous command.

Example:

```json
{ "type": "componentCommand", "seq": 7, "simTick": 142,
  "payload": { "commands": [
    { "id": "left-wheel", "value": 1.0 },
    { "id": "right-wheel", "value": -1.0 }
  ] } }
```

Payload schema:

| Field | Type | Range | Meaning |
|---|---|---|---|
| `commands` | array | — | One entry per commanded component |
| `commands[].id` | string | manifest id | Target component id (e.g. `left-wheel`) |
| `commands[].value` | number | `[-1, 1]` for wheels | Commanded value; sim clamps to range |

Wheel `value` maps to the wheel's derived lateral role (left/right from
`sin(angle)` in the manifest). `1.0` = full forward, `-1.0` = full reverse,
`0` = stop.
Unknown id, wrong component type, or malformed value → log and ignore.

#### `componentSnapshot` — sim → robot

Published by the sim on each tick (or as configured). The robot reads the
latest available value on its behaviour loop (~20 Hz). There is **one reading
per declared proximity sensor** in the manifest; each ray is cast from the
robot centre at `heading + component.angle` (mount angle relative to heading;
radians, 0 = forward, + = CCW, right = -π/2). A reading is `null` when nothing
is detected along that ray. There are no hardcoded front/right sensors.

Payload schema:

| Field | Type | Meaning |
|---|---|---|
| `readings` | array | One entry per declared `proximitySensor` |
| `readings[].id` | string | Sensor component id (e.g. `front-sensor`) |
| `readings[].value` | number \| null | Distance in world units to nearest hit, or `null` when nothing detected |

`simTick` is on the envelope as always (source of truth for staleness).

Example — front wall 3.5 units ahead, right sensor detects nothing:

```json
{ "type": "componentSnapshot", "seq": 7, "simTick": 142,
  "payload": { "readings": [
    { "id": "front-sensor", "value": 3.5 },
    { "id": "right-sensor", "value": null }
  ] } }
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

Payload schema (MVP iteration 1):

| Field | Type | Meaning |
|---|---|---|
| `robot.x` | number | Robot X position in world units |
| `robot.y` | number | Robot Y position in world units |
| `robot.heading` | number | Robot heading in radians (0 = +X axis) |

No obstacles in iteration 1 — bounded box dimensions are hardcoded in the
visualiser.

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

The sim maintains a **single slot of sensor readings + `simTick`**, overwritten
each tick:

- No queue.
- No polling.
- **One reading per declared proximity sensor** in the manifest. Each ray is
  cast from the robot centre at `heading + component.angle` (mount angle
  relative to heading; radians, 0 = forward, + = CCW, right = -π/2) and reports
  the nearest hit among obstacles and world box edges, or `null` when nothing
  is detected. There are no hardcoded front/right sensors.
- If the sim runs at 60 Hz and the robot reads at 20 Hz, ~2/3 of samples are
  silently dropped by being overwritten; the robot always sees the most
  recent reading.
- `simTick` is on every snapshot so a behaviour can check staleness later if
  needed, but no maximum-staleness check is required for MVP.

The `componentSnapshot` payload carries `{ readings: [{ id, value }] }`, keyed
by manifest component id (see section 6.1).

## 9. Summary table: message types per channel

| Channel | Message | Direction | simTick on every message? | MVP iteration 1 | Notes |
|---------|---------|-----------|---------------------------|-----------------|-------|
| Robot (`ROBOT_PORT`) | `componentCommand` | robot → sim | Yes | ✅ | Persistent actuator state; last command reused if robot is slow; payload: `{ commands: [{ id, value }] }`, wheel `value` in `[-1, 1]`, mapped via derived lateral role |
| Robot (`ROBOT_PORT`) | `componentSnapshot` | sim → robot | Yes | ✅ | Single-slot sensor model; one reading per declared proximity sensor at `heading + angle`; payload: `{ readings: [{ id, value }] }`, value number or `null` |
| Visualiser (`VISUALISER_PORT`) | `worldSnapshot` | sim → visualiser | Yes | ✅ | Drop-oldest backpressure; no blocking on visualiser; payload: `{ robot: { x, y, heading } }` |
| Visualiser (`VISUALISER_PORT`) | `userControl` | visualiser → sim | — | ❌ deferred | Start/pause/reset etc.; future iteration |

## 10. Deferred beyond MVP

- Heartbeat/timeout for hung-process detection (safe-stop relies on WebSocket
  close only).
- Binary framing (revisit if NDJSON parsing proves a bottleneck).
- `userControl` messages on the visualiser channel.
- Robot IDs / multi-robot support.
- Formal error protocol (malformed JSON is logged and ignored).
- World file format and static obstacles (iteration 2).
