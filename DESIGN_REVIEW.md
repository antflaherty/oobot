# Design Review — Open Questions & Suggestions

This document records the design grill of `ARCHITECTURE.md` and `MVP.md`. It is intended to be worked through item by item; each item can be resolved, deferred, or rejected as we implement.

Format per item:
- **Question or suggestion**
- **Context / why it matters**
- **Decision:** (pending | accepted | rejected | deferred) + notes

---

## 1. Communication & Protocol

### 1.1 Two transports, two codecs?
**Question:** The design splits transport: raw TCP for the Java robot controller, WebSocket for the browser visualiser, both carrying JSON. WebSocket *is* TCP under the hood, so this doubles framing, connection management, reconnection logic, and serialization. Why not one transport for both?

**Context:** One codec and one reconnection strategy means less code and fewer subtle bugs. Java has WebSocket client libraries just as easily as TCP sockets.

**Decision:** **accepted** — unify on WebSocket for both controller and visualiser, distinguished by a `clientType` field in the handshake. Rationale: simplicity first (local machine for now); the communication layer must be encapsulated behind an interface so it can be swapped for physical-hardware transport later without touching robot code.

---

### 1.2 JSON at 60 Hz?
**Question:** JSON is verbose and text-based. For a real-time control loop pushing sensor snapshots at ~60 Hz to the visualiser *and* motor commands/sensor updates to the robot, parsing overhead adds up. Have you considered a minimal binary framing, or at least newline-delimited JSON with a length prefix? How are messages delimited on raw TCP right now? (Stream sockets have no message boundaries.)

**Context:** Framing is not an implementation detail — it determines the whole wire protocol. Defining it early prevents rework.

**Decision:** **accepted** — keep JSON with newline-delimited framing (NDJSON). Human-readable, easy to debug, language-independent. Revisit binary only if profiling shows parsing is a bottleneck.

---

### 1.3 Push vs. pull for sensor readings?
**Question:** The doc says the simulation "sends sensor updates" and "repeatedly invokes a behaviour update method." Is the sim pushing sensor readings to the robot at 60 Hz, or is the robot polling? If push, does the robot get overwhelmed if it can't keep up at 20 Hz?

**Context:** Mismatched rates between sim (60 Hz) and robot behaviour (20 Hz) need an explicit strategy (drop, buffer, throttle).

**Decision:** **accepted** — sim maintains the latest sensor value + `simTick` in a single slot, overwriting each tick. No queue, no polling. The robot reads the latest available value on its behaviour loop (~20 Hz) and can use `simTick` to judge staleness.

---

### 1.4 Clock skew & sequencing across processes
**Question:** The doc mentions sequencing/timing info to detect stale data, but the robot and sim are separate processes with independent clocks. How do you timestamp messages meaningfully — sim logical ticks, wall-clock, or both? What happens if the robot's machine clock drifts from the sim's?

**Context:** Without a shared time source, "stale data" is hard to define. Sim logical ticks are the most robust source of truth.

**Decision:** **accepted** — sim logical tick numbers are the sequence source of truth. Every snapshot carries `simTick`. The robot echoes the last tick it acted on in its command messages. Wall-clock timestamps may accompany messages for debugging only; staleness is judged by tick difference.

---

### 1.5 Suggested message envelope
**Suggestion:** Define a minimal message envelope now: `{ type, seq, timestamp, payload }`. Use sim logical tick numbers as the sequencing source of truth rather than wall-clock.

**Decision:** **accepted with modification** — two separate WebSocket ports/channels instead of one tagged channel:
- Robot channel (`ws://localhost:ROBOT_PORT`): bidirectional — `motorCommand` (robot→sim), `sensorSnapshot` (sim→robot)
- Visualiser channel (`ws://localhost:VISUALISER_PORT`): primarily unidirectional — `worldSnapshot` (sim→visualiser); `userControl` (visualiser→sim) added later

No `clientType`/`clientId` needed — the channel implies who is talking. Baseline envelope per message: `{ type, seq, simTick, payload }`. Each channel has its own simple protocol; both are encapsulated behind a common `Transport` interface in the sim so the transport can be swapped for physical hardware later without touching robot code.

---

## 2. Timing & Execution Model

### 2.1 Safe-stop vs. persistent actuator state
**Question:** Motors keep running until overridden ("persistent actuator state"), but the design also promises safe stop on disconnect. How is a clean disconnect distinguished from a slow GC pause, a frozen thread, or a network blip? What heartbeat/timeout do you use?

**Context:** If the robot hangs mid-command, motors stay on unless the sim detects liveness failure. A TCP socket can remain open while the process is dead.

**Decision:** **accepted with modification** — for the MVP, rely on WebSocket close events for safe stop (if the Java process dies or socket closes, sim stops motors immediately). Defer heartbeat/timeout for hung-process detection; manual intervention covers that for now. Keep persistent actuator state: motors run until overridden.

---

### 2.2 Stale sensor readings at mismatched rates
**Question:** The robot gets "latest available reading" at 20 Hz while the sim runs at 60 Hz — 2/3 of samples are silently dropped. If an obstacle appears between samples, the robot could miss it entirely. Is there a maximum staleness the robot should respect?

**Context:** For a simple "turn when close" behaviour this may be fine, but it limits what obstacle avoidance can reliably do.

**Decision:** **accepted** — no maximum staleness check for MVP. Robot uses whatever is in the latest slot (~50 ms worst-case latency at 20 Hz, fine for simple obstacle avoidance). `simTick` is on every reading (from 1.4) so a behaviour *can* check staleness later if needed, but it's not required.

---

### 2.3 Degradation under load
**Question:** The loops are independent and never wait for each other. What happens under load when the sim can't maintain 60 Hz? Does it degrade gracefully, or does the control loop become erratic?

**Decision:** **accepted** — sim runs at best effort, never blocks. If it can't maintain 60 Hz, visualiser gets fewer snapshots and robot gets fewer sensor updates (uses latest available). No adaptive simulation or frame-skipping logic for MVP; just log actual achieved Hz so it's observable.

---

### 2.4 Visualiser interpolation strategy
**Question:** The visualiser targets ~60 fps and the sim publishes ~60 Hz snapshots. If they drift out of phase, you'll see jitter. Is interpolation parameterized by timestamp or by frame alpha between snapshots? If a snapshot arrives late, do you interpolate between old states or extrapolate forward?

**Decision:** **accepted with modification** — keep it simple for MVP. Sim runs on its own fixed timestep (speed controllable independently of the visualiser). Visualiser holds the last received snapshot and interpolates to the next available one on each render tick. No extrapolation. The sim timestamp travels with each snapshot (from 1.4) so interpolation can use it, but the visualiser does not drive sim timing.

---

### 2.5 Suggested additions
**Suggestion:** Add a heartbeat/liveness check with a hard timeout; on timeout, transition actuators to safe state even if the socket is technically still open. Expose `readingAge` or `simTick` with every sensor reading so the robot can decide whether to trust it. Make the sim timestep configurable and log actual achieved Hz.

**Decision:** **resolved** — sub-items covered by 2.1 (heartbeat deferred for MVP), 1.4 (simTick on every reading), 2.3 (configurable timestep + log achieved Hz).

---

## 3. Language & Runtime Choices

### 3.1 Why Node.js for the simulation?
**Question:** The sim is CPU-bound (collision detection, kinematics at 60 Hz) and Node is single-threaded. Was Python, Go, or even Java considered? Node's main advantage is shared TypeScript with the visualiser, but the two components don't share code (different transports, different concerns).

**Decision:** **accepted** — Node.js/TypeScript for the MVP sim. Rationale: simple 2D kinematics with static obstacles is trivial for Node at 60 Hz; single-threaded event loop gives deterministic world state (no race conditions); shared TS with the React visualiser; the language-independent boundary means the sim can be rewritten later without changing the Java controller API.

---

### 3.2 Why a separate Java process for the controller?
**Question:** Process isolation is good (student code can't crash the sim) but introduces all the IPC complexity the design tries to hide from students. Why not load student code in the same JVM with a sandbox? (Java's `SecurityManager` is largely removed, so process isolation is pragmatic — but that trade-off should be explicit.)

**Decision:** **accepted** — keep the separate Java process. IPC = Inter-Process Communication (socket/WebSocket between processes). Java's `SecurityManager` is largely removed, so in-JVM sandboxing isn't robust. Process isolation means a crashing or infinite-looping student controller can't take down the sim; the IPC complexity stays hidden behind the Java framework API so student code never touches sockets.

---

### 3.3 TypeScript in two of three components
**Question:** TS is used for the sim and visualiser, but the framework is plain Java and no code is shared between components. Is the build complexity of TS worth it for the MVP?

**Decision:** **accepted** — TS for sim and visualiser. The sim is Node-based so TS adds type safety with minimal tooling overhead; the visualiser benefits from TS with React/canvas. No cross-component code sharing needed — TS is just the per-component implementation language.

---

### 3.4 Language-independence may be YAGNI
**Question:** The framework is "independent of the simulation engine's implementation language." For an MVP with one sim (Node) and one framework (Java), this is untested abstraction. What's the actual cost of coupling the Java framework to a Node sim for now?

**Decision:** **accepted with modification** — treat language-independence as a guiding principle, not a hard constraint. Keep the wire protocol simple and documented (JSON over WebSocket, defined envelope) so a future sim rewrite could be language-agnostic, but don't build an abstraction layer or IDL yet. The Java framework talks WebSocket/JSON directly.

---

### 3.5 Suggested approach
**Suggestion:** Prototype the sim in plain JavaScript first; add TypeScript only if type safety pays off. Treat language-independence as a guiding principle, not a hard constraint, until a second implementation exists. Document *why* process isolation was chosen over in-JVM sandboxing.

**Decision:** **resolved** — sub-points covered by 3.1 (accept Node/TS), 3.2 (accept separate process), 3.3 (accept TS), and 3.4 (language-independence as guiding principle).

---

## 4. Visualiser Decoupling

### 4.1 Why React for a canvas visualiser?
**Question:** React's reconciliation model is DOM-oriented. Drawing to an imperative `<canvas>` largely bypasses the virtual DOM, so React is essentially a mount point. Would vanilla TypeScript + canvas, or a lighter framework, be less fighting-the-framework?

**Decision:** **accepted with modification** — no React for MVP. Vanilla TypeScript + canvas. Add React later only if UI controls (start/pause/reset) benefit from a component model.

---

### 4.2 Backpressure on visualiser snapshots
**Question:** The sim must not let rendering delays determine sim timing. But if the visualiser drops frames or disconnects, does the sim keep publishing snapshots into a full buffer? Does it drop subscribers? An unbounded publisher queue can still OOM.

**Decision:** **accepted** — sim publishes snapshots without waiting; if a visualiser falls behind, drop the oldest unpublished snapshot for that client (keep only latest). Disconnected visualisers are removed from the subscriber list — no retry, no buffering. Sim never blocks on a visualiser. No coupling between sim and visualiser.

---

### 4.3 Interpolation / extrapolation artefacts
**Question:** If a snapshot arrives late, what's the visual artefact strategy — hold the last state, interpolate between old states, or extrapolate forward?

**Decision:** **resolved** — covered by 2.4 (hold last frame, interpolate to next available, no extrapolation).

---

## 5. Scope & Capability Limits

### 5.1 One distance sensor — enough?
**Question:** A single forward-facing sensor lets a robot detect "something ahead" but not where it is relative to the robot's sides, not wall-following, not corner detection. Is one sensor enough to demonstrate "respond to a basic obstacle"? It works for a simple "turn when close" behaviour, but anything more interesting needs at least two (left/right) or a scanning sensor.

**Decision:** **accepted with modification** — for the very first iteration, **ignore sensors entirely.** The first robot just moves forward for one tick, turns for one tick, etc., so it traces a square. Sensors (and obstacle avoidance) are kept in mind but deferred to iteration 2. This proves the core control loop (sim → motor command → movement → visualiser) without sensor complexity.

---

### 5.2 "Trusted example code only" — who writes the programs?
**Question:** If the goal is education, students will want to run their own code. "Trusted local execution only" means no arbitrary user code ever runs — so who writes the robot programs? Only the maintainers? That makes it a demo, not a learning platform. When does this constraint get revisited?

**Decision:** **accepted** — explicit phase-1 limitation. Maintainers write the example robot programs for now. Path forward: process isolation is already there (separate Java process) → later add OS-level sandboxing (containers/seccomp) → then allow student uploads, probably alongside a code editor. Don't block the MVP on sandboxing.

---

### 5.3 Robot IDs in the protocol despite "no multiple robots"
**Question:** The protocol says messages should identify the relevant robot or hardware component, but multiple robots are explicitly out of scope. Is that premature generality, or a signal that multi-robot is closer to scope than the doc admits?

**Decision:** **accepted** — remove robot IDs from the MVP protocol. One robot = implicit. Add `robotId` back when multi-robot becomes a real goal.

---

### 5.4 Static obstacles only / collision response
**Question:** With only static obstacles, what's the collision response — stop dead, slide along surface, bounce, penetrate?

**Decision:** **accepted** — stop dead on contact for MVP. Robot movement is clamped so it doesn't penetrate an obstacle; no sliding or bouncing. World is a bounded box for now. Collision response can be refined later.

---

## 6. Missing Pieces (Not Addressed in Either Doc)

### 6.1 Robot launch & discovery
**Question:** How does the Java process know the sim's host/port? Command-line args, config file, environment variables?

**Decision:** **accepted** — command-line args with sensible defaults (`--host localhost --port 8080`). No config file for MVP. Dev startup via npm scripts to bring up sim + visualiser + example robot together.

---

### 6.2 World definition
**Question:** Are obstacle coordinates hardcoded in the sim? A JSON world file? Who edits it?

**Decision:** **accepted with modification** — world is just a bounded box with no obstacles for the first iteration. No world file needed at all. Obstacles (and a world file format) come in iteration 2 alongside sensors.

---

### 6.3 Collision response rules
**Question:** Robot vs. obstacle — stop dead, slide, bounce, penetrate? Robot vs. robot (if added later)?

**Decision:** **resolved** — covered by 5.4 (stop dead on contact). Moat for iteration 1 since there are no obstacles, but the rule stands when obstacles arrive.

---

### 6.4 Visualiser discovery
**Question:** How does the browser discover the sim's WebSocket URL? Hardcoded `localhost:PORT`?

**Decision:** **accepted** — hardcoded `ws://localhost:VISUALISER_PORT` for MVP. User opens `http://localhost:PORT` and the visualiser connects to the sim on the same host. No discovery service.

---

### 6.5 Error handling contract
**Question:** Malformed JSON, partial TCP reads, controller crash mid-behaviour, visualiser reconnect mid-simulation — what's the contract for each?

**Decision:** **accepted** — minimal for MVP. Malformed JSON: log and ignore. Partial reads: NDJSON (newline-delimited) — buffer until newline, then parse. Controller crash: WebSocket close → safe stop motors. Visualiser reconnect: sim accepts new connection, sends latest snapshot; sim never blocks on a visualiser. No formal error protocol yet; log everything for debugging.

---

### 6.6 Build & dev startup story
**Question:** Monorepo build tool (npm workspaces? lerna? turborepo? gradle+maven for Java?). How do you start sim + visualiser + example controller in dev — one command or three?

**Decision:** **accepted** — keep it dead simple. Sim + visualiser: one Node `package.json`. Java controller: single `pom.xml`/`build.gradle` producing a fat JAR (`java -jar`). Dev startup: one npm script (`npm run dev`) that starts sim + visualiser dev server + example robot controller together (e.g., via `concurrently`). No lerna/turborepo yet.

---

### 6.7 Observability
**Question:** No logging, metrics, or debugging hooks mentioned. Debugging a distributed sim+controller+visualiser without logs is miserable.

**Decision:** **accepted** — basic console logging for MVP. Sim logs connections/disconnections, motor commands, sensor reads. Java framework logs connection state and commands sent. Visualiser logs frames received. No metrics system yet; add structured logging later if needed.

---

### 6.8 Suggested spec additions
**Suggestion:** Add a `specs/` section for wire protocol message schema, world file format, robot launch/config, and collision response rules. Define a dev startup story before writing much code.

**Decision:** **accepted** — write spec files as decisions finalize: `specs/WIRE_PROTOCOL.md`, `specs/WORLD_FORMAT.md`, `specs/ROBOT_LAUNCH.md`.

---

## 7. Internal Contradictions

### 7.1 Language independence vs. locked-in languages
**Contradiction:** "The framework should remain independent of the simulation engine's implementation language" vs. "Initial implementation: Node sim, Java framework." You're claiming independence while locking in two languages. Fine for now, but acknowledge it's aspirational.

**Decision:** **resolved** — covered by 3.4. Language-independence is a guiding principle, not a hard constraint; no abstraction layer built yet.

---

### 7.2 "Smallest working version" vs. heavy upfront design
**Contradiction:** "Keep the MVP small and prove the end-to-end control loop early" vs. designing language-independent protocols, two transports, and a separate visualiser before a single line of code exists.

**Decision:** **resolved** — scope stripped aggressively through this review: no sensors, no obstacles, no React, no sandboxing, hardcoded ports, single robot. The design review itself is the upfront work; implementation stays minimal.

---

### 7.3 Robot rate vs. sim non-blocking
**Contradiction:** Robot controller runs ~20 Hz vs. "the simulation must not block while waiting for a robot controller." If the robot takes longer than 50 ms to respond, does the sim skip that tick or use the previous command? Persistent actuator state implies "use last command," but it's not stated explicitly.

**Decision:** **resolved** — sim uses the last command (persistent actuator state) and never waits for the robot. Explicitly stated now.

---

## Suggested First Step

Write the **wire protocol spec** (`specs/WIRE_PROTOCOL.md`) based on the accepted decisions:
- Two WebSocket ports: robot channel (bidirectional: `motorCommand` / `sensorSnapshot`) and visualiser channel (unidirectional: `worldSnapshot`)
- NDJSON framing, envelope `{ type, seq, simTick, payload }`
- Sim logical tick as sequence source of truth

Then scaffold: Node/TS sim + vanilla TS visualiser + Java robot framework + example square-tracing robot. A dummy controller that connects, sends one motor command, reads one snapshot, and disconnects proves the loop end-to-end.

## All Decisions Summary

Every item in sections 1–7 is now **accepted**, **accepted with modification**, or **resolved**. The design has been stripped to its essence for iteration 1:

- **Sim:** Node/TS, bounded box world, no obstacles, fixed 60 Hz timestep, best-effort under load
- **Robot channel:** WebSocket, NDJSON, `motorCommand` in, `sensorSnapshot` out (sensor slot deferred)
- **Visualiser channel:** WebSocket, NDJSON, `worldSnapshot` out, hold-last-frame interpolation
- **Java controller:** separate process, CLI args (`--host`, `--port`), fat JAR, framework hides sockets
- **First robot:** square-tracing (forward/turn), no sensors
- **Safe stop:** on WebSocket close, motors stop; heartbeat deferred
- **Dev startup:** one `npm run dev` script
- **Logging:** basic console logging, no metrics yet

Next: write `specs/WIRE_PROTOCOL.md`, then start scaffolding code.