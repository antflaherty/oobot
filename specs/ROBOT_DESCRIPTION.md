# Robot Description & Component Registry (v2)

Canonical contract for the shared component manifest and id-addressed wire
messages. The manifest is checked into the repo (`robots/default.json`) and
loaded independently by the Java controller and the sim at startup. The
visualiser does not read it.

## Conventions (fixed)

| Area | Decision |
|---|---|
| Manifest | Shared v2 JSON, loaded by Java (`RobotDescription.load(Path)`) and sim (`loadRobotDescription(path)`) at startup |
| Schema | `schemaVersion` must be the integer literal `2`; known types are exactly `wheel`, `proximitySensor`, `goalSensor` and `tool` |
| Geometry | Wheels placed by mount `angle` (radians, 0 = forward, + = CCW) plus radial `offset`; sensing is centre-based, collision uses the rotated body + wheel footprint. Tools and goal sensors declare `angle` but no `offset` and have no geometry, kinematics or track-width effect |
| Body | Optional top-level `body` (metres): `width`, `height`, `wheels.length`, `wheels.width`; defaults `0.5` / `0.5` / `0.3` / `0.1`; D-1 ×100 → world units |
| Wheels | Roll tangentially along heading; no `side` field — left/right derived from `sin(angle)` |
| Sensors | Sim casts each `proximitySensor` ray from the robot centre at `heading + component.angle`; sensors have no offset. `goalSensor` casts no ray — it reports whether the robot centre is inside the world's goal area |
| Track width | `y_left - y_right` from derived wheel positions; overrides `DEFAULT_WORLD_CONFIG.trackWidth` in the sim |
| Kinematics (D-1) | `WORLD_UNITS_PER_METRE = 100`; wheel powers → m/s for angular rate, linear speed stays in world units/s; no `maxAngularSpeed` clamp |
| Java API | Component model is id-addressed, but `Brain.frontSensor()` / `rightSensor()` / `goalSensor()` are fixed-id accessors (D-3); distance sensors are `Sensor<Double>` and the goal sensor is `Sensor<Boolean>`; `Brain.tool()` returns the single manifest-declared tool (or `Tool.NOOP`); no generic sensor lookup, `leftWheel()`, `rightWheel()` or static `Sensors` |
| Wire | Id-addressed `componentCommand` (wheel values in `[-1, 1]`, tool values exactly `0`/`1`) / `componentSnapshot` (goal-sensor reading `1`/`0`/`null`); `worldSnapshot` reports tool activity and the goal area; envelope and NDJSON unchanged |
| Visualiser | Renders obstacles, the goal area, robot shape and tool state from `worldSnapshot`; does not read the manifest |

Wheel position = `offset * (cos(angle), sin(angle))`; lateral coordinate `y`.
Derived role: `sin(angle) > 0.9` → left, `sin(angle) < -0.9` → right. Exactly
one of each is required; all other angles are rejected. Default track width
for the shipped manifest is `0.5`.

Sensors still cast from the robot centre, so readings and behaviour thresholds
are unchanged. Collision instead uses the manifest-derived footprint: three
oriented boxes — the body at the pose centre and one per wheel at its mount
offset, each wheel's long axis along the robot's heading. D-1's
`WORLD_UNITS_PER_METRE = 100` converts the metre dimensions to world units.

## Manifest — `robots/default.json`

```json
{
  "schemaVersion": 2,
  "name": "default-two-wheel",
  "body": { "width": 0.5, "height": 0.5, "wheels": { "length": 0.3, "width": 0.1 } },
  "components": [
    { "id": "left-wheel",   "type": "wheel",           "angle": 1.5707963267948966,  "offset": 0.25 },
    { "id": "right-wheel",  "type": "wheel",           "angle": -1.5707963267948966, "offset": 0.25 },
    { "id": "front-sensor", "type": "proximitySensor", "angle": 0.0 },
    { "id": "right-sensor", "type": "proximitySensor", "angle": -1.5707963267948966 },
    { "id": "front-light",  "type": "tool",            "angle": 0.0 },
    { "id": "goal-sensor",  "type": "goalSensor",      "angle": 0.0 }
  ]
}
```

## Validation (identical decisions in Java and TypeScript)

Both loaders must accept/reject the same inputs. The authoritative fixture set
is specified in `docs/DECISIONS.md` and implemented byte-identically in
`simulator/server/test/fixtures/` and
`robot-framework/src/test/resources/fixtures/`.

| Field | Rule |
|---|---|
| `schemaVersion` | Integer literal `2` only. `1`, `2.0`, strings, missing values rejected. TS inspects the raw token because `JSON.parse("2.0")` yields `2`; Java requires an integral `IntNode`. |
| `name` | String, non-blank after `trim()`. |
| `body` | Optional; when present must be a non-null object (arrays rejected). Unknown keys ignored. |
| `body.width`, `body.height` | Optional, default `0.5`; when present must be finite numbers `> 0`. |
| `body.wheels` | Optional; when present must be a non-null object. |
| `body.wheels.length`, `body.wheels.width` | Optional, defaults `0.3` / `0.1`; when present must be finite numbers `> 0`. |
| `components` | Array (required). |
| `id` | String, non-blank after `trim()`, unique across the manifest. |
| `type` | Exactly `wheel`, `proximitySensor`, `goalSensor` or `tool` (case-sensitive; no aliases). |
| `angle` | Finite number (radians; 0 = forward, + = CCW), required for every type including `tool` and `goalSensor`. |
| `wheel.offset` | Optional, default `0.25`; when present must be finite and `> 0`. |
| `proximitySensor.offset` | Forbidden — reject if the key is present. |
| `goalSensor.offset` | Forbidden — reject if the key is present; the goal sensor is centre-based. |
| `tool.offset` | Forbidden — reject if the key is present; tools have no geometry. |
| `side` | Forbidden on any component — reject if the key is present. |
| Wheel roles | Exactly one wheel with `sin(angle) > 0.9` (left) and one with `sin(angle) < -0.9` (right); other angles rejected. |
| Unknown extra fields | Ignored, **except** the explicitly forbidden `side`. |

**Error-message contract.** Every failure message names the manifest path as
supplied to the loader. Component-scoped failures additionally include the
0-based index as `component[<i>]`. `body`-scoped failures name the path plus the
field, e.g. `body.width` or `body.wheels.length`. Manifest-level failures (bad
version, blank name, wrong wheel roles) still name the path; no index is
required. Messages need not be byte-identical between languages.

### Fixture set (from `docs/DECISIONS.md`)

| Accept | Reject |
|---|---|
| `valid-default.json` | `reject-schema-v1.json` |
| `accept-body.json` | `reject-body-width-zero.json` (`body.width = 0`) |
| `accept-proximity-sensor-spelling.json` | `reject-body-wheels-non-finite.json` (`body.wheels.length` non-finite) |
| `accept-sensor-without-offset.json` | `reject-body-not-object.json` (`body` not an object) |
| `accept-alternative-angles.json` | `reject-schema-float.json` |
| | `reject-side-field.json` |
| | `reject-duplicate-ids.json` |
| | `reject-blank-id.json` |
| | `reject-blank-name.json` |
| | `reject-non-finite-angle.json` |
| | `reject-wheel-offset-zero.json` |
| | `reject-wheel-offset-negative.json` |
| | `reject-two-left-wheels.json` |
| | `reject-no-right-wheel.json` |
| | `reject-sensor-with-offset.json` |

`valid-default.json` is a byte-identical copy of `robots/default.json`, which
now includes the optional `body` block and six components (including the
`front-light` tool and the `goal-sensor` goal sensor) shown above. The four
`*-body*` fixtures are the geometry additions; every fixture remains
byte-identical in both fixture directories.

These fixtures test manifest validation only. The D-3 checks for the fixed
`front-sensor` / `right-sensor` / `goal-sensor` ids and the controller's one-tool
rule (fail at startup if more than one `tool` is declared) are separate
controller startup checks (`specs/ROBOT_LAUNCH.md`) and must not be folded into
these rules. The sim accepts any number of tools; the Java controller injects
the single tool as `Tool tool()` or `Tool.NOOP` when none is declared.

CLI: both processes accept `--robot <path>`; `scripts/dev.js` and
`scripts/runRobot.js` pass an absolute path. The sim also accepts `--world
<path>` for obstacles, the optional goal area and the start pose
(`specs/WORLD_FORMAT.md`).

## Wire protocol — envelope unchanged `{ type, seq, simTick, payload }`

```json
{ "type": "componentCommand", "seq": 7, "simTick": 142,
  "payload": { "commands": [
    { "id": "left-wheel", "value": 1.0 },
    { "id": "right-wheel", "value": -1.0 },
    { "id": "front-light", "value": 1.0 }
  ] } }

{ "type": "componentSnapshot", "seq": 7, "simTick": 142,
  "payload": { "readings": [
    { "id": "front-sensor", "value": 3.5 },
    { "id": "right-sensor", "value": null },
    { "id": "goal-sensor", "value": 1 }
  ] } }
```

| Message | Direction | Payload |
|---|---|---|
| `componentCommand` | robot → sim | `commands: [{ id, value }]` |
| `componentSnapshot` | sim → robot | `readings: [{ id, value }]` |
| `worldSnapshot` | sim → visualiser | `robot: { x, y, heading, shape?, toolStates? }` (shape in world units) plus `obstacles: [...]` and `goal` (see `WIRE_PROTOCOL.md` §6.2) |

- `motorCommand` and `sensorSnapshot` are **removed** (breaking change).
- Wheel `value` ∈ `[-1, 1]`, clamped by the sim and mapped via the derived role.
  Tool `value` is exactly `1.0` (active) or `0.0` (inactive); other values are
  ignored. Persistent state applies to wheels and tools; safe stop has two
  layers (D-2) and commands are gated by connection, `seq` and `simTick`
  freshness (D-4).
- Proximity-sensor `value` is a world-unit distance, or `null` for no detection
  (Java: `Infinity`). The `goal-sensor` reading is `1` (inside the goal), `0`
  (outside) or `null` (world has no goal). Commands targeting `goal-sensor` are
  read-only and ignored. `worldSnapshot` never carries sensor readings; it does
  carry `robot.toolStates` and the world's `goal` area.
- Unknown id / wrong component type / malformed value → log and ignore.
- The visualiser channel remains unidirectional; no `userControl` exists.

## Component APIs (summary)

| Layer | Type | Shape |
|---|---|---|
| `oobot.robot.student` | `Component` | `String id()` |
| | `Wheel extends Component` | `double angle()` (radians; `sin > 0.9` left, `< -0.9` right), `clockwise()`, `antiClockwise()`, `stop()` |
| | `Sensor<T> extends Component` | `long MAX_STALE_TICKS = 30L`, `long latestSimTick()` (0 before first reading/after reset), `T value()` (never null: `Infinity` for `Double`, `false` for `Boolean`). `SimProximitySensor implements Sensor<Double>`, `SimGoalSensor implements Sensor<Boolean>`; no `ProximitySensor`/`GoalSensor` interfaces, no static `Sensors` |
| | `Tool extends Component` | `activate()`, `deactivate()`, `Tool.NOOP` (no-op handle when the manifest declares no tool) |
| | `Mover` | `forward()`, `backward()`, `turnRight()`, `turnLeft()`, `stop()` |
| | `Brain` | `mover()`, `frontSensor()` (`Sensor<Double>`, fixed id `front-sensor`), `rightSensor()` (`Sensor<Double>`, fixed id `right-sensor`), `goalSensor()` (`Sensor<Boolean>`, fixed id `goal-sensor`), `tool()` (single manifest tool or `Tool.NOOP`, not a fixed id), `setBehaviour(...)`, `tick()` (D-3) |
| `oobot.robot.description` | `ComponentType` | `WHEEL`, `PROXIMITY_SENSOR`, `GOAL_SENSOR`, `TOOL` |
| | `ComponentDescription` | `(String id, ComponentType type, double angle, double offset)` (wheel offset default `0.25`; tools and goal sensors carry no offset; `WheelSide` deleted) |
| | `RobotBody` / `WheelGeometry` | Optional manifest `body` dimensions in metres; `RobotBody.DEFAULT` = `0.5` / `0.5` / `0.3` / `0.1` |
| | `RobotDescription` | `(String name, RobotBody body, List<ComponentDescription> components)`, `static load(Path)` via Jackson + v2 validation |
| | `ComponentRegistry` | `bind(id, Component)`, `component(id)` (nullable), `wheel(id)`, `sensor(id)` (returns `Sensor<Double>`, proximity only), `booleanSensor(id)` (returns `Sensor<Boolean>`, goal only), `wheels()` (angle-sorted bound handles), `tools()` (manifest-order bound handles; the controller injects the single tool via `Brain.tool()`), `all()` |
| `oobot.robot.bridge` | `RobotPlatform` | `components()`, `run(Brain)`, `stop()` (extends `AutoCloseable`) |
| `oobot.robot.transport` | records | `ComponentValue`, `ComponentReading`, `ComponentCommandPayload`, `ComponentSnapshotPayload`, `ComponentCommandMessage`, `ComponentSnapshotMessage`; sealed `WireMessage` |
| `oobot.robot.transport` | `Json` | `writeComponentCommand(seq, simTick, commands)`; `parseMessage` handles both current message types; unknown payload fields tolerated; old message records deleted |
| `oobot.robot.bridge.sim` | — | `SimWheel(robot, id, angle)` (local state, `wheelChanged()`), `SimProximitySensor(id)` (`Sensor<Double>`; `update(tick, value)`, null → `Infinity`), `SimGoalSensor(id)` (`Sensor<Boolean>`; null/0 → `false`), both using `Sensor.MAX_STALE_TICKS`, `SimTool(id)` (local `1.0`/`0.0` state, batched with wheel changes, resynced after reconnect), `SimRobot(host, port, description)` (binds both sensor types, routes snapshots by id, invalidates both maps on close/reconnect/rollback, batches one command per tick, wheels-only final zero frame, two-layer safe stop) |
| server `robotDescription.ts` | — | `ComponentType`, `ComponentDescription`, `RobotBody`, `RobotDescription`, `loadRobotDescription(path)`, `DEFAULT_ROBOT_BODY`, `ComponentRegistry` (`get`, `wheels`, `sensors` (proximity only), `goalSensors`, `tools`, `wheelPair()`, `trackWidth()`) |
| server `types.ts` | — | `ComponentValue`, `ComponentReading`, `ComponentCommandPayload`, `ComponentSnapshotPayload`, `RobotShape`, `Obstacle`, `GoalArea`, `WorldSnapshotPayload` (optional `robot.shape` and `robot.toolStates: [{ id, active }]`, plus `goal: GoalArea \| null`) |
| server `sim.ts` / `geometry.ts` | — | one `nearestHit(heading + angle)` per proximity sensor from the robot centre; one `isInGoal(pose)` reading per goal sensor; SAT footprint collision (body + wheels) against obstacles/bounds; track width overridden from the manifest; tools tracked per id but no geometry effect; D-4 command gating; D-2 layer A |

Left/right are derived, not declared: `sin(angle) > 0.9` → left,
`sin(angle) < -0.9` → right (`WheelSide` deleted in both Java and TypeScript).

The default behaviour is `wall-follow-right`; goal arrival belongs to the
brain, not a behaviour. On a fresh goal-sensor "inside" reading the brain latches
the robot stopped with `front-light` ON and stops driving the behaviour.
Stale or no goal data (`latestSimTick() == 0`) means "not reached" and never
lights the tool. `wall-follow-right` keeps its existing front-light rule while
seeking. See `specs/ROBOT_LAUNCH.md` and `specs/COMPONENTS.md` §3.6.
