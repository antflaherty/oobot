# Future directions

Ideas for expanding oobot beyond the current implementation. No prioritisation
here — just a place to keep them so they aren't lost. Items already implemented
are listed at the end, not here.

## Sensors & perception

-   Sensor types beyond the two manifest-declared proximity rays (scanning or
    left/right arrays), with a generic `Brain` lookup instead of the fixed
    `front-sensor` / `right-sensor` accessors.
-   Behaviours that actively refuse readings older than N sim ticks. The
    `latestSimTick()` value and a documented 30-tick max-staleness policy exist;
    the example behaviours do not yet apply it.
-   Confidence/noise models or filtering for ray readings.

## World & obstacles

-   Moving or destructible obstacles.
-   More refined collision response (slide along surface, bounce).
-   More obstacle shapes than axis-aligned boxes.

## Multi-robot

-   Support multiple robots in the same world.
-   Add `robotId` to the wire protocol envelope (the robot slot is currently
    single-occupancy and implicit from the channel).
-   Robot-vs-robot collision handling.

## Controller lifecycle & safety

-   Heartbeat/liveness detection: if the robot process hangs but the socket
    stays open, the sim stops the motors after a timeout.
-   Safe-stop policy for GC pauses and frozen threads that keep a socket open.
-   A richer health/status channel for controllers and visualisers.

## Sandboxing & untrusted code

-   OS-level sandboxing for student code (containers, seccomp, etc.).
-   Student code submission and execution
    (currently out of scope: "trusted example code only").
-   In-browser code editor.

## Communication & protocol

-   Binary framing if JSON/NDJSON parsing proves to be a bottleneck at higher
    frequencies.
-   Language-independent protocol validation (IDL/schema) if a second sim
    implementation is ever built.
-   Physical hardware support: swap the WebSocket bridge for a real robot's
    hardware interface without changing student code.

## Observability

-   Structured logging (JSON logs).
-   Metrics beyond achieved sim Hz: behaviour loop latency, message drop rates,
    connection uptime.
-   Debugging hooks: step-through, pause, inspect robot state.

## Visualiser

-   React for UI controls (start, pause, reset, speed control).
-   Extrapolation for late snapshots.
-   Camera controls (zoom, pan).

## Simulation

-   Adaptive simulation under load and a runtime-configurable timestep.
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
-   Public execution of arbitrary user code with production-grade sandboxing.

## Done (previously listed as future)

These ideas were on earlier future lists but are implemented now:

-   Two proximity sensors and sensor-driven wall-following (`wall-avoid` and
    `wall-follow-right`), with mount angles declared in the shared manifest.
-   Static obstacles and a JSON world file (`simulator/server/worlds/maze.json`,
    selectable with `--world`).
-   Robot reconnection and resume after a socket close, with safe stop on both
    sides and sensor state reset on a fresh session.
-   Multiple simultaneous visualisers with independent per-connection sequence
    numbers.
-   Achieved-Hz and event-counter metrics in the sim's periodic log line and
    `getState()`.
-   Manifest-driven id-addressed components shared by the sim and the Java
    controller, with validation parity across both languages.
-   Batched wheel commands: one `componentCommand` frame per behaviour tick.
