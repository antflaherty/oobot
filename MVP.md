# MVP

## Purpose

Build the smallest working version of Object Oriented Robotics that
demonstrates the core idea: a Java program controls a robot in a
continuously running simulation, and the robot's behaviour is visible in
a separate visualiser.

The MVP is a technical proof of concept, not a complete learning
product.

## MVP experience

A user starts the application and runs a simple, trusted Java robot
program. The program controls the robot's hardware through a small
framework API. The robot moves around a basic 2D world, and the
visualiser displays its movement.

The initial demonstration should show a robot moving around a bounded
2D box, and stopping safely when its controller stops or disconnects.

## In scope

-   A simple bounded 2D box world containing a single robot, with no
    obstacles.
-   Basic robot movement and stop-dead collision handling at box edges.
-   A small Java hardware API, initially focused on motors (differential
    drive via the `Mover` abstraction) plus two proximity sensors
    (`Sensors.distance()` front, `Sensors.rightDistance()` right).
-   A Java robot controller running as a separate process.
-   Communication between the controller and the simulation.
-   A simulation that advances independently of the visualiser.
-   A browser-based visualiser that displays the world in near real
    time.
-   Simple example behaviours (square trace without sensor feedback;
    wall-follow-right using the two proximity sensors).
-   Local execution of trusted example code only.

## Out of scope

-   In-browser code editor or code submission experience.
-   User accounts, persistence, multiplayer or cloud hosting.
-   Public execution of arbitrary user code or production-grade
    sandboxing.
-   Robot construction or custom hardware configuration UI.
-   Multiple robots.
-   Realistic physics, detailed mechanical modelling or advanced
    sensors.
-   A challenge catalogue, progression system, scoring or educational
    content.
-   Production-grade observability, scaling or deployment
    infrastructure.

## MVP success criteria

The MVP is successful when:

1.  The simulation can run independently of the browser visualiser.
2.  A separate Java process can connect to the simulation.
3.  Java motor commands cause the simulated robot to move.
4.  The simulation provides `sensorSnapshot` messages to the Java
    controller carrying `front` and `right` proximity readings (a reading
    is `null` when nothing is detected in that direction).
5.  The Java controller can exercise the sensor path
    (`Sensors.distance()` / `Sensors.rightDistance()` and
    `Sensors.latestSimTick()`) on each behaviour update.
6.  The visualiser shows the robot's behaviour without controlling the
    simulation's timing.
7.  A controller that stops or disconnects cannot leave the robot moving
    indefinitely.

The emphasis is on proving the end-to-end interaction between Java code,
simulated hardware and a visible world. Keep the implementation
deliberately small; defer refinements until this loop works reliably.
