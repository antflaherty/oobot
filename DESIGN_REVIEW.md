# Design review — historical record (superseded)

This is the original design grill for `ARCHITECTURE.md` and `MVP.md`. It is kept
for context: it records the questions that were asked and the reasoning behind
early choices, **not** the current design. Many of its original answers were
superseded as sensors, obstacles and the component manifest were implemented.

**Source of truth today:** `specs/` for the wire and component contracts,
`docs/DECISIONS.md` for the locked hardening decisions (D-1 … D-6), and
`ARCHITECTURE.md` / `MVP.md` for the current architecture and scope. Where this
document disagrees with any of them, they win. Do not use it to plan new code.

Format per item: the original question, the decision that was made, and (where
it changed) its status today.

---

## 1. Communication & protocol

### 1.1 Two transports, two codecs?
**Question:** The early sketch used raw TCP for the Java controller and
WebSocket for the browser visualiser. Why not unify them?

**Original decision:** unify on WebSocket and keep the communication layer
behind an interface so robot code is unaffected by a future transport swap.

**Status today:** one sim process hosts two WebSocket servers, distinguished by
port rather than a client-type field. Java uses OkHttp; the sim uses `ws`.

### 1.2 JSON at 60 Hz?
**Question:** Is verbose text framing acceptable for a 60 Hz control loop?

**Decision:** newline-delimited JSON (NDJSON): human-readable, debuggable and
language-independent. Revisit binary framing only if profiling demands it.
Still current, with a bounded inbound line length and non-blocking sends.

### 1.3 Push vs. pull for sensor readings?
**Question:** The sim runs at ~60 Hz and behaviour at ~20 Hz. Buffer, drop or
throttle readings?

**Decision:** one latest-value slot per sensor, overwritten each tick, with a
`simTick` on every reading. No queue, no polling; the behaviour reads the
latest value. Still current.

### 1.4 Clock skew & sequencing across processes
**Question:** Wall clocks drift. How is freshness defined across processes?

**Decision:** the sim's logical tick is the source of truth; staleness is judged
by tick difference, never wall-clock time. Still current and extended by the
per-connection `seq`/`simTick` acceptance rules in D-4.

### 1.5 Suggested message envelope
**Suggestion:** `{ type, seq, timestamp, payload }` with logical ticks.

**Original decision:** separate robot and visualiser ports; baseline envelope
`{ type, seq, simTick, payload }`.

**Status today:** the envelope is unchanged, but the message types are
`componentCommand`, `componentSnapshot` and `worldSnapshot`. The visualiser
channel is unidirectional; there is no visualiser control message.

---

## 2. Timing & execution model

### 2.1 Safe-stop vs. persistent actuator state
**Question:** Motors keep their last command, so how does a disconnect stop them?

**Original decision:** rely on the WebSocket close event; defer a heartbeat.

**Status today:** the sim still stops motors when the active robot socket
closes or the sim shuts down, and a heartbeat remains out of scope. D-2 adds a
second, independent layer: the Java bridge writes a zero command for every
wheel before closing or reconnecting its socket. Each layer must work alone.

### 2.2 Stale sensor readings at mismatched rates
**Question:** Is dropping two out of three samples acceptable?

**Original decision:** no maximum-age check in the first iteration; the tick on
each reading would allow behaviours to add one later.

**Status today:** `ProximitySensor.latestSimTick()` exposes that tick and a
30-tick maximum-age policy is documented; example behaviours do not yet refuse
stale readings. Sim-side command freshness is now enforced (D-4).

### 2.3 Degradation under load
**Question:** What happens when the sim cannot keep up with 60 Hz?

**Decision:** best effort, no blocking; achieved Hz is logged. Still current,
with command/collision counters in the same periodic line.

### 2.4 Visualiser interpolation strategy
**Question:** Interpolate, hold or extrapolate between snapshots?

**Decision:** hold the last snapshot and interpolate toward the next; no
extrapolation; the visualiser never drives sim timing. Still current. D-5 adds
a full state reset on every (re)connect and on a sim tick rollback.

### 2.5 Suggested additions
**Suggestion:** heartbeat with timeout, per-reading age and configurable sim
timestep.

**Status today:** heartbeat still deferred; `latestSimTick()` covers reading
age; the timestep is fixed in code and only the world path is configurable.

---

## 3. Language & runtime choices

### 3.1 Why Node.js for the simulation?
**Decision:** Node/TypeScript keeps a single-threaded authoritative world model
and shares a language with the visualiser. Still current.

### 3.2 Why a separate Java process for the controller?
**Decision:** process isolation keeps crashes and infinite loops in student
code from taking down the sim; IPC details stay behind the framework API.
Still current.

### 3.3 TypeScript in two of three components
**Decision:** TS for sim and visualiser, plain Java for the framework; no shared
runtime code. Still current.

### 3.4 Language independence may be YAGNI
**Decision:** treat language independence as a guiding principle, not a hard
constraint; keep the wire protocol documented but build no IDL. Still current.

### 3.5 Suggested approach
**Status:** resolved by 3.1–3.4. The framework builds with Gradle into a fat JAR
and uses Jackson, OkHttp, picocli and SLF4J/Logback.

---

## 4. Visualiser decoupling

### 4.1 Why React for a canvas visualiser?
**Decision:** vanilla TypeScript + canvas; add a UI framework only if controls
need one. Still current.

### 4.2 Backpressure on visualiser snapshots
**Decision:** the sim never waits for a browser; keep only the newest pending
snapshot per client and remove disconnected clients. Still current: outbound
slots are depth-1 drop-oldest.

### 4.3 Interpolation / extrapolation artefacts
**Status:** covered by 2.4.

---

## 5. Scope & capability limits

### 5.1 One distance sensor — enough?
**Question:** The early sketch assumed one forward-facing sensor.

**Original decision:** two front/side sensors were planned as an enhancement.

**Status today:** two manifest-declared proximity sensors exist (`front-sensor`
at 0 rad, `right-sensor` at −π/2 rad), read over `componentSnapshot` via
`ProximitySensor` handles. `wall-avoid` and `wall-follow-right` use them. The
`Brain` accessors remain fixed to those two ids (D-3), which is a documented
constraint rather than a generic sensor API.

### 5.2 "Trusted example code only" — who writes the programs?
**Decision:** a phase-1 limitation; maintainers write examples. Process
isolation exists, OS sandboxing does not. Still current.

### 5.3 Robot IDs in the protocol despite "no multiple robots"
**Decision:** omit robot IDs; the robot channel has a single active connection.
Still current.

### 5.4 Static obstacles only / collision response
**Decision:** stop dead on contact; no sliding or bouncing. Still current, now
applied to obstacles from the world file as well as the box edges.

---

## 6. Missing pieces addressed during the review

### 6.1 Robot launch & discovery
**Decision:** CLI arguments with sensible defaults; no discovery service.
Still current, extended by `--robot`, `--behaviour`, `--help` and `--version`,
with picocli usage errors for invalid input.

### 6.2 World definition
**Question:** Hardcoded obstacle coordinates or a JSON world file?

**Original decision:** a bounded box only in the first iteration; a world file
would come later.

**Status today:** `world.ts` loads a JSON world with bounds, start pose and
axis-aligned obstacles. The default is `simulator/server/worlds/maze.json`, and
`--world <path>` overrides it. An unreadable file falls back to the empty
default world with a warning; invalid start poses are rejected. See
`specs/WORLD_FORMAT.md`.

### 6.3 Collision response rules
**Status:** covered by 5.4.

### 6.4 Visualiser discovery
**Decision:** hardcoded localhost ports; origin checked on upgrade and sockets
bound on `0.0.0.0` for IPv4 clients. Still current.

### 6.5 Error handling contract
**Decision:** malformed JSON is logged and ignored; NDJSON lines are buffered
until a newline; a controller close stops the motors. Still current, with
bounded inbound lines, explicit inbound validation and bounded shutdown.

### 6.6 Build & dev startup story
**Original decision:** one Node package, a Gradle fat JAR and one `npm run dev`
script.

**Status today:** npm workspaces cover both TS packages; `npm run dev` starts
sim, visualiser and robot with absolute paths and cleans up its children;
`npm run build:robot` builds the JAR; `npm run dev:robot` launches it.

### 6.7 Observability
**Decision:** basic console logging in all three processes. Still current; the
sim reports achieved Hz and aggregate command/collision counters periodically.

### 6.8 Suggested spec additions
**Decision:** write specs for the wire protocol, world format and launch.
Done; `specs/` is the current contract.

---

## 7. Internal contradictions revisited

### 7.1 Language independence vs. locked-in languages
**Status:** resolved by 3.4 — a guiding principle, not an abstraction layer.

### 7.2 "Smallest working version" vs. heavy upfront design
**Original resolution:** strip the first iteration down to a minimal loop.

**Status today:** that narrow scope has been overtaken. The implemented system
has two sensors, a maze world and six behaviours; `FUTURE.md` records only work
that is still deferred. Use `MVP.md` and `specs/` for the current scope.

### 7.3 Robot rate vs. sim non-blocking
**Status:** resolved — the sim reuses the last command and never waits for the
robot.

---

## Historical first step (completed)

The original first step was to write the wire protocol spec and scaffold the
three processes. That work is done. The current equivalents are:

- `specs/WIRE_PROTOCOL.md` — envelope, message names, ports and payloads.
- `specs/ROBOT_DESCRIPTION.md` — shared v2 component manifest.
- `simulator/server/src/index.ts` — sim startup and configuration.
- `robot-framework/src/main/java/oobot/robot/RobotController.java` — controller
  startup and behaviour registration.

## Current summary (for orientation)

-   **Sim:** Node/TS, bounded box plus static obstacles from a JSON world file,
    fixed ~60 Hz timestep, best effort under load.
-   **Robot channel:** WebSocket, NDJSON, `componentCommand` in (one frame per
    behaviour tick), `componentSnapshot` out (one reading per declared sensor).
-   **Visualiser channel:** WebSocket, NDJSON, `worldSnapshot` out only, with a
    hardcoded in-browser fallback and full reset on reconnect.
-   **Java controller:** separate process, picocli CLI, fat JAR, framework hides
    sockets; Gradle, OkHttp, Jackson and SLF4J/Logback.
-   **Behaviours:** square, circle, heart, wall-avoid, wall-follow-right
    (default) and brownian.
-   **Safe stop:** sim zeroes actuators on active-robot close/shutdown; Java
    writes a zero command for every wheel before closing/reconnecting.
-   **Dev startup:** `npm install`, `npm run build:robot`, `npm run dev`.

For the decisions that govern the current code, read `docs/DECISIONS.md`.
