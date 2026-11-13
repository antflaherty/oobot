# oobot — Object Oriented Robotics (MVP)

A Java program controls a robot in a continuously running simulation, and the robot's behaviour is visible in a separate browser visualiser.

This is a technical proof of concept, not a complete learning product. See `MVP.md` for the full scope and success criteria.

## Architecture (high level)

```
+------------------+      robot channel      +---------------------+
|   Java robot     |  ws://localhost:8080    |   Sim server        |
|   process        |  (NDJSON)               |  (Node/TS)          |
|                  |  motorCommand ------->  |  world model,       |
|  Robot framework |  <------- sensorSnapshot|  60 Hz timestep,    |
|                  |  (front + right readings)  |  actuator state     |
+------------------+                         +----------+----------+
                                                          |
                                                          | visualiser channel
                                                          | ws://localhost:8081 (NDJSON)
                                                          | worldSnapshot
                                                          v
                                                   +---------------------+
                                                   |  Visualiser         |
                                                   |  (vanilla TS+canvas)|
                                                   |  holds+interpolates |
                                                   |  worldSnapshot      |
                                                   +---------------------+
```

The simulation engine owns world state and advances on its own fixed ~60 Hz timestep. The robot (separate Java process) sends `motorCommand` messages and receives `sensorSnapshot` messages. The visualiser (browser) receives `worldSnapshot` snapshots and renders them with hold-last-frame interpolation. Rendering never drives or blocks simulation timing.

## Monorepo layout

```
oobot/
├── simulator/
│   ├── server/          # Node.js/TypeScript sim + WebSocket server (ports 8080/8081)
│   └── visualiser/      # vanilla TypeScript + HTML5 canvas browser app (vite)
├── robot-framework/     # Java framework + example controllers (fat JAR, JDK 21)
│   └── src/main/java/oobot/robot/
│       ├── SquareTraceBehaviour.java  (default example)
│       ├── CircleTraceBehaviour.java
│       ├── HeartTraceBehaviour.java
│       ├── WallAvoidBehaviour.java
│       └── WallFollowRightBehaviour.java
├── specs/               # WIRE_PROTOCOL.md, COMMS.md, COMPONENTS.md, ROBOT_LAUNCH.md, SENSORS.md, WORLD_FORMAT.md
├── ARCHITECTURE.md      # high-level architecture and component boundaries
├── MVP.md               # scope, in/out of scope, success criteria
├── DESIGN_REVIEW.md     # resolved open questions and decisions
├── FUTURE.md            # post-MVP ideas
├── PLAN.md              # implementation plan of attack
├── README.md            # this file
└── package.json         # root dev scripts (npm run dev)
```

## Getting started

### One-command dev startup

From the repo root:

```bash
npm install        # install root dev dependency (concurrently) — once
npm run dev        # starts sim server + visualiser dev server + example robot
```

This launches, in parallel:

- Sim server on `ws://localhost:8080` (robot channel) and `ws://localhost:8081` (visualiser channel)
- Visualiser dev server (opens `http://localhost:5173` by default)
- Example robot controller (`java -jar robot.jar --host localhost --port 8080 --behaviour wall-follow-right`), running the wall-follow-right behaviour (use `--behaviour square` for the square trace)

Open the visualiser URL in your browser to see the robot move.

### Starting components individually

| Component | Command | Directory |
|-----------|---------|-----------|
| Sim server | `npm run dev` | `simulator/server/` |
| Visualiser | `npm run dev` | `simulator/visualiser/` |
| Robot | `java -jar robot.jar --host localhost --port 8080` | `robot-framework/` |

Requirements: Node.js ≥ 20, npm ≥ 10, JDK 21+.

## MVP success criteria

The MVP is successful when:

1. The simulation can run independently of the browser visualiser.
2. A separate Java process can connect to the simulation.
3. Java motor commands cause the simulated robot to move.
4. The simulation provides `sensorSnapshot` messages to the Java controller (empty payload in iteration 1 — no readings yet).
5. The Java controller can exercise the sensor path (`Sensors.latestSimTick()`) on each behaviour update.
6. The visualiser shows the robot's behaviour without controlling the simulation's timing.
7. A controller that stops or disconnects cannot leave the robot moving indefinitely.

## Docs & specs

- `MVP.md` — scope, in/out of scope, success criteria
- `ARCHITECTURE.md` — high-level architecture and component boundaries
- `DESIGN_REVIEW.md` — resolved open questions and decisions
- `FUTURE.md` — post-MVP ideas
- `PLAN.md` — implementation plan of attack
- `specs/WIRE_PROTOCOL.md` — NDJSON envelope, message types, two WebSocket channels
- `specs/COMMS.md` — encapsulated transport interface
- `specs/COMPONENTS.md` — per-component responsibilities and interfaces
- `specs/ROBOT_LAUNCH.md` — how the Java controller is started
- `specs/SENSORS.md` — informal, pre-implementation notes on sensors (not an MVP spec)
- `specs/WORLD_FORMAT.md` — world model (iteration 1: bounded box, no obstacles)

## License

UNLICENSED
