# Robot Description & Component Registry (v2)
Canonical contract for the shared component manifest and id-addressed wire
messages. The manifest is checked into the repo and loaded independently by
the Java controller and the sim at startup.

## Conventions (fixed)

| Area | Decision |
|---|---|
| Manifest | Shared JSON (`robots/default.json`), loaded by Java + sim at startup |
| Schema | v2; known types `wheel`, `proximitySensor` |
| Geometry | Wheels placed by mount `angle` (radians, 0 = forward, + = CCW) plus radial `offset`; robot stays a point for sensing/collision |
| Wheels | Roll tangentially along heading; no `side` field — left/right derived from `sin(angle)` |
| Sensors | Sim uses the declared `angle` for rays from the robot centre (no hardcoded front/right) |
| Track width | `y_left - y_right` from derived wheel positions; replaces `DEFAULT_WORLD_CONFIG.trackWidth` at sim construction |
| Layouts | Change angles/offsets via the manifest; different backends via `RobotPlatform` |
| Java API | Fully generic — no `leftWheel()`, `rightWheel()`, `Sensors`; derived roles, `Wheel.angle()` |
| Wire | Generalised to component ids (**breaking**); envelope + NDJSON unchanged |
| Visualiser | Deferred |

Wheel position = `offset * (cos(angle), sin(angle))`; lateral coordinate `y`.
Derived role: `sin(angle) > 0.9` → left, `sin(angle) < -0.9` → right (exactly
one of each; other angles rejected). Default track width = `0.5`.

## Manifest — `robots/default.json`

```json
{
  "schemaVersion": 2,
  "name": "default-two-wheel",
  "components": [
    { "id": "left-wheel",   "type": "wheel",           "angle": 1.5707963267948966,  "offset": 0.25 },
    { "id": "right-wheel",  "type": "wheel",           "angle": -1.5707963267948966, "offset": 0.25 },
    { "id": "front-sensor", "type": "proximitySensor", "angle": 0.0 },
    { "id": "right-sensor", "type": "proximitySensor", "angle": -1.5707963267948966 }
  ]
}
```

Validation — identical in Java and TS; `schemaVersion` must be `2`; `side`
rejected if present; other unknown extra fields ignored:

| Rule | Requirement |
|---|---|
| `id` | unique, non-empty |
| `type` | ∈ {`wheel`, `proximitySensor`} |
| `angle` | finite number (radians, 0 = forward, + = CCW) |
| `schemaVersion` | must be `2`, else fail fast naming the file path |
| `side` | rejected if present |
| `wheel.offset` | optional, default `0.25`; finite and > 0 when present |
| `proximitySensor.offset` | must not be declared |
| `wheel` angle | `sin(angle) > 0.9` → left, `sin(angle) < -0.9` → right; exactly one of each; other angles rejected |

CLI: both processes accept `--robot <path>`; the dev script passes an absolute path.

## Wire protocol — envelope unchanged `{ type, seq, simTick, payload }`

```json
{ "type": "componentCommand", "seq": 7, "simTick": 142,
  "payload": { "commands": [
    { "id": "left-wheel", "value": 1.0 },
    { "id": "right-wheel", "value": -1.0 }
  ] } }

{ "type": "componentSnapshot", "seq": 7, "simTick": 142,
  "payload": { "readings": [
    { "id": "front-sensor", "value": 3.5 },
    { "id": "right-sensor", "value": null }
  ] } }
```

| Message | Direction | Payload |
|---|---|---|
| `componentCommand` | robot → sim | `commands: [{ id, value }]` |
| `componentSnapshot` | sim → robot | `readings: [{ id, value }]` |

- `motorCommand` and `sensorSnapshot` are **removed** (breaking change).
- Wheel `value` ∈ `[-1, 1]`, clamped by sim; mapped to the wheel's derived
  lateral role. Persistent actuator state and safe stop are unchanged.
- Sensor `value` = world-unit distance, or `null` when nothing is detected.
- Unknown id / wrong type / malformed value → log and ignore.
- `worldSnapshot` unchanged.

## Component APIs (summary)

| Layer | Type | Shape |
|---|---|---|
| `oobot.robot.student` | `Component` | `String id()` |
| | `Wheel extends Component` | `double angle()` (mount angle, radians, 0 = forward, + = CCW), `clockwise()`, `antiClockwise()`, `stop()` |
| | `ProximitySensor extends Component` | `long latestSimTick()`, `double distance()` (`Sensors.java` deleted) |
| `oobot.robot.description` | `ComponentType` | `WHEEL`, `PROXIMITY_SENSOR` |
| | `ComponentDescription` | `(String id, ComponentType type, double angle, double offset)` (`offset` default `0.25`; `WheelSide` deleted) |
| | `RobotDescription` | `(String name, List<ComponentDescription> components)`, `static load(Path)` via Jackson + validation (v2 only) |
| | `ComponentRegistry` | `bind(id, Component)` (once at startup, no locking), `component(id)`, `wheel(id)`, `sensor(id)`, `wheels()` (angle-sorted bound `Wheel` handles), `all()` |
| `oobot.robot.bridge` | `RobotPlatform` | `components()`, `run(Brain)`, `stop()` (extends `AutoCloseable`) |
| `oobot.robot.transport` | records | `ComponentValue(id, value)`, `ComponentReading(id, Double value)`, `ComponentCommandPayload(commands)`, `ComponentSnapshotPayload(readings)`, `ComponentCommandMessage(seq, simTick, payload)`, `ComponentSnapshotMessage(seq, simTick, payload)` |
| `oobot.robot.transport` | `Json` | `writeComponentCommand(seq, simTick, commands)`; `parseMessage` handles both new types; delete `MotorCommandMessage`, `SensorSnapshotMessage`, `MotorPayload`, `SensorPayload` |
| `oobot.robot.bridge.sim` | — | `SimWheel(robot, id, angle)`, `SimProximitySensor(robot, id)` (`update(tick, value)`, null → +∞), `SimRobot(host, port, description)` (`setWheelValue` clamps, `wheelChanged` batches; binds every handle into its registry) |
| server `robotDescription.ts` | — | `ComponentType`, `ComponentDescription { id, type, angle, offset }`, `RobotDescription`, `loadRobotDescription(path)`, `ComponentRegistry` (`get`, `wheels`, `sensors`, `wheelPair()`, `trackWidth()`) |
| server `types.ts` | — | `ComponentValue`, `ComponentReading`, `ComponentCommandPayload`, `ComponentSnapshotPayload` |
| server `sim.ts` | — | per sensor: `nearestHit(heading + angle)` from robot centre; `WorldConfig.trackWidth` overridden from manifest; no physics changes |

Left/right are derived, not declared: `sin(angle) > 0.9` → left,
`sin(angle) < -0.9` → right (`WheelSide` deleted in both Java and TS).
