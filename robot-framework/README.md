# robot-framework

Java robot framework and example controller.

## Responsibilities

- Runs as a separate Java process (fat JAR `robot.jar`)
- Provides the student-facing hardware API (Mover, Sensors, Wheel, Brain)
- Hides WebSocket connection, NDJSON framing, reconnection, and concurrency from robot code
- Invokes a behaviour update method (~20 Hz)
- Example behaviours: square trace, wall avoidance, wall-follow-right, circle, heart, brownian

See `specs/WIRE_PROTOCOL.md` for the wire contract.

## Build tool

Gradle (Kotlin DSL) with the following dependencies, all embedded in the fat JAR:

| Library | Purpose |
|---------|---------|
| Jackson (`jackson-databind`) | JSON serialisation / deserialisation with typed records |
| OkHttp (`okhttp`) | RFC 6455 WebSocket client |
| SLF4J + Logback | Structured logging |
| picocli | Command-line parsing |

Java 21+ is required (Gradle wrapper downloads Gradle 8.10.2 automatically).

## Build

From `robot-framework/`:

```bash
./gradlew fatJar
```

This produces `robot-framework/robot.jar` — a self-contained fat JAR with all
dependencies embedded. No extra classpath flags needed.

From the repo root:

```bash
npm run build:robot
```

Other useful Gradle tasks:

```bash
./gradlew classes    # compile only
./gradlew run        # run with Gradle (pass args after --)
./gradlew clean      # remove build outputs
```

## Run

```bash
java -jar robot.jar --host localhost --port 8080 --behaviour wall-follow-right
```

Options (all have defaults):

| Flag | Default | Description |
|------|---------|-------------|
| `--host` | `localhost` | Sim server host |
| `--port` | `8080` | Sim robot-channel port |
| `--behaviour` | `brownian` | `square`, `wall-avoid`, `wall-follow-right`, `circle`, `heart`, `brownian` |
| `--help` | — | Show usage |
| `--version` | — | Show version |

Invalid arguments exit with code 2 and print usage.

## Logging

Structured logging via SLF4J + Logback. Default level is INFO; per-tick
messages are DEBUG. To see verbose output:

```bash
LOG_LEVEL=DEBUG java -jar robot.jar
```

Or via system property:

```bash
java -Dlogback.configurationFile=logback-debug.xml -jar robot.jar
```

## Public API (student-facing)

```java
interface RobotBehaviour {
    void init();   // called once on start
    void update(); // called ~20 Hz
}

interface Mover {
    void forward();   // move forward at full speed
    void backward();
    void turnRight(); // turn right in place
    void turnLeft();
    void stop();
}

interface Wheel {
    void clockwise();
    void antiClockwise();
    void stop();
}

interface Sensors {
    long latestSimTick();
    double distance();        // front proximity, Infinity if nothing detected
    double rightDistance();   // right proximity, Infinity if nothing detected
}

interface Brain {
    Mover mover();
    Sensors sensors();
    void setBehaviour(RobotBehaviour behaviour);
    void tick();
}
```

Robot code implements `RobotBehaviour` and commands movement via `Mover`
(e.g. `forward()`, `turnRight()`). `Mover` implementations translate those
commands into per-wheel actions. A `Brain` ties them together; the behaviour
loop (`oobot.robot.core.BehaviourLoop`) calls `Brain.tick()` at ~20 Hz.

## Wire protocol

Envelopes are typed records in `oobot.robot.transport`:

```java
sealed interface WireMessage permits MotorCommandMessage, SensorSnapshotMessage
record MotorCommandMessage(long seq, long simTick, MotorPayload payload)
record SensorSnapshotMessage(long seq, long simTick, SensorPayload payload)
record MotorPayload(double left, double right)         // [-1, 1]
record SensorPayload(Double front, Double right)       // null = nothing detected
```

Jackson uses the `type` property for polymorphic deserialisation; the on-wire
format is unchanged: `{"type":"motorCommand","seq":7,"simTick":142,"payload":{"left":1.0,"right":0.5}}`

## Framework behaviour notes

- WebSocket reconnects automatically with a fixed 2000ms delay when the socket closes.
- On WebSocket close: motors are safe-stopped (zero speed sent before reconnect).
- Malformed JSON is logged and ignored (never crashes).
- Behaviour exceptions are caught and logged — a misbehaving behaviour never kills the loop.
- No heartbeat/timeout (deferred beyond MVP); safe-stop relies on WebSocket close.
