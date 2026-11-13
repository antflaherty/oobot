# Sensor Specification — Iteration 2

Object Oriented Robotics (oobot)

**Status:** draft — refinement of the informal `specs/SENSORS.md` notes.
Not yet implemented. Sensors are deferred beyond MVP iteration 1 (see
`DESIGN_REVIEW.md` §5.1, `specs/WIRE_PROTOCOL.md` §10). Sensors and obstacles
arrive together in iteration 2.

---

## 1. Purpose

Defines the sensor model for iteration 2: a **single front-mounted proximity
sensor** that lets a robot behaviour react to what is ahead of it. This is the
perception half of the sense→plan→act loop:

1. The sim computes a reading each tick from the robot's pose `(x, y,
   heading)` and the current obstacle set.
2. The sim publishes it on `sensorSnapshot` (robot channel), single-slot,
   overwritten each tick, no queue, no polling.
3. Robot code reads it on each ~20 Hz behaviour update through the `Sensors`
   API and commands movement via `Mover`.

---

## 2. Guiding decisions (already accepted — do not reopen)

| Decision | Source |
|---|---|
| Single-slot sensor model: latest reading + `simTick`, overwritten each tick, no queue, no polling | `DESIGN_REVIEW` §1.3; `WIRE_PROTOCOL` §8 |
| `simTick` on every reading; robot judges staleness by `simTick`, never wall-clock | `WIRE_PROTOCOL` §5 |
| One robot only; no `robotId`; channel/port implies participant | `DESIGN_REVIEW` §5.3 |
| Robot is a point in space (pose = x, y, heading) | SENSORS.md notes |
| Sensors + obstacles arrive together in iteration 2 | `DESIGN_REVIEW` §6.2; `WORLD_FORMAT` iteration 2 |
| Robot code never touches sockets; reads sensors via static `Sensors` API | `COMPONENTS` §3 |
| `sensorSnapshot` message type already exists on the robot channel (empty payload today) | `WIRE_PROTOCOL` §6.1, §8 |

---

## 3. Sensor model

### 3.1 Single front-mounted proximity sensor

One sensor, rigidly mounted facing the robot's forward direction (its current
heading).

- **Origin:** robot's current position `(x, y)` (robot is a point).
- **Direction:** robot's current heading (radians, 0 = +X axis).
- **Shape:** a single ray (half-line) extending from the robot in its heading
  direction.
- **Range:** infinite by default — the ray extends until it hits something or
  reaches the world bounds. A configurable `maxRange` may cap it later
  (open question §9.5).

### 3.2 What the ray detects

The ray reports the distance to the **nearest hit** among:

1. **Obstacles** (static, defined by the iteration-2 world file; see
   `specs/WORLD_FORMAT.md` iteration 2).
2. **Box edges** (the bounded world box treated as walls).

If nothing is hit within range, the reading is "nothing detected"
(represented as `null` / absent on the wire, `Double.POSITIVE_INFINITY` in the
Java API).

### 3.3 Reading value

Each reading carries:

- `distance` — world units from the robot's front to the nearest hit, ≥ 0.
- `simTick` — the sim tick the reading was computed on (on the envelope, not
  the payload).

Robot code uses `distance` to decide behaviour (e.g. "if `distance < threshold`,
turn away").

---

## 4. Wire protocol changes

`sensorSnapshot` (sim → robot, robot channel) gains a payload. The envelope
stays `{ type, seq, simTick, payload }`.

Payload schema (iteration 2):

```json
{ "type": "sensorSnapshot", "seq": 7, "simTick": 142, "payload": { "distance": 3.5 } }
```

| Field   | Type    | Meaning                                               | Presence                        |
|---------|---------|-------------------------------------------------------|---------------------------------|
| `distance` | number | Distance in world units to nearest hit along front ray, ≥ 0 | present when something detected |
| `distance` | null   | Nothing detected within range                         | present when nothing detected   |

`simTick` is on the envelope as always (source of truth for staleness).

Example — something detected 3.5 world units ahead:

```json
{ "type": "sensorSnapshot", "seq": 7, "simTick": 142, "payload": { "distance": 3.5 } }
```

Example — nothing detected:

```json
{ "type": "sensorSnapshot", "seq": 8, "simTick": 143, "payload": { "distance": null } }
```

### 4.1 What does NOT change

- `motorCommand` payload `{ left, right }` — unchanged.
- `worldSnapshot` — unchanged (visualiser does not receive sensor readings;
  sensor visualisation is OOS, see §7).
- Envelope fields `type`, `seq`, `simTick` — unchanged.
- NDJSON framing — unchanged.
- Transport interface (`Transport`) — unchanged; `sensorSnapshot` is just
  another outgoing envelope sent via `transport.send(...)` (see `COMMS.md`
  §5.2).

---

## 5. Sim side

### 5.1 Sensor slot (single-slot model)

The sim keeps one sensor slot: `{ distance: number|null, simTick }`,
overwritten each tick. On every tick:

1. Read the robot's current pose `(x, y, heading)` from the world model.
2. Cast the front ray against the current obstacle set plus the world box
   edges (nearest hit wins).
3. Compute the nearest hit distance.
4. Overwrite the sensor slot with `{ distance, simTick }`.
5. Publish `sensorSnapshot` with that payload on the robot channel (existing
   publish path in `SimEngine.tick()`, non-blocking).

If no robot is connected, the slot is still overwritten; the send is a no-op.

### 5.2 Ray casting

Ray-vs-obstacle intersection is computed in the sim (TypeScript). Obstacle
geometry comes from the world model / world file. Exact obstacle shapes and
the intersection algorithm are fixed at scaffolding.

Ray-vs-box-edge: the world box is a rectangle centred at the origin. A ray
from an interior point always intersects exactly one edge (unless parallel to
an axis); distance to that edge is computable analytically.

### 5.3 When readings are available

- **Iteration 1 (current):** no obstacles exist → sensor always reports nothing
  detected. `sensorSnapshot` payload stays `{}` (placeholder), as today.
- **Iteration 2:** obstacles loaded from the world file → sensor reports real
  readings. The sim and world file arrive together; there is no sensor without
  something to sense.

---

## 6. Java robot framework API

### 6.1 `Sensors` class

```java
package oobot.robot;

public final class Sensors {
    private Sensors() {}

    private static Framework framework;

    static void setFramework(Framework f) {
        framework = f;
    }

    /** The sim tick of the most recent {@code sensorSnapshot} received. */
    public static long latestSimTick() {
        if (framework == null) {
            return 0L;
        }
        return framework.lastSensorSimTick();
    }

    /**
     * Distance in world units to the nearest obstacle/wall ahead of the robot,
     * along its front-facing ray.
     *
     * @return the distance, or {@code Double.POSITIVE_INFINITY} if nothing is
     *         detected within range.
     */
    public static double distance() {
        if (framework == null) {
            return Double.POSITIVE_INFINITY;
        }
        return framework.lastSensorDistance();
    }
}
```

- `latestSimTick()` already exists in iteration 1 (returns the tick of the last
  empty `sensorSnapshot`). Reused unchanged.
- `distance()` is new in iteration 2. The framework keeps the latest sensor
  slot (overwrite each tick) and exposes it here. Robot code calls this in
  `RobotBehaviour.update()` (~20 Hz).

The framework needs one new piece of state: `lastSensorDistance` (a
`volatile Double`, `null` when nothing detected), set when parsing a
`sensorSnapshot` envelope whose payload contains `distance`.

### 6.2 OOP / dependency injection

The notes ask for "nice OOP stuff" and "dependency injection to put mover and
behaviour into robot." The existing architecture already provides this
implicitly, and no new DI mechanism is required for iteration 2:

- `RobotBehaviour` is injected into `Framework` (constructor).
- `Mover` is injected into behaviours that need it (constructor or setter).
- `Sensors` is a static facade backed by the framework (set via
  `setFramework`).

A behaviour that wants sensors reads `Sensors.distance()` in `update()` and
commands movement via its `Mover`. If a behaviour later needs multiple sensor
instances or a mockable sensor for testing, a `Sensor` interface can be
introduced then — deferred, not part of iteration 2.

---

## 7. Visualiser

Sensor visualisation (drawing the ray, hit point, distance on canvas) is
**out of scope** for iteration 2 (SENSORS.md notes). The visualiser continues
to render `worldSnapshot` (robot pose + obstacles) only. Sensor readings stay
on the robot channel.

If desired later: the sim could include the sensor ray end-point in
`worldSnapshot`, or a new message on the visualiser channel. Deferred.

---

## 8. In scope / out of scope (iteration 2)

**In scope:**
- One front-mounted proximity sensor (single ray).
- Ray vs. obstacle intersection (obstacle shapes from the iteration-2 world
  file).
- `sensorSnapshot` payload with `distance` (number or `null` for nothing
  detected).
- `Sensors.distance()` Java API; existing `latestSimTick()` reused.
- Sim computes + publishes one reading each tick (single-slot, no queue).

**Out of scope:**
- More than one sensor (left/right pair, scanning sensor) — `FUTURE.md`.
- Sensor visualisation on the canvas.
- Moving obstacles.
- Sensor-based maze solving / goal-seeking (OOS: "A goal").
- Configurable sensor mount angle/offset (fixed facing forward at robot centre).
- A mockable `Sensor` interface / DI for testing.
- World file parsing lives in `specs/WORLD_FORMAT.md` iteration 2, not here.

---

## 9. Decisions (resolved)

| # | Question | Decision |
|---|----------|----------|
| 9.1 | Box edges as detectable walls? | **Yes** — the front ray reports the nearest hit among obstacles and the world box edges. Lets a robot wall-follow in an empty box; cheap analytic math. |
| 9.2 | Nothing-detected representation? | **`"distance": null`** when nothing detected; Java maps null → `Double.POSITIVE_INFINITY`. |
| 9.3 | Obstacle shapes? | **Axis-aligned boxes (walls)** to start. Circles can be added later. |
| 9.4 | Sensor mount? | **Fixed at robot centre, facing heading.** Robot is currently a point; keep it simple. Future non-point robots may need a configurable offset/angle — revisit then. |
| 9.5 | Range cap? | **Infinite within the box** — box edges act as the backstop (per 9.1). No `maxRange` config for iteration 2. |

---

## 10. Related specs

- `specs/WIRE_PROTOCOL.md` — envelope, `sensorSnapshot` message, single-slot model (§8)
- `specs/COMMS.md` — transport interface (unchanged; `sensorSnapshot` is just another outgoing envelope)
- `specs/WORLD_FORMAT.md` — iteration 2 world file + obstacles (what the ray hits)
- `DESIGN_REVIEW.md` §1.3, §5.1, §6.2 — accepted decisions this spec builds on
- `FUTURE.md` §"Sensors & perception" — post-iteration-2 ideas
