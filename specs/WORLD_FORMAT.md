# World Format Specification

Object Oriented Robotics — MVP

**Status:** implemented. The world file is loaded by the sim server at startup
(`simulator/server/src/index.ts`, `world.ts`); the older "deferred to iteration
2" status no longer applies. Obstacles and start pose are live and are included
in every `worldSnapshot` (`specs/WIRE_PROTOCOL.md` §6.2).

## Purpose

Defines how the simulated world is described: bounds, static obstacles and the
robot start pose. The sim also supplies these obstacles to proximity-sensor ray
casting and to the visualiser.

## Loading and selection

- Default file: `simulator/server/worlds/maze.json`, resolved from the server
  source directory so standalone launches work from any CWD.
- `--world <path>` overrides it (explicit paths are resolved from the working
  directory). The chosen path is logged at startup.
- A missing, unreadable or invalid file is **not** fatal: the server logs a
  warning naming the path and reason, then falls back to the default box with
  no obstacles and start pose `(0, 0, heading 0)`.
- An invalid component manifest, by contrast, is fatal (see
  `specs/ROBOT_DESCRIPTION.md`).

## Current world format

The world is a bounded 2D box centred at the origin. Default extents are 800 ×
600 world units (`x ∈ [-400, 400]`, `y ∈ [-300, 300]`); `width` and `height`
in the file override those two values while other world config fields keep
their defaults. The box is a wall: position is clamped at its edges
(stop-dead collision).

World file JSON (the shipped default, `worlds/maze.json`):

```json
{
  "width": 800,
  "height": 600,
  "robot": { "x": -340, "y": -240, "heading": 0 },
  "obstacles": [
    { "x": -60,  "y": -160, "width": 680, "height": 20 },
    { "x": 60,   "y":   0,  "width": 680, "height": 20 },
    { "x": -60,  "y": 160,  "width": 680, "height": 20 },
    { "x": -300, "y": -80,  "width": 20,  "height": 100 },
    { "x": 0,    "y": -80,  "width": 60,  "height": 60 }
  ]
}
```

| Field | Type | Requirement |
|---|---|---|
| `width`, `height` | number | Finite and > 0; box spans `[-width/2, width/2] × [-height/2, height/2]`. |
| `robot` | `{ x, y, heading }` | All finite numbers; heading in radians, 0 = +X axis. |
| `obstacles` | `Obstacle[]` | Optional; may be empty. Must be an array when present. |

Each obstacle is `{ x, y, width, height }` with **centre-based** semantics:
`(x, y)` is the centre and `width`/`height` are the full extents, so the box
spans `[x - width/2, x + width/2] × [y - height/2, y + height/2]`. Coordinates
must be finite and `width`/`height` > 0.

### Validation rules

`parseWorld(raw, filePath)` rejects invalid input with a message naming the
file path. A start pose inside an obstacle also names `obstacle[<i>]`.
Obstacles are parsed **before** the start pose so the pose can be checked
against them:

- start pose outside the box is rejected (bounds are inclusive);
- start pose strictly inside an obstacle is rejected; a point exactly on a
  face is allowed (that is where stop-dead contact leaves the robot).

The sim constructs `WorldModel` with the parsed config, start pose and
obstacles. `WorldModel.trackWidth` is then overridden by the value derived from
the robot manifest (D-1); world files do not set track width.

## Obstacle use

- Motion: a ray from the current pose toward the intended next pose detects
  the nearest obstacle contact and stops translation there (no penetration,
  no sliding, no bouncing); rotation continues. Box clamping sets the same
  `collided` flag.
- Sensing: each proximity sensor casts along `heading + angle` and reports the
  nearest hit among obstacles and box edges (`specs/SENSORS.md`).
- Publishing: `worldSnapshot.payload.obstacles` is the static obstacle array,
  sent to every visualiser each tick.

## Session changes

The world itself does not roll back. If the sim restarts, a decreasing
`simTick` is treated as a new session by clients: the visualiser clears its
snapshot buffer, obstacles and trail (D-5), and the Java bridge invalidates
sensor readings and resets its seq counter (D-2/D-4).

## Out of scope

- Moving obstacles, complex/rotated shapes, sliding or bouncing.
- Multiple worlds, world-selection UI, runtime world switching.
- A world file changing max speed, tick rate or track width (those come from
  sim defaults / the manifest).
