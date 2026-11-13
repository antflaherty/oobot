// Bounded-box 2D world model (iteration 1 world + iteration 2 obstacles).
//
// The world is a rectangle centred at the origin. The robot is a point with
// pose (x, y, heading) where heading is in radians and 0 points along the +X
// axis. The world may contain a static set of axis-aligned box obstacles
// (see the Obstacle interface) that the front proximity sensor can detect via
// ray casting.
//
// Kinematics: differential drive. Motor powers left/right in [-1, 1] map to
// linear and angular velocity; the pose is integrated each fixed timestep and
// clamped at the box edges with stop-dead collision response (no sliding,
// bouncing, or penetration).
//
// NOTE: box dimensions, max speed, track width and start pose are scaffolding
// decisions (specs/COMPONENTS.md §1.4, §1.7 say "fixed at scaffolding"). They
// are exported constants so they can be changed in one place.

export interface WorldConfig {
  width: number;      // world units, X extent of the box
  height: number;     // world units, Y extent of the box
  maxSpeed: number;   // world units per second at full motor power (1.0)
  maxAngularSpeed: number; // radians per second at full differential drive
  trackWidth: number; // world units, distance between the two wheels
  tickRate: number;   // sim steps per second (~60)
}

// Axis-aligned box obstacle.
//
// Convention: centre-based. (x, y) is the centre of the box in world units and
// width/height are its full dimensions, so the box spans
// [x - width/2, x + width/2] x [y - height/2, y + height/2]. This matches the
// world box being centred at the origin and the robot pose being a point at
// (x, y).
export interface Obstacle {
  x: number;   // centre X, world units
  y: number;   // centre Y, world units
  width: number;  // full width, world units
  height: number; // full height, world units
}

export const DEFAULT_WORLD_CONFIG: WorldConfig = {
  width: 800,
  height: 600,
  maxSpeed: 100.0, // world units / s at full motor power — perceptible on canvas
  maxAngularSpeed: Math.PI / 2, // radians / s at full differential — ~90° per turn state
  trackWidth: 0.5,
  tickRate: 60,
};

import { RobotPose } from "./types";

export const DEFAULT_START_POSE: RobotPose = { x: 0, y: 0, heading: 0 };

function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), max);
}

export class WorldModel {
  public readonly minX: number;
  public readonly maxX: number;
  public readonly minY: number;
  public readonly maxY: number;

  // Static obstacles loaded into the world (defaults to empty). Exposed so the
  // ray caster / sensor slot can query them.
  public readonly obstacles: Obstacle[];

  private readonly dt: number;
  private readonly maxSpeed: number;
  private readonly maxAngularSpeed: number;
  private readonly trackWidth: number;

  public pose: RobotPose;

  constructor(
    config: WorldConfig = DEFAULT_WORLD_CONFIG,
    startPose: RobotPose = DEFAULT_START_POSE,
    obstacles: Obstacle[] = [],
  ) {
    this.minX = -config.width / 2;
    this.maxX = config.width / 2;
    this.minY = -config.height / 2;
    this.maxY = config.height / 2;
    // Shallow copy so callers can't mutate our obstacle list through the
    // reference they passed in.
    this.obstacles = [...obstacles];
    this.dt = 1 / config.tickRate;
    this.maxSpeed = config.maxSpeed;
    this.maxAngularSpeed = config.maxAngularSpeed;
    this.trackWidth = config.trackWidth;
    this.pose = { ...startPose };
  }

  // Apply one tick of differential-drive motion with the given motor powers.
  // Returns flags indicating whether either axis was clamped at a box edge
  // (stop-dead collision).
  apply(left: number, right: number): { clampedX: boolean; clampedY: boolean } {
    const l = clamp(left, -1, 1);
    const r = clamp(right, -1, 1);

    const linear = ((l + r) / 2) * this.maxSpeed;            // world units / s
    let angular = ((r - l) / this.trackWidth) * this.maxSpeed; // radians / s
    // Cap angular velocity independently so full differential drive turns at a
    // sane rate (~90°/s) even when linear speed is high.
    angular = clamp(angular, -this.maxAngularSpeed, this.maxAngularSpeed);

    const newHeading = this.pose.heading + angular * this.dt;
    // Integrate translation at the midpoint heading for smoother curves.
    const midHeading = this.pose.heading + angular * this.dt / 2;

    let nx = this.pose.x + Math.cos(midHeading) * linear * this.dt;
    let ny = this.pose.y + Math.sin(midHeading) * linear * this.dt;

    let clampedX = false;
    let clampedY = false;

    if (nx < this.minX) { nx = this.minX; clampedX = true; }
    else if (nx > this.maxX) { nx = this.maxX; clampedX = true; }

    if (ny < this.minY) { ny = this.minY; clampedY = true; }
    else if (ny > this.maxY) { ny = this.maxY; clampedY = true; }

    this.pose = { x: nx, y: ny, heading: newHeading };
    return { clampedX, clampedY };
  }

  // Zero out translational motion at the current pose (used after a clamp if
  // callers want explicit stop-dead semantics beyond position clamping).
  stopTranslation() {
    // Position clamping in apply() already enforces stop-dead; this is a hook
    // for future explicit velocity state. Kept for API symmetry.
  }

  // Distance from the ray origin along the front-facing ray (given by heading,
  // 0 = +X axis) to the nearest obstacle hit, or null if the ray misses every
  // obstacle. Complements the box-edge ray cast (the world box edges are
  // treated as walls by the sensor slot).
  nearestObstacleHit(ox: number, oy: number, heading: number): number | null {
    const dirX = Math.cos(heading);
    const dirY = Math.sin(heading);
    let nearest: number | null = null;
    for (const obs of this.obstacles) {
      const hit = rayVsAxisAlignedBox(ox, oy, dirX, dirY, obs);
      if (hit !== null && (nearest === null || hit < nearest)) {
        nearest = hit;
      }
    }
    return nearest;
  }
}

// Ray (half-line) vs axis-aligned box intersection using the slab method.
//
// The ray is p(t) = (ox, oy) + t * (dirX, dirY) for t >= 0. The box is centred
// at (box.x, box.y) with full width/height box.width/box.height.
//
// Returns the distance t to the nearest hit (t >= 0), or null if the ray misses
// the box. A distance of 0 means the origin is already inside the box.
export function rayVsAxisAlignedBox(
  ox: number,
  oy: number,
  dirX: number,
  dirY: number,
  box: Obstacle,
): number | null {
  // Degenerate direction (not a real ray) — nothing to hit.
  if (Math.abs(dirX) < 1e-12 && Math.abs(dirY) < 1e-12) {
    return null;
  }

  const minX = box.x - box.width / 2;
  const maxX = box.x + box.width / 2;
  const minY = box.y - box.height / 2;
  const maxY = box.y + box.height / 2;

  let tMin = 0;
  let tMax = Infinity;

  const slab = (origin: number, min: number, max: number, dir: number): boolean => {
    if (Math.abs(dir) < 1e-12) {
      // Parallel to this axis: the ray hits only if the origin already lies
      // inside the slab (no entry/exit constraint on t).
      return origin >= min && origin <= max;
    }
    const t1 = (min - origin) / dir;
    const t2 = (max - origin) / dir;
    tMin = Math.max(tMin, Math.min(t1, t2));
    tMax = Math.min(tMax, Math.max(t1, t2));
    return tMin <= tMax;
  };

  if (!slab(ox, minX, maxX, dirX)) return null;
  if (!slab(oy, minY, maxY, dirY)) return null;

  // The box occupies ray parameters [tMin, tMax]. The nearest hit at or in
  // front of the origin is max(tMin, 0). A negative tMax means the box is
  // entirely behind the ray.
  if (tMax < 0) return null;
  return Math.max(tMin, 0);
}
