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

From the repo root, the recommended command is `npm run dev:robot`;
`scripts/runRobot.js` starts the same JAR with an **absolute** `--robot` path
derived from its own location, so it works from any working directory. To run a
different behaviour, add e.g. `--behaviour brownian` (extra args are forwarded
after the script's own `--robot` argument).

## Arguments

| Argument | Default | Meaning |
|---|---|---|
| `--host` | `localhost` | Sim host (no discovery). |
| `--port` | `8080` | Robot channel port (`ROBOT_PORT` on the sim). |
| `--robot` | `../robots/default.json` | v2 component manifest. The default is resolved relative to the framework directory (normally `robot-framework/`, the JAR's parent) so standalone launches work from any CWD; an explicit path is used as given (resolved to absolute). |
| `--behaviour` | `wall-follow-right` | One of `square`, `wall-avoid`, `wall-follow-right`, `circle`, `heart`, `brownian`. |
| `--help`, `--version` | — | Provided by picocli `mixinStandardHelpOptions`. |

The sim mirrors `--robot` and also accepts `--world <path>`; its ports come
from `ROBOT_PORT` / `VISUALISER_PORT` (integers 1–65535). `scripts/dev.js`
validates `ROBOT_PORT`, probes `127.0.0.1` on it, then starts the visualiser and
the robot with an explicit host, port, behaviour and manifest path.

## Startup validation

All validation happens before any socket is opened or thread started. Failures
are wrapped in `CommandLine.ParameterException`, so picocli prints usage and
the process exits with code **2** — no stack trace and no half-started robot.

1. The behaviour name must be in the six-value set above.
2. The manifest must be a regular file; a missing default names the resolved
   path and explains `--robot`.
3. `RobotDescription.load(Path)` must accept the v2 manifest (same rules as
   the TypeScript loader; see `specs/ROBOT_DESCRIPTION.md`).
4. The manifest must declare `proximitySensor` components with ids
   `front-sensor` and `right-sensor` (D-3). A missing id produces an error
   naming the id, the accessor that needs it and the manifest path.
5. The bound tool components are resolved by type for `Brain.tool()`: zero
   tools injects `Tool.NOOP`, exactly one injects that handle, and more than one
   is a usage error naming the manifest and the tool count.

## Behaviour

On a valid launch the controller:

1. Loads the manifest and creates `SimRobot(host, port, description)`, which
   binds `SimWheel` / `SimProximitySensor` / `SimTool` handles by id into a
   `ComponentRegistry` and starts its reconnect thread.
2. Builds `WheeledMover` from `ComponentRegistry.wheels()` (sorted by mount
   angle; left/right derived from `sin(angle)`), resolves the two fixed-id
   sensors and the single tool (`Tool.NOOP` when none is declared), creates the
   selected behaviour and wraps them in `DefaultBrain`.
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
6. Updates sensor handles from `componentSnapshot.readings`; a tick rollback
   starts a fresh session with `distance() == Infinity` and `latestSimTick() == 0`.
7. Reconnects after ~2 s when the socket closes, unless stopped.

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
