# Wire Protocol Specification

Object Oriented Robotics — MVP

**Status:** current. Describes the implemented v2 component protocol and the
post-hardening connection rules, including manifest-declared tools and their
state in `worldSnapshot`, the `goalSensor` reading and the world's goal area in
`worldSnapshot`. Binding decisions: `docs/DECISIONS.md` D-2 and D-4. The
envelope and NDJSON framing are unchanged from the original design;
`motorCommand` and `sensorSnapshot` were replaced by the id-addressed messages
below.

## 1. Purpose

This document defines the wire contract between the simulation engine, the
Java robot controller and the browser visualiser. It is language-independent
JSON over local WebSockets: one robot, no discovery, and no error protocol
beyond "log and ignore malformed input".

## 2. Transport: two WebSocket channels

Communication uses **two separate WebSocket ports** from a single sim server
process. The channel/port implies who is talking — there is no `clientType` or
`clientId` field in messages.

| Channel | URL | Direction | Carries |
|---------|-----|-----------|---------|
| Robot channel | `ws://localhost:ROBOT_PORT` (default `8080`) | Bidirectional | `componentCommand` (robot → sim), `componentSnapshot` (sim → robot) |
| Visualiser channel | `ws://localhost:VISUALISER_PORT` (default `8081`) | **Unidirectional (sim → visualiser)** | `worldSnapshot` only |

- The robot controller (Java process) connects to the robot channel.
- The visualiser (browser) connects to the visualiser channel. It sends no
  messages; `userControl` remains deferred and is not implemented.
- One robot only. The robot slot is single-occupancy: a new robot connection
  closes the previous one, and commands are accepted only from the active slot
  (§5.1). Robot IDs are absent from the protocol.
- The ports default to 8080/8081 and can be overridden by `ROBOT_PORT` /
  `VISUALISER_PORT`. Both values must be integers in 1–65535; invalid values
  fail startup loudly.

### 2.1 Bind address and Origin policy

Both WebSocket servers bind **`0.0.0.0`** (IPv4 wildcard), not the literal
hostname `localhost`. This is deliberate: `localhost` can resolve to IPv6-only
on macOS, which breaks IPv4-only clients such as Java's socket connection.
Therefore do not describe the servers as "bound to localhost only".

As browser-oriented defence in depth, the transport checks the WebSocket
`Origin` header on both channels:

- no `Origin` header (the normal case for the Java client) → allowed;
- `Origin: null`, a non-http(s) scheme, or a hostname other than `localhost`,
  `127.0.0.1`, `::1` / `[::1]` → rejected with close code 1008 and
  `origin not allowed`.

The Origin check is **not** a network firewall — the wildcard bind is still
reachable from other interfaces. `listen()` awaits both servers' `listening`
events before the sim starts; a bind failure such as `EADDRINUSE` rejects with
the original error, any successfully bound sibling server is closed, and the
sim server exits non-zero fast instead of hanging. Shutdown terminates open
sockets and caps each server close wait at ~2 s so a stuck peer cannot stall
process exit.

## 3. Message framing: NDJSON

All messages use **newline-delimited JSON (NDJSON)**:

- Exactly one JSON object per line, terminated by `\n`.
- A receiver buffers text until a newline, then parses one complete object.
  Both the Java client and the server buffer lines split across frames.
- Empty lines are skipped. Malformed JSON, non-object JSON or a missing
  `type`/`seq` is logged and ignored — never a crash and never a disconnect on
  bad input alone.
- Inbound lines are capped at **64 KiB** (including a trailing partial line) on
  the server. An oversized line is logged and the connection is terminated so
  a peer that never sends a newline cannot grow the buffer without bound.
  Terminating an active robot connection still triggers the sim-side safe stop.
- Outbound envelopes are cloned per connection before the transport assigns
  `seq`; fan-out to several visualisers therefore yields independent
  monotonic sequences. Sends are non-blocking with a depth-1 pending slot per
  connection (a new snapshot replaces an unpublished one).

## 4. Baseline message envelope

Every message on either channel carries the same envelope:

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
| `type` | Message kind: `componentCommand`, `componentSnapshot`, `worldSnapshot`. `userControl` is defined as a future idea only. |
| `seq` | Per-connection sequence number. On outbound messages the transport assigns it from 0 independently for each connection. On inbound `componentCommand` it must be a finite non-negative integer and is checked for duplicates/out-of-order delivery (§5.1). |
| `simTick` | Simulation logical tick the message relates to. The **source of truth** for freshness; inbound `componentCommand` must also carry a finite non-negative integer here (§5.1). |
| `payload` | Message-specific data (§6). |

### Wall-clock timestamps

`simTick` — not wall-clock — is the sequence source of truth. Wall-clock
timestamps may be logged for debugging only. Staleness is never judged by
wall-clock drift across processes.

## 5. Clock & sequencing

- The sim owns the authoritative logical clock (`simTick`), advancing one tick
  per ~60 Hz sim step.
- Every snapshot carries the `simTick` of the world state it describes.
- The robot echoes the most recent `simTick` it has seen in every
  `componentCommand`. It is required (not merely for debugging) because the sim
  uses it for the freshness check in §5.1.
- A receiver judges freshness by comparing `simTick` values, never wall-clock
  times between the robot and sim processes.

### 5.1 Command acceptance on the robot channel (D-4)

The sim tracks `lastAppliedSeq = -1` and `lastSeenTick = null` per active robot
connection and resets both when the active robot slot changes. It accepts a
`componentCommand` only when **all** of the following hold, in this order:

1. `channel === "robot"` and `connectionId === activeRobotConnectionId`.
   Anything else is logged and ignored before any state is touched.
2. `seq` and `simTick` are finite, non-negative integers. `NaN`, `Infinity`,
   fractional values, negatives and missing values are ignored.
3. `seq > lastAppliedSeq`; otherwise it is a duplicate or out-of-order command.
4. `lastSeenTick === null || simTick >= lastSeenTick - 1`. A command more than
   one tick older than the last seen tick is stale. Exactly one tick of slack
   is allowed for benign 20 Hz / 60 Hz reordering.

Only on acceptance does the sim update `lastAppliedSeq = seq` and
`lastSeenTick = max(lastSeenTick ?? simTick, simTick)` before applying the
command to wheel/tool state. Rejected commands update neither value and never
touch actuators or tools. Commands from a visualiser connection, from a displaced
robot connection, or with a malformed payload are logged and ignored.

## 6. Message types

### 6.1 Robot channel

Commands and readings carry component `id`s from the shared manifest
(`specs/ROBOT_DESCRIPTION.md`, `robots/default.json`) instead of fixed
left/right/front/right slots. `motorCommand` and `sensorSnapshot` are removed
(breaking change). The envelope and NDJSON framing are unchanged.

#### `componentCommand` — robot → sim

Persistent actuator state: wheels keep their last commanded value and tools
keep their active/inactive state until overridden. The sim never waits for the
robot; if the robot is slow, the sim reuses the previous command. The Java
bridge batches all wheel and tool values changed in one behaviour tick into a
single frame (~20 Hz).

Example:

```json
{ "type": "componentCommand", "seq": 7, "simTick": 142,
  "payload": { "commands": [
    { "id": "left-wheel", "value": 1.0 },
    { "id": "right-wheel", "value": -1.0 },
    { "id": "front-light", "value": 1.0 }
  ] } }
```

Payload schema:

| Field | Type | Range | Meaning |
|-------|------|-------|---------|
| `commands` | array | — | One entry per commanded component |
| `commands[].id` | string | manifest id | Target component id (e.g. `left-wheel`, `front-light`) |
| `commands[].value` | number | `[-1, 1]` for wheels; exactly `0` or `1` for tools | Commanded value; the sim clamps wheel values and ignores non-`0`/`1` tool values |

Wheel `value` maps to the wheel's derived lateral role (`sin(angle) > 0.9` →
left, `< -0.9` → right). `1.0` = full forward, `-1.0` = full reverse, `0` =
stop. Tool `value` is a boolean flag: `1.0` = active, `0.0` = inactive; any
other value is ignored. The sim tracks tool state per declared tool id; tools
have no effect on kinematics, footprint, collision or track width. Unknown ids,
wrong component types and malformed entries are logged and ignored; the rest of
the frame is still applied. Acceptance also requires §5.1.

Commands targeting a `goalSensor` id (e.g. `goal-sensor`) are read-only: the sim
ignores them (counting/logging if convenient) and applies nothing.

Safe-stop uses this message type with one entry per bound wheel id at `0.0`
(§7), written on the still-open socket before the Java client closes it; that
final frame is wheels-only.

#### `componentSnapshot` — sim → robot

Published by the sim every tick to the active robot connection. The robot reads
the latest available value on its behaviour loop (~20 Hz). There is **one
reading per declared proximity sensor and one per declared `goalSensor`** in the
manifest. Each proximity ray is cast from the robot centre at
`heading + component.angle` (mount angle relative to heading; radians, 0 =
forward, + = CCW, right = -π/2); a value of `null` means nothing was detected
and the Java API maps it to `Double.POSITIVE_INFINITY`. The goal sensor reports
whether the robot centre is inside the world's goal area (see
`specs/WORLD_FORMAT.md`). There are no hardcoded front/right/goal sensors on the
wire — the fixed `front-sensor` / `right-sensor` / `goal-sensor` accessor ids are
a documented Java `Brain` constraint only (D-3, see
`specs/ROBOT_DESCRIPTION.md`).

Payload schema:

| Field | Type | Meaning |
|-------|------|---------|
| `readings` | array | One entry per declared `proximitySensor` plus one per declared `goalSensor` |
| `readings[].id` | string | Sensor component id (e.g. `front-sensor`, `goal-sensor`) |
| `readings[].value` | number \| null | For a proximity sensor: distance in world units to the nearest hit, or `null` when nothing is detected. For a `goalSensor`: `1` = inside the goal, `0` = outside, `null` = the world has no goal (numeric because `ComponentReading.value` is `number \| null`) |

Example — front wall 3.5 units ahead, right sensor detects nothing, robot inside
the goal:

```json
{ "type": "componentSnapshot", "seq": 7, "simTick": 142,
  "payload": { "readings": [
    { "id": "front-sensor", "value": 3.5 },
    { "id": "right-sensor", "value": null },
    { "id": "goal-sensor", "value": 1 }
  ] } }
```

The Java bridge maps a `goalSensor` reading of `1`/`0`/`null` to a
`Sensor<Boolean>` whose `value()` is `true`/`false`/`false` respectively;
`false` also covers missing/stale data (`Sensor.MAX_STALE_TICKS`).

### 6.2 Visualiser channel

The visualiser channel is unidirectional in the MVP: the sim publishes and the
visualiser only listens (D-5 describes client-side state reset, not an
upstream message).

#### `worldSnapshot` — sim → visualiser

Published by the sim every tick to all connected visualisers. The sim publishes
without waiting for a visualiser; per client at most one unpublished snapshot
is queued and a newer snapshot replaces it (drop-oldest). Disconnected
visualisers are removed from the subscriber list — no retry or buffering on the
sim side.

Example (matching the default `worlds/maze.json` obstacle convention):

```json
{ "type": "worldSnapshot", "seq": 142, "simTick": 142,
  "payload": {
    "robot": {
      "x": 2.4, "y": -1.1, "heading": 0.78,
      "shape": {
        "width": 50, "height": 50,
        "wheels": [
          { "angle":  1.5707963267948966, "offset": 25, "length": 30, "width": 10 },
          { "angle": -1.5707963267948966, "offset": 25, "length": 30, "width": 10 }
        ]
      },
      "toolStates": [ { "id": "front-light", "active": true } ]
    },
    "obstacles": [ { "x": -180, "y": 40, "width": 120, "height": 120 } ],
    "goal": { "x": -220, "y": -240, "width": 80, "height": 80 }
  } }
```

Payload schema:

| Field | Type | Meaning |
|-------|------|---------|
| `robot.x` | number | Robot X position in world units |
| `robot.y` | number | Robot Y position in world units |
| `robot.heading` | number | Robot heading in radians (0 = +X axis) |
| `robot.shape` | object (optional) | `{ width, height, wheels: [{ angle, offset, length, width }] }`; all dimensions and `offset` in **world units**, wheel `angle` in radians. Derived from the manifest with D-1's ×100 conversion. |
| `robot.toolStates` | array (optional) | One `{ id, active }` entry per declared `tool`, in manifest order. `id` is the manifest id; `active` is `true` for commanded `1.0`, `false` for `0.0`. |
| `obstacles` | array of `{ x, y, width, height }` | Static axis-aligned box obstacles, centre-based (`x`,`y` = centre; `width`,`height` = full extents). May be empty. |
| `goal` | `{ x, y, width, height }` \| null | Optional goal area from the world file, centre-based with full extents. The sim always emits this field, using `null` when the world declares no goal; the visualiser treats absent, `null` or malformed values as "no goal drawn". |

The sim always sends `robot.shape`, `robot.toolStates` and `goal`; all three are
optional in the schema so older snapshots remain valid. The visualiser uses
`shape` to draw the square body and wheels (falling back to the same default
geometry when absent) and caches `toolStates` to draw tool indicators. It draws
the `goal` rectangle (before obstacles/robot) when the field is a valid area.
Missing `toolStates` are treated as all-inactive; the cached tool states and
goal are cleared on connect / reconnect and on a decreasing `simTick` (D-5).
Envelope and all other payload fields are unchanged.
`worldSnapshot` does not carry sensor readings. The visualiser renders the
robot, its tool indicators, the box, the goal and these obstacles; it never
drives sim timing. The brain turns `front-light` ON (visible as an active tool
indicator) when the goal sensor reports arrival, whatever behaviour is running;
`wall-follow-right` is the default behaviour.

#### `userControl` — visualiser → sim (deferred, not implemented)

No payload schema is defined and no such message is sent in the MVP. The
channel remains documented as unidirectional.

## 7. Safe-stop on disconnect (D-2)

Safe-stop is a two-layer contract; each layer works independently.

1. **Sim (layer A, ultimate safety net).** On close of the **active** robot
   connection, and on sim shutdown, the sim zeros its persistent wheel state
   **and clears all tool states to inactive** before the next tick can use
   them. A stale/non-active robot disconnect must not stop the active robot.
   This layer does not depend on Java behaving correctly.
2. **Java (layer B).** On every orderly disconnect, shutdown or reconnect the
   controller stops the behaviour tick, builds **one** `componentCommand` with
   an entry for every bound wheel id at `0.0` (using the next unused `seq` and
   the last seen `simTick`), writes and flushes it while the socket is still
   open, and only then clears `open`, calls `close()` and schedules any
   reconnect. This final zero frame is **wheels-only** (D-2 is unchanged); the
   sim clears tools locally. The Java bridge force-resyncs tool state after a
   reconnect. If the socket is already dead this layer is best-effort; layer A
   still stops the motors.

Persistent actuator state means motors run until overridden, so a clean
disconnect must explicitly stop them; there is no reliance on commands
expiring. **Heartbeat/timeout for hung-but-connected processes is deferred
beyond the MVP**; a TCP socket can stay open while a process is dead, and
manual intervention covers that for now.

## 8. Sensor model

The sim keeps a single slot of readings per declared sensor: one value per
proximity sensor and one per `goalSensor`, overwritten every tick:

- No queue, no polling; the robot always sees the most recent reading.
- Each proximity ray originates at the robot centre with direction
  `heading + component.angle` and reports the nearest hit among the world box
  edges and the static obstacles from the world file, or `null` when nothing
  is detected. A goal sensor instead reads the world goal area and reports
  `1` (robot centre inside), `0` (outside) or `null` (world has no goal).
- `simTick` on every snapshot is the freshness reference. The Java bridge
  invalidates both sensor maps (distance → `Infinity` / goal → `false`, tick →
  0) on disconnect, reconnect and tick rollback; `Sensor<T>.latestSimTick()`
  lets behaviour code detect stale data, with staleness threshold
  `Sensor.MAX_STALE_TICKS` (30 sim ticks). See `specs/SENSORS.md`.
- Sensor readings travel only on