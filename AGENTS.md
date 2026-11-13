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
├── simulator/
│   ├── server/               # Node.js/TS sim + WebSocket server (ports 8080/8081)
│   │   ├── src/index.ts      # server entry point
│   │   ├── src/sim.ts        # world model + fixed timestep
│   │   ├── src/transport.ts  # WebSocket transport (two channels)
│   │   ├── src/types.ts      # shared TS types
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
│   ├── robot.jar             # prebuilt (gitignored but committed here for dev)
│   ├── src/main/java/oobot/robot/
│   │   ├── RobotController.java  # main: connects, runs behaviour loop
│   │   ├── Framework.java        # connection + behaviour scheduler (~20 Hz)
│   │   ├── RobotBehaviour.java   # student-facing: init() / update()
│   │   ├── Mover.java            # forward / turnRight / turnLeft / stop
│   │   ├── WheeledMover.java     # Mover impl → motorCommand NDJSON
│   │   ├── Motor.java            # static setSpeed(double)
│   │   ├── Sensors.java          # static latestSimTick()
│   │   ├── SquareTraceBehaviour.java (default example)
│   │   ├── CircleTraceBehaviour.java
│   │   ├── HeartTraceBehaviour.java
│   │   ├── WsConnection.java     # hand-rolled WebSocket client
│   │   ├── Json.java / Envelope.java / WsListener.java
│   │   └── build/                # javac output (gitignored)
│   └── README.md
├── specs/                    # wire contract & component specs (read-only reference)
│   ├── WIRE_PROTOCOL.md      # NDJSON envelope, ports, message types
│   ├── COMMS.md              # transport interface (swappable)
│   ├── COMPONENTS.md         # per-component responsibilities
│   ├── ROBOT_LAUNCH.md       # how the Java controller is started
│   ├── SENSORS.md            # informal sensor notes (not MVP spec)
│   └── WORLD_FORMAT.md       # world model (iteration 1: bounded box)
└── .gitignore                # node_modules, dist, build, *.jar, .idea, .vscode
```

## How to run (one command)

From repo root (requires Node.js ≥ 20, npm ≥ 10, JDK 21+):

```bash
npm install        # once (installs sub-package deps; root has no deps of its own)
npm run dev        # starts sim server, visualiser dev server, example robot
```

`scripts/dev.js` starts the sim first, waits for port 8080, then starts the
visualiser, then the Java robot. Opens `http://localhost:5173` for the visualiser.

### Start components individually

| Component | Command | Directory |
|---|---|---|
| Sim server | `npm run dev` (ts-node) | `simulator/server/` |
| Visualiser | `npm run dev` (vite) | `simulator/visualiser/` |
| Robot | `java -jar robot.jar --host localhost --port 8080` | `robot-framework/` |

### Build

- Sim server: `cd simulator/server && npm run build` (tsc → `dist/`)
- Typecheck sim: `cd simulator/server && npm run typecheck`
- Visualiser: `cd simulator/visualiser && npm run build` (vite build + tsc --noEmit)
- Robot JAR: `cd robot-framework && mkdir -p build/classes && javac -d build/classes $(find src/main/java -name '*.java') && jar cfe robot.jar oobot.robot.RobotController -C build/classes .`

## Wire protocol (the contract everything agrees on)

- Format: newline-delimited JSON (NDJSON), one JSON object per line.
- Envelope: `{ "type": "...", "seq": 0, "simTick": 0, "payload": { ... } }`
- `simTick` is the source of truth for sequencing/staleness (not wall-clock).
- Two channels from one sim server process, distinguished by port:
  - **Robot channel** `ws://localhost:8080` — `motorCommand` (robot→sim),
    `sensorSnapshot` (sim→robot). Persistent, bidirectional.
  - **Visualiser channel** `ws://localhost:8081` — `worldSnapshot` (sim→visualiser).
    Unidirectional (MVP).
- Both bind `0.0.0.0`; only `localhost` origins accepted (or no Origin header).
- On robot WebSocket close: motors stop (safe stop). No heartbeat/timeout yet.

Full spec: `specs/WIRE_PROTOCOL.md`. Transport interface: `specs/COMMS.md`.

## Student-facing Java API (robot code touches only this)

```java
interface RobotBehaviour { void init(); void update(); }      // ~20 Hz
interface Mover { void forward(); void turnRight(); void turnLeft(); void stop(); }
class Motor { static void setSpeed(double speed); }           // [-1, 1]
class Sensors { static long latestSimTick(); static double distance(); static double rightDistance(); }   // front + right proximity readings
```

Example behaviours implement `RobotBehaviour` and command movement via `Mover`.
Default example: `SquareTraceBehaviour`. Framework (`Framework.java`) hides
WebSocket, NDJSON framing, reconnection, and concurrency.

## Coding conventions & constraints

- TypeScript: `strict: true` in both `tsconfig.json`s. No ESLint/Prettier config present — match existing file style.
- Java: plain `javac`/`jar`, **no Maven or Gradle**, zero external dependencies. JDK 21+.
- Simulation owns world state; robot code only commands hardware via the framework.
- Rendering never drives or blocks simulation timing. Sim runs on its own fixed ~60 Hz timestep.
- Keep the MVP small. Defer: obstacles with complex shapes, heartbeat, multiple robots, tests.
- No test files exist yet in any component.

## Where specs live (reference, rarely need editing)

`specs/WIRE_PROTOCOL.md`, `COMMS.md`, `COMPONENTS.md`, `ROBOT_LAUNCH.md`,
`SENSORS.md`, `WORLD_FORMAT.md`. Edit these only when the wire contract or
component boundaries change; code is source of truth otherwise.

## Common tasks & where to start

| Task | Files to touch |
|---|---|
| Change sim world/timestep/collision | `simulator/server/src/sim.ts`, `world.ts` |
| Add a sim→visualiser message type | `simulator/server/src/transport.ts`, `types.ts` + `specs/WIRE_PROTOCOL.md` |
| Change robot→sim command handling | `simulator/server/src/transport.ts` |
| Add a robot behaviour | New `*.java` implementing `RobotBehaviour` in `robot-framework/src/...` |
| Change the Java hardware API | `RobotBehaviour.java`, `Mover.java`, `Motor.java`, `Sensors.java` |
| Change visualiser rendering | `simulator/visualiser/src/renderer.ts`, `main.ts` |
| Change visualiser WS client | `simulator/visualiser/src/liveStream.ts` |
| One-command dev startup | `scripts/dev.js` + root `package.json` |

## What to avoid

- Don't let rendering block or drive the sim tick.
- Don't add Maven/Gradle to robot-framework (keep zero-dependency `javac`/`jar`).
- Don't make robot code depend on the sim's implementation language — the framework boundary is intentional.
- Don't commit `node_modules/`, `dist/`, `build/`, `*.jar`, `.idea/`, `.vscode/` (covered by `.gitignore`).