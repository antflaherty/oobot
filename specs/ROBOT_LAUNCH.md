# Robot Launch Specification

Object Oriented Robotics — MVP

**Status:** current. Describes the picocli-based `RobotController` entry point
(`robot-framework/src/main/java/oobot/robot/RobotController.java`),
`specs/COMPONENTS.md` and `docs/DECISIONS.md` D-2/D-3.

## Purpose

Defines how the Java robot controller process is launched, validated and wired
to the sim's robot channel.

## Launch command

From the `robot-framework/` directory (after `npm run build:robot` has produced
`robot.jar`):

```bash
java -jar robot.jar --host localhost --port 8080 \
  --robot ../robots/default.json --behaviour wall-follow-right
```

`--behaviour wall-follow-right` is the default and may be omitted. Goal arrival
is owned by the brain: a fresh goal-sensor "inside" reading latches the robot
stopped with the front light ON, whatever behaviour is running; stale or missing
goal data never lights it. From the repo root, the recommended command is
`npm run dev:robot`; `scripts/runRobot.js` starts the same JAR with an
**absolute** `--robot` path derived from its own location, so it works from any
working directory, and uses `--behaviour wall-follow-right` by default
(`ROBOT_BEHAVIOUR` in `scripts/dev.js` overrides it). To run a different
behaviour, add e.g. `--behaviour square` or `--behaviour brownian` (extra args
are forwarded after the script's own `--robot` argument).

## Arguments

| Argument | Default | Meaning |
|---|---|---|
| `--host` | `localhost` | Sim host (no discovery). |
| `--port` | `8080` | Robot channel port (`ROBOT_PORT` on the sim). |
| `--robot` | `../robots/default.json` | v2 component manifest. The default is resolved relative to the framework directory (normally `robot-framework/`, the JAR's parent) so standalone launches work from any CWD; an explicit path is used as given (resolved to absolute). |
| `--behaviour` | `wall-follow-right` | One of `wall-follow-right`, `square`, `wall-avoid`, `circle`, `heart`, `brownian`. `wall-follow-right` keeps its existing front-light rule; the brain owns goal arrival. |
| `--help`, `--version` | — | Provided by picocli `mixinStandardHelpOptions`. |

The sim mirrors `--robot` and also accepts `--world <path>`; its ports come
from `ROBOT_PORT` / `VISUALISER_PORT` (integers 1–65535). `scripts/dev.js`
validates `ROBOT_PORT`, probes `127.0.0.1` on it, then starts the visualiser and
the robot with an explicit host, port, behaviour and manifest path.

## Startup validation

All validation happens before any socket is opened or thread started. Failures
are wrapped in `CommandLine.ParameterException`, so picocli prints usage and
the process exits with code **2** — no stack trace and no half-started robot.

1. The behaviour name must be in the seven-value set above.
2. The manifest must be a regular file; a missing default names the resolved
   path and explains `--robot`.
3. `RobotDescription.load(Path)` must accept the v2 manifest (same rules as
   the TypeScript loader; see `specs/ROBOT_DESCRIPTION.md`).
4. The manifest must declare `proximitySensor` components with ids
   `front-sensor` and `right-sensor` and a `goalSensor` component with id
   `goal-sensor` (D-3 and the goal-feature amendment). A missing id or wrong
   component type produces an error naming the id, the accessor that needs it
   and the manifest path.
5. The bound tool components are resolved by type for `Brain.tool()`: zero
   tools injects `Tool.NOOP`, exactly one injects that handle, and more than one
   is a usage error naming the manifest and the tool count.

## Behaviour

On a valid launch the controller:

1. Loads the manifest and creates `SimRobot(host, port, description)`, which
   binds `SimWheel` / `SimProximitySensor` / `SimGoalSensor` / `SimTool` handles
   by id into a `ComponentRegistry` and starts its reconnect thread.
2. Builds `WheeledMover` from `ComponentRegistry.wheels()` (sorted by mount
   angle; left/right derived from `sin(angle)`), resolves the two fixed-id
   proximity sensors, the fixed-id goal sensor (`goal-sensor`) and the single
   tool (`Tool.NOOP` when none is declared), creates the selected behaviour
   (`WallFollowRightBehaviour` by default) and wraps them in `DefaultBrain`.
3. Registers a JVM shutdown hook that calls `platform.stop()`, then starts a
   `BehaviourLoop` that calls `Brain.tick()` every 50 ms (~20 Hz). Each tick is
   followed by `SimRobot.flushCommands()`.
4. Connects with OkHttp via `WsConnection` to `ws://<host>:<port>`. Inbound
   NDJSON is buffered across frames; malformed lines are ignored; inbound
   `componentCommand` messages are explicitly discarded. On each open the seq
   counter restarts at 0 and sensor state is invalidated; after a reconnect the
   persistent tool state is force-resynced on the next flush.
5. Batches all wheel and tool values changed during a tick into **one**
   `componentCommand` frame with the last seen `simTick` (J2.1); tool `value`
   is exactly `1.0` (active) or `0.0` (inactive) alongside the wheel entries.
   The sim gates these frames by channel/connection, `seq` and `simTick`
   freshness (D-4, `WIRE_PROTOCOL.md` §5.1) and ignores non-`0`/`1` tool values.
6. Updates proximity and goal sensor handles from `componentSnapshot.readings`.
   A `goal-sensor` reading is `1` (robot centre inside the goal), `0` (outside)
   or `null` (world has no goal); it maps to `Sensor<Boolean>.value()` = `true`
   only for `1`, with `false` for `null`/`0`/missing/stale data. A tick rollback
   starts a fresh session with `value() == Infinity` for proximity sensors,
   `value() == false` for the goal sensor and `latestSimTick() == 0` for both.
7. Reconnects after ~2 s when the socket closes, unless stopped.

By default the selected behaviour is `wall-follow-right`; it keeps its
front-light rule while seeking. Goal arrival is owned by `DefaultBrain`: a fresh
`goalSensor().value() == true` latches the robot stopped with the front light ON
(`Tool.activate()`) and stops driving the behaviour; stale/no-goal data never
turns it on. Any other behaviour gets the same brain-level arrival rule.

## Safe stop (D-2)

On every orderly disconnect, shutdown or reconnect the bridge:

1. stops the behaviour tick;
2. writes one **wheels-only** `componentCommand` containing **every** bound
   wheel id with `value: 0.0`, using the next unused seq and the last seen
   `simTick`; tools keep their local state and are not in this frame;
3. writes/flushes it while `WsConnection.isOpen()` is still true
   (`closeWithFinalFrame`), then clears `open` and calls `close()`;
4. only then schedules any reconnect; the next open force-resyncs tool state.

If the socket is already dead this is best-effort. Independently, the sim zeros
its actuators and clears all tool states when the active robot socket closes and
on shutdown (layer A). There is no heartbeat/timeout for hung-but-connected
processes.

## Out of scope

- Config files or environment variables as an alternative to the robot's CLI
  arguments (the dev script reads `ROBOT_PORT` only to forward it as `--port`).
- Discovery, automatic port selection, multiple robots, per-robot launch
  profiles, untrusted/user-uploaded behaviours.
