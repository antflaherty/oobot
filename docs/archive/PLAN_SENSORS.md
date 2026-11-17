# Sensor Implementation Plan — Iteration 2

> **Historical iteration-2 plan — superseded by the implemented id-addressed `ProximitySensor` / `componentSnapshot` model.** See [README.md](../../README.md), [SENSORS.md](../../specs/SENSORS.md) and [DECISIONS.md](../DECISIONS.md) for the current design.

Object Oriented Robotics (oobot)

**Status:** plan. Refines `specs/SENSORS.md` into implementation work with
dependencies and parallelism. Sensors are deferred beyond MVP iteration 1.

Goal: ship a single front-mounted proximity sensor so a robot behaviour can
react to what is ahead (obstacles and box edges), end to end:
sim computes → `sensorSnapshot` payload → Java `Sensors.distance()` → behaviour.

---

## 0. Constraints (do not violate)

- Robot code never touches sockets; reads sensors via static `Sensors` API.
- Single-slot sensor model: latest reading + `simTick`, overwritten each tick,
  no queue, no polling.
- `simTick` on every `sensorSnapshot`; staleness judged by `simTick`, never
  wall-clock.
- Sim never blocks on the robot or visualiser; sends are non-blocking.
- One robot only; no `robotId`.
- Java built with Gradle + external libs (Jackson/OkHttp/SLF4J/picocli); TypeScript `strict: true`.
- Sensor visualisation is out of scope (canvas draws `worldSnapshot` only).

---

## 1. Workstreams (can run in parallel)

Three largely independent tracks, plus one integration track:

| Track | Owner | Depends on | Produces |
|---|---|---|---|
| **A. Sim sensor + ray casting** | TypeScript | none (box edges available now) | sensor slot, ray vs box + obstacles, `sensorSnapshot` payload |
| **B. Java framework sensor API** | Java | none (codes to spec) | `Sensors.distance()`, `Framework.lastSensorDistance` parsing |
| **C. World obstacles** | TypeScript + JSON | none | obstacle definitions / world file format (what the ray hits) |
| **D. Example behaviour + integration** | Java | A + B | behaviour using `Sensors.distance()`; end-to-end verification |

Tracks A, B, and C can be implemented in parallel. Track D starts once A and B
are done enough to wire together; C is needed for interesting obstacle readings
but box edges alone make D testable immediately.

---

## 2. Detailed tasks

### Track A — Sim (TypeScript): `simulator/server/src/`

**A1. Ray vs box-edge intersection** — analytic distance from an interior point
along a heading to the nearest world-box edge. Uses existing
`WorldModel.minX/maxX/minY/maxY`. No new state.

**A2. Ray vs obstacle intersection** — ray vs axis-aligned box (obstacle).
Takes obstacle rects from the world model. Returns hit distance or none.

**A3. Sensor slot in `SimEngine`** — one slot `{ distance: number|null, simTick }`,
overwritten each tick in `tick()`. Reads robot pose from `WorldModel`, casts
front ray against obstacles + box edges (nearest hit wins), writes slot.

**A4. `sensorSnapshot` payload** — add `distance: number|null` to the outgoing
envelope payload in `SimEngine.tick()` (already publishes empty `{}` each tick).
Add `SensorSnapshotPayload` type to `types.ts`.

**A5. Wire `worldSnapshot` obstacle payload (deferred decision)** — if the
visualiser is to draw obstacles, `worldSnapshot.payload.obstacles` needs the
obstacle list. This is only needed if we want obstacle rendering; sensor
readings themselves stay on the robot channel. (Can be done in parallel with
A1–A4, but not required for the core sensor loop.)

### Track B — Java framework: `robot-framework/src/main/java/oobot/robot/`

**B1. `Sensors.distance()`** — static method returning `double`; world units to
nearest hit ahead, or `Double.POSITIVE_INFINITY` if nothing detected. Falls back
to `POSITIVE_INFINITY` when `framework == null`.

**B2. `Framework.lastSensorDistance`** — `volatile Double` state, `null` when
nothing detected. Set in `handleIncoming()` when parsing a `sensorSnapshot`
envelope whose payload contains `distance` (number or null). Expose via a
package-private getter for `Sensors`.

**B3. JSON parsing** — `Json.parseEnvelope` / `Envelope` already exist; ensure
the `distance` field (number or null) survives round-trip into the payload so
`Framework` can read it. No protocol change needed beyond reading the field.

### Track C — World obstacles (iteration 2, `specs/WORLD_FORMAT.md`)

**C1. Obstacle shape definition** — axis-aligned boxes (walls): `{ x, y, width,
height }` in world units, centred or corner-based (pick one convention).

**C2. World model ingestion** — `WorldModel` accepts a list of obstacles at
construction (or load). Exposes them to the ray caster in Track A2.

**C3. World file format** — JSON file the sim can load at startup (per
`WORLD_FORMAT.md` iteration 2). Hardcoded fallback for iteration 2a if no file.

**C4. Visualiser obstacle rendering** — `worldSnapshot.payload.obstacles`
(optional, see A5); canvas draws rects. Not required for the sensor to work.

### Track D — Example behaviour + integration

**D1. Sensor-driven behaviour** — e.g. "drive forward until `Sensors.distance() < threshold`, then turn away." Implements `RobotBehaviour`, uses `Mover` + `Sensors.distance()`.

**D2. End-to-end verification** — run sim + robot + visualiser; confirm the robot
reacts to box edges (and obstacles if C is done). Observe `sensorSnapshot`
payloads and behaviour choices.

---

## 3. Dependency graph

```
                          ┌──────────────┐
                          │   Track A    │
                          │  Sim sensor  │
                          │  (ray cast,  │
                          │   slot,      │
                          │   payload)   │
                          └──────┬───────┘
                                 │
                                 ▼
                          ┌──────────────┐
                          │   Track D    │ ◄── Example behaviour + integration
                          │ (behaviour)  │     needs A + B
                          └──────┬───────┘
                                 │
                          ┌──────┴──────┐
                          ▼             ▼
                    ┌─────────┐   ┌─────────┐
                    │ Track B │   │ Track C │
                    │ Java    │   │ Obstacles│
                    │ Sensors │   │ (world  │
                    │ API     │   │  file)  │
                    └─────────┘   └─────────┘
                              │
                              ▼
                       (makes readings interesting)
```

**Key dependencies:**
- **A → D**: behaviour needs real `sensorSnapshot` payloads.
- **B → D**: behaviour needs `Sensors.distance()`.
- **A ↔ B**: both code to the same `sensorSnapshot` payload spec; neither blocks the other (they agree on the contract in `specs/SENSORS.md` §4).
- **C → A2**: obstacle ray-casting needs obstacle geometry; box edges (A1) work without C.
- **C → C4**: visualiser obstacle drawing needs world file / obstacle list.

---

## 4. Parallelism summary

**Can start immediately, in parallel:**
- A1 (ray vs box edges), A3 (sensor slot skeleton), A4 (payload type)
- B1 (`Sensors.distance()`), B2 (`Framework.lastSensorDistance`)
- C1 (obstacle shape convention), C3 (world file format sketch)

**Blocked on A + B:**
- D1 (example behaviour) — needs both sim payload and Java API

**Blocked on C:**
- A2 full obstacle ray-casting (box edges still work without it)
- C4 visualiser obstacle rendering

**Suggested order:**
1. Week 1 (parallel): A1, A3, A4, B1, B2, C1
2. Week 2: A2 (obstacles, once C1 settled), C3, D1
3. Week 3: D2 end-to-end, C4 optional (obstacle rendering)

---

## 5. Definition of done (iteration 2)

- [ ] `sensorSnapshot` carries `{ "distance": number \| null }` on every tick.
- [ ] Sim computes distance to nearest hit among obstacles + box edges each tick.
- [ ] `Sensors.distance()` returns the value from the latest snapshot, or
  `Double.POSITIVE_INFINITY` when nothing detected / not connected.
- [ ] An example behaviour uses `Sensors.distance()` to change movement (e.g.
  turn away when close to a wall).
- [ ] Running `npm run dev` shows the robot reacting to box edges.
- [ ] No test files added (per AGENTS.md: no test files exist yet; keep MVP small).
- [ ] `specs/SENSORS.md` remains the source of truth; this plan tracks tasks only.

---

## 6. Related specs

- `specs/SENSORS.md` — sensor model, wire payload, decisions (source of truth)
- `specs/WORLD_FORMAT.md` — iteration 2 obstacles / world file (Track C)
- `specs/WIRE_PROTOCOL.md` — envelope, `sensorSnapshot`, single-slot model
- `specs/COMMS.md` — transport interface (unchanged)
