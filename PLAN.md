# Plan of Attack

Dependency-ordered plan for building the oobot MVP. Items are grouped into phases;
items within a phase can be done in parallel unless marked as blocking.

## Phase 0 — Setup & specs (no code yet)

### 0.1 Create component directories — COMPLETE
```
simulator/
  server/     # Node.js/TypeScript sim + WebSocket server
  visualiser/ # vanilla TS + canvas browser app
robot-framework/
  # Java framework + example controller (Maven/Gradle)
```
Created directories with README.md in each:
- `simulator/server/README.md`
- `simulator/visualiser/README.md`
- `robot-framework/README.md`

No dependencies. Can be done immediately.

### 0.2 Write wire protocol spec (`specs/WIRE_PROTOCOL.md`) — COMPLETE
Depends on: all decisions in DESIGN_REVIEW.md (now complete).
Defines:
- Two WebSocket ports: ROBOT_PORT and VISUALISER_PORT
- NDJSON framing: one JSON object per newline
- Envelope: `{ type, seq, simTick, payload }`
- Message types:
  - Robot channel: `motorCommand` (robot→sim), `sensorSnapshot` (sim→robot)
  - Visualiser channel: `worldSnapshot` (sim→visualiser); `userControl` deferred
- Sim logical tick as sequence source of truth
- Safe-stop on WebSocket close
**Output:** `specs/WIRE_PROTOCOL.md` created.

### 0.3 Spec out each component for MVP (`specs/COMPONENTS.md`) — COMPLETE
Depends on: wire protocol spec (0.2).
Defines the exact responsibilities and interfaces of:
- Sim server: world model, timestep, collision, publishing
- Visualiser: canvas drawing, snapshot interpolation, WebSocket client
- Java framework: Motor API, connection handling, behaviour update loop
- Example robot: square-trace behaviour
**Output:** `specs/COMPONENTS.md` created — one section per component (inputs,
outputs, key interfaces, out-of-scope items) plus an "interfaces between
components" section summarising connections via the wire protocol.

### 0.4 Spec the comms layer (`specs/COMMS.md`) — COMPLETE
Depends on: wire protocol spec (0.2).
Defines the encapsulated transport interface in the sim so it can be swapped
later (e.g. for physical hardware) without touching robot code. Includes:
- Transport interface (sim side) with WebSocketTransport (MVP) and PhysicalHardwareTransport (deferred)
- WebSocket server setup on two ports: ROBOT_PORT and VISUALISER_PORT from a single Node.js process
- Connection lifecycle (accept, message, close) with safe-stop on robot close
- Publish/subscribe to visualiser clients with drop-oldest backpressure; no blocking, no retry, no buffering
- Robot channel handling: motorCommand stored as latest command (persistent actuator state), sensorSnapshot from single slot
- Error handling contract: malformed JSON logged and ignored, NDJSON partial-read buffering, controller crash → safe stop, visualiser reconnect → accept + latest snapshot
- Future swap to physical hardware: what changes and what stays the same
**Output:** `specs/COMMS.md` created.

No dependencies between 0.2, 0.3, 0.4 other than 0.2 → 0.3 and 0.2 → 0.4.
0.1 can run in parallel with 0.2–0.4.

---

## Phase 1 — Visualiser first (no sim needed yet)

### 1.1 Scaffold visualiser project
Vanilla TypeScript + canvas. Dev server (e.g. vite or plain ts-node + http-server).

### 1.2 Render a hardcoded stream of sim snapshots
The visualiser connects to nothing yet. It reads a hardcoded array of
`worldSnapshot` messages and renders them on a loop, interpolating between
consecutive snapshots.

Goal: prove the visualiser side of the wire protocol and the hold-last-frame
interpolation strategy before the sim exists.

### 1.3 Connect visualiser to live sim endpoint — COMPLETE
The visualiser connects to `ws://localhost:VISUALISER_PORT` (8081) and
renders live `worldSnapshot` messages from the sim with hold-last-frame
interpolation. Falls back to the hardcoded stream when no sim is connected.

Files:
- `simulator/visualiser/src/liveStream.ts` — WebSocket client, NDJSON parsing,
  snapshot buffer, interpolation at latest received simTick, auto-reconnect
- `simulator/visualiser/src/main.ts` — prefers live data; falls back to
  hardcoded stream; logs source per second
- `simulator/visualiser/src/snapshotStream.ts` — header comment updated
  (fallback, not primary)

Dependency: 1.1 → 1.2 → 1.3 (sequential). ✅ All complete.

---

## Phase 2 — Sim server

### 2.1 Scaffold sim server project (Node/TS)
WebSocket server on VISUALISER_PORT and ROBOT_PORT.

### 2.2 World model: bounded box, robot position/heading, no obstacles
Fixed 60 Hz timestep. Applies motor commands to update robot pose. Clamps at
box edges (stop-dead).

### 2.3 Publish `worldSnapshot` on visualiser channel
Every sim tick (or every N ticks), publish latest world state to all connected
visualisers. Drop-oldest backpressure if a client falls behind.

### 2.4 Robot channel: accept connections, handle `motorCommand`, publish `sensorSnapshot`
Store latest command per robot (persistent actuator state). On WebSocket close,
stop motors (safe stop). Publish `sensorSnapshot` (empty/no readings for MVP).

Dependency: 2.1 → 2.2 → 2.3, and 2.1 → 2.4. (2.3 and 2.4 can be parallel
once 2.1 is done, but both need the wire protocol spec.)

---

## Phase 3 — Java robot framework & example controller

### 3.1 Scaffold Java project (fat JAR)
Maven or Gradle. Produces `robot.jar`.

### 3.2 Framework API: Motor + connection
- `Motor.setSpeed(double)` — sends `motorCommand` over WebSocket
- Connection handling hidden from robot code (reconnect, close → safe stop)
- Behaviour update method invoked repeatedly (~20 Hz)

### 3.3 Example robot: square-trace
Move forward one tick, turn one tick, repeat. Reads `sensorSnapshot` (ignored
for MVP).

Dependency: 3.1 → 3.2 → 3.3.

---

## Phase 4 — End-to-end

### 4.1 Dev startup: one `npm run dev`
Starts sim server, visualiser dev server, and example robot controller together.

### 4.2 Verify all MVP success criteria
1. Sim runs independently of browser.
2. Java process connects to sim.
3. Motor commands move the robot.
4. Sensor readings provided (empty for MVP).
5. Controller changes behaviour based on readings (square trace).
6. Visualiser shows behaviour without controlling sim timing.
7. Controller disconnect → robot stops.

---

## Dependency graph summary

```
0.1 dirs          0.2 wire protocol ──┐
                                          ├─ 1.1 visualiser scaffold ── 1.2 hardcoded render ── 1.3 stub socket
0.3 component specs ──┘                    │
                                          └─ 2.1 sim scaffold ── 2.2 world model ── 2.3 publish snapshots
                                                               │                    │
                                                               └────────────────────┴─ 2.4 robot channel
                                                                                                         │
                                                                                       3.1 Java scaffold ── 3.2 framework API ── 3.3 square-trace robot
                                                                                                                                          │
                                                                                                                                          └─ 4.1 npm run dev ── 4.2 verify MVP
```

## Parallel workstreams

- **0.1 (dirs)** can run in parallel with **0.2–0.4 (specs)**.
- **1.2 (hardcoded visualiser)** can be built while **2.x (sim)** is being built — they only need to agree on the wire protocol, which is fixed in Phase 0.
- **3.x (Java)** can be built in parallel with **2.x (sim)** once the wire protocol is fixed — they only need to agree on message formats.

## First thing to actually build

Per your direction: **the visualiser rendering a hardcoded stream of sim snapshots** (Phase 1.2). No sim, no Java, no sockets needed yet — just TypeScript + canvas + a hardcoded snapshot array. This validates the most uncertain piece (the visualiser side of the protocol) first.
