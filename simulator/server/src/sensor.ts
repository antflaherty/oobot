// Ray casting for the front-mounted proximity sensor (iteration 2).
//
// Casts a single ray from the robot's pose along its heading against the
// world box edges (treated as walls per specs/SENSORS.md §9.1). Returns the
// distance to the nearest hit, or null if nothing is detected / the result is
// not finite.
//
// The world box is a rectangle centred at the origin. The robot is treated as
// an interior point (it is clamped into the box by WorldModel.apply()). A ray
// from an interior point always hits exactly one edge, so a finite hit always
// exists for a unit heading direction.
//
// Algorithm: for each edge the ray is moving toward, compute the parametric
// distance t = (edge - coord) / directionComponent. The first boundary hit of
// a convex box is the minimum positive t among the four edge-line
// intersections, and that hit is guaranteed to lie on the edge segment.

import { RobotPose } from "./types";

export interface BoxBounds {
  minX: number;
  maxX: number;
  minY: number;
  maxY: number;
}

/**
 * Distance from the robot's position along its heading to the nearest world
 * box edge ahead of it.
 *
 * Heading is in radians, 0 = +X axis. Direction (cos, sin) is a unit vector,
 * so the returned distance is in world units.
 *
 * @returns distance in world units (>= 0), or null if no finite hit.
 */
export function castFrontRay(pose: RobotPose, bounds: BoxBounds): number | null {
  const { x, y, heading } = pose;
  const dx = Math.cos(heading);
  const dy = Math.sin(heading);

  let nearest: number | null = null;

  // Right edge (x = maxX): only reachable when moving in +X.
  if (dx > 0) {
    const t = (bounds.maxX - x) / dx;
    if (t >= 0 && (nearest === null || t < nearest)) {
      nearest = t;
    }
  }
  // Left edge (x = minX): only reachable when moving in -X.
  if (dx < 0) {
    const t = (bounds.minX - x) / dx;
    if (t >= 0 && (nearest === null || t < nearest)) {
      nearest = t;
    }
  }
  // Top edge (y = maxY): only reachable when moving in +Y.
  if (dy > 0) {
    const t = (bounds.maxY - y) / dy;
    if (t >= 0 && (nearest === null || t < nearest)) {
      nearest = t;
    }
  }
  // Bottom edge (y = minY): only reachable when moving in -Y.
  if (dy < 0) {
    const t = (bounds.minY - y) / dy;
    if (t >= 0 && (nearest === null || t < nearest)) {
      nearest = t;
    }
  }

  if (nearest === null || !Number.isFinite(nearest)) {
    return null;
  }
  return nearest;
}
