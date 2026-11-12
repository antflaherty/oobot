# Future Directions

Ideas for expanding oobot beyond the MVP. No prioritisation here — just a
place to keep them so they aren't lost.

## Sensors & perception

-   Add one or more distance sensors (left/right pair, or a scanning
    sensor) so the robot can do wall-following, corner detection, and
    more interesting obstacle avoidance.
-   Sensor staleness checks: the robot can refuse to act on readings
    older than N sim ticks.

## World & obstacles

-   Static obstacles inside the bounded box.
-   A JSON world file format so obstacle coordinates can be configured
    without changing sim code.
-   Moving obstacles.
-   More refined collision response (slide along surface, bounce).

## Multi-robot

-   Support multiple robots in the same world.
-   Add `robotId` to the wire protocol envelope.
-   Robot-vs-robot collision handling.

## Controller lifecycle & safety

-   Heartbeat/liveness detection: if the robot process hangs but the
    socket stays open, the sim stops the motors after a timeout.
-   Reconnection: robot can reconnect and resume without restarting the
    sim.
-   Safe-stop on GC pauses, frozen threads, and network blips.

## Sandboxing & untrusted code

-   OS-level sandboxing for student code (containers, seccomp, etc.).
-   Student code submission and execution (currently out of scope:
    "trusted example code only").
-   In-browser code editor.

## Communication & protocol

-   Binary framing if JSON/NDJSON parsing proves to be a bottleneck at
    higher frequencies.
-   Language-independent protocol validation (IDL, schema) if a second
    sim implementation is ever built.
-   Physical hardware support: swap the WebSocket transport for a real
    robot's hardware interface without changing robot code.

## Observability

-   Structured logging (JSON logs).
-   Metrics: achieved sim Hz, behaviour loop latency, message drop
    rates, connection uptime.
-   Debugging hooks: step-through, pause, inspect robot state.

## Visualiser

-   React for UI controls (start, pause, reset, speed control).
-   Extrapolation for late snapshots.
-   Multiple simultaneous visualisers.
-   Camera controls (zoom, pan).

## Simulation

-   Adaptive simulation under load.
-   Configurable timestep at runtime.
-   Realistic physics (deferred: "prefer understandable behaviour over
    realistic physics").

## Educational product features

-   Challenge catalogue.
-   Progression system.
-   Scoring.
-   Educational content (lessons, tutorials).

## Production concerns

-   Scaling, deployment, cloud hosting.
-   Multiplayer.
-   User accounts, persistence.
-   Public execution of arbitrary user code with production-grade
    sandboxing.