# Hardening decisions (D-1 … D-6)

Canonical record for the pre-feature hardening pass. Produced by task `T0.5`;
read-only for all other tasks. Where `AGENTS.md`, root docs or specs disagree
with this file, **this file wins** (per `HARDENING_PLAN.md`, which wins over
`AGENTS.md`). Do not re-decide these items — report objections to the merge-gate
owner instead.

Locked source: the "Decisions locked in" table in `HARDENING_PLAN.md`. This
file expands each decision into the exact constants, formulas and sequences
that implementing agents must use.

---

## D-1 — Kinematics and world scale

**Decision.** Keep the world scale (box is 800 × 600 world units, robot pose in
world units). Introduce a single conversion constant:

```
WORLD_UNITS_PER_METRE = 100
```

Wheel power `p ∈ [-1, 1]` is converted to metres/second before computing
angular velocity. `trackWidth` is the manifest-derived number (`0.5` for the
default manifest) and is used **directly** in the denominator — do not convert
it again. `linear` stays in world units/second so existing travel speeds are
unchanged.

Exact formulas (clamp each wheel power to `[-1, 1]` first):

```
v_l = clamp(p_left,  -1, 1) * maxSpeed / WORLD_UNITS_PER_METRE   // m/s
v_r = clamp(p_right, -1, 1) * maxSpeed / WORLD_UNITS_PER_METRE   // m/s

linear  = (v_l + v_r) / 2 * WORLD_UNITS_PER_METRE                // world units/s
angular = (v_r - v_l) / trackWidth                              // rad/s

heading_new = heading + angular * dt
pose        = integrate(linear, heading midpoint, dt)            // dt = 1/60 s
```

With the defaults `maxSpeed = 100`, `WORLD_UNITS_PER_METRE = 100`,
`trackWidth = 0.5`:

- full differential (`p_left = -1, p_right = +1`) → `v_l = -1`, `v_r = 1`
  m/s → `linear = 0`, `angular = 4 rad/s ≈ 229°/s`;
- half differential (`p_left = -0.5, p_right = +0.5`) → `angular = 2 rad/s`;
- symmetric drive (`p_left = p_right = 1`) → `linear = 100` world units/s,
  `angular = 0`.

**Remove the independent `maxAngularSpeed` clamp** from motion. It may be
deleted from `WorldConfig`/`DEFAULT_WORLD_CONFIG` or documented as unused, but
it must not influence `angular`. `linear` must not be clamped or rescaled.

**Rationale.** The old `maxAngularSpeed = π/2` clamp saturated at ~0.8%
differential, making turns bang-bang and making the manifest-derived track
width irrelevant. This change preserves travel speed and behaviour thresholds
while making turn rate proportional to input.

**Alternatives considered.** (a) Change the world box to real metres — rejected:
would break every behaviour threshold and the visualiser's 800 × 600 canvas.
(b) Raise/remove the clamp but keep the old formula — rejected: without the
metre conversion the angular rate would scale wrongly with `maxSpeed`.
(c) Express track width in metres — rejected: it changes the numeric result
and contradicts the locked 4 rad/s figure.

---

## D-2 — Safe-stop is a two-layer contract

**Decision.** Safe-stop requires **both** layers; each must work independently
of the other.

**Layer A (sim, owns `sim.ts`).** On close of the **active** robot connection,
and on sim shutdown, zero the actuator state (`latestCommand.left = 0`,
`latestCommand.right = 0`) before the next tick uses it. A stale/non-active
robot disconnect must **not** stop the active robot. This is the ultimate
safety net: it does not depend on Java behaving correctly.

**Layer B (Java, owns `WsConnection.java` / `SimRobot.java`, implemented by
`J1.1`).** The controller sends an explicit zero command **before** closing or
reconnecting the socket. The current code sets `open = false` first, so its
"safe stop" frame is dropped — that is the bug being fixed.

**Exact Java sequence (every orderly disconnect, shutdown and reconnect):**

1. Stop the behaviour tick so no further normal command can be written.
2. Build **one** `componentCommand` whose `payload.commands` contains an entry
   for **every bound wheel id** from `ComponentRegistry.wheels()` with
   `"value": 0.0` (not an empty array, not a subset). Use the next unused `seq`
   and the last seen `simTick`.
3. Write and flush that frame while the socket is still open (`open == true`).
   The write must complete before step 4.
4. Only then clear `open`, call `close()` on the WebSocket, and — for a
   reconnect rather than a shutdown — schedule the reconnect.
5. Verify with a fake WebSocket that the **last frame** contains all wheel ids
   at `0.0` and that it was written before `close()` was called.

If the socket is already dead, layer B is best-effort; layer A still stops the
motors. Layer B must never send on a socket it has already marked closed.

**Rationale.** Relying only on the sim leaves a window where a robot that
closes its own socket cannot zero its own persistent actuator state; relying
only on Java fails when the Java process dies. The two layers cover both cases.

**Alternatives considered.** (a) Rely solely on the sim's close handler —
rejected by the plan (D-2 explicitly requires both). (b) Add a heartbeat
timeout — out of scope, deferred beyond the MVP. (c) Send the zero frame after
`close()` — the current broken behaviour, explicitly rejected.

---

## D-3 — Sensor API is fixed-id, and must say so

**Decision.** For this pass the student-facing `Brain` API keeps the fixed
sensor ids `front-sensor` and `right-sensor`:

```
ProximitySensor Brain.frontSensor()   // must resolve id "front-sensor"
ProximitySensor Brain.rightSensor()   // must resolve id "right-sensor"
```

This is a documented constraint, **not** a claim of full genericity. The Java
controller must validate that both ids exist in the loaded manifest and fail
with a clear, actionable message naming the missing id (and the manifest path)
if either is absent. `Brain` Javadoc and the Java README must state the
constraint explicitly. The wire protocol and the rest of the component model
remain id-addressed; only these two accessor ids are fixed.

**Rationale.** Making `Brain` genuinely generic is a feature, not hardening.
Honest documentation plus fail-fast validation removes the false promise
without changing the API.

**Alternatives considered.** (a) Generalise `Brain` to lookup by id —
rejected: feature work. (b) Silently return `null` — rejected: hides
misconfiguration. (c) Leave the "fully generic" claim in docs — rejected: this
is exactly the discrepancy the plan asks us to fix.

---

## D-4 — Sequence and freshness rules on the robot channel

**Decision.** The sim ignores robot commands that are duplicates, too old, or
from the wrong connection. Track this state **per robot connection** and reset
it when a new robot connection becomes active:

```
lastAppliedSeq = -1        // or "none" — no command accepted yet
lastSeenTick   = null      // no accepted command yet
```

Accept a `componentCommand` **only** when all of the following hold, in this
order:

1. `channel === "robot"` **and** `connectionId === activeRobotConnectionId`.
   Anything else is logged and ignored before any state is touched.
2. `seq` and `simTick` are finite, non-negative **integers**. Otherwise log
   and ignore (including `NaN`, `Infinity`, fractional values and negatives).
3. `seq > lastAppliedSeq`. A command with `seq <= lastAppliedSeq` is a
   duplicate/out-of-order command and is ignored.
4. `lastSeenTick === null || simTick >= lastSeenTick - 1`. A command whose
   `simTick` is **more than one tick older** than the last seen tick is
   ignored. `simTick === lastSeenTick - 1` is accepted; a decreasing tick of
   more than 1 is treated as stale, not as a session reset on the sim side.

On acceptance only, update:

```
lastAppliedSeq = seq
lastSeenTick   = max(lastSeenTick ?? simTick, simTick)
```

Rejected commands must not update either value and must not touch actuator
state. A new active robot connection resets both values. The "active robot"
slot is single-occupancy: on a new robot connection, close the previous one;
on disconnect, clear the slot and safe-stop only if the disconnecting
connection is the active one (D-2 layer A).

**Rationale.** `seq` and `simTick` are currently parsed and ignored, so a
replayed or delayed frame can drive actuators. One tick of tolerance allows
benign reordering at the 20 Hz / 60 Hz boundary without rejecting legitimate
commands.

**Alternatives considered.** (a) Trust `seq` alone — rejected: a stale tick
can still arrive with a fresh seq after a reconnect. (b) Reject any decreasing
`simTick` — rejected: one-tick reordering is normal. (c) Track state globally
rather than per connection — rejected: a new robot must start at seq 0 without
being treated as a replay.

---

## D-5 — Visualiser state resets on connect/reconnect

**Decision.** On **every** `onopen` (first connect and every reconnect) the
visualiser clears all derived state before accepting snapshots:

- snapshot buffer → empty;
- `latestTick`, `baseTick`, `baseTime` → cleared/reset;
- `latestObstacles` → cleared;
- the trail callback's accumulated effects → cleared (the renderer's own
  trail is cleared by `V1.1`, which owns `renderer.ts`);
- buffered partial NDJSON text → empty.

A `simTick` that decreases relative to the last applied tick is a **new
session**: perform the same full reset once, then apply the new snapshot.
`stop()` must prevent any further reconnect (clear the reconnect flag before
closing and guard `onclose`) and leave no pending timer.

**Rationale.** Without a reset, a sim restart leaves old snapshots, a stale
interpolation clock and a concatenated trail, so the canvas freezes or draws
duplicated robot paths.

**Alternatives considered.** (a) Reset only on explicit `onclose` — rejected:
a missed close event or a sim restart with the socket still open would leave
state intact. (b) Keep history across sessions — rejected: it produces the
observed frozen/duplicated rendering.

---

## D-6 — Lockfiles and build outputs

**Decision.**

- Commit the three existing lockfiles exactly where they are:
  - `package-lock.json`
  - `simulator/server/package-lock.json`
  - `simulator/visualiser/package-lock.json`
- `.gitignore` must ignore `dist-test/` (added by `T0.4`/`X3.2`).
- `robot.jar` remains a **local build output**, gitignored and not committed
  (the prebuilt copy in the working tree is not to be staged).
- `next.md` is out of scope for every task and must remain untracked and
  unchanged (ground rule 7). Never stage it, never add an ignore rule for it.

**Rationale.** Lockfiles give reproducible installs; `dist-test/` is generated
by the W0 test harnesses; committing the fat JAR would cause binary churn and
conflict with the existing `.gitignore`.

**Alternatives considered.** (a) Ignore lockfiles — rejected: breaks
reproducibility. (b) Commit `robot.jar` — rejected: build output, not source.
(c) Add `next.md` to `.gitignore` — explicitly forbidden by ground rule 7.

---

## Validation fixture set (for `S1.3` and `J1.4`)

Both languages must accept/reject **identically** every fixture below. Fixtures
are for **manifest validation only** — the D-3 fixed-id check on `Brain` is a
separate startup check and must not be folded into these decisions.

**Locations.** `S1.3` creates these under
`simulator/server/test/fixtures/`; `J1.4` copies them **byte-for-byte** into
`robot-framework/src/test/resources/fixtures/` and verifies the hashes.
Filenames are the contract — use them verbatim.

**Base manifest.** All deltas below are relative to this exact content, which
is also fixture `valid-default.json` (a copy of `robots/default.json`):

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

### Accept fixtures

| Filename | Exact content / delta | Expected |
|---|---|---|
| `valid-default.json` | Base manifest above, byte-identical to `robots/default.json` | accept; 4 components; derived track width `0.5` |
| `accept-proximity-sensor-spelling.json` | Base; both sensor entries use exactly `"type": "proximitySensor"` (case-sensitive, no alias) | accept |
| `accept-sensor-without-offset.json` | Base; each sensor entry has only `id`, `type`, `angle` — no `offset` key | accept |
| `accept-alternative-angles.json` | Full content below | accept; `sin(2.0) ≈ 0.9093 > 0.9` so first wheel is left, second right; track width ≈ `0.5456` |

`accept-alternative-angles.json`:

```json
{
  "schemaVersion": 2,
  "name": "alternative-angles",
  "components": [
    { "id": "left-wheel",   "type": "wheel",           "angle":  2.0,  "offset": 0.3 },
    { "id": "right-wheel",  "type": "wheel",           "angle": -2.0,  "offset": 0.3 },
    { "id": "front-sensor", "type": "proximitySensor", "angle":  0.5 },
    { "id": "right-sensor", "type": "proximitySensor", "angle": -1.0 }
  ]
}
```

### Reject fixtures

| Filename | Exact delta from base | Rejected because |
|---|---|---|
| `reject-schema-v1.json` | `"schemaVersion": 1` | schemaVersion must be exactly `2` |
| `reject-schema-float.json` | `"schemaVersion": 2.0` | must be the integer literal `2` |
| `reject-side-field.json` | add `"side": "left"` to the `left-wheel` entry | `side` is forbidden on any component |
| `reject-duplicate-ids.json` | change the second component's `id` to `"left-wheel"` | ids must be unique |
| `reject-blank-id.json` | change the first component's `id` to `"   "` | id must be non-blank after trimming |
| `reject-blank-name.json` | `"name": "   "` | name must be non-blank after trimming |
| `reject-non-finite-angle.json` | first wheel `"angle": 1e999` | angle must be finite (parses to `Infinity`) |
| `reject-wheel-offset-zero.json` | first wheel `"offset": 0` | wheel offset must be `> 0` |
| `reject-wheel-offset-negative.json` | second wheel `"offset": -0.25` | wheel offset must be `> 0` |
| `reject-two-left-wheels.json` | full content below | two wheels with `sin(angle) > 0.9` |
| `reject-no-right-wheel.json` | full content below | no wheel with `sin(angle) < -0.9` |
| `reject-sensor-with-offset.json` | add `"offset": 0.25` to the `front-sensor` entry | sensors must not declare `offset` |

`reject-two-left-wheels.json` (two left, one right, unique ids):

```json
{
  "schemaVersion": 2,
  "name": "two-left-wheels",
  "components": [
    { "id": "left-wheel-a", "type": "wheel", "angle":  1.5707963267948966, "offset": 0.25 },
    { "id": "left-wheel-b", "type": "wheel", "angle":  1.5707963267948966, "offset": 0.25 },
    { "id": "right-wheel",  "type": "wheel", "angle": -1.5707963267948966, "offset": 0.25 },
    { "id": "front-sensor", "type": "proximitySensor", "angle": 0.0 }
  ]
}
```

`reject-no-right-wheel.json` (one left wheel, no right wheel):

```json
{
  "schemaVersion": 2,
  "name": "no-right-wheel",
  "components": [
    { "id": "left-wheel",   "type": "wheel",           "angle":  1.5707963267948966, "offset": 0.25 },
    { "id": "front-sensor", "type": "proximitySensor", "angle":  0.0 },
    { "id": "right-sensor", "type": "proximitySensor", "angle": -1.5707963267948966 }
  ]
}
```

### Validation rules both languages must apply

| Field | Rule |
|---|---|
| `schemaVersion` | integer literal `2` only; `1`, `2.0`, strings and missing values rejected |
| `name` | string, non-blank after `trim()` |
| `components` | array |
| `id` | string, non-blank after `trim()`, unique across the manifest |
| `type` | exactly `wheel` or `proximitySensor` (case-sensitive; no aliases) |
| `angle` | finite number (radians; `0` = forward, `+` = CCW) |
| `wheel.offset` | when present, finite and `> 0`; when absent, the existing default `0.25` applies |
| `proximitySensor.offset` | forbidden — reject if the key is present |
| `side` | forbidden on any component — reject if the key is present |
| wheel roles | exactly one with `sin(angle) > 0.9` (left) and exactly one with `sin(angle) < -0.9` (right); all other angles rejected |

**Gotcha for `S1.3`:** `JSON.parse("2.0")` yields the number `2` in JavaScript,
so a plain `=== 2` check will **accept** `reject-schema-float.json`. TS must
inspect the raw `schemaVersion` token (or parse with a literal-preserving
reader) to distinguish `2` from `2.0`.

**Error-message contract.** Error strings need not be byte-identical between
languages, but both must include:

1. the manifest path as supplied to the loader (`loadRobotDescription(path)` /
   `RobotDescription.load(Path)`), and
2. for any component-scoped failure, the 0-based array index formatted as
   `component[<i>]` (e.g. `component[0]: angle must be finite`).

Manifest-level failures (bad `schemaVersion`, blank `name`, wrong wheel roles)
must still name the path; a component index is required only when the failure
is attributable to a specific entry. `J1.4`'s parity test should assert these
two tokens, and the fixture files themselves must be hash-identical across the
two fixture directories.

**POST-HARDENING AMENDMENT (feature work after W0–W4; D-1…D-6 unchanged).**
`robots/default.json` now includes the optional top-level `body` block, and
fixture `valid-default.json` is a byte-identical copy of that new manifest.
Four body-validation fixtures were added to both fixture directories:
`accept-body.json`, `reject-body-width-zero.json`,
`reject-body-wheels-non-finite.json` and `reject-body-not-object.json`. The
`body` rules and defaults are specified in `specs/ROBOT_DESCRIPTION.md`; no
locked decision or value above is changed.

---

## Exact safe-stop / stale-command values at a glance

| Item | Value / rule |
|---|---|
| Java zero command | one `componentCommand`, one entry per bound wheel id, `value: 0.0`, written before `open = false` and before `close()` |
| Sim safe stop | `latestCommand = { left: 0, right: 0 }` on active-robot close and on shutdown |
| Duplicate rule | ignore when `seq <= lastAppliedSeq` |
| Tick rule | ignore when `lastSeenTick !== null && simTick < lastSeenTick - 1` |
| Accepted-seq update | `lastAppliedSeq = seq` only on acceptance |
| Accepted-tick update | `lastSeenTick = max(lastSeenTick ?? simTick, simTick)` only on acceptance |
| `seq`/`simTick` validation | finite, non-negative integers; otherwise reject |
| Active connection | single robot slot; reject commands from other connections/channels |
