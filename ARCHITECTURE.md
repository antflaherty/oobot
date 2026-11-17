# Architectural overview

## Purpose

This document describes the system that exists today: a Java robot controller,
a Node/TypeScript simulation server and a browser visualiser, connected by two
WebSocket channels. `specs/` holds the wire and component contracts;
`docs/DECISIONS.md` records the locked hardening decisions (D-1 … D-6).
`DESIGN_REVIEW.md` is a historical review and is not the source of truth.

The system is a small monorepo containing the simulation engine, visualiser,
Java robot framework and example behaviours.

## High-level components

### Simulation engine

The simulation engine (`simulator/server/src/`) is the authoritative owner of
world state. It:

- maintains the world, robot pose, component command state and sensor readings;
- advances a fixed ~60 Hz timestep independently of rendering;
- applies wheel commands through differential-drive kinematics;
- detects collisions with the box edges and static obstacles;
- casts sensor rays against box edges and obstacles;
- publishes `componentSnapshot` to the robot and `worldSnapshot` to all
  visualisers without waiting for either.

#### World model

The world is an 800 × 600 world-unit box centred at the origin. The robot is a
point with pose `(x, y, heading)`, where heading is radians and 0 points along
+X. The default world file `simulator/server/worlds/maze.json` supplies the box
size, start pose and five axis-aligned box obstacles (`{ x, y, width, height }`,
centre-based). `--world <path>` selects another file; a missing or invalid file
logs a warning and falls back to the empty default world. Start poses outside
the box or strictly inside an obstacle are rejected.

Collision response is stop-dead: translation is clamped at a contact point,
rotation continues, and there is no sliding, bouncing or penetration. Boundary
clamping and obstacle contact both set the collision flag reported in the
periodic log line.

#### Kinematics

Wheel power in `[-1, 1]` is converted to metres/second using
`WORLD_UNITS_PER_METRE = 100` before computing angular velocity (D-1). The track
width comes from the manifest (0.5 for the default layout) and is already in
metres; linear speed is kept in world units/second:

```
v_l     = clamp(p_left,  -1, 1) * maxSpeed / 100        // m/s
v_r     = clamp(p_right, -1, 1) * maxSpeed / 100        // m/s
linear  = (v_l + v_r) / 2 * 100                         // world units/s
angular = (v_r - v_l) / trackWidth                      // rad/s
heading_new = heading + angular * dt
pose = integrate(linear, heading at tick midpoint, dt)  // dt = 1/60 s
```

At full differential (`-1`, `+1`) with `maxSpeed = 100`, angular velocity is
4 rad/s (≈229°/s) and 50 % differential yields 2 rad/s. There is no separate
angular-speed clamp: turn rate is proportional to input.

#### Timestep and observability

The loop is best-effort and never blocks. Achieved Hz is logged every 5 s along
with the current pose, command and per-window aggregate counters for applied,
ignored and clamped commands and for collisions. `getState()` exposes the tick,
pose, command and counters for tests and tooling.

### Robot controller

Each controller runs as a separate Java process. It executes one
`RobotBehaviour` via a `Brain` at ~20 Hz, reads the latest `ProximitySensor`
values and issues high-level `Mover` actions. It never mutates world state and
never sees sockets, NDJSON or motor power values.

The controller is launched with picocli CLI options:

```
java -jar robot.jar [--host localhost] [--port 8080]
                    [--robot ../robots/default.json]
                    [--behaviour wall-follow-right|square|circle|heart|wall-avoid|brownian]
```

The manifest path above is relative to `robot-framework/`; the dev scripts pass
an absolute path instead.

`--help` and `--version` are also available. The default behaviour is
`wall-follow-right`. `--robot` defaults to `../robots/default.json` resolved
against the framework directory (the JAR's parent). Missing manifests, invalid
manifests, unknown behaviours and missing required sensor ids are reported as
usage errors with exit code 2, before any connection is opened.

### Java robot framework

The student-facing API lives in `oobot.robot.student`:

- `Component` — id-addressed hardware handle (`id()`).
- `Wheel` — carries its manifest mount `angle` and can be driven clockwise,
  anticlockwise or stopped.
- `ProximitySensor` — `distance()` (world units, `Infinity` when nothing is
  detected) and `latestSimTick()` (tick of the last reading; `0` after a fresh
  session). The documented max-staleness policy is 30 sim ticks (~0.5 s at 60
  Hz).
- `Mover` — high-level `forward()`, `backward()`, `turnLeft()`, `turnRight()`,
  `stop()`; `WheeledMover` derives left/right wheel roles from `sin(angle)`
  (`> 0.9` left, `< -0.9` right).
- `Brain` — dependency injection for a `Mover` and the two sensors, plus
  `setBehaviour(...)` and `tick()`. Setting a null behaviour stops the robot.
- `RobotBehaviour` — `init()` and `update()`, called by the framework loop.

The framework owns the concrete implementations. `RobotDescription` /
`ComponentRegistry` load and validate the v2 manifest; `SimRobot` implements the
`RobotPlatform` lifecycle (connect, reconnect, batch commands, safe stop).
Wheel changes are buffered locally and flushed by the behaviour loop as **one**
`componentCommand` frame per tick. The OkHttp WebSocket client, Jackson JSON,
SLF4J/Logback logging and the Gradle fat JAR are framework details, not student
API.

`Brain` is intentionally fixed-id (D-3): `frontSensor()` must resolve
`front-sensor` and `rightSensor()` must resolve `right-sensor`. The wire model
remains id-addressed, but a manifest missing either id is rejected at startup.

Example implementations live in `oobot.robot.example` (five behaviours plus
`WheeledMover` and `DefaultBrain`); the random-walk behaviour lives in
`oobot.robot.student`.

### Visualiser

The visualiser (`simulator/visualiser/`) connects to the visualiser channel and
renders `worldSnapshot` messages: robot pose, heading and the obstacle list.
It holds the last snapshot and interpolates toward the next available one on
each animation frame; when no newer snapshot exists it holds the last pose
(no extrapolation). A hardcoded fallback stream keeps the demo viewable when
no sim is running. The trail is cleared on mode switches and on every
(re)connect; snapshots, interpolation clock and obstacles are also reset when a
sim tick rolls back, so a restarted sim cannot freeze or duplicate rendering
(D-5). The visualiser never sends messages to the sim.

## Communication boundaries

One sim server process hosts two WebSocket servers:

- **Robot channel** (`ws://localhost:8080`, bidirectional):
  `componentCommand` (robot → sim) and `componentSnapshot` (sim → robot).
  Only the active robot connection may command the robot; a second robot slot
  closes the previous connection.
- **Visualiser channel** (`ws://localhost:8081`, unidirectional out):
  `worldSnapshot` (sim → all visualisers).

The port implies the participant; there is no client-type field. Both servers
bind `0.0.0.0` so IPv4-only clients can connect, and the upgrade handler
accepts only localhost/loopback origins (or no Origin header, for the Java
client). Ports can be overridden for the sim with `ROBOT_PORT` and
`VISUALISER_PORT`; the visualiser client currently targets 8081.

### Message format

All messages are newline-delimited JSON with the envelope:

```json
{ "type": "...", "seq": 0, "simTick": 0, "payload": { ... } }
```

- `type` is `componentCommand`, `componentSnapshot` or `worldSnapshot`.
- `seq` is assigned per outgoing connection by the transport, so multiple
  visualisers have independent monotonic sequences.
- `simTick` is the logical clock and the source of truth for staleness;
  wall-clock timestamps are never used to judge freshness.
- `payload` is message-specific:
  - `componentCommand`: `commands: [{ id, value }]`, value in `[-1, 1]`;
  - `componentSnapshot`: `readings: [{ id, value | null }]`, one per declared
    proximity sensor, in world units;
  - `worldSnapshot`: `robot: { x, y, heading }` plus `obstacles: [...]`.

### Command integrity and safe stop

A `componentCommand` is accepted only from the active robot connection, with
finite non-negative integer `seq` and `simTick`, `seq` greater than the last
accepted value and `simTick` no more than one tick behind the last seen value
(D-4). Rejected commands are logged and ignored before touching actuator state;
per-connection tracking resets when the active robot slot changes.

Actuator state is persistent: wheels keep their last commanded level until a
new command arrives. Safe stop has two independent layers (D-2): the sim zeroes
its command state when the active robot socket closes or on sim shutdown, and
the Java bridge writes one zero command for every bound wheel id before closing
or reconnecting its socket. Sensor readings are invalidated on disconnect,
reconnect and tick rollback.

Inbound NDJSON lines are buffered across frames, capped in length (oversized
peers are dropped) and malformed lines are logged. Outbound sends are
non-blocking with a depth-1 drop-oldest slot per connection; shutdown is time
bounded so an unresponsive client cannot stall exit.

## Timing and execution model

- Simulation: ~60 updates per second, authoritative clock, best effort.
- Robot behaviour: ~20 updates per second, one batched command frame per tick.
- Visualiser: ~60 frames per second, subject to the browser.

The loops are independently scheduled and coordinate only through snapshots and
persistent actuator state. If a controller disconnects the sim safe-stops; the
controller reconnects after a fixed delay, resets `seq` and sensor state and
resumes. A heartbeat/timeout for a hung-but-connected process remains deferred
beyond this scope.

## Technology choices

- Visualiser: vanilla TypeScript, HTML5 canvas, Vite; no UI framework.
- Simulation: Node.js and TypeScript, `ws` for WebSocket transport.
- Robot framework/controller: Java 21+, Gradle (Kotlin DSL), Jackson, OkHttp,
  picocli, SLF4J + Logback; fat JAR built with `./gradlew fatJar`.
- Message format: newline-delimited JSON.

These are pragmatic choices, not permanent constraints. The transport and
component boundaries keep robot code independent of the sim's implementation.

## Monorepo outline

```
oobot/
├── robots/default.json          # shared v2 manifest
├── simulator/
│   ├── server/
│   │   ├── src/                 # index, sim, world, sensor, transport,
│   │   │                        # types, robotDescription
│   │   └── worlds/maze.json     # default world and obstacles
│   └── visualiser/              # live stream, fallback stream, renderer, math
├── robot-framework/             # controller, framework, student API, examples
├── specs/                       # WIRE_PROTOCOL, COMMS, COMPONENTS, SENSORS,
│                                 # ROBOT_DESCRIPTION, ROBOT_LAUNCH, WORLD_FORMAT
├── docs/                        # DECISIONS, DOC_AUDIT, archive/ (historical plans)
├── HARDENING_PLAN.md            # current hardening scope and decisions
├── AGENTS.md                    # agent-facing summary
├── README.md, MVP.md, ARCHITECTURE.md, FUTURE.md, DESIGN_REVIEW.md
└── package.json                 # npm workspaces and dev scripts
```

## Guiding principles

- The simulation owns world state; robot code commands hardware abstractions.
- Rendering is a client of the simulation, fully decoupled from sim timing.
- Networking and concurrency stay hidden behind the Java framework.
- The manifest is data shared by sim and robot; the visualiser ignores it.
- Contracts are explicit and language-independent; no IDL is needed yet.
- Prefer understandable behaviour over realistic physics.
- Keep safety failures obvious: reject bad configuration early, stop on
  disconnect, and surface stale or ignored commands in logs.
