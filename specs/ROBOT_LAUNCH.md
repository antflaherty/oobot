# Robot Launch Specification

Object Oriented Robotics — MVP

**Status:** accepted (see `DESIGN_REVIEW.md` items 6.1, 7.2 and `ARCHITECTURE.md` §Robot controller / Launch and configuration).

## Purpose

Defines how the Java robot controller process is started and how it connects to the sim's robot channel.

## Launch command

```bash
java -jar robot.jar --host localhost --port ROBOT_PORT
```

## Arguments

| Argument | Default | Meaning |
|----------|---------|---------|
| `--host` | `localhost` | Sim host (hardcoded to localhost for MVP; no discovery). |
| `--port` | `8080` | Robot channel WebSocket port (`ROBOT_PORT`). |

## Defaults

- Host: `localhost`
- Port: `8080`

These match the sim server defaults (`ROBOT_PORT=8080`, `VISUALISER_PORT=8081` in `simulator/server/src/index.ts`).

## Behaviour

On startup the controller:

1. Parses `--host` and `--port` from the command line.
2. Instantiates the configured `RobotBehaviour` (default: `SquareTraceBehaviour`).
3. Starts the `Framework`, which:
   - Connects to `ws://<host>:<port>` (robot channel) via a hand-rolled WebSocket client.
   - Invokes `RobotBehaviour.init()` once, then `RobotBehaviour.update()` on a ~20 Hz behaviour loop.
   - Sends `motorCommand` NDJSON messages as `Motor.setSpeed(...)` is called.
   - Parses incoming `sensorSnapshot` NDJSON messages (empty payload in iteration 1).
4. On WebSocket close (robot disconnects or sim shuts down): best-effort zero-speed command is sent; the sim performs the actual motor stopping.
5. On process shutdown (SIGINT/SIGTERM): shuts down the behaviour loop and closes the WebSocket.

## Out of scope for MVP

- Config file; environment variables as an alternative to CLI args.
- Discovery service (the robot is told the sim's host/port explicitly).
- Automatic port selection / finding a free port.
- Multiple robots / per-robot launch configuration.
