# MVP

## Purpose

Build a small working version of Object Oriented Robotics that demonstrates
the core idea: a Java program controls a robot in a continuously running
simulation, and the robot's behaviour is visible in a separate visualiser.

The MVP is a technical proof of concept, not a complete learning product.

## MVP experience

A user starts the application and runs one of several trusted Java example
behaviours. The behaviour controls the robot through a small framework API: it
reads two proximity sensors and issues high-level movement commands. The robot
moves around a bounded 2D maze, the visualiser shows the robot, its trail and
the obstacles, and the robot stops safely when its controller stops or
disconnects. The component layout, world and behaviour are selectable on the
command line.

## In scope

-   A bounded 2D box world with static axis-aligned obstacles loaded from a JSON
    world file (`simulator/server/worlds/maze.json` by default).
-   Differential-drive movement with manifest-derived wheel geometry; stop-dead
    collision response against obstacles and box edges.
-   A small Java hardware API: id-addressed `Component`s, `Wheel`, two
    `ProximitySensor` handles, `Mover` and a `Brain` that wires behaviour code
    to them. `front-sensor` and `right-sensor` are fixed, documented accessor
    ids.
-   A shared v2 component manifest (`robots/default.json`) loaded by both the
    sim and the controller; mount angles and offsets are data, not code.
-   A Java robot controller running as a separate process, with CLI validation
    and usage-style errors for bad configuration.
-   Two WebSocket channels from one sim process: bidirectional robot channel
    (`componentCommand` / `componentSnapshot`) and unidirectional visualiser
    channel (`worldSnapshot` with robot pose and obstacles).
-   A simulation that advances independently of the visualiser, with best-effort
    timing and achieved-Hz logging.
-   A browser visualiser with hold-last-frame interpolation, a fallback demo,
    obstacle rendering and a trail; state resets on reconnect.
-   Six example behaviours: `square`, `circle`, `heart`, `wall-avoid`,
    `wall-follow-right` (default) and `brownian`.
-   Safe stop from both sides: sim zeroes motors on active-robot close/shutdown,
    Java writes an explicit zero command before closing or reconnecting.
-   Local execution of trusted example code only.

## Out of scope

-   In-browser code editor or code submission experience.
-   User accounts, persistence, multiplayer or cloud hosting.
-   Public execution of arbitrary user code or production-grade sandboxing.
-   Robot construction or custom hardware configuration UI.
-   Multiple robots, robot IDs in the protocol or robot-vs-robot collisions.
-   Moving obstacles, sliding/bouncing collision response or realistic physics.
-   A generic `Brain` sensor lookup: only the two fixed ids are addressable in
    this pass.
-   A heartbeat/liveness timeout for a hung-but-connected robot process.
-   A challenge catalogue, progression system, scoring or educational content.
-   Production-grade observability, scaling or deployment infrastructure.

## MVP success criteria

1.  The simulation runs independently of the browser visualiser.
2.  A separate Java process connects to the simulation over the robot channel.
3.  Java movement commands cause the simulated robot to move; turn rate is
    proportional to wheel-power differential (full differential ≈4 rad/s with
    the default manifest).
4.  The sim publishes `componentSnapshot` messages with one reading per declared
    proximity sensor (nearest hit among box edges and obstacles, or `null`);
    Java exposes them as `ProximitySensor` handles with `distance()` and
    `latestSimTick()`.
5.  Behaviours use those handles and the `Mover` API on every update, without
    touching sockets, JSON or motor power values.
6.  The visualiser shows the robot, obstacles and movement without controlling
    simulation timing, and recovers cleanly when the sim restarts.
7.  A controller that stops or disconnects cannot leave the robot moving
    indefinitely: both safe-stop layers work independently.
8.  Duplicate, invalid or stale robot commands are ignored, and a second robot
    cannot drive the active one.
9.  A missing or invalid manifest, world or required sensor id produces a clear
    startup error rather than a stack trace or a silent misconfiguration.

The emphasis is on proving the end-to-end interaction between Java code,
simulated hardware and a visible world. Keep the implementation deliberately
small; defer refinements until this loop works reliably.
