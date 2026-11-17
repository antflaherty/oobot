# Sensor Specification

Object Oriented Robotics (oobot)

**Status:** implemented. Proximity sensors are declared in the shared v2
manifest, read from `componentSnapshot` on the robot channel, and exposed to
robot code as id-bound `ProximitySensor` handles. The superseded static
`Sensors` facade and the old `sensorSnapshot { front, right }` payload no longer
exist.

---

## 1. Purpose

Defines the implemented sensing path:

1. The sim owns the world and casts one ray per manifest-declared proximity
   sensor every tick (~60 Hz).
2. It publishes one `componentSnapshot` per tick with one reading per sensor
   (single slot, no queue).
3. The Java bridge updates the matching sensor handle; behaviours read it on
   their ~20 Hz loop via their `Brain` and command movement through `Mover`.

## 2. Guiding decisions

| Decision | Contract / source |
|---|---|
| Component ids on the wire | Manifest ids in `readings[]`; no fixed sensor slots in the protocol (`ROBOT_DESCRIPTION.md`). |
| Fixed Java accessors (D-3) | `Brain.frontSensor()` → id `front-sensor`, `Brain.rightSensor()` → id `right-sensor`; validated at startup. Other sensors may be declared but are not addressable through `Brain`. |
| Single-slot delivery | Latest values overwrite the previous ones each tick; no queue, no polling (`WIRE_PROTOCOL.md` §8). |
| Freshness by `simTick` | `simTick` on each snapshot, never wall-clock (`WIRE_PROTOCOL.md` §5). |
| Mount geometry | `angle` in radians relative to heading: 0 = forward, + = CCW, right = -π/2; robot stays a point (`ROBOT_DESCRIPTION.md`). |
| Obstacles + box edges | Rays hit static axis-aligned obstacles from the world file and the world box edges; nearest hit wins (`WORLD_FORMAT.md`). |
| Session reset | Java invalidates readings on disconnect, reconnect and tick rollback; visualiser resets its own state (D-5). |

---

## 3. Sensor model

### 3.1 Declaration and placement

Sensors are `proximitySensor` entries in `robots/default.json` (or the
`--robot` manifest). Each declares `id` and `angle`; sensors must not declare
`offset` (they are point-mounted at the robot centre). The default manifest
declares:

| id | Mount `angle` | Direction at heading 0 |
|---|---|---|
| `front-sensor` | `0.0` | forward (+X) |
| `right-sensor` | `-1.5707963267948966` | right (−Y) |

Additional sensors with other exact angles are valid; the sim creates one
reading per declared sensor automatically.

### 3.2 Ray casting

For each sensor the sim computes the direction `pose.heading + component.angle`
and finds the nearest hit among:

1. static axis-aligned box obstacles loaded from the world file;
2. the bounded world box edges (treated as walls).

Range is effectively infinite within the box — there is no `maxRange` setting.
A ray starting on a face and moving outward does not report a spurious `t=0`
hit, so a robot resting against a wall can move away.

### 3.3 Reading values

The reported distance is in world units from the robot centre along the ray to
the nearest hit. If nothing is detected, the wire value is `null`; the Java
`distance()` returns `Double.POSITIVE_INFINITY`. Values are recomputed every
tick from the current pose; there is no history or sensor queue.

---

## 4. Wire protocol

`componentSnapshot` (sim → robot, robot channel) carries all readings:

```json
{ "type": "componentSnapshot", "seq": 7, "simTick": 142,
  "payload": { "readings": [
    { "id": "front-sensor", "value": 3.5 },
    { "id": "right-sensor", "value": null }
  ] } }
```

| Field | Type | Meaning |
|---|---|---|
| `readings` | array | One entry per declared `proximitySensor` |
| `readings[].id` | string | Manifest sensor id |
| `readings[].value` | number \| null | Distance in world units, or `null` when nothing detected |

`seq` and `simTick` are on the envelope; `simTick` is the freshness source.
Commands (the reverse direction) are separately gated by the D-4 seq/tick
checks in `WIRE_PROTOCOL.md` §5.1. NDJSON framing and the envelope are
unchanged.

### 4.1 What does NOT change

- Envelope `{ type, seq, simTick, payload }` and NDJSON framing.
- `componentCommand` payload `{ commands: [{ id, value }] }` for wheels.
- `worldSnapshot` carries the robot pose and obstacles, never sensor readings.
- The visualiser channel remains unidirectional.

---

## 5. Sim side

Each tick the sim:

1. reads the current pose `(x, y, heading)`;
2. for each manifest sensor, casts along `heading + angle` against obstacles
   and box edges, taking the nearest hit (`null` if none);
3. overwrites the single reading slot and publishes one `componentSnapshot` to
   the active robot connection (non-blocking).

When no robot is connected the readings are still computed, but the send is a
no-op. Sensor geometry comes from the manifest, not from behaviour code.

---

## 6. Java robot framework API

### 6.1 `ProximitySensor`

```java
public interface ProximitySensor extends Component {
    long latestSimTick();  // tick of the most recent reading; 0 after reset
    double distance();     // world units, or Double.POSITIVE_INFINITY
}
```

- One handle per manifest sensor, bound by id in `ComponentRegistry`.
- `distance()` returns the latest distance, or `Infinity` before the first
  reading and after a disconnect, reconnect or tick rollback.
- There is no static `Sensors.distance()` / `rightDistance()` facade.

### 6.2 `Brain` accessors (D-3)

```java
ProximitySensor frontSensor();  // id "front-sensor"
ProximitySensor rightSensor();  // id "right-sensor"
```

The controller validates both ids at startup and exits with a picocli usage
error (code 2) naming the missing id and manifest path if either is absent.
A manifest with additional sensors loads fine; this API simply cannot address
them. `DefaultBrain` is the reference DI implementation.

### 6.3 Staleness policy

`SimProximitySensor.latestSimTick()` lets behaviour code detect old data. The
bridge's documented threshold is `MAX_STALE_TICKS = 30` sim ticks (~0.5 s at
60 Hz): a reading is stale when the newest tick the behaviour knows is more
than 30 ticks ahead of `latestSimTick()`. Stale readings should be ignored even
though `distance()` still returns their numeric value. On a fresh session the
bridge resets tick and value, so `latestSimTick()` is 0 and `distance()` is
infinity.

---

## 7. Visualiser

Sensor rays, hit points and distances are **not** rendered. The visualiser
receives only `worldSnapshot` (robot pose + obstacles); readings stay on the
robot channel.

---

## 8. In scope / out of scope

**In scope (implemented):**

- Manifest-declared proximity sensors with mount `angle`.
- Ray vs. axis-aligned obstacles and box edges, nearest hit wins.
- `componentSnapshot.readings: [{ id, value }]`, `null` for no detection.
- `ProximitySensor.latestSimTick()` / `distance()` with `Infinity` mapping.
- Fixed-id `Brain` accessors and startup validation (D-3).
- Session invalidation and staleness detection by behaviours.

**Out of scope:**

- More sensors via `Brain` (fixed-id constraint), scanning sensors.
- Sensor visualisation, moving obstacles, configurable range cap.
- Sensor-driven goal seeking beyond the registered wall-follow/avoid
  behaviours; a generic `Sensor` DI mechanism for arbitrary ids.

---

## 9. Resolved decisions

| # | Question | Decision |
|---|---|---|
| 9.1 | Box edges detectable? | Yes — nearest hit among obstacles and box edges; permits wall-following in an empty box. |
| 9.2 | Nothing-detected representation? | `null` on the wire; `Double.POSITIVE_INFINITY` in Java. |
| 9.3 | Obstacle shapes? | Axis-aligned boxes (walls) from the world file. |
| 9.4 | Sensor mount? | Declared `angle` in the manifest; ray originates at robot centre at `heading + angle`. |
| 9.5 | Range cap? | None within the box; box edges are the backstop. |
| 9.6 | Stale readings? | Detectable via `latestSimTick()`; 30-tick guidance; reset on reconnect/rollback. |

---

## 10. Related specs

- `specs/WIRE_PROTOCOL.md` — envelope, `componentSnapshot`, D-2/D-4
- `specs/COMPONENTS.md` — sim and Java framework responsibilities
- `specs/ROBOT_DESCRIPTION.md` — manifest schema and validation fixtures
- `specs/WORLD_FORMAT.md` — world file, obstacles and start pose
- `docs/DECISIONS.md` — locked decisions D-1…D-6
