# Documentation audit

Produced by task `T0.5`; read-only for all other tasks. This is the work list
for the W3 documentation rewrite (`D3.a`, `D3.b`, `D3.c`) and for `V1.1` where
noted. The current code is the source of truth; this table records where docs
disagree with it. `docs/DECISIONS.md` is the source of truth for the locked
hardening decisions.

**Status key**

- `current` — accurate enough to use as-is; no rewrite required.
- `stale` — contains claims that are false for the current code; must be
  rewritten or explicitly marked historical.
- `abandoned` — a historical plan for a superseded iteration; archive with a
  "historical" header rather than treat as current.

**Ground rule 7:** `next.md` is the user's personal notes and is intentionally
not part of this audit — it was not read, and must remain untracked and
untouched.

---

## Audit table

| Doc | Status | Concrete contradiction with current code | Fix task |
|---|---|---|---|
| `README.md` | stale | Diagram and prose use `motorCommand` / `sensorSnapshot` (now `componentCommand` / `componentSnapshot`); says snapshots have "empty payload in iteration 1 — no readings yet" and success criterion 5 uses `Sensors.latestSimTick()` (static `Sensors` was deleted); layout lists only 5 behaviours (actually 6, incl. brownian) and omits `sensor.ts`, `worlds/maze.json`, `robots/default.json`, `docs/`; individual robot command lacks `--robot`/`--behaviour`. | `D3.a` |
| `ARCHITECTURE.md` | stale | "World model (MVP iteration 1): bounded box with no obstacles … obstacles deferred to iteration 2" while `world.ts` loads `worlds/maze.json` obstacles; "no sensors for iteration 1" contradicts two `ProximitySensor` components; robot channel sends `motorCommand` / `sensorSnapshot`; first example described as square trace with sensors deferred (default is wall-follow-right); visualiser "can eventually send `userControl`" while channel is documented as unidirectional; monorepo outline omits `sensor.ts`, manifest, maze and docs. | `D3.a` |
| `MVP.md` | stale | "no obstacles" in scope; success criteria 4/5 use `sensorSnapshot` with `front`/`right` and deleted `Sensors.distance()` / `Sensors.rightDistance()`; example list omits circle/heart/brownian and manifest-driven sensors; "iteration 1" framing contradicts implemented iteration-2 features. | `D3.a` |
| `FUTURE.md` | stale | Lists already-implemented work as future: distance sensors + wall-following, static obstacles and a JSON world file, reconnection/resume after socket close, multiple simultaneous visualisers, achieved-Hz metrics. Needs those items removed or moved to "done". | `D3.a` |
| `DESIGN_REVIEW.md` | stale | Historical review presented as current: §6.2 "no world file needed at all", §5.1 "sensors were deferred for the very first iteration", §7.2 "no sensors, no obstacles", "Suggested First Step" and "All Decisions Summary" still describe iteration-1 `motorCommand`/`sensorSnapshot` and a square-trace first robot. Must be labelled historical/superseded and cross-linked to `docs/DECISIONS.md`. | `D3.a` |
| `PLAN.md` | abandoned | Iteration-1 implementation plan: `motorCommand`/`sensorSnapshot`, `Motor.setSpeed`, square-trace-only robot, "no obstacles", `sensorSnapshot` payload `front`/`right`. All phases marked COMPLETE against a superseded design. | `D3.c` (move to `docs/archive/` with historical header) |
| `PLAN_SENSORS.md` | abandoned | Iteration-2 plan for a **single** front sensor via static `Sensors.distance()` and `sensorSnapshot` `{distance}`; code has id-addressed `componentSnapshot`, `ProximitySensor` and manifest-driven angles. Definition-of-done checklist and dependency graph are obsolete. | `D3.c` (move to `docs/archive/` with historical header) |
| `AGENTS.md` | stale | "MVP" overview omits `sensor.ts`, `worlds/maze.json`, `Robots` manifest consumers' current shape, `BrownianMotionBehaviour.java`, `RobotPlatform.java`; says no test files exist; claims default behaviour `WallFollowRightBehaviour` in one place but lists `SquareTraceBehaviour` as default example; `Wheel.angle()` threshold described loosely; no mention of fixed `front-sensor`/`right-sensor` Brain constraint (D-3), safe-stop two-layer contract (D-2) or D-1 kinematics formula. | `D3.c` (**preserve user's in-flight edits**) |
| `robot-framework/MODERNISATION.md` | stale | Progress log names deleted types (`SensorSnapshotMessage`, `MotorPayload`) and old message names (`motorCommand`, `sensorSnapshot`) in "done & verified" checks; verification checklist marks network smoke tests `[x]` although no automated tests exist in W0 — must be marked manual/ad-hoc where appropriate (`X3.2` scope). | `D3.c` |
| `HARDENING_PLAN.md` | current | No contradiction — authoritative scope, ownership and decisions for this pass. | none (do not edit) |
| `docs/DECISIONS.md` | current | Created by `T0.5`; expands D-1..D-6, fixture set and safe-stop/stale-command rules. | none (read-only after W0) |
| `docs/DOC_AUDIT.md` | current | Created by `T0.5`; this file. | none (read-only after W0) |
| `specs/WIRE_PROTOCOL.md` | stale | §6.2 `worldSnapshot` payload lists only `robot.{x,y,heading}`, but `sim.ts` publishes `payload.obstacles`; §4–5 have no `seq`/`simTick` validation or D-4 stale-command rule; §7 documents only sim-side safe stop, not Java's zero-before-close (D-2); §10 still lists world file and obstacles as "iteration 2"; `userControl` described as deferred on a "primarily unidirectional" channel. | `D3.b` |
| `specs/COMMS.md` | stale | Whole document speaks `motorCommand` / `sensorSnapshot` (incl. §5.1, §8.2, §9 sequence diagrams); `OnMessage` signature lacks channel, which `S1.2` must add; "sim never imports a concrete socket library" and hand-rolled pseudocode no longer describe `transport.ts`; claims `sensorSnapshot` has an empty placeholder payload; no seq ownership or stale-command rule; "different transports" rationale overstated. | `D3.b` |
| `specs/COMPONENTS.md` | stale | Iteration-1 component spec throughout: `motorCommand` `{left,right}`, empty `sensorSnapshot` placeholder, `Motor.setSpeed`, static `Sensors`, "no obstacles in iteration 1", square-trace-only example, "one sensor" framing, no manifest, no test harnesses. | `D3.b` |
| `specs/ROBOT_DESCRIPTION.md` | stale | Conventions table claims "Java API fully generic — no `leftWheel()`, `rightWheel()`, `Sensors`", but `RobotController` hardcodes `front-sensor`/`right-sensor` ids (D-3); validation table is not aligned with the T0.5 fixture set (no explicit `2.0`, blank/duplicate id, blank name, non-finite angle, two-left/no-right, sensor-offset cases); "other unknown extra fields ignored" needs reconciling with `side` rejection; wheel-offset default/required rule ambiguous. | `D3.b` (align with `docs/DECISIONS.md` fixtures) |
| `specs/ROBOT_LAUNCH.md` | stale | Launch uses only `--host`/`--port`, but code accepts `--robot`, `--behaviour`, `--help`, `--version`; says default behaviour `SquareTraceBehaviour` (code default `wall-follow-right`); "hand-rolled WebSocket client" contradicts OkHttp; `motorCommand`/`sensorSnapshot` names obsolete; "best-effort zero-speed command sent on close" describes the currently broken no-op rather than D-2's zero-before-close sequence; no mention of startup validation / exit code 2. | `D3.b` |
| `specs/SENSORS.md` | stale | Describes `sensorSnapshot` payload `{front, right}` and a static `Sensors` facade with `distance()` / `rightDistance()` — both removed; says "iteration 2: single front-mounted sensor" while two manifest-declared sensors exist; §6.2 "no new DI mechanism" contradicts current `Brain`/`DefaultBrain` wiring; §4.1 "motorCommand payload unchanged"; sensor mount "fixed facing forward at robot centre" contradicts manifest `angle`. | `D3.b` |
| `specs/WORLD_FORMAT.md` | stale | Status header says "deferred to iteration 2 … intentionally unimplemented in iteration 1" although `loadWorldFile` and `worlds/maze.json` are live; "Iteration 1 (current)" section describes the implemented world; no spawn validation (S1.1), no `--world` flag and no world rollback handling (D-5). | `D3.b` |
| `simulator/server/README.md` | stale | Responsibilities list `motorCommand` and "`sensorSnapshot` (empty payload for iteration 1)"; omits manifest loading, obstacle/ray sensing, id-addressed messages, `--world`, seq/freshness checks and the two-layer safe stop (D-2). | `D3.c` |
| `simulator/visualiser/README.md` | current | No direct contradiction: "renders the bounded box, robot and obstacles", "`worldSnapshot` + interpolation", "does not drive sim timing" match code. Minor gap: does not mention the hardcoded fallback stream or reconnect/reset behaviour. | `D3.c` (optional minor update) |
| `robot-framework/README.md` | stale | `--robot` default shown as `robots/default.json` even though that path is wrong relative to the JAR's CWD; `Wheel.angle()` comment says left if `sin > 0`, code uses `sin > 0.9`; "On WebSocket close: motors are safe-stopped (zero speed sent before reconnect)" is false today (D-2); fixed `front-sensor`/`right-sensor` Brain ids (D-3) not documented; behaviour list and logging details need final check. | `D3.c` (**preserve user's in-flight edits**) |
| `simulator/visualiser/index.html` | stale | Heading says "hardcoded snapshot stream" and hint says "No WebSocket, no sim", but `main.ts` always opens a WebSocket and uses the hardcoded stream only as a fallback. | `V1.1` (see coordination note below) |

---

## Coordination notes

1. `simulator/visualiser/index.html` is owned by **`V1.1`** in the plan's File
   ownership table, not by `D3.a`/`D3.b`/`D3.c`. `T0.5`'s step 2 lists only
   `D3.*` as fixer columns; the ownership table is authoritative, so the audit
   records `V1.1` and leaves the file untouched by this task.
2. `HARDENING_PLAN.md`, `docs/DECISIONS.md` and `docs/DOC_AUDIT.md` are listed
   for completeness but have no `D3.*` owner. `D3.c` only needs to add a
   "Current work" pointer to the plan from `AGENTS.md`.
3. No contradiction found that requires changing code; all stale claims can be
   fixed by documentation tasks without touching source files.
