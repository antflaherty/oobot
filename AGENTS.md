# AGENTS.md — oobot (Object Oriented Robotics MVP)

Context for AI coding assistants working on this repo. Read this first; it
replaces scanning the whole repository. Keep it in sync with the code.

## One-liner

A Java robot controller (separate process) drives a robot in a
continuously running Node.js/TypeScript simulation; a browser visualiser
displays the robot's movement. Two WebSocket channels carry NDJSON between
a single sim server, the Java robot, and the visualiser.

## Repository layout (what lives where)

```
oobot/
├── AGENTS.md                 # this file — assistant context
├── README.md                 # user-facing getting started
├── ARCHITECTURE.md           # high-level architecture & boundaries
├── MVP.md                    # scope, in/out of scope, success criteria
├── PLAN.md                   # implementation plan of attack
├── DESIGN_REVIEW.md          # resolved open questions / decisions
├── FUTURE.md                 # post-MVP ideas
├── package.json              # root dev scripts (npm run dev)
├── scripts/dev.js            # one-command dev startup (sim → viz → robot)
├── robots/                   # shared robot component manifest(s)
│   └── default.json          # v2 component layout (id, type, angle, offset)
├── simulator/
│   ├── server/               # Node.js/TS sim + WebSocket server (ports 8080/8081)
│   │   ├── src/index.ts      # server entry point
│   │   ├── src/sim.ts        # world model + fixed timestep
│   │   ├── src/transport.ts  # WebSocket transport (two channels)
│   │   ├── src/types.ts      # shared TS types
│   │   ├── src/robotDescription.ts # TS mirror/loader of the shared manifest
│   │   └── src/world.ts      # world model helpers
│   │   ├── package.json      # ts-node, ws, typescript
│   │   └── tsconfig.json
│   └── visualiser/           # vanilla TS + HTML5 canvas (vite)
│       ├── src/main.ts       # entry: live WS or hardcoded fallback stream
│       ├── src/liveStream.ts # WebSocket client + NDJSON + interpolation
│       ├── src/snapshotStream.ts
│       ├── src/renderer.ts   # canvas drawing
│       ├── src/math.ts
│       ├── src/types.ts
│       ├── index.html
│       ├── package.json      # vite, typescript
│       └── tsconfig.json
├── robot-framework/          # Java framework + example controller (fat JAR)
│   ├── build.gradle.kts      # Gradle Kotlin DSL build (fat JAR task: fatJar)
│   ├── settings.gradle.kts
│   ├── gradle/libs.versions.toml  # centralised dependency versions
│   ├── gradlew / gradlew.bat # Gradle wrapper (downloads Gradle 8.10.2)
│   ├── MODERNISATION.md      # build/library upgrade progress tracker
│   ├── robot.jar             # prebuilt fat JAR (gitignored but committed here for dev)
│   ├── src/main/resources/logback.xml  # logging config
│   ├── src/main/java/oobot/robot/
│   │   ├── RobotController.java  # main entry point (wires student + framework)
│   │   ├── student/              # ← STUDENT CODE AREA (DI happens here)
│   │   │   ├── RobotBehaviour.java  # student implements: init() / update()
│   │   │   ├── Mover.java           # student writes: wheel→movement abstraction
│   │   │   ├── Brain.java           # student writes: DI/wiring of components + behaviour
│   │   │   ├── Component.java       # generic id-addressed component (id only)
│   │   │   ├── Wheel.java           # student-facing wheel component
│   │   │   └── ProximitySensor.java # student-facing proximity component
│   │   ├── example/               # example implementations (reference, not student)
│   │   │   ├── WheeledMover.java    # reference Mover implementation
│   │   │   ├── DefaultBrain.java    # reference Brain (DI) implementation
│   │   │   ├── SquareTraceBehaviour.java (default example)
│   │   │   ├── CircleTraceBehaviour.java
│   │   │   ├── HeartTraceBehaviour.java
│   │   │   ├── WallAvoidBehaviour.java
│   │   │   └── WallFollowRightBehaviour.java
│   │   ├── core/                  # framework core (not student)
│   │   │   └── BehaviourLoop.java
│   │   ├── description/           # component-manifest model (not student)
│   │   │   └── ...                # Java mirror of robots/default.json
│   │   ├── bridge/sim/            # sim bridge (not student)
│   │   │   ├── SimRobot.java
│   │   │   ├── SimProximitySensor.java
│   │   │   └── SimWheel.java
│   │   └── transport/             # transport (not student)
│   │       ├── WsConnection.java    # OkHttp WebSocket client
│   │       ├── Json.java            # Jackson serialisation
│   │       ├── WireMessage.java     # sealed interface (polymorphic envelope)
│   │       ├── ComponentCommandMessage.java / ComponentSnapshotMessage.java
│   │       ├── ComponentValue.java / ComponentReading.java
│   │       ├── ComponentCommandPayload.java / ComponentSnapshotPayload.java
│   │       └── WsListener.java
│   └── README.md
├── specs/                    # wire contract & component specs (read-only reference)
│   ├── WIRE_PROTOCOL.md      # NDJSON envelope, ports, message types
│   ├── COMMS.md              # transport interface (swappable)
│   ├── COMPONENTS.md         # per-component responsibilities
│   ├── ROBOT_DESCRIPTION.md  # manifest schema + id-addressed wire messages
│   ├── ROBOT_LAUNCH.md       # how the Java controller is started
│   ├── SENSORS.md            # informal sensor notes (not MVP spec)
│   └── WORLD_FORMAT.md       # world model (iteration 1: bounded box)
└── .gitignore                # node_modules, dist, build, *.jar, .idea, .vscode
```

## How to run (one command)

From repo root (requires Node.js ≥ 20, npm ≥ 10, JDK 21+):

```bash
npm install        # once (installs sub-package deps; root has no deps of its own)
npm run build:robot # once (builds robot.jar via Gradle — wrapper downloads Gradle on first run)
npm run dev        # starts sim server, visualiser dev server, example robot
```

`scripts/dev.js` starts the sim first, waits for port 8080, then starts the
visualiser, then the Java robot. Opens `http://localhost:5173` for the visualiser.

### Start components individually

| Component | Command | Directory |
|---|---|---|
| Sim server | `npm run dev` (ts-node) | `simulator/server/` |
| Visualiser | `npm run dev` (vite) | `simulator/visualiser/` |
| Robot | `java -jar robot.jar --host localhost --port 8080 --robot ../robots/default.json` | `robot-framework/` |

Both the robot and the sim server accept `--robot <path>` to select a component
manifest (default layout: `robots/default.json`); `npm run dev` passes an
absolute path. It decides which id-addressed components the robot exposes and
their mount angles. The visualiser does not read the manifest.

### Build

- Sim server: `cd simulator/server && npm run build` (tsc → `dist/`)
- Typecheck sim: `cd simulator/server && npm run typecheck`
- Visualiser: `cd simulator/visualiser && npm run build` (vite build + tsc --noEmit)
- Robot JAR: `npm run build:robot` (runs `./gradlew fatJar` in `robot-framework/` → `robot.jar`)
  or `cd robot-framework && ./gradlew fatJar` directly

## Wire protocol (the contract everything agrees on)

- Format: newline-delimited JSON (NDJSON), one JSON object per line.
- Envelope: `{ "type": "...", "seq": 0, "simTick": 0, "payload": { ... } }`
- `simTick` is the source of truth for sequencing/staleness (not wall-clock).
- Two channels from one sim server process, distinguished by port:
  - **Robot channel** `ws://localhost:8080` — `componentCommand` (robot→sim;
    `payload.commands: [{id, value}]`), `componentSnapshot` (sim→robot;
    `payload.readings: [{id, value|null}]`). Persistent, bidirectional.
  - **Visualiser channel** `ws://localhost:8081` — `worldSnapshot` (sim→visualiser).
    Unidirectional (MVP).
- Both bind `0.0.0.0`; only `localhost` origins accepted (or no Origin header).
- On robot WebSocket close: motors stop (safe stop). No heartbeat/timeout yet.

Full spec: `specs/WIRE_PROTOCOL.md`. Transport interface: `specs/COMMS.md`.

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
  radians), `clockwise()` / `antiClockwise()` / `stop()`.
- **`ProximitySensor`** — a `Component` with `latestSimTick()` (tick of the most
  recent reading) and `distance()` in its mounted direction (`Infinity` if
  nothing detected).
- **`Mover`** — high-level wheel-to-movement abstraction: translates
  `forward()` / `turnRight()` / `turnLeft()` / `stop()` into per-wheel actions.
- **`Brain`** — the DI/wiring interface: exposes `mover()`, `frontSensor()` and
  `rightSensor()`, plus `setBehaviour(...)` and `tick()`.

Student code never touches sockets, JSON, motor power values, or wheel mounting —
it uses the `Mover` and sensors exposed by its `Brain`. The behaviour loop
(`oobot.robot.core.BehaviourLoop`) calls `Brain.tick()` at ~20 Hz.

### Mount-angle convention

Components are placed by **mount angle** plus a radial **offset**, declared in
the v2 manifest `robots/default.json`. `angle` is in radians: 0 = robot forward,
positive = counter-clockwise (right = -π/2). `offset` is the radial distance
from the robot centre (wheels only; default `0.25`, must be finite and > 0).
Wheels still roll along the robot's heading (tangentially); sensors cast rays in
their mounted direction. The layout is data, not code: both the Java robot and
sim server load it at startup. The visualiser does not read the manifest
(visualiser changes are deferred).

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

```java
interface Component {
    String id();
}
interface Wheel extends Component {
    double angle();           // mount angle (rad): 0 = forward, + = CCW; left if sin > 0, right if sin < 0
    void clockwise();
    void antiClockwise();
    void stop();
}
interface ProximitySensor extends Component {
    long latestSimTick();         // tick of the most recent reading
    double distance();            // Infinity if nothing detected
}
interface RobotBehaviour { void init(); void update(); }   // student implements (~20 Hz)
interface Mover { void forward(); void turnRight(); void turnLeft(); void stop(); }
interface Brain {                 // student writes DI/wiring implementation
    Mover mover();
    ProximitySensor frontSensor();
    ProximitySensor rightSensor();
    void setBehaviour(RobotBehaviour behaviour);
    void tick();                  // delegates to current behaviour.update()
}
```

Example implementations (reference, not student code) live in `oobot.robot.example`
(`WheeledMover` is the reference Mover; `DefaultBrain` is the reference Brain;
`WallFollowRightBehaviour` is the default behaviour).
`WheeledMover` takes the full wheel list (`WheeledMover(List<Wheel>)`) and derives
left/right roles from each wheel's `angle()`; `RobotController` supplies that
list via `ComponentRegistry.wheels()`, which returns the bound `Wheel` handles
sorted by ascending mount angle.
The bridge (`oobot.robot.bridge.sim.SimRobot`) hides WebSocket, NDJSON framing,
reconnection, and concurrency.

To add a new behaviour: create a class implementing `RobotBehaviour`, use the
`Mover` and sensors exposed by your `Brain`, and register everything in
`oobot.robot.RobotController` (`createBehaviour`). Rebuild the JAR.

## Coding conventions & constraints

- TypeScript: `strict: true` in both `tsconfig.json`s. No ESLint/Prettier config present — match existing file style.
- Java: Gradle (Kotlin DSL) with pinned dependency versions in `gradle/libs.versions.toml`.
  Libraries: Jackson (JSON), OkHttp (WebSocket), SLF4J + Logback (logging), picocli (CLI).
  JDK 21+. Fat JAR built with `./gradlew fatJar`.
- Simulation owns world state; robot code only commands hardware via the framework.
- Rendering never drives or blocks simulation timing. Sim runs on its own fixed ~60 Hz timestep.
- Keep the MVP small. Defer: obstacles with complex shapes, heartbeat, multiple robots, tests.
- No test files exist yet in any component.

## Where specs live (reference, rarely need editing)

`specs/WIRE_PROTOCOL.md`, `COMMS.md`, `COMPONENTS.md`, `ROBOT_DESCRIPTION.md`,
`ROBOT_LAUNCH.md`, `SENSORS.md`, `WORLD_FORMAT.md`. Edit these only when the
wire contract or component boundaries change; code is source of truth otherwise.

## Common tasks & where to start

| Task | Files to touch |
|---|---|
| Change sim world/timestep/collision | `simulator/server/src/sim.ts`, `world.ts` |
| Add a sim→visualiser message type | `simulator/server/src/transport.ts`, `types.ts` + `specs/WIRE_PROTOCOL.md` |
| Change robot→sim command handling | `simulator/server/src/transport.ts` |
| Add a robot behaviour | New `*.java` implementing `RobotBehaviour` in `robot-framework/src/main/java/oobot/robot/student/` (or `example/`), register in `RobotController.createBehaviour` + `VALID_BEHAVIOURS`, then `npm run build:robot` |
| Change the Java hardware API | `oobot/robot/student/{RobotBehaviour,Mover,Component,Wheel,ProximitySensor,Brain}.java` + `oobot/robot/description/` |
| Change the robot component layout | `robots/default.json`, `oobot/robot/description/`, `simulator/server/src/robotDescription.ts` |
| Change visualiser rendering | `simulator/visualiser/src/renderer.ts`, `main.ts` |
| Change visualiser WS client | `simulator/visualiser/src/liveStream.ts` |
| One-command dev startup | `scripts/dev.js` + root `package.json` |

## What to avoid

- Don't let rendering block or drive the sim tick.
- Don't reintroduce hand-rolled JSON/WebSocket code — use Jackson/OkHttp (see `robot-framework/MODERNISATION.md`).
- Don't make robot code depend on the sim's implementation language — the framework boundary is intentional.
- Don't commit `node_modules/`, `dist/`, `build/`, `*.jar`, `.idea/`, `.vscode/` (covered by `.gitignore`).