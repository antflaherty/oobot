# Component Specifications — MVP

Object Oriented Robotics (oobot) — MVP iteration 1.

**Status:** component spec (Phase 0.3, complete). Derived from `ARCHITECTURE.md`,
`DESIGN_REVIEW.md`, `MVP.md`, `PLAN.md`, and `specs/WIRE_PROTOCOL.md`. All
decisions recorded there are accepted; this spec turns them into exact
responsibilities and interfaces per component.

This spec is intentionally small. Out-of-scope items are listed explicitly for
each component so nothing creeps into iteration 1.

---

## 0. Shared constraints (all components)

| Constraint | Source |
|---|---|
| One robot only; robot IDs implicit, absent from protocol | DESIGN_REVIEW 5.3 |
| Two WebSocket channels from one sim process: robot channel (`ROBOT_PORT`), visualiser channel (`VISUALISER_PORT`) | DESIGN_REVIEW 1.5; ARCHITECTURE §Communication boundaries |
| NDJSON framing: exactly one JSON object per line, `\n` delimited | DESIGN_REVIEW 1.2; WIRE_PROTOCOL §3 |
| Baseline envelope: `{ type, seq, simTick, payload }`; sim logical tick is the source of truth for sequencing/staleness | DESIGN_REVIEW 1.4–1.5; WIRE_PROTOCOL §4–5 |
| Wall-clock timestamps may accompany messages for debugging only; never used to judge staleness across processes | DESIGN_REVIEW 1.4; ARCHITECTURE §Message format |
| Malformed JSON: log and ignore; never crash or disconnect on bad input | DESIGN_REVIEW 6.5; WIRE_PROTOCOL §3 |
| Single robot; sensor slot holds latest reading only (overwrite each tick), no queue, no polling | DESIGN_REVIEW 1.3 |
| Persistent actuator state: motors keep running at last commanded speed until overridden; sim never waits for the robot | DESIGN_REVIEW 2.1; ARCHITECTURE §Actuator model |
| Safe stop on robot WebSocket close (motors stop); heartbeat/timeout deferred | DESIGN_REVIEW 2.1; WIRE_PROTOCOL §7 |
| Sim runs on its own fixed timestep (~60 Hz target), best-effort under load, never blocks; does not wait for robot or visualiser | DESIGN_REVIEW 2.2–2.3; ARCHITECTURE §Timestep and speed control |
| Visualiser is a client of the sim; it never drives or blocks sim timing | DESIGN_REVIEW 2.4; ARCHITECTURE §Visualiser |
| Language-independence is a guiding principle, not a hard constraint; no abstraction layer or IDL yet | DESIGN_REVIEW 3.4; ARCHITECTURE §Java robot framework |
| Localhost only (enforced at socket bind, not just URL strings), hardcoded ports, no discovery, no config file | DESIGN_REVIEW 6.1, 6.4 |
| Basic console logging only; no metrics | DESIGN_REVIEW 6.7 |

---

## 1. Sim server (`simulator/server`, Node.js/TypeScript)

### 1.1 Purpose

Authoritative owner of the simulated world. Runs as a single Node.js process
hosting two WebSocket servers (robot channel and visualiser channel) on a
fixed ~60 Hz timestep. Owns world state, advances physics, applies actuator
commands, publishes snapshots, and enforces safe stop on robot disconnect.

### 1.2 Inputs

- `motorCommand` messages from the robot controller on the robot channel
  (`{ type: "motorCommand", seq, simTick, payload: { left, right } }`), one
  per robot (implicit single robot; `robotId` absent).
- WebSocket close events on the robot channel.
- WebSocket connect/disconnect events on the visualiser channel.
- CLI/env configuration: host (`localhost` default), `ROBOT_PORT`,
  `VISUALISER_PORT` (sensible defaults; e.g. 8080/8081, fixed per
  DESIGN_REVIEW 6.1).

### 1.3 Outputs

- `worldSnapshot` messages published on the visualiser channel every sim tick:
  `{ type: "worldSnapshot", seq, simTick, payload: { robot: { x, y, heading } } }`
  (iteration 1 payload: robot pose only; obstacles empty/absent).
- `sensorSnapshot` messages published on the robot channel every sim tick:
  `{ type: "sensorSnapshot", seq, simTick, payload: {} }` (empty placeholder
  for iteration 1; no readings).
- Console log lines: connections/disconnections, motor commands, achieved Hz.

### 1.4 World model (iteration 1)

- Bounded 2D box with defined width/height (metres or arbitrary units).
- Single robot pose: `x`, `y`, `heading` (radians). Point or circle robot;
  exact geometry fixed at scaffolding (Phase 2.2).
- **No obstacles** in iteration 1 (WIRE_PROTOCOL §10; DESIGN_REVIEW 6.2).
  Collision/stop-dead against box edges only.

### 1.5 Timestep

- Fixed timestep loop, target ~60 Hz (one sim step = one `simTick` increment).
- Best-effort under load: if a tick overruns, the next tick fires as soon as
  possible; the sim **never blocks** waiting for the robot, the visualiser, or
  any I/O.
- Log actual achieved Hz for observability (DESIGN_REVIEW 2.3).
- Sim speed controllable independently of the visualiser frame rate
  (ARCHITECTURE §Timestep and speed control).

### 1.6 Actuator model (persistent state)

- Store the latest `motorCommand` per robot (single robot, implicit ID).
- Motors keep running at the last commanded speed until a new command
  overrides it.
- If the robot is slow (behaviour loop ~20 Hz vs sim 60 Hz), the sim reuses
  the previous command; it never waits for a new one.

### 1.7 Movement & collision (iteration 1)

- Integrate pose from motor commands each tick (exact kinematics fixed at
  scaffolding).
- Clamp robot position at box edges: movement that would penetrate a boundary
  is clamped so the robot stops at the edge (**stop-dead**). No sliding,
  bouncing, or penetration (DESIGN_REVIEW 5.4).
- No obstacle collision in iteration 1 (rule stands for iteration 2).

### 1.8 Publishing & backpressure

- **Visualiser channel:** publish `worldSnapshot` every sim tick to all
  connected visualisers. Publish without waiting; never block on a visualiser.
  If a visualiser falls behind, drop the oldest unpublished snapshot for that
  client (keep only latest). Disconnected visualisers are removed from the
  subscriber list — no retry, no buffering (DESIGN_REVIEW 4.2; WIRE_PROTOCOL
  §6.2).
- **Robot channel:** publish `sensorSnapshot` (empty payload) every sim tick
  to the connected robot. Single-slot sensor model: latest value overwritten
  each tick, no queue (DESIGN_REVIEW 1.3).

### 1.9 Safe stop

- On robot WebSocket close (Java process dies, socket closes, controller
  disconnects): immediately stop the motors (transition actuators to safe
  state).
- No heartbeat/timeout for hung-but-connected processes in MVP; manual
  intervention covers that (DESIGN_REVIEW 2.1; WIRE_PROTOCOL §7).

### 1.10 Key interfaces

| Interface | Direction | Notes |
|---|---|---|
| `ws://localhost:ROBOT_PORT` (WebSocket, NDJSON) | In `motorCommand`; out `sensorSnapshot` | Robot channel; port implies client type |
| `ws://localhost:VISUALISER_PORT` (WebSocket, NDJSON) | Out `worldSnapshot` | Visualiser channel; `userControl` deferred |
| `simTick` counter | Internal | Authoritative logical clock; incremented each tick |
| Latest motor command slot | Internal | Persistent actuator state; overwritten by each `motorCommand` |
| Latest sensor slot | Internal | Empty for iteration 1; overwritten each tick |
| World box bounds + robot pose | Internal state | Authoritative world model |

### 1.11 OUT of scope for MVP

- Obstacles, world file, static/dynamic obstacle collision (iteration 2).
- Multiple robots / `robotId` (iteration later).
- Heartbeat/timeout liveness detection (safe-stop relies on WebSocket close).
- `userControl` from visualiser (start/pause/reset) — deferred.
- Binary framing; adaptive frame skipping; metrics; structured logging.
- Any waiting/blocking on robot or visualiser.

---

## 2. Visualiser (`simulator/visualiser`, vanilla TypeScript + canvas)

### 2.1 Purpose

Browser-based client that renders the simulated world. Connects to the sim's
visualiser channel and draws the world, robot, and (later) obstacles. Fully
decoupled from sim timing: rendering delays or disconnection never affect how
the sim advances.

### 2.2 Inputs

- `worldSnapshot` messages over WebSocket on `ws://localhost:VISUALISER_PORT`
  (NDJSON; buffer until newline, parse one object per line).
- Hardcoded URL `ws://localhost:VISUALISER_PORT` (no discovery).
- Render-tick clock (~60 fps, browser `requestAnimationFrame`).

### 2.3 Outputs

- Canvas rendering of: bounded box, robot (pose from latest interpolated
  snapshot), obstacles (empty/absent iteration 1).
- Console log lines: frames received, render ticks.
- No outgoing control messages in MVP (`userControl` deferred).

### 2.4 Rendering & interpolation

- Holds the last received `worldSnapshot` and interpolates to the next
  available one on each render tick (~60 fps) — hold-last-frame, interpolate
  to next, **no extrapolation** (DESIGN_REVIEW 2.4).
- Interpolation parameter may use `simTick` carried with each snapshot, but
  the visualiser does not drive sim timing.
- If snapshots arrive slower than 60 fps, hold the last state; if faster,
  render the latest available.
- No React; vanilla TS + canvas only (DESIGN_REVIEW 4.1).
- UI controls (start/pause/reset) deferred (DESIGN_REVIEW 4.1; ARCHITECTURE
  §Visualiser).

### 2.5 Connection handling

- Connects on load; on disconnect, stop rendering or hold last frame (no
  retry logic required in MVP; sim removes disconnected clients).
- Malformed NDJSON lines: log and ignore.

### 2.6 Key interfaces

| Interface | Direction | Notes |
|---|---|---|
| `ws://localhost:VISUALISER_PORT` (WebSocket, NDJSON) | In `worldSnapshot` | Visualiser channel only |
| Browser canvas (`<canvas>`) | Out (rendering) | Vanilla TS, no framework |
| `requestAnimationFrame` loop | Internal | ~60 fps render ticks |

### 2.7 OUT of scope for MVP

- React or any UI framework.
- Start/pause/reset or any `userControl` (deferred).
- Obstacle rendering beyond an empty list (iteration 1 world has none).
- Driving or blocking sim timing; any influence on simulation.
- Reconnection logic beyond accepting a fresh WebSocket (sim accepts new
  connections and sends latest snapshot).

---

## 3. Java robot framework (`robot-framework`, Java)

### 3.1 Purpose

Student-facing Java library bundled into a fat JAR (`robot.jar`). Runs as a
separate Java process. Provides the robot hardware API (`Motor`) and hides all
communication: WebSocket connection, NDJSON framing, reconnection, concurrency,
and safe stop. Robot code never touches sockets directly.

### 3.2 Inputs (CLI)

- `--host` (default `localhost`) and `--port ROBOT_PORT` (ARCHITECTURE §Launch
  and configuration; DESIGN_REVIEW 6.1).
- Robot behaviour implementation: a behaviour update method invoked by the
  framework (~20 Hz), plus optional initialisation.

### 3.3 Outputs

- `motorCommand` messages sent on the robot channel (`ws://localhost:ROBOT_PORT`),
  NDJSON, one per command: `{ type: "motorCommand", seq, simTick, payload:
  { left, right } }` (exact payload schema fixed at sim scaffolding Phase 2.4;
  `simTick` may be echoed from last sensor snapshot or omitted).
- `sensorSnapshot` messages received from the sim; framework keeps the latest
  in a single slot (overwrite each tick), exposed to robot code via the API.
- Console log lines: connection state, commands sent.

### 3.4 Public API (iteration 1)

```java
// Framework entry point — robot program implements this
interface RobotBehaviour {
    void init();          // called once on start
    void update();        // called ~20 Hz; read sensors, command motors
}

// Motor hardware abstraction
class Motor {
    static void setSpeed(double speed); // speed in [-1, 1], sends motorCommand
}

// Sensor access (iteration 1: no readings; API present for iteration 2)
class Sensors {
    // e.g. double distance(); // deferred to iteration 2
}
```

Framework responsibilities:
- Connect to `ws://localhost:ROBOT_PORT` on startup; reconnect logic hidden
  (reconnection behaviour fixed at scaffolding Phase 3.2).
- Frame outgoing `motorCommand` as NDJSON; parse incoming `sensorSnapshot`
  NDJSON.
- Invoke `robotBehaviour.update()` on a fixed ~20 Hz loop, independent of the
  sim's 60 Hz (ARCHITECTURE §Timing and execution model).
- Expose latest `sensorSnapshot` (empty payload iteration 1) to `update()`.
- On WebSocket close: framework performs safe-stop handling (e.g. sends a
  final zero-speed `motorCommand` / marks motors stopped); the **sim performs
  the actual motor stopping** on close (DESIGN_REVIEW 2.1; WIRE_PROTOCOL §7).

### 3.5 Concurrency model

- Network I/O (WebSocket read/write) on framework-managed threads; behaviour
  `update()` called on the behaviour loop thread.
- Concurrency hidden from robot code; robot code calls `Motor.setSpeed` and
  reads sensors without locking.

### 3.6 Key interfaces

| Interface | Direction | Notes |
|---|---|---|
| `java -jar robot.jar --host localhost --port ROBOT_PORT` | Process launch | CLI args; sensible defaults |
| `Motor.setSpeed(double)` | Out → sim (`motorCommand`) | Public API; hides WebSocket/NDJSON |
| `RobotBehaviour.update()` (~20 Hz) | Framework → robot code | Behaviour hook |
| Latest `sensorSnapshot` slot | Sim → framework → robot code | Overwrite each tick; empty iteration 1 |
| WebSocket close handler | Internal | Safe-stop handling; sim stops motors |

### 3.7 OUT of scope for MVP

- Distance sensor / any sensor readings (iteration 1 `sensorSnapshot` payload
  empty; API may exist but returns no data).
- Multiple robots / `robotId`.
- Heartbeat/timeout for hung-process detection.
- In-JVM sandboxing / `SecurityManager`; separate process is the isolation
  mechanism (DESIGN_REVIEW 3.2).
- Abstraction layer / IDL for transport swapping (guiding principle only).
- Robot construction UI, code editor, user accounts, etc.

---

## 4. Example robot (square-trace) (`examples/square-trace` or within `robot-framework`)

### 4.1 Purpose

Trusted example robot program proving the end-to-end control loop: Java code
→ motor command → sim movement → visualiser. First behaviour: trace a square
by moving forward one behaviour tick, turning one behaviour tick, repeating.

### 4.2 Inputs

- `sensorSnapshot` messages from the sim via the framework (read but ignored
  in iteration 1 — no sensors used).
- Behaviour `update()` invocations (~20 Hz) from the framework.

### 4.3 Outputs

- `Motor.setSpeed(...)` calls via the framework API: forward for one tick,
  then turn for one tick, repeat (exact speeds/durations fixed at
  scaffolding Phase 3.3).
- No sensor-based decisions in iteration 1.

### 4.4 Behaviour (square trace)

- State machine: `FORWARD` → `TURN` → `FORWARD` → ...
- Each state lasts one behaviour update tick (~50 ms at 20 Hz); exact per-tick
  distances/angles fixed at scaffolding.
- Reads `sensorSnapshot` each tick (ignored) to exercise the sensor path.

### 4.5 Key interfaces

| Interface | Direction | Notes |
|---|---|---|
| `RobotBehaviour.init()` / `update()` | Framework → robot | Implemented by example robot |
| `Motor.setSpeed(double)` | Robot → framework → sim | Only hardware API used |
| `Sensors.*` (read) | Framework → robot | Ignored iteration 1 |

### 4.6 OUT of scope for MVP

- Obstacle avoidance / sensor-driven navigation (iteration 2).
- Any behaviour beyond forward/turn square trace.
- User-written or uploaded robot programs (trusted maintainer code only;
  DESIGN_REVIEW 5.2).

---

## 5. Interfaces between components

```
+------------------+      robot channel      +---------------------+
|   Java robot     |  ws://localhost:ROBOT_  |   Sim server        |
|   process        |  PORT  (NDJSON)         |  (Node/TS)          |
|                  |  motorCommand  -------> |  world model,       |
|  Robot framework |  (robot -> sim)         |  60 Hz timestep,    |
|                  |  <-------  sensorSnapshot |  actuator state   |
|                  |  (sim -> robot)         |                     |
+------------------+                         +----------+----------+
                                                         |
                                                         | visualiser channel
                                                         | ws://localhost:VISUALISER_PORT
                                                         | (NDJSON, worldSnapshot)
                                                         v
                                                  +---------------------+
                                                  |  Visualiser         |
                                                  |  (vanilla TS+canvas)|
                                                  |  holds+interpolates |
                                                  |  worldSnapshot      |
                                                  +---------------------+
```

### 5.1 Robot channel (sim ↔ Java robot)

- Transport: WebSocket on `ws://localhost:ROBOT_PORT`.
- Framing: NDJSON (one JSON object per `\n`-terminated line).
- Envelope: `{ type, seq, simTick, payload }`.
- `motorCommand` (robot → sim): carries the latest motor speeds; persistent
  actuator state in the sim.
- `sensorSnapshot` (sim → robot): published every sim tick; empty payload in
  iteration 1; single-slot (latest only).
- Safe stop: robot WebSocket close → sim stops motors immediately.

### 5.2 Visualiser channel (sim → visualiser)

- Transport: WebSocket on `ws://localhost:VISUALISER_PORT`.
- Framing: NDJSON; envelope as above.
- `worldSnapshot` (sim → visualiser): published every sim tick; robot pose
  payload in iteration 1; `userControl` deferred.
- Backpressure: drop-oldest per client; disconnected clients removed; sim
  never blocks.

### 5.3 Sequencing & time

- `simTick` (sim logical tick) is the source of truth on every message.
- Wall-clock may accompany messages for debugging only; never used for
  staleness across processes.
- No `clientType`/`clientId`/`robotId` fields; channel/port implies the
  participant; one robot only.

### 5.4 What connects what (summary)

| From | To | Channel | Message(s) |
|---|---|---|---|
| Java robot framework | Sim server | Robot channel (`ROBOT_PORT`) | `motorCommand` |
| Sim server | Java robot framework | Robot channel (`ROBOT_PORT`) | `sensorSnapshot` (empty, iter 1) |
| Sim server | Visualiser | Visualiser channel (`VISUALISER_PORT`) | `worldSnapshot` |

---

## 6. Deferred beyond MVP (all components)

- Heartbeat/timeout liveness detection (safe-stop on WebSocket close only).
- Binary framing; world file format; static obstacles; `robotId`/multi-robot.
- `userControl` (start/pause/reset) on the visualiser channel.
- Distance sensor readings in `sensorSnapshot`.
- React visualiser; UI controls.
- Metrics, structured logging, observability beyond console logs.
- Sandboxing for untrusted/user code; code editor; user accounts.
