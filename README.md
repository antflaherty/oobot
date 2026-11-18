# oobot — Object Oriented Robotics (MVP)

A Java robot controller (separate process) drives a robot in a continuously
running TypeScript simulation; a browser visualiser displays the robot's
movement and the world's obstacles.

This is a technical proof of concept, not a complete learning product. See
`MVP.md` for scope and success criteria, and `ARCHITECTURE.md` for the design.

## Architecture (high level)

```
+------------------+      robot channel       +------------------------+
|   Java robot     |  ws://localhost:8080     |   Sim server           |
|   process        |  (NDJSON)                |   (Node/TS)            |
|                  |  componentCommand ---->  |  60 Hz timestep,       |
|  Robot framework |  <---- componentSnapshot |  world + obstacles,    |
|  behaviour loop  |  (one reading per sensor)|  manifest components   |
+--------+---------+                          +-----------+------------+
         ^                                                | visualiser channel
         | Gradle fat JAR, OkHttp/Jackson                 | ws://localhost:8081 (NDJSON)
         |                                                | worldSnapshot (robot + tools + obstacles)
         |                                                v
         |                                     +------------------------+
         +-------------------------------------+  Visualiser            |
              (human watches; no control path) |  (vanilla TS + canvas) |
                                               |  live WS only          |
                                               |  hold-last/interpolate |
                                               +------------------------+
```

The simulation owns world state and advances on its own fixed ~60 Hz timestep.
The robot sends `componentCommand` messages (one batched frame per behaviour
tick) and receives `componentSnapshot` messages with one reading per declared
proximity sensor. Tools use the same `componentCommand` payload (`1.0` active,
`0.0` inactive). The visualiser receives `worldSnapshot` messages containing
the robot pose, tool states and the obstacle list, and renders with
hold-last-frame plus interpolation. Rendering never drives or blocks simulation
timing; the visualiser channel is unidirectional.

## Monorepo layout

```
oobot/
├── robots/default.json          # shared manifest (wheels, 2 proximity sensors,
│                                 # goal sensor, front-light tool)
├── simulator/
│   ├── server/                  # Node.js/TypeScript sim + WebSocket server
│   │   ├── src/                 # index, sim, world, sensor, transport, types,
│   │   │                        # robotDescription
│   │   └── worlds/maze.json     # default world: bounds, start pose, obstacles, goal
│   └── visualiser/              # vanilla TypeScript + HTML5 canvas (vite)
├── robot-framework/             # Java framework, examples and student API
│   ├── robot.jar                # local build output from `npm run build:robot`
│   └── src/main/java/oobot/robot/
│       ├── student/             # RobotBehaviour, Mover, Brain, Component, Wheel,
│       │                        # Sensor, Tool, BrownianMotionBehaviour
│       └── example/             # WheeledMover, DefaultBrain and five examples
├── specs/                       # wire protocol, components, sensors, launch,
│                                 # robot description, world format
├── docs/                        # DECISIONS.md, DOC_AUDIT.md, archive/
├── HARDENING_PLAN.md            # scope, ownership and decisions for this pass
├── README.md                    # this file
└── package.json                 # npm workspaces + root dev scripts
```

Available behaviours (`--behaviour`): `wall-follow-right` (default), `square`,
`circle`, `heart`, `wall-avoid`, `brownian`. Five live in `oobot.robot.example`;
the random-walk behaviour lives in `oobot.robot.student`. Goal arrival is owned
by the brain, not by a behaviour.

## Getting started

### One-command dev startup

From the repo root (requires Node.js ≥ 20, npm ≥ 10, JDK 21+):

```bash
npm install         # installs both workspaces — once
npm run build:robot # builds robot.jar via Gradle — once (first run downloads Gradle)
npm run dev         # starts sim server, visualiser dev server and robot controller
```

`npm run dev` starts the sim first, waits for its robot port, then starts the
visualiser and the Java controller. It passes absolute `--robot` and `--world`
paths, so it works from any directory; Ctrl-C stops all three children. Open
`http://localhost:5173` for the visualiser. The default robot behaviour is
`wall-follow-right` (set `ROBOT_BEHAVIOUR` to override). `robot.jar` is a local
build output, not a committed file.

### Starting components individually

| Component | Command (from repo root) | Notes |
|-----------|--------------------------|-------|
| Sim server | `npm run dev:server` | Defaults: `simulator/server/worlds/maze.json` and `robots/default.json`, resolved from the server source. From `simulator/server/`, use `npm run dev -- --world <path> --robot <path>` to override. |
| Visualiser | `npm run dev:visualiser` | Vite dev server; connects to `ws://localhost:8081` and shows a "no connection" message until the sim is running. |
| Robot | `npm run dev:robot` | Launches the JAR with an absolute `--robot` path and `wall-follow-right`. |

From `robot-framework/` the equivalent direct launch is:

```bash
java -jar robot.jar --host localhost --port 8080 \
     --robot ../robots/default.json --behaviour wall-follow-right
```

The controller also accepts `--help`, `--version` and any of the six behaviour
names above. The default manifest path is resolved against the framework
directory (the JAR's parent), and invalid CLI values, a missing manifest or a
missing required sensor id produce a usage-style error with exit code 2.

## Component model, sensors and tool

Both the sim and the Java controller load the same v2 manifest at startup; the
visualiser does not. `robots/default.json` declares two wheels (`left-wheel`,
`right-wheel`, each with a mount `angle` and radial `offset` of `0.25`), two
proximity sensors (`front-sensor` at 0 rad, `right-sensor` at −π/2 rad), a
`goal-sensor` (type `goalSensor`, 0 rad) and a `front-light` tool at 0 rad.
Wheel roles and the track width (`0.5`) are derived from the wheel angles and
offsets; there is no `side` field. Sensors and tools declare a finite `angle`
but no `offset`, and tools affect no geometry or kinematics.

Proximity readings are ray-cast from the robot centre along `pose.heading +
angle` against both the box edges and the static obstacles. The nearest hit
becomes the component's reading in world units, or `null` on the wire (mapped to
`Double.POSITIVE_INFINITY` in Java) when nothing is detected. The `goal-sensor`
instead reports `1` when the robot centre is inside the world's goal area, `0`
when outside, and `null` when the world declares no goal (mapped to `false` in
Java). All sensors expose the generic `Sensor<T>` API: `latestSimTick()` (so a
behaviour can detect stale data) and `value()` (`Sensor<Double>` for proximity,
`Sensor<Boolean>` for the goal).

The world file may declare an optional top-level `goal` area
(`{ "x", "y", "width", "height" }`, centre-based like an obstacle). The default
`simulator/server/worlds/maze.json` places an 80 × 80 goal at `(340, 100)`;
the robot counts as in-goal when its centre point lies inside the rectangle.
The sim always publishes the area as `worldSnapshot.goal` (`null` when the world
has no goal) so the visualiser can draw it.

The wire and component model are id-addressed, but the student-facing `Brain`
sensor accessors are deliberately **fixed-id**: `frontSensor()` resolves
`front-sensor`, `rightSensor()` resolves `right-sensor` and `goalSensor()`
resolves `goal-sensor`. The controller fails fast with a clear message if any is
missing from the manifest. `tool()` is instead a single reference injected by
type: the one manifest tool, or `Tool.NOOP` if none is declared; more than one
tool is a startup error.

The sim tracks tool state per id and publishes it in
`worldSnapshot.robot.toolStates`; commands use the existing `componentCommand`
payload (`1.0` active, `0.0` inactive, any other value ignored). The brain stops
the robot and turns the front light ON once the goal sensor reports arrival;
stale or missing goal data never lights it. The default `wall-follow-right`
behaviour also switches the front light ON in `APPROACH`, `DRIVE` and
`TURN_RIGHT_AT_END`, and OFF in `SEARCH`, `AVOID`, `RECOVER_BACKUP`,
`RECOVER_TURN` and `init()`.

## Wire channels and safety

- Robot channel (`ws://localhost:8080`): `componentCommand` (robot → sim, one
  frame per behaviour tick) and `componentSnapshot` (sim → robot, one reading
  per declared sensor, every tick). Only the active robot connection may
  command the robot; duplicate/out-of-order `seq` values and commands more than
  one tick old are ignored.
- Visualiser channel (`ws://localhost:8081`): `worldSnapshot` (sim →
  visualiser) only. Per-connection send slots are depth-1 drop-oldest, so a slow
  browser never blocks the sim. It carries `toolStates` and the goal area; the
  visualiser draws a small front circle for `front-light` (red `#ef4444` active,
  darker blue `#1d4ed8` inactive) and the goal rectangle.
- Both servers bind `0.0.0.0` for IPv4 clients and accept only localhost origins
  (or no Origin header).
- Safe stop is two-layered: the sim zeroes its actuator state and clears tool
  states when the active robot disconnects or it shuts down, and the Java bridge
  writes one explicit wheels-only zero command for all wheels *before* closing
  or reconnecting its socket (tool state is force-resynced on reconnect).
- Visualiser state (snapshots, clock, obstacles, cached goal, cached tool states
  and trail) resets on every connect/reconnect, including a sim tick rollback.

## MVP success criteria

1. The simulation runs independently of the browser visualiser.
2. A separate Java process connects to the simulation over the robot channel.
3. Java movement commands cause the simulated robot to move.
4. The sim publishes `componentSnapshot` readings for the proximity and goal
   sensors; the Java controller exposes them as `Sensor<Double>` and
   `Sensor<Boolean>` handles with `value()` and `latestSimTick()`.
5. Behaviours use those handles to drive high-level `Mover` actions without
   touching sockets, JSON or wheel power values.
6. The visualiser shows the robot, obstacles and movement without controlling
   simulation timing.
7. A controller that stops or disconnects cannot leave the robot moving
   indefinitely (safe stop on both sides of the robot channel).
8. The manifest, world file and behaviour are configurable from the CLI: invalid
   manifests, behaviours or required sensor ids are usage-style errors, while an
   unusable world file is reported with a warning and an empty-world fallback.
9. The front-light tool is switched through `componentCommand`, reflected in
   `worldSnapshot.robot.toolStates` and drawn by the visualiser.
10. The brain stops the robot and switches the front light ON when the goal
    sensor reports arrival, and the goal area is drawn by the visualiser from
    `worldSnapshot.goal`.

## Docs & specs

- `ARCHITECTURE.md` — components, boundaries, wire contract and lifecycle
- `MVP.md` — scope, in/out of scope, success criteria
- `DESIGN_REVIEW.md` — historical design review (superseded by the specs and
  `docs/DECISIONS.md`)
- `FUTURE.md` — work deliberately deferred; implemented ideas are listed as done
- `docs/DECISIONS.md` — locked hardening decisions (D-1 … D-6)
- `docs/DOC_AUDIT.md` — documentation audit for the hardening pass
- `HARDENING_PLAN.md` — hardening scope, file ownership and verification gates
- `docs/archive/PLAN.md`, `docs/archive/PLAN_SENSORS.md` — archived historical
  plans; do not use them as current design docs
- `specs/WIRE_PROTOCOL.md` — NDJSON envelope, message types and two channels
- `specs/COMMS.md` — transport interface and ownership
- `specs/COMPONENTS.md` — per-component responsibilities
- `specs/ROBOT_DESCRIPTION.md` — v2 manifest schema and validation rules
- `specs/ROBOT_LAUNCH.md` — controller CLI and startup validation
- `specs/SENSORS.md` — proximity sensor model
- `specs/WORLD_FORMAT.md` — world file format and obstacle conventions

## License

UNLICENSED
