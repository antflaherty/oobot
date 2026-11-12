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

## Iteration 2 (planned)

A JSON world file format so obstacle coordinates can be configured without changing sim code. The format is not yet defined. When introduced, the sim should:

- Load obstacle definitions from the world file at startup.
- Support static obstacles inside the bounded box.
- Apply stop-dead collision response against obstacles (movement that would penetrate an obstacle is clamped).
- Optionally support moving obstacles and refined collision response (slide, bounce) in later iterations.

## Out of scope for MVP

- World file parsing (deferred to iteration 2).
- Static obstacles (deferred to iteration 2).
- Moving obstacles, sliding, bouncing (future iterations).
- Multiple worlds / world selection UI.
