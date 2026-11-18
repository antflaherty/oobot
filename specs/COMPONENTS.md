# Component Specifications — MVP

Object Oriented Robotics (oobot)

**Status:** current. Describes the implemented components: id-addressed
`componentCommand` / `componentSnapshot`, manifest-driven wheels, proximity
sensors and tools, a maze world with obstacles, and the six registered behaviours.
Binding rules: `specs/WIRE_PROTOCOL.md`, `docs/DECISIONS.md` and
`docs/DOC_AUDIT.md`.

---

## 0. Shared constraints (all components)

| Constraint | Contract |
|---|---|
| One robot, implicit identity | Single active robot slot; a new robot connection closes the previous one. No `robotId` on the wire. |
| Two channels from one sim process | Robot channel `ROBOT_PORT` (default 8080), visualiser channel `VISUALISER_PORT` (default 8081); both validated as integers in 1–65535. |
| Bind / origin | Servers bind `0.0.0.0`; no `Origin` or an http(s) localhost/loopback `Origin` is allowed, others are rejected. Not a network firewall. |
| NDJSON | One JSON object per `\n`-terminated line; inbound server lines capped at 64 KiB; malformed input is logged and ignored. |
| Envelope | `{ type, seq, simTick, payload }`; `simTick` (never wall-clock) is the freshness source. Outbound `seq` is assigned per connection by the transport. |
| Command integrity (D-4) | Commands only from the active robot socket; `seq`/`simTick` finite non-negative integers, strictly increasing `seq`, `simTick` no more than one tick old. |
| Actuator state | Persistent: wheels keep the last commanded value until overridden or safe-stopped; tools keep their active/inactive state. The sim never waits for the robot. |
| Tool state | A `tool` command value of `1.0` means active, `0.0` inactive; any other value is ignored. Tools have no geometry, kinematics or track-width effect. The sim tracks tool state per manifest id and reports it in `worldSnapshot.robot.toolStates`. |
| Safe stop (D-2) | Sim zeros motors and clears all tools on active-robot close and on shutdown; Java writes a zero command for every wheel before closing/reconnecting. Each layer works alone. |
| Simulation timing | Fixed ~60 Hz sim tick, never blocked by the robot or visualiser. Logging is aggregated into counters on a 5 s achieved-Hz line. |
| Visualiser | Client of the sim; channel is unidirectional `worldSnapshot` only. Rendering never drives sim timing. |
| Manifest | `robots/default.json` (v2) loaded by both Java and sim; wheel roles derived from `sin(angle)`; tools have `angle` but no `offset`; visualiser does not read it. |
| Kinematics (D-1) | `WORLD_UNITS_PER_METRE = 100`; wheel powers → m/s → angular rate; manifest-derived track width; no `maxAngularSpeed` clamp. |
| Language boundary | Robot code uses the Java student API only; no sockets, JSON or motor power values. |

---

## 1. Sim server (`simulator/server`, Node.js/TypeScript)

### 1.1 Purpose

Authoritative owner of the world. One Node.js process hosts both WebSocket
channels and runs `SimEngine` on a fixed ~60 Hz timestep. It applies actuator
commands, integrates motion, casts sensor rays, publishes snapshots and
enforces safe stop.

### 1.2 Inputs

- `componentCommand` on the robot channel:
  `{ type, seq, simTick, payload: { commands: [{ id, value }] } }` — wheel values
  in `[-1, 1]`, tool values exactly `0.0`/`1.0`.
- Robot connect/disconnect events (active slot ownership).
- CLI/env configuration: `--robot <path>` (v2 manifest), `--world <path>`
  (world file), `ROBOT_PORT`, `VISUALISER_PORT`.
- Startup loads the manifest through `loadRobotDescription()` and the world
  through `loadWorldFile()`; an unreadable/invalid manifest is fatal, while an
  unreadable/invalid world file is logged and falls back to an empty world.

### 1.3 Outputs

- `componentSnapshot` to the active robot every tick:
  `{ type, seq, simTick, payload: { readings: [{ id, value }] } }` — one entry
  per declared `proximitySensor`, `value` = world-unit distance or `null`.
- `worldSnapshot` to every visualiser every tick:
  `{ type, seq, simTick, payload: { robot: { x, y, heading, shape?, toolStates: [{ id, active }] }, obstacles: [...] } }` — one `toolStates` entry per declared tool in manifest order.
- Console logging: startup choices, connections, safe stop, ignored commands,
  a collision detail at most once per 5 s window, and the 5 s achieved-Hz line
  with pose, command, connection state and event counters (applied / ignored /
  clamped / collisions). Counters are also exposed via `SimEngine.getState()`.

### 1.4 World model

- Bounded 2D box centred at the origin; default 800 × 600 world units
  (`x ∈ [-400, 400]`, `y ∈ [-300, 300]`).
- Default world file `simulator/server/worlds/maze.json` supplies the start pose
  and five static axis-aligned box obstacles; a missing/invalid file falls back
  to `(0, 0, heading 0)` and no obstacles with a warning. See
  `specs/WORLD_FORMAT.md`.
- Obstacles are centre-based `{ x, y, width, height }`. The loader parses
  obstacles before the start pose and rejects a start outside the box or
  strictly inside an obstacle, naming the file (and obstacle index).
- Collision uses the full manifest-derived footprint (rotated body box plus one
  box per wheel at its mount offset); sensing stays centre-based. Tools declare
  no offset and do not affect the footprint, kinematics or track width.

### 1.5 Timestep and kinematics (D-1)

- Fixed timestep at 60 Hz (`dt = 1/60 s`); achieved Hz logged every 5 s.
- Wheel powers are clamped to `[-1, 1]`, converted to m/s with
  `v = power * maxSpeed / WORLD_UNITS_PER_METRE`, and used as:

  ```
  linear  = (v_left + v_right) / 2 * WORLD_UNITS_PER_METRE   // world units/s
  angular = (v_right - v_left) / trackWidth                  // rad/s
  ```

- `trackWidth` is derived from the manifest (`offset_left·sin(angle_left) −
  offset_right·sin(angle_right)`, `0.5` by default) and overrides the world
  config value. With defaults, full differential drive is 4 rad/s (≈229°/s)
  and 50% differential is 2 rad/s. There is no independent angular clamp.
- Translation is integrated at the midpoint heading and clamped at box edges
  with stop-dead collision; obstacle contact also stops translation. The robot
  keeps rotating when translation is blocked. Box clamping and obstacle hits
  both set `collided`.

### 1.6 Actuator model and command ownership

- Latest commanded wheel levels and per-tool active flags are persistent
  internal state, reused each tick when the robot is slow. A tool value of
  `1.0` sets active, `0.0` sets inactive; other values are ignored.
- One active robot connection: a new robot closes the old one; a stale
  disconnect cannot clear the active slot or stop the active robot.
- D-4 acceptance rules are enforced before actuator/tool state is touched (§0).
  Rejected commands are logged/counted and leave state unchanged.

### 1.7 Sensing

- Every declared proximity sensor casts a ray from the robot centre at
  `heading + component.angle` (radians, 0 = forward, + = CCW, right = -π/2).
- The reported distance is the nearest hit among all static obstacles and the
  world box edges, or `null` when nothing is detected (infinite range within
  the box). Ray-vs-box uses a slab intersection that does not report `t=0`
  when starting on a face and moving outward.
- One reading per sensor per tick, single slot, no queue. See
  `specs/SENSORS.md`.

### 1.8 Publishing and backpressure

- Visualiser channel: broadcast `worldSnapshot` every tick (pose, shape,
  `toolStates`, obstacles); non-blocking, depth-1 drop-oldest queue per client,
  no sim-side retry or buffering.
- Robot channel: send `componentSnapshot` every tick to the active robot only;
  the client's behaviour loop reads the latest available readings.
- Multiple visualiser tabs are supported with independent outbound `seq`
  counters; the visualiser channel stays unidirectional.

### 1.9 Safe stop

- On close of the active robot socket, and on sim shutdown, zero
  `latestCommand` and clear all tool states before the next tick. A stale robot
  disconnect is ignored.
- The Java final zero frame remains wheels-only; the sim clears tools locally
  (D-2 layer A), so tools cannot be left on by a disconnect.
- There is no heartbeat/timeout for a hung-but-connected process (D-2 layer B
  on the Java side covers orderly disconnects).

### 1.10 Key interfaces

| Interface | Direction | Notes |
|---|---|---|
| `ws://localhost:ROBOT_PORT` | in `componentCommand`, out `componentSnapshot` | Single active robot; D-4 checks |
| `ws://localhost:VISUALISER_PORT` | out `worldSnapshot` | Unidirectional; fan-out with drop-oldest |
| `simTick` counter | internal | Authoritative logical clock |
| `latestCommand` / tool state | internal | Persistent wheel levels (zeroed on safe stop) and per-tool active flags (cleared on safe stop) |
| `ComponentRegistry` (TS) | internal | Resolves manifest ids, wheel pair, tools and derived track width |
| `WorldModel` / `castFrontRay` | internal | Kinematics, collision, ray casting |

### 1.11 Out of scope

- Multiple robots, `userControl`, binary framing, heartbeats, moving obstacles,
  sliding/bouncing, world-selection UI.

---

## 2. Visualiser (`simulator/visualiser`, vanilla TypeScript + canvas)

### 2.1 Purpose

Browser client that renders the simulated world. It is fully decoupled from
sim timing; it renders only live WebSocket data and shows a "no connection"
message when there is none.

### 2.2 Inputs

- `worldSnapshot` NDJSON on `ws://localhost:VISUALISER_PORT` (hardcoded URL;
  no discovery). Partial NDJSON lines are buffered across `onmessage` calls;
  malformed or invalid snapshots are logged and ignored.
- `requestAnimationFrame` render clock (~60 fps).

### 2.3 Outputs

- Canvas rendering of the box, robot pose (interpolated), trail, static
  obstacles from `payload.obstacles`, and tool state from
  `payload.robot.toolStates` (a small front-edge circle for `front-light`: red
  `#ef4444` when active, darker blue `#1d4ed8` when inactive; the body is
  `#2563eb`). Missing `toolStates` are treated as inactive.
- Console logs for connection state and periodic render source/tick/pose.
- No outgoing messages of any kind (`userControl` is deferred).

### 2.4 Rendering and interpolation

- Hold last frame and interpolate to the next buffered snapshot; never
  extrapolate. Heading interpolation takes the shortest angular path.
- Snapshot buffer is bounded (120 snapshots). Obstacles and tool states are
  re-cached from each valid snapshot.
- The trail is recorded from authoritative snapshot poses so it continues
  while the tab is hidden; it is cleared on mode/session changes.

### 2.5 Connection handling (D-5)

- Auto-reconnect on a ~2 s timer after close; `stop()` cancels the timer and
  prevents reconnects.
- On **every** open (including reconnect), all derived state is reset: snapshot
  buffer, latest/base ticks, interpolation clock, cached obstacles, cached tool
  states, partial line buffer and trail.
- A `simTick` that decreases is treated as a new session: the same reset runs
  once before applying the new snapshot. This prevents frozen or duplicated
  rendering after a sim restart.

### 2.6 Key interfaces

| Interface | Direction | Notes |
|---|---|---|
| `ws://localhost:VISUALISER_PORT` | in `worldSnapshot` only | Unidirectional channel |
| Browser `<canvas>` | rendering output | Vanilla TS; no framework |

### 2.7 Out of scope

- `userControl` / UI controls, React, sensor ray rendering, tool controls,
  multi-robot, anything that influences sim timing.

---

## 3. Java robot framework (`robot-framework`, Java 21)

### 3.1 Purpose

Student-facing library bundled as a fat JAR (`robot.jar`). Runs in a separate
process, loads the shared manifest, connects to the sim and hides OkHttp,
Jackson, NDJSON framing, reconnection, batching, concurrency and safe stop.

### 3.2 Launch and inputs

- CLI (picocli): `--host` (default `localhost`), `--port` (default 8080),
  `--robot <path>`, `--behaviour <name>`, plus `--help` / `--version`.
  See `specs/ROBOT_LAUNCH.md`.
- Startup validates the behaviour name, manifest readability/v2 schema, the
  presence of the fixed `front-sensor` / `right-sensor` proximity ids and that
  at most one `tool` is declared (more than one is a startup failure); failures
  are usage errors with exit code 2 and no stack trace.

### 3.3 Outputs

- One `componentCommand` per behaviour tick containing all wheel and tool values
  changed in that tick (`{ commands: [{ id, value }] }`, tool `1.0` = active /
  `0.0` = inactive), plus a final wheels-only zero command on orderly
  disconnect/shutdown/reconnect. Tool state is force-resynced after reconnect.
- Consumes `componentSnapshot` and updates id-bound proximity sensor handles;
  inbound commands are explicitly ignored.
- SLF4J + Logback logging of connection state, lifecycle and errors.

### 3.4 Student API

```java
interface Component { String id(); }
interface Wheel extends Component {
    double angle();                 // radians; sin > 0.9 left, < -0.9 right
    void clockwise(); void antiClockwise(); void stop();
}
interface ProximitySensor extends Component {
    long latestSimTick();           // 0 after reset/disconnect/rollback
    double distance();              // Infinity when nothing detected
}
interface Tool extends Component {
    void activate(); void deactivate();
    Tool NOOP = ...;                // no-op handle when no tool is declared
}
interface Mover {
    void forward(); void backward(); void turnRight(); void turnLeft(); void stop();
}
interface RobotBehaviour { void init(); void update(); }   // ~20 Hz
interface Brain {                                          // fixed-id sensor accessors
    Mover mover();
    ProximitySensor frontSensor();  // id "front-sensor"
    ProximitySensor rightSensor();  // id "right-sensor"
    Tool tool();                    // single manifest tool, or Tool.NOOP
    void setBehaviour(RobotBehaviour behaviour);           // null stops motion
    void tick();
}
```

`ComponentRegistry` binds runtime handles to manifest ids (`all()`, `component`,
`wheel`, `sensor`, `wheels()` sorted by mount angle, `tools()` in manifest order). `SimRobot` implements `RobotPlatform` and provides the sim bridge;
`WheeledMover` is the reference `Mover`, `DefaultBrain` the reference `Brain`.
The component model is id-addressed, but `Brain`'s two sensor accessors are
**fixed-id** (D-3): a manifest may declare other sensors, but this API cannot
address them. `Brain.tool()` is a generic type-based reference: the one
manifest-declared tool is injected (or `Tool.NOOP` if none); declaring more than
one tool fails at startup.

### 3.5 Framework responsibilities

- `BehaviourLoop` calls `Brain.tick()` at 50 ms intervals and then flushes
  batched wheel and tool changes; a failing behaviour cannot kill the loop.
  `SimTool` batches tool changes into the same `componentCommand` as wheel
  changes; `DefaultBrain.setBehaviour(null)` deactivates the tool.
- `WsConnection` (OkHttp) buffers inbound NDJSON across frames, delivers
  `onClose` before clearing `open`, and provides `closeWithFinalFrame(...)` for
  D-2 layer B.
- `Json` (Jackson) writes `componentCommand`, parses snapshots with unknown
  fields tolerated, and throws on serialisation failure rather than emitting an
  empty frame.
- On disconnect/reconnect/tick rollback, sensor values are invalidated
  (distance → `Infinity`, `latestSimTick()` → 0). Behaviour code can treat a
  reading older than ~30 sim ticks as stale (`SimProximitySensor` policy). After
  reconnect, tool state is force-resynced so the sim knows the current state.
- `stop()` halts the loop, writes the final wheels-only zero frame first, closes
  the socket and joins the reconnect thread with a bounded wait.
  `setBehaviour(null)` stops the robot and deactivates the tool.

### 3.6 Registered behaviours

`square`, `wall-avoid`, `wall-follow-right` (default), `circle`, `heart`,
`brownian`. The first five live in `oobot.robot.example`;
`BrownianMotionBehaviour` lives in `oobot.robot.student`. All use `Mover` and
the two sensor accessors; none touch transport code. The default
wall-follow behaviour also drives the front light: ON in `APPROACH`, `DRIVE`
and `TURN_RIGHT_AT_END`; OFF in `SEARCH`, `AVOID`, `RECOVER_BACKUP`,
`RECOVER_TURN` and on `init()`. The other behaviours leave it off.

### 3.7 Concurrency model

- Network I/O runs on OkHttp/framework threads; behaviour ticks run on a
  single-threaded scheduler. Component reads/writes are hidden from robot code.
- One command frame is emitted per tick regardless of wheel/tool count.

### 3.8 Out of scope

- Multiple robots, generic `Brain` sensor lookup and generic tool lookup by id,
  sandboxed/user-uploaded code, heartbeat liveness detection, transport
  libraries in student code.

---

## 4. Interfaces between components

```
+------------------+   robot channel (NDJSON)   +---------------------+
|  Java robot      | ws://localhost:ROBOT_PORT  |  Sim server         |
|  process         |  componentCommand -------> |  world model,       |
|                  |  <------- componentSnapshot|  60 Hz timestep,    |
|  RobotPlatform   |  (single active robot,     |  actuator/tool state|
|  SimRobot        |   D-4 seq/tick checks)     |  obstacle sensing   |
+--------+---------+                            +----------+----------+
         |                                                 |
         | Brain / Mover / ProximitySensor / Tool           | visualiser channel
         v                                                  | ws://localhost:VISUALISER_PORT
+---------------------+                            <--------+ (NDJSON, worldSnapshot,
| Student behaviour   |                            |         unidirectional, drop-oldest)
| ~20 Hz update()     |                            v
+---------------------+                   +---------------------+
                                          | Visualiser (canvas) |
                                          | live WS only        |
                                          | holds/interpolates  |
                                          +---------------------+
```

| From | To | Channel | Message(s) |
|---|---|---|---|
| Java robot framework | Sim server | Robot (`ROBOT_PORT`) | `componentCommand` (normal wheel/tool values + final wheels-only zero) |
| Sim server | Java robot framework | Robot (`ROBOT_PORT`) | `componentSnapshot` |
| Sim server | Visualiser | Visualiser (`VISUALISER_PORT`) | `worldSnapshot` (robot pose/shape, `toolStates`, obstacles) |

### 4.1 Sequencing & time

- `simTick` is the freshness source on every message. Wall-clock is debug-only.
- D-4 applies to robot commands; D-5 applies to visualiser session state.
- No `clientType`, `clientId` or `robotId` fields are present on the wire.

---

## 5. Deferred beyond MVP

- Heartbeat/timeout liveness detection; `userControl` and visualiser → sim
  traffic; multiple robots; binary framing; formal error protocol.
- Generic `Brain` sensor accessors and generic tool lookup by id (the single
  manifest tool is injected by type); moving obstacles; sensor rendering;
  UI frameworks/controls; simulated hardware backends beyond the interface
  rationale in `specs/COMMS.md`.
