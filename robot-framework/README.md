# robot-framework

Java robot framework and example controller.

## Responsibilities

- Runs as a separate Java process (fat JAR `robot.jar`)
- Provides the student-facing hardware API (Mover, Component, Wheel, Sensor<T>, Tool, Brain)
- Hides WebSocket connection, NDJSON framing, reconnection, and concurrency from robot code
- Invokes a behaviour update method (~20 Hz)
- Behaviours: wall-follow-right (default), square, circle, heart, wall-avoid, brownian
- The brain owns goal arrival: stop the robot and light the tool for good

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
dependencies embedded. No extra classpath flags needed. It is a local build
output (gitignored, not committed).

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
| `--behaviour` | `wall-follow-right` | `square`, `circle`, `heart`, `wall-avoid`, `brownian` |
| `--robot` | `../robots/default.json` (resolved against the JAR's parent, i.e. `robot-framework/`) | Path to a component manifest; `npm run dev` / `npm run dev:robot` pass an absolute path |
| `--help` | — | Show usage |
| `--version` | — | Show version |

Invalid arguments exit with code 2 and print usage. The default behaviour is
`wall-follow-right`; `--behaviour brownian` selects the `BrownianMotionBehaviour`
from the student package. Whatever behaviour is selected, `DefaultBrain` stops
the robot and lights the tool when the goal sensor reports arrival.
From the repo root,
`npm run dev:robot` starts the JAR with an absolute `--robot` path so it works
from any CWD.

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
    double angle();           // mount angle (rad): 0 = forward, + = CCW; left if sin > 0.9, right if sin < -0.9
    void clockwise();
    void antiClockwise();
    void stop();
}

interface Sensor<T> extends Component {
    long MAX_STALE_TICKS = 30L;   // readings older than this are stale
    long latestSimTick();        // 0 before the first reading / after reset
    T value();                   // never null: Infinity for Double, false for Boolean
}

interface Tool extends Component {
    void activate(); void deactivate();
    Tool NOOP = ...;       // no-op handle when no tool is declared
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
    Sensor<Double> frontSensor();
    Sensor<Double> rightSensor();
    Sensor<Boolean> goalSensor();
    Tool tool();           // single manifest tool, or Tool.NOOP
    void setBehaviour(RobotBehaviour behaviour);
    void tick();
}
```

**Fixed sensor ids (D-3).** The component model is id-addressed, but the `Brain`
accessors are *not* generic: `frontSensor()` must resolve the manifest id
`front-sensor`, `rightSensor()` must resolve `right-sensor` and `goalSensor()`
must resolve `goal-sensor`. Startup fails with a usage-style error (exit code 2)
naming the missing id and manifest path if any is absent. Other sensors may be
declared in the manifest but cannot be addressed through this API.
`Sensor.latestSimTick()` lets behaviour code detect stale readings (the bridge
treats a reading older than `Sensor.MAX_STALE_TICKS` = 30 sim ticks as stale).

`tool()` is not a fixed-id lookup: the controller injects the one manifest
`tool` by type, or `Tool.NOOP` when none is declared. Declaring more than one
tool fails at startup (usage-style error, exit code 2). Tools have no geometry
or kinematics effect.

Robot code implements `RobotBehaviour`, uses the `Mover`, sensors and tool
exposed by its `Brain` and commands movement via `Wheel`s or the `Mover`
abstraction. The behaviour loop (`oobot.robot.core.BehaviourLoop`) calls
`Brain.tick()` at ~20 Hz, then flushes wheel and tool changes as one batched
`componentCommand` frame.
The reference `WheeledMover` is constructed from the full wheel list
(`WheeledMover(List<Wheel>)`) and derives left/right roles from each wheel's
`angle()`; `RobotController` obtains that list from `ComponentRegistry.wheels()`
(bound wheels sorted by ascending mount angle).

### Mount-angle convention

Components are placed by **mount angle** plus a radial **offset**, declared in
the v2 manifest `robots/default.json`. `angle` is in radians: 0 = robot forward,
positive = counter-clockwise (right = -π/2). `offset` is the radial distance
from the robot centre (wheels only; default `0.25`, must be finite and > 0);
tools and `goalSensor`s declare `angle` but no `offset`. Wheels still roll along
the robot's heading (tangentially); proximity sensors cast rays in their mounted
direction, while the goal sensor reports whether the robot centre is inside the
world's goal rectangle. Only the Java robot and sim server read the manifest, so
adding or repositioning a component is a data change, not a code change;
visualiser changes are deferred.

```json
{
  "schemaVersion": 2,
  "name": "default-two-wheel",
  "components": [
    { "id": "left-wheel",   "type": "wheel",           "angle":  1.5707963267948966, "offset": 0.25 },
    { "id": "right-wheel",  "type": "wheel",           "angle": -1.5707963267948966, "offset": 0.25 },
    { "id": "front-sensor", "type": "proximitySensor", "angle":  0.0 },
    { "id": "right-sensor", "type": "proximitySensor", "angle": -1.5707963267948966 },
    { "id": "front-light",  "type": "tool",            "angle":  0.0 },
    { "id": "goal-sensor",  "type": "goalSensor",      "angle":  0.0 }
  ]
}
```

- `schemaVersion` must be `2`; a `side` field is rejected and wheel roles are
  **derived** from angle instead: `sin(angle) > 0.9` → left, `sin(angle) < -0.9`
  → right (exactly one of each required; other angles rejected).
- A `tool` declares a finite `angle`, no `offset` and no geometry effect; the
  sim accepts any number of tools, but the controller injects only one.
- A `goalSensor` declares a finite `angle` and no `offset`; it reports whether
  the robot centre lies inside the optional goal area of the world file.
- Track width is derived from the manifest as `y_left - y_right` =
  `offset_left·sin(angle_left) − offset_right·sin(angle_right)` (`0.5` for the
  default manifest). The sim uses this in place of the default world config's
  `trackWidth`; sensing stays centre-based and collision uses the manifest-derived
  body/wheel footprint.

## Wire protocol

Envelopes are typed records in `oobot.robot.transport`:

```java
sealed interface WireMessage permits ComponentCommandMessage, ComponentSnapshotMessage
record ComponentCommandMessage(long seq, long simTick, ComponentCommandPayload payload)
record ComponentSnapshotMessage(long seq, long simTick, ComponentSnapshotPayload payload)
record ComponentCommandPayload(List<ComponentValue> commands)
record ComponentSnapshotPayload(List<ComponentReading> readings)
record ComponentValue(String id, double value)     // wheel ∈ [-1, 1]; tool exactly 0 or 1
record ComponentReading(String id, Double value)   // proximity null = no hit; goal 1/0/null
```

Jackson uses the `type` property for polymorphic deserialisation. NDJSON framing
and the `{ type, seq, simTick, payload }` envelope are unchanged; messages are
now id-addressed:

```json
{"type":"componentCommand","seq":7,"simTick":142,"payload":{"commands":[{"id":"left-wheel","value":1.0},{"id":"right-wheel","value":-1.0},{"id":"front-light","value":1.0}]}}
{"type":"componentSnapshot","seq":7,"simTick":142,"payload":{"readings":[{"id":"front-sensor","value":3.5},{"id":"right-sensor","value":null},{"id":"goal-sensor","value":0}]}}
```

| Message | Direction | Payload |
|---|---|---|
| `componentCommand` | robot → sim | `commands: [{ id, value }]` (wheels and tools) |
| `componentSnapshot` | sim → robot | `readings: [{ id, value }]` |

Wheel and tool values share `componentCommand`: tool `1.0` = active, `0.0` =
inactive; the sim ignores any other tool value. The sim publishes activity in
`worldSnapshot.robot.toolStates`. Goal readings use `1` = inside the goal,
`0` = outside and `null` = no goal in the world; commands targeting
`goal-sensor` are read-only and ignored. The sim also publishes the world goal
as `worldSnapshot.goal` (`null` when absent). `motorCommand` and `sensorSnapshot`
are removed.

## Framework behaviour notes

- WebSocket reconnects automatically with a fixed 2000 ms delay when the socket closes.
- **Two-layer safe stop (D-2).** The Java bridge writes one `componentCommand`
  with *every* bound wheel id at `0.0` before closing or reconnecting the socket;
  that final frame is wheels-only and the sim independently zeroes motors and
  clears tool states when the active robot socket closes. Each layer works on
  its own (e.g. if the Java process dies); Java force-resyncs its persistent tool
  state on the next connection.
- Wheel and tool changes are buffered locally and flushed as one
  `componentCommand` per behaviour tick; `seq` restarts at 0 on every new
  connection. Sensor readings are invalidated on close, reconnect and sim-tick
  rollback; `setBehaviour(null)` stops motion and deactivates the tool.
- `DefaultBrain` injects the single manifest tool (or `Tool.NOOP`) and owns goal
  arrival for every behaviour: a fresh goal reading latches "arrived", stopping
  the robot and lighting the front light (never on stale or missing data). The
  default `wall-follow-right` behaviour sets the front light active in
  `APPROACH`, `DRIVE` and `TURN_RIGHT_AT_END`, and inactive in `SEARCH`, `AVOID`,
  `RECOVER_BACKUP`, `RECOVER_TURN` and `init()`.
- Malformed JSON is logged and ignored (never crashes); serialisation failures
  throw rather than emitting an empty frame.
- Behaviour exceptions are caught and logged — a misbehaving behaviour never kills the loop.
- No heartbeat/timeout (deferred beyond MVP).

## Tests

```bash
./gradlew test
```

JUnit 5 unit tests cover JSON round-trips, manifest validation against
byte-identical fixtures in `src/test/resources/fixtures/` (including the
`goalSensor` type), the connection close/safe-stop contract (fake WebSocket),
tool injection and light state, goal-sensor readings, the brain's goal-arrival
latch, batching and reconnect state.
`./gradlew clean fatJar` builds the fat JAR from scratch; `./gradlew test fatJar`
runs both. CLI `--help`, `--version` and a live end-to-end run against the sim
remain manual/ad-hoc checks.
