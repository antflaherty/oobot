# robot-framework

Java robot framework and example controller.

## Responsibilities

- Runs as a separate Java process (fat JAR `robot.jar`)
- Provides the student-facing hardware API (Mover, Component, Wheel, ProximitySensor, Brain)
- Hides WebSocket connection, NDJSON framing, reconnection, and concurrency from robot code
- Invokes a behaviour update method (~20 Hz)
- Example behaviours: square trace, wall avoidance, wall-follow-right, circle, heart, brownian

See `specs/WIRE_PROTOCOL.md` for the wire contract and
`specs/ROBOT_DESCRIPTION.md` for the manifest schema.

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
java -jar robot.jar --host localhost --port 8080 --behaviour wall-follow-right --robot ../robots/default.json
```

Options (all have defaults):

| Flag | Default | Description |
|------|---------|-------------|
| `--host` | `localhost` | Sim server host |
| `--port` | `8080` | Sim robot-channel port |
| `--behaviour` | `brownian` | `square`, `wall-avoid`, `wall-follow-right`, `circle`, `heart`, `brownian` |
| `--robot` | `robots/default.json` | Path to a component manifest; `npm run dev` passes an absolute path |
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

Robot hardware is modelled as generic, id-addressed components described by the
shared manifest in `robots/` (default: `robots/default.json`). The Java model of
that manifest lives in the `oobot.robot.description` package; the TypeScript
mirror is `simulator/server/src/robotDescription.ts`. The Java robot and sim
server load the manifest at startup; the visualiser does not read it.

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
    long latestSimTick();  // tick of the most recent reading
    double distance();     // Infinity if nothing detected
}

interface Mover {
    void forward();        // move forward at full speed
    void backward();
    void turnRight();
    void turnLeft();
    void stop();
}

interface RobotBehaviour {
    void init();   // called once on start
    void update(); // called ~20 Hz
}

interface Brain {          // student writes DI/wiring implementation
    Mover mover();
    ProximitySensor frontSensor();
    ProximitySensor rightSensor();
    void setBehaviour(RobotBehaviour behaviour);
    void tick();
}
```

Robot code implements `RobotBehaviour`, uses the `Mover` and sensors exposed by
its `Brain` and commands movement via `Wheel`s or the `Mover` abstraction. The
behaviour loop (`oobot.robot.core.BehaviourLoop`) calls `Brain.tick()` at ~20 Hz.
The reference `WheeledMover` is constructed from the full wheel list
(`WheeledMover(List<Wheel>)`) and derives left/right roles from each wheel's
`angle()`; `RobotController` obtains that list from `ComponentRegistry.wheels()`
(bound wheels sorted by ascending mount angle).

### Mount-angle convention

Components are placed by **mount angle** plus a radial **offset**, declared in
the v2 manifest `robots/default.json`. `angle` is in radians: 0 = robot forward,
positive = counter-clockwise (right = -π/2). `offset` is the radial distance
from the robot centre (wheels only; default `0.25`, must be finite and > 0).
Wheels still roll along the robot's heading (tangentially); sensors cast rays in
their mounted direction. Only the Java robot and sim server read the manifest,
so adding or repositioning a component is a data change, not a code change;
visualiser changes are deferred.

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

## Wire protocol

Envelopes are typed records in `oobot.robot.transport`:

```java
sealed interface WireMessage permits ComponentCommandMessage, ComponentSnapshotMessage
record ComponentCommandMessage(long seq, long simTick, ComponentCommandPayload payload)
record ComponentSnapshotMessage(long seq, long simTick, ComponentSnapshotPayload payload)
record ComponentCommandPayload(List<ComponentValue> commands)
record ComponentSnapshotPayload(List<ComponentReading> readings)
record ComponentValue(String id, double value)     // wheel value ∈ [-1, 1]
record ComponentReading(String id, Double value)   // null = nothing detected
```

Jackson uses the `type` property for polymorphic deserialisation. NDJSON framing
and the `{ type, seq, simTick, payload }` envelope are unchanged; messages are
now id-addressed:

```json
{"type":"componentCommand","seq":7,"simTick":142,"payload":{"commands":[{"id":"left-wheel","value":1.0},{"id":"right-wheel","value":-1.0}]}}
{"type":"componentSnapshot","seq":7,"simTick":142,"payload":{"readings":[{"id":"front-sensor","value":3.5},{"id":"right-sensor","value":null}]}}
```

| Message | Direction | Payload |
|---|---|---|
| `componentCommand` | robot → sim | `commands: [{ id, value }]` |
| `componentSnapshot` | sim → robot | `readings: [{ id, value }]` |

`motorCommand` and `sensorSnapshot` are removed.

## Framework behaviour notes

- WebSocket reconnects automatically with a fixed 2000ms delay when the socket closes.
- On WebSocket close: motors are safe-stopped (zero speed sent before reconnect).
- Malformed JSON is logged and ignored (never crashes).
- Behaviour exceptions are caught and logged — a misbehaving behaviour never kills the loop.
- No heartbeat/timeout (deferred beyond MVP); safe-stop relies on WebSocket close.
