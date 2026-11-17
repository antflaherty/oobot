# simulator/server

Node.js + TypeScript simulation engine and WebSocket server.

## Responsibilities

- Loads and validates the v2 component manifest (`--robot`, default
  `robots/default.json`) and the obstacle world (`--world`, default
  `simulator/server/worlds/maze.json`) at startup.
- Owns the bounded 800 × 600 world, robot pose and static axis-aligned box
  obstacles; advances on a fixed ~60 Hz timestep.
- Applies id-addressed `componentCommand`s from the single active robot
  connection: validates `seq`/`simTick`, drops duplicate/stale commands (D-4),
  clamps wheel powers to `[-1, 1]` and runs differential-drive kinematics with
  `WORLD_UNITS_PER_METRE = 100` (D-1).
- Casts a ray per declared `proximitySensor` along `pose.heading + angle` against
  box edges and obstacles (nearest hit wins), and publishes one
  `componentSnapshot` per tick with one reading per sensor id (`null` = no hit).
- Publishes `worldSnapshot` (`robot: {x, y, heading}` and `obstacles`) to the
  visualiser channel. Sends are non-blocking with per-connection depth-1
  drop-oldest backpressure; outgoing `seq` is assigned per connection.
- Safe-stops motors (D-2 layer A) when the **active** robot socket closes and on
  shutdown. A stale robot disconnect never stops the active robot.
- Caps inbound NDJSON line length and bounds shutdown so a stuck client cannot
  stall exit.

## Run

From the repo root: `npm run dev:server`. From this directory: `npm run dev`
(ts-node). Defaults resolve from this package, so standalone commands work from
any CWD; override with `--robot <path>` / `--world <path>`. Ports default to
8080 (robot) and 8081 (visualiser); `ROBOT_PORT` / `VISUALISER_PORT` override
them and must be integers in 1–65535.

## Build and test

```bash
npm test          # npm run build:test (tsc → dist-test/) + node --test
npm run typecheck # tsc --noEmit
npm run build     # tsc → dist/
```

Tests in `test/**` cover world physics, the wire protocol/ownership rules and
manifest validation against the JSON fixtures in `test/fixtures/`.

See `specs/WIRE_PROTOCOL.md`, `specs/WORLD_FORMAT.md` and `docs/DECISIONS.md`
for the contract and the locked hardening decisions.
