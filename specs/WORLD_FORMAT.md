# World Format Specification

Object Oriented Robotics — MVP

**Status:** deferred to iteration 2 (see `DESIGN_REVIEW.md` item 6.2 and `ARCHITECTURE.md` §World model).

## Purpose

Defines how the simulated world is described: its bounds, obstacles, and robot start pose. Documented now so the format is settled before iteration 2, but intentionally unimplemented in iteration 1.

## Iteration 1 (current)

The world is a **bounded 2D box with no obstacles**. All world parameters are hardcoded in the sim:

| Parameter | Value | Source |
|-----------|-------|--------|
| Width | 800 world units | `simulator/server/src/world.ts` `DEFAULT_WORLD_CONFIG.width` |
| Height | 600 world units | `simulator/server/src/world.ts` `DEFAULT_WORLD_CONFIG.height` |
| Obstacles | none | iteration 1 world is empty |
| Robot start pose | `(0, 0, heading=0)` | `DEFAULT_START_POSE` |

The box is centred at the origin: `x ∈ [-400, 400]`, `y ∈ [-300, 300]`. Collision response at the box edges is stop-dead (position clamped).

No world file exists and none is needed for iteration 1.

## Iteration 2 (this spec)

A JSON world file describes the bounded box plus a static set of axis-aligned
box obstacles. Format (obstacles are **centre-based**):

```json
{
  "width": 800,
  "height": 600,
  "robot": { "x": 0, "y": 0, "heading": 0 },
  "obstacles": [
    { "x": 100, "y": 0, "width": 40, "height": 200 },
    { "x": -200, "y": 100, "width": 120, "height": 40 }
  ]
}
```

| Field | Type | Meaning |
|-------|------|---------|
| width | number | World box X extent (world units). Box is `x ∈ [-width/2, width/2]`. |
| height | number | World box Y extent (world units). Box is `y ∈ [-height/2, height/2]`. |
| robot | `{x, y, heading}` | Robot start pose; heading in radians, 0 = +X axis. |
| obstacles | `Obstacle[]` | Static axis-aligned box obstacles (may be empty or omitted). |

Each obstacle is `{ x, y, width, height }` where `x, y` is the **centre** of the
box and `width`/`height` are its full dimensions, so the box spans
`[x - width/2, x + width/2] × [y - height/2, y + height/2]`. This matches
`simulator/server/src/world.ts` (`Obstacle` interface, centre-based convention)
and `DEFAULT_WORLD_CONFIG` (box centred at the origin).

Obstacles should lie inside the world box; the sim clamps the robot at the box
edges regardless, and the front proximity sensor reports the nearest hit among
obstacles and box edges.

The world model accepts obstacles programmatically at construction
(`WorldModel` constructor, `public readonly obstacles`) and exposes
`nearestObstacleHit(ox, oy, heading)` plus the exported
`rayVsAxisAlignedBox(...)` helper for ray-vs-obstacle intersection. Loading and
parsing the JSON file itself is the sim's responsibility (`sim.ts`) and is wired
in separately from this format definition.

## Out of scope for MVP

- Moving obstacles, sliding, bouncing (future iterations).
- Multiple worlds / world selection UI.
