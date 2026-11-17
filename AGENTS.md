# AGENTS.md — oobot (Object Oriented Robotics MVP)

Context for AI coding assistants working on this repo. Read this first; it
replaces scanning the whole repository. Keep it in sync with the code.

## Current work

The repo is partway through the pre-feature hardening pass: `HARDENING_PLAN.md`
is the authoritative scope, wave plan and merge-gate checklist, and
`docs/DECISIONS.md` records the locked decisions (D-1…D-6). Where this file
disagrees with either, they win. `docs/DOC_AUDIT.md` is the stale-doc work list.
`next.md` is the user's personal notes and is strictly off-limits (ground rule
7 of the plan) — never read, edit, stage, commit or ignore it.

## One-liner

A Java robot controller (separate process) drives a robot in a
continuously running Node.js/TypeScript simulation; a browser visualiser
displays the robot's movement. Two WebSocket channels carry NDJSON between
a single sim server, the Java robot, and the visualiser.

## Repository layout (what lives where)

```
oobot/
├── AGENTS.md                 # this file — assistant context
├── HARDENING_PLAN.md         # authoritative hardening scope, waves, merge gates
├── README.md                 # user-facing getting started
├── ARCHITECTURE.md           # high-level architecture & boundaries
├── MVP.md                    # scope, in/out of scope, success criteria
├── DESIGN_REVIEW.md          # resolved open questions / decisions (historical)
├── FUTURE.md                 # post-MVP ideas
├── package.json              # npm workspaces + root dev scripts (npm run dev)
├── package-lock.json         # committed lockfile (D-6)
├── scripts/
│   ├── dev.js                # one-command dev startup (sim → viz → robot)
│   └── runRobot.js           # starts robot.jar with an absolute --robot path
├── robots/
│   └── default.json          # v2 component layout (id, type, angle, offset)
├── simulator/
│   ├── server/               # Node.js/TS sim + WebSocket server (ports 8080/8081)
│   │   ├── src/index.ts      # entry point (--robot, --world, port env vars)
│   │   ├── src/sim.ts        # sim engine: timestep, command ownership, safe stop
│   │   ├── src/transport.ts  # WebSocket transport (two channels, per-conn seq)
│   │   ├── src/types.ts      # shared TS types
│   │   ├── src/world.ts      # world model, kinematics, collision, world validation
│   │   ├── src/sensor.ts     # box-edge ray casting for proximity sensors
│   │   ├── src/robotDescription.ts # TS mirror/loader of the shared manifest
│   │   ├── worlds/maze.json  # default obstacle world (loaded by --world)
│   │   ├── test/**           # node:test suites + manifest fixtures
│   │   ├── package.json      # ts-node, ws, typescript
│   │   └── tsconfig.json
│   └── visualiser/           # vanilla TS + HTML5 canvas (vite)
│       ├── src/main.ts       # entry: live WS or hardcoded fallback stream
│       ├── src/liveStream.ts # WS client + NDJSON buffer + interpolation/reset
│       ├── src/snapshotStream.ts # hardcoded fallback keyframes
│       ├── src/renderer.ts   # canvas drawing
│       ├── src/math.ts
│       ├── src/types.ts
│       ├── test/**           # node:test suites
│       ├── index.html
│       ├── package.json      # vite, typescript
│       └── tsconfig.json
├── robot-framework/          # Java framework + example controller (fat JAR)
│   ├── build.gradle.kts      # Gradle Kotlin DSL build (fat JAR task: fatJar)
│   ├── settings.gradle.kts
│   ├── gradle/libs.versions.toml  # centralised dependency versions
│   ├── gradlew / gradlew.bat # Gradle wrapper (downloads Gradle 8.10.2)
│   ├── MODERNISATION.md      # build/library upgrade progress tracker
│   ├── robot.jar             # LOCAL build output — gitignored, not committed
│   ├── src/main/resources/logback.xml  # logging config
│   ├── src/test/**           # JUnit 5 tests + manifest fixtures
│   ├── README.md
│   └── src/main/java/oobot/robot/
│       ├── RobotController.java  # picocli entry point (wires student + framework)
│       ├── student/              # ← STUDENT CODE AREA (DI happens here)
│       │   ├── RobotBehaviour.java  # student implements: init() / update()
│       │   ├── Mover.java           # student writes: wheel→movement abstraction
│       │   ├── Brain.java           # student writes: DI/wiring; FIXED sensor ids (D-3)
│       │   ├── Component.java       # generic id-addressed component (id only)
│       │   ├── Wheel.java           # student-facing wheel component
│       │   ├── ProximitySensor.java # student-facing proximity component
│       │   └── BrownianMotionBehaviour.java  # opt-in behaviour (not the default)
│       ├── example/               # example implementations (reference, not student)
│       │   ├── WheeledMover.java    # reference Mover implementation
│       │   ├── DefaultBrain.java    # reference Brain (DI) implementation
│       │   ├── SquareTraceBehaviour.java
│       │   ├── CircleTraceBehaviour.java
│       │   ├── HeartTraceBehaviour.java
│       │   ├── WallAvoidBehaviour.java
│       │   └── WallFollowRightBehaviour.java  # DEFAULT behaviour
│       ├── core/                  # framework core (not student)
│       │   └── BehaviourLoop.java
│       ├── description/           # component-manifest model (not student)
│       │   └── ...                # Java mirror of robots/default.json
│       ├── bridge/                # platform abstraction (not student)
│       │   └── RobotPlatform.java
│       ├── bridge/sim/            # sim bridge (not student)
│       │   ├── SimRobot.java      # lifecycle, batching, two-layer safe stop
│       │   ├── SimProximitySensor.java
│       │   └── SimWheel.java
│       └── transport/             # transport (not student)
│           ├── WsConnection.java    # OkHttp WebSocket client
│           ├── Json.java            # Jackson serialisation
│           ├── WireMessage.java     # sealed interface (polymorphic envelope)
│           ├── ComponentCommandMessage.java / ComponentSnapshotMessage.java
│           ├── ComponentValue.java / ComponentReading.java
│           ├── ComponentCommandPayload.java / ComponentSnapshotPayload.java
│           └── WsListener.java
├── specs/                    # wire contract & component specs (read-only reference)
│   ├── WIRE_PROTOCOL.md      # NDJSON envelope, ports, message types
│   ├── COMMS.md              # transport interface (swappable)
│   ├── COMPONENTS.md         # per-component responsibilities
│   ├── ROBOT_DESCRIPTION.md  # manifest schema + id-addressed wire messages
│   ├── ROBOT_LAUNCH.md       # how the Java controller is started
│   ├── SENSORS.md            # proximity-sensor model (iteration 2)
│   └── WORLD_FORMAT.md       # world model: bounded box + maze obstacles
├── docs/
│   ├── DECISIONS.md          # locked D-1..D-6 decisions (authoritative)
│   ├── DOC_AUDIT.md          # W3 documentation work list
│   └── archive/              # historical plans, kept for context only
│       ├── PLAN.md
│       └── PLAN_SENSORS.md
└── .gitignore                # node_modules, dist, dist-test, build, jars, IDE files
```

## How to run (one command)

From repo root (requires Node.js ≥ 20, npm ≥ 10, JDK 21+):

```bash
npm install        # installs both npm workspaces (sim server + visualiser); lockfiles are committed
npm run build:robot # once (builds robot.jar via Gradle — wrapper downloads Gradle on first run)
npm run dev        # starts sim server, visualiser dev server, example robot
```

`scripts/dev.js` starts the sim first, probes `127.0.0.1` on the robot port
(`ROBOT_PORT`, default 8080) with a 30 s startup timeout, then starts the
visualiser and the Java robot with `--behaviour wall-follow-right`.
SIGINT/SIGTERM clean up all children. Opens `http://localhost:5173` for the
visualiser.

### Start components individually

| Component | Command | Directory |
|---|---|---|
| Sim server | `npm run dev:server` (ts-node) | repo root (or `npm run dev` in `simulator/server/`) |
| Visualiser | `npm run dev:visualiser` (vite) | repo root (or `npm run dev` in `simulator/visualiser/`) |
| Robot | `npm run dev:robot` (recommended — `scripts/runRobot.js` passes an absolute `--robot` path), or `java -jar robot.jar --host localhost --port 8080 --behaviour wall-follow-right --robot <path>` | repo root / `robot-framework/` |

The robot and sim server both accept `--robot <path>` (v2 component manifest);
their defaults resolve relative to their own code/JAR so standalone commands
work from any CWD. The sim also accepts `--world <path>` for the obstacle world
(default `simulator/server/worlds/maze.json`). The manifest decides which
id-addressed components exist and their mount angles; the world file supplies
obstacles and the start pose. The visualiser reads neither — it renders
`worldSnapshot.payload.obstacles`.

### Build and test

- Sim server: `cd simulator/server && npm run build` (tsc → `dist/`)
- Typecheck sim: `cd simulator/server && npm run typecheck`
- Sim tests: `cd simulator/server && npm test` (node:test → `dist-test/`)
- Visualiser: `cd simulator/visualiser && npm run build` (vite build + tsc --noEmit)
- Visualiser tests: `cd simulator/visualiser && npm test`
- Robot JAR: `npm run build:robot` (runs `./gradlew fatJar` in `robot-framework/` → `robot.jar`)
  or `cd robot-framework && ./gradlew fatJar` directly
- Robot tests: `cd robot-framework && ./gradlew test`

## Wire protocol (the contract everything agrees on)

- Format: newline-delimited JSON (NDJSON), one JSON object per line.
- Envelope: `{ "type": "...", "seq": 0, "simTick": 0, "payload": { ... } }`.
  `seq` is assigned per outgoing connection by the transport (two visualisers
  get independent monotonic sequences); `simTick` is the sim's logical clock and
  the source of truth for freshness, not wall-clock.
- Two channels from one sim server process, distinguished by port:
  - **Robot channel** `ws://localhost:8080` — `componentCommand` (robot→sim;
    `payload.commands: [{id, value}]`), `componentSnapshot` (sim→robot;
    `payload.readings: [{id, value|null}]`). Persistent, bidirectional; single
    active robot slot (a new robot closes the previous one).
  - **Visualiser channel** `ws://localhost:8081` — `worldSnapshot`
    (`payload.robot: {x, y, heading}`, `payload.obstacles: [...]`) (sim→visualiser).
    Unidirectional (MVP); multiple tabs are supported.
- Ports default to 8080/8081 and can be overridden via `ROBOT_PORT` /
  `VISUALISER_PORT` (validated as integers in 1–65535).
- Sockets bind `0.0.0.0` (IPv4 client compatibility); no Origin header or an
  http(s) localhost/loopback Origin is accepted, `Origin: null` and other
  origins are rejected. Inbound lines are length-capped and shutdown is bounded.
- Commands are accepted only from the active robot connection: `seq` and
  `simTick` must be finite non-negative integers, `seq` must strictly increase
  and `simTick` must be no more than one tick older than the last accepted
  command (D-4). Rejected commands never touch actuator state.
- **Safe stop has two independent layers (D-2):** the sim zeroes motors when the
  active robot socket closes (and on shutdown); the Java bridge writes one
  `componentCommand` with every wheel id at `0.0` **before** closing or
  reconnecting the socket. No heartbeat/timeout yet. Java batches all wheel
  changes from a behaviour tick into a single command frame.
- Visualiser state (snapshot buffer, interpolation clock, trail) resets on every
  connect/reconnect and when `simTick` goes backwards (D-5).

Full spec: `specs/WIRE_PROTOCOL.md`. Transport interface: `specs/COMMS.md`.
Locked decisions: `docs/DECISIONS.md`.

## Student-facing Java API (robot code touches only this)

The student code area is `robot-framework/src/main/java/oobot/robot/student/`.
Robot hardware is modelled as generic, id-addressed **components**; the default
layout is the shared manifest `robots/default.json`, mirrored in Java by the
`oobot.robot.description` package and in TypeScript by
`simulator/server/src/robotDescription.ts`. The Java robot and sim server load
it at startup; the visualiser does not. Students implement `RobotBehaviour`
and use the components exposed by their `Brain`.

- **`RobotBehaviour`** — implement `init()` / `update()` (~20 Hz): read
  components, command movement.
- **`Component`** — generic interface implemented by every component; declares
  only `id()`.
- **`Wheel`** — a `Component` that drives the robot: `angle()` (mount angle in
  radians; `sin(angle) > 0.9` = left, `< -0.9` = right), `clockwise()` /
  `antiClockwise()` / `stop()`.
- **`ProximitySensor`** — a `Component` with `latestSimTick()` (tick of the most
  recent reading) and `distance()` in its mounted direction (`Infinity` if
  nothing detected). `latestSimTick()` lets behaviour code detect stale data;
  the bridge's staleness threshold is 30 sim ticks.
- **`Mover`** — high-level wheel-to-movement abstraction: translates
  `forward()` / `backward()` / `turnRight()` / `turnLeft()` / `stop()` into
  per-wheel actions.
- **`Brain`** — the DI/wiring interface: exposes `mover()`, `frontSensor()` and
  `rightSensor()`, plus `setBehaviour(...)` and `tick()`. **Constraint (D-3):**
  these accessors are fixed-id, not generic — `frontSensor()` must resolve
  `front-sensor` and `rightSensor()` must resolve `right-sensor`; the controller
  fails at startup if either `proximitySensor` id is missing. A manifest may
  declare other sensors, but this API cannot address them.

Student code never touches sockets, JSON, motor power values, or wheel mounting —
it uses the `Mover` and sensors exposed by its `Brain`. The behaviour loop
(`oobot.robot.core.BehaviourLoop`) calls `Brain.tick()` at ~20 Hz.

### Mount-angle convention

Components are placed by **mount angle** plus a radial **offset**, declared in
the v2 manifest `robots/default.json`. `angle` is in radians: 0 = robot forward,
positive = counter-clockwise (right = -π/2). `offset` is the radial distance
from the robot centre (wheels only; default `0.25`, must be finite and > 0).
Wheels still roll along the robot's heading (tangentially); each sensor casts a
ray along `pose.heading + component.angle` against box edges and obstacles, and
the nearest hit wins. The layout is data, not code: both the Java robot and sim
server load it at startup. The visualiser does not read the manifest — it draws
the obstacles supplied in `worldSnapshot`.

```json
{
  "schemaVersion": 2,
  "name": "default-two-wheel",
  "components": [
    { "id": "left-wheel",   "type": "wheel",           "angle":  1.5707963267948966, "offset": 0.25 },
    { "id": "right-wheel",  "type": "wheel",           "angle": -1.5707963267948966, "offset": 0.25 },
    { "id": "front-sensor", "type": "proximitySensor", "angle":  0.0 },
    { "id": "right-sensor", "type": "proximitySensor", "angle": -1.5707963267948966 }
  ]
}
```

- `schemaVersion` must be `2`; a `side` field is rejected and wheel roles are
  **derived** from angle instead: `sin(angle) > 0.9` → left, `sin(angle) < -0.9`
  → right (exactly one of each required; other angles rejected).
- Track width is derived from the manifest as `y_left - y_right` =
  `offset_left·sin(angle_left) − offset_right·sin(angle_right)` (`0.5` for the
  default manifest). The sim uses this in place of the default world config's
  `trackWidth`; the robot stays a point for sensing/collision.
- Kinematics (D-1): the world box stays in world units, with
  `WORLD_UNITS_PER_METRE = 100`. Wheel powers are converted to m/s before
  computing angular velocity, then linear speed is converted back to world
  units/s so travel speeds are unchanged:

  ```
  v_l = clamp(p_left,  -1, 1) * maxSpeed / 100   // m/s
  v_r = clamp(p_right, -1, 1) * maxSpeed / 100   // m/s
  linear  = (v_l + v_r) / 2 * 100                // world units/s
  angular = (v_r - v_l) / trackWidth             // rad/s (trackWidth in metres)
  ```

  With defaults (`maxSpeed=100`, track width `0.5`) full differential drive is
  `4 rad/s ≈ 229°/s` and 50% differential is `2 rad/s`. There is no independent
  `maxAngularSpeed` clamp; turns are proportional to input.

```java
interface Component {
    String id();
}
interface Wheel extends Component {
    double angle();           // mount angle (rad): 0 = forward, + = CCW; left if sin > 0.9, right if sin < -0.9
    void clockwise();
    void antiClockwise();
    void stop();
}
interface ProximitySensor extends Component {
    long latestSimTick();         // tick of the most recent reading
    double distance();            // Infinity if nothing detected
}
interface RobotBehaviour { void init(); void update(); }   // student implements (~20 Hz)
interface Mover {                 // high-level, wheel-agnostic movement
    void forward(); void backward(); void turnRight(); void turnLeft(); void stop();
}
interface Brain {                 // student writes DI/wiring implementation
    Mover mover();
    ProximitySensor frontSensor();  // fixed id "front-sensor" (D-3)
    ProximitySensor rightSensor();  // fixed id "right-sensor" (D-3)
    void setBehaviour(RobotBehaviour behaviour); // null stops motion
    void tick();                  // delegates to current behaviour.update()
}
```

Example implementations (reference, not student code) live in `oobot.robot.example`
(`WheeledMover` is the reference Mover; `DefaultBrain` is the reference Brain;
`WallFollowRightBehaviour` is the default behaviour selected by `--behaviour`
and `npm run dev`). `BrownianMotionBehaviour` lives in the student package and
is opt-in (`--behaviour brownian`).
`WheeledMover` takes the full wheel list (`WheeledMover(List<Wheel>)`) and derives
left/right roles from each wheel's `angle()`; `RobotController` supplies that
list via `ComponentRegistry.wheels()`, which returns the bound `Wheel` handles
sorted by ascending mount angle.
The platform abstraction is `oobot.robot.bridge.RobotPlatform`; its sim
implementation (`oobot.robot.bridge.sim.SimRobot`) hides WebSocket, NDJSON
framing, reconnection, batched commands (one `componentCommand` per tick),
two-layer safe stop, and concurrency.

To add a new behaviour: create a class implementing `RobotBehaviour`, use the
`Mover` and sensors exposed by your `Brain`, and register everything in
`oobot.robot.RobotController` (`VALID_BEHAVIOURS` and `createBehaviour`).
Rebuild the JAR with `npm run build:robot`.

## Coding conventions & constraints

- TypeScript: `strict: true` in both `tsconfig.json`s. No ESLint/Prettier config present — match existing file style.
- Java: Gradle (Kotlin DSL) with pinned dependency versions in `gradle/libs.versions.toml`.
  Libraries: Jackson (JSON), OkHttp (WebSocket), SLF4J + Logback (logging), picocli (CLI).
  JDK 21+. Fat JAR built with `./gradlew fatJar`.
- Simulation owns world state; robot code only commands hardware via the framework.
- Rendering never drives or blocks simulation timing. Sim runs on its own fixed ~60 Hz timestep.
- Axis-aligned box obstacles, two id-addressed proximity sensors and six
  behaviours are implemented. Defer: complex obstacle shapes, heartbeat,
  multiple robots, genuinely generic `Brain` sensor lookup.
- Tests exist in all three packages: `simulator/server/test/**` and
  `simulator/visualiser/test/**` (node:test), `robot-framework/src/test/**`
  (JUnit 5 + byte-identical manifest fixtures). Run them before changing code.
- Lockfiles are committed; `robot.jar` is a local, gitignored build output.

## Where specs live (reference, rarely need editing)

`specs/WIRE_PROTOCOL.md`, `COMMS.md`, `COMPONENTS.md`, `ROBOT_DESCRIPTION.md`,
`ROBOT_LAUNCH.md`, `SENSORS.md`, `WORLD_FORMAT.md`. Edit these only when the
wire contract or component boundaries change; code is source of truth otherwise.
Locked decisions live in `docs/DECISIONS.md`; the doc audit / rewrite work list
is `docs/DOC_AUDIT.md`. `docs/archive/` holds superseded iteration-1 plans.

## Common tasks & where to start

| Task | Files to touch |
|---|---|
| Change sim world/timestep/collision/sensing | `simulator/server/src/world.ts`, `sensor.ts`, `sim.ts`, `worlds/maze.json` |
| Add a sim→visualiser message type | `simulator/server/src/transport.ts`, `types.ts`, `sim.ts` + `specs/WIRE_PROTOCOL.md` |
| Change robot→sim command handling / stale-command rules | `simulator/server/src/sim.ts`, `transport.ts`, `types.ts` |
| Add a robot behaviour | New `*.java` implementing `RobotBehaviour` in `robot-framework/src/main/java/oobot/robot/student/` (or `example/`), register in `RobotController.createBehaviour` + `VALID_BEHAVIOURS`, then `npm run build:robot` |
| Change the Java hardware API | `oobot/robot/student/{RobotBehaviour,Mover,Component,Wheel,ProximitySensor,Brain}.java` + `oobot/robot/description/` |
| Change the robot component layout | `robots/default.json`, `oobot/robot/description/`, `simulator/server/src/robotDescription.ts` + both fixture directories |
| Change world file / spawn validation | `simulator/server/src/world.ts`, `worlds/maze.json` + `specs/WORLD_FORMAT.md` |
| Change visualiser rendering | `simulator/visualiser/src/renderer.ts`, `main.ts` |
| Change visualiser WS client / reconnect reset | `simulator/visualiser/src/liveStream.ts` |
| One-command dev startup | `scripts/dev.js`, `scripts/runRobot.js` + root `package.json` |

## What to avoid

- Don't let rendering block or drive the sim tick.
- Don't reintroduce hand-rolled JSON/WebSocket code — use Jackson/OkHttp (see `robot-framework/MODERNISATION.md`).
- Don't make robot code depend on the sim's implementation language — the framework boundary is intentional.
- Don't describe `motorCommand` / `sensorSnapshot` or static `Sensors` as current; they were replaced by id-addressed `componentCommand` / `componentSnapshot` and `ProximitySensor`.
- Don't touch `next.md` (user's personal notes; ground rule 7 of `HARDENING_PLAN.md`).
- Don't commit `node_modules/`, `dist/`, `dist-test/`, `build/`, `*.jar`, `.idea/`, `.vscode/` (covered by `.gitignore`); lockfiles *are* committed.