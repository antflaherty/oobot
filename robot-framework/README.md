# robot-framework

Java robot framework and example controller.

Responsibilities (MVP):
- Runs as a separate Java process (fat JAR `robot.jar`)
- Provides the student-facing hardware API (initially Motors)
- Hides WebSocket connection, NDJSON framing, reconnection, and concurrency from robot code
- Invokes a behaviour update method (~20 Hz)
- Example behaviour: square-trace robot (forward one tick, turn one tick)

See `specs/WIRE_PROTOCOL.md` for the wire contract.

## Build tool

Plain `javac` / `jar` from the JDK (no Maven/Gradle required; zero external
dependencies — the WebSocket client and JSON code are hand-rolled from JDK
APIs so `java -jar robot.jar` works with no flags).

Java 21+ is required.

## Build

From `robot-framework/`:

```
mkdir -p build/classes
javac -d build/classes $(find src/main/java -name '*.java')
jar cfe robot.jar oobot.robot.RobotController -C build/classes .
```

This produces a self-contained fat JAR at `robot-framework/robot.jar`.

## Run

```
java -jar robot.jar --host localhost --port <ROBOT_PORT>
```

Example (if the sim robot channel is on port 8080):

```
java -jar robot.jar --host localhost --port 8080
```

Defaults: `--host localhost --port 8080`.

## Public API (student-facing)

```java
interface RobotBehaviour {
    void init();   // called once on start
    void update(); // called ~20 Hz
}

class Motor {
    static void setSpeed(double speed); // [-1, 1], sends motorCommand
}

class Sensors {
    static long latestSimTick(); // iteration 1: no readings, just the tick
}
```

Robot code implements `RobotBehaviour` and calls `Motor.setSpeed(...)`. The
example behaviour (`SquareTraceBehaviour`) drives the square trace.

## Framework behaviour notes (iteration 1)

- `Motor.setSpeed(double)` drives both motors at the same speed
  (wire payload `{left: speed, right: speed}`). A true in-place turn needs
  per-motor control, which is deferred beyond iteration 1; the example's TURN
  state stops for one tick.
- `sensorSnapshot` payload is empty in iteration 1; `Sensors.latestSimTick()`
  exposes the last tick for staleness checks later.
- Reconnects automatically with a fixed delay when the WebSocket closes
  (hidden from robot code).
- On WebSocket close: best-effort zero-speed command is sent; the sim performs
  the actual motor stopping. The framework does not crash.
- No heartbeat/timeout (deferred beyond MVP); safe-stop relies on WebSocket close.
