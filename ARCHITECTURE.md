# Architectural Overview

## Purpose

This document describes the high-level architecture for the Object
Oriented Robotics MVP. It establishes the main responsibilities and
boundaries of the system, reflecting decisions captured in
`DESIGN_REVIEW.md`.

The system is organised as a small monorepo containing the simulation
engine, visualiser, Java robot framework and example controller.

## High-level components

### Simulation engine

The simulation engine is the authoritative owner of the simulated world.

It is responsible for:

-   Maintaining world and robot state.
-   Advancing the simulation using a fixed timestep (target ~60 Hz).
-   Applying actuator commands, such as motor speeds.
-   Calculating movement and basic collisions.
-   Calculating sensor readings.
-   Publishing world-state snapshots to connected visualisers.

The simulation must continue independently of rendering and must not
block while waiting for a robot controller or a visualiser.

#### World model (MVP iteration 1)

The world is a bounded 2D box with no obstacles. The robot is a point
that moves within the box. Collision response is "stop dead" —
movement that would penetrate a boundary is clamped so the robot stops
at the edge. Obstacles and a world file format are deferred to
iteration 2.

#### Timestep and speed control

The sim runs on its own fixed timestep and is not driven by the
visualiser. The sim speed can be controlled independently of the
visualiser frame rate. Under load the sim degrades gracefully — it runs
at best effort and never blocks. Actual achieved Hz is logged.

### Robot controller

Each robot controller runs as a separate Java process.

It is responsible for:

-   Executing the robot's behaviour.
-   Reading the latest available sensor state through the framework API.
-   Issuing commands through simulated hardware components.

The controller does not directly modify world state, position or
collisions. It interacts with the world only through the hardware
abstractions exposed by the Java framework.

For the MVP, the framework manages communication and repeatedly invokes
a simple behaviour update method. Concurrency and networking details
remain hidden from example robot code.

The first example behaviour is a square-tracing robot: move forward for
one behaviour state (~1 second at 20 Hz), turn for one behaviour state,
repeat. Sensors are deferred to iteration 2.

#### Launch and configuration

The robot process is started with command-line arguments, with sensible
defaults:

```
java -jar robot.jar --host localhost --port ROBOT_PORT
```

### Java robot framework

The framework provides the student-facing abstractions for interacting
with simulated hardware.

Initially this is limited to motors. A distance sensor is planned for
iteration 2 but is not part of the first iteration. The framework owns
the concrete implementations and communication details; robot code uses
the public interfaces.

The framework should remain independent of the simulation engine's
implementation language as a guiding principle, not a hard constraint.
For the MVP the Java framework talks JSON over WebSocket directly — no
abstraction layer or IDL is built yet. The communication layer is
encapsulated behind an interface so it can be swapped for a different
transport (e.g. physical hardware) later without touching robot code.

### Visualiser

The visualiser is a separate browser application responsible for
presenting the simulated world.

It is responsible for:

-   Drawing the world, robots and obstacles.
-   Displaying the latest simulation state.
-   Holding the last received snapshot and interpolating to the next
    available one on each render tick.
-   Providing basic controls such as start, pause and reset when
    required.

The visualiser is a client of the simulation, not the owner of world
state or simulation timing. Rendering delays or browser disconnection
must not determine how the simulation advances.

The MVP visualiser uses vanilla TypeScript and a browser canvas. React
is not used; it can be added later if UI controls benefit from a
component model.

## Communication boundaries

The MVP uses two WebSocket channels from a single sim server process:

-   **Robot controller ↔ simulation** (robot channel, `ws://localhost:ROBOT_PORT`):
    persistent, bidirectional connection. The controller sends
    `motorCommand` messages; the simulation sends `sensorSnapshot`
    messages. Distinguished from the visualiser channel by port, not by
    a message field.
-   **Visualiser ↔ simulation** (visualiser channel,
    `ws://localhost:VISUALISER_PORT`): primarily unidirectional connection
    through which the visualiser receives `worldSnapshot` messages and
    can eventually send `userControl` messages.

Both WebSocket servers bind to `host: "0.0.0.0"` so that IPv4-only
clients (such as Java's `Socket.connect("localhost")`, which may not
fall back from `::1`) can connect alongside dual-stack clients. Binding
to `localhost` on macOS results in IPv6-only listening, which breaks
IPv4-only clients. Origin is additionally validated on upgrade: only
`localhost` origins (or no Origin header, for non-browser clients such
as the Java robot framework) are accepted.

### Message format

All messages use newline-delimited JSON (NDJSON). Each message carries
the envelope:

``` json
{ "type": "...", "seq": 0, "simTick": 0, "payload": { ... } }
```

-   `type` identifies the message kind (e.g. `motorCommand`,
    `sensorSnapshot`, `worldSnapshot`).
-   `seq` is a per-connection sequence number for deduplication and
    ordering.
-   `simTick` is the simulation logical tick number, used as the source
    of truth for sequencing and staleness. Wall-clock timestamps may
    accompany messages for debugging only; staleness is never judged by
    wall-clock drift across processes.
-   `payload` carries the message-specific data.

### Sensor model

The sim maintains the latest sensor value and its `simTick` in a single
slot, overwriting each tick. There is no queue and no polling. The
robot reads the latest available value on its behaviour loop (~20 Hz).
If the robot's loop is slower than the sim, it simply sees the most
recent reading; older samples are silently dropped by being overwritten.

For iteration 1 there are no sensors, so `sensorSnapshot` is part of the
protocol but carries no meaningful readings yet.

### Actuator model

Motors use persistent actuator state: they keep running at the last
commanded speed until a new command overrides it. The sim never waits
for the robot — if the robot takes longer than one behaviour tick to
respond, the sim uses the previous command.

## Timing and execution model

The simulation uses its own fixed timestep and authoritative simulation
clock. It should not wait for controller updates or rendering frames.

Initial target frequencies:

-   Simulation: approximately 60 updates per second.
-   Robot behaviour: approximately 20 updates per second.
-   Visualiser: approximately 60 frames per second, subject to browser
    performance.

These are starting points rather than strict requirements. The loops are
independently scheduled and coordinated through state updates, timestamps
and persistent actuator state — not by waiting for one another on every
tick.

If a controller disconnects (WebSocket close), the simulation
transitions its actuators to a safe state, such as stopping the motors.
A heartbeat/timeout for detecting a hung-but-connected process is
deferred beyond the MVP.

## Initial technology direction

-   **Visualiser:** vanilla TypeScript and a browser canvas.
-   **Simulation engine:** Node.js and TypeScript.
-   **Robot framework and controller:** Java.
-   **Controller communication:** local WebSocket (robot channel).
-   **Visualiser communication:** local WebSocket (visualiser channel).
-   **Message format:** newline-delimited JSON.

These are pragmatic MVP choices, not permanent constraints. The
encapsulated communication boundary should allow the simulation
implementation or transport to be replaced without changing the
student-facing Java API.

## Monorepo outline

```
oobot/
├── simulator/
│   ├── server/          # Node.js/TypeScript sim + WebSocket server (ports 8080/8081)
│   └── visualiser/      # vanilla TypeScript + HTML5 canvas browser app (vite)
├── robot-framework/     # Java framework + example controllers (fat JAR, JDK 21)
│   └── src/main/java/oobot/robot/
│       ├── SquareTraceBehaviour.java  (default example behaviour)
│       ├── CircleTraceBehaviour.java
│       └── HeartTraceBehaviour.java
├── specs/               # WIRE_PROTOCOL.md, COMMS.md, COMPONENTS.md, ROBOT_LAUNCH.md, SENSORS.md, WORLD_FORMAT.md
├── ARCHITECTURE.md      # high-level architecture and component boundaries (this file)
├── MVP.md               # scope, in/out of scope, success criteria
├── DESIGN_REVIEW.md     # resolved open questions and decisions
├── FUTURE.md            # post-MVP ideas
├── PLAN.md              # implementation plan of attack
├── README.md            # this file
└── package.json         # root dev scripts (npm run dev)
```

## Guiding principles

-   Keep the MVP small and prove the end-to-end control loop early.
-   The simulation owns world state; robot code controls hardware.
-   Rendering is a client of the simulation and is fully decoupled from
    simulation timing.
-   Keep networking and concurrency out of the normal student
    programming experience.
-   Keep interfaces and protocols simple, explicit and
    language-independent.
-   Prefer understandable behaviour over realistic physics.
-   Defer scalability, security hardening and production concerns until
    the product requires them.