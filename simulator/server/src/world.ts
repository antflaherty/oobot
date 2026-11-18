// Bounded-box 2D world model (iteration 1 world + iteration 2 obstacles).
//
// The world is a rectangle centred at the origin. The robot pose is
// (x, y, heading) in radians (0 = +X). Motion is differential drive, and the
// physical footprint is a body box plus wheel boxes (see geometry.ts); those
// boxes drive collision with the box edges and static axis-aligned obstacles.
// Proximity sensing still casts rays from the centre — it is unaffected by the
// footprint.
//
// Kinematics: differential drive. Motor powers left/right in [-1, 1] are
// converted to metres/second before computing angular velocity (D-1); linear
// speed stays in world units/second. The pose is integrated each fixed
// timestep; if the candidate footprint is blocked, translation is shortened to
// the furthest clear fraction and rotation is held when it alone would
// penetrate (stop-dead, no sliding, bouncing, or penetration).
//
// NOTE: box dimensions, max speed, track width and start pose are scaffolding
// decisions (specs/COMPONENTS.md §1.4, §1.7 say "fixed at scaffolding"). They
// are exported constants so they can be changed in one place.

import * as fs from "fs";

import {
  RobotShape,
  WorldBounds,
  defaultRobotShape,
  footprintTouchesBounds,
  footprintWithinBounds,
  isFootprintClear,
  isFootprintClearOfObstacles,
  WORLD_UNITS_PER_METRE,
} from "./geometry";

// The world box is in world units, but wheel speed and track width are in
// metres (see docs/DECISIONS.md D-1). geometry.ts is the conversion point;
// re-export here so existing imports keep working.
export { WORLD_UNITS_PER_METRE } from "./geometry";

export interface WorldConfig {
  width: number;      // world units, X extent of the box
  height: number;     // world units, Y extent of the box
  maxSpeed: number;   // world units per second at full motor power (1.0)
  trackWidth: number; // metres, distance between the two wheels
  tickRate: number;   // sim steps per second (~60)
}

export const DEFAULT_WORLD_CONFIG: WorldConfig = {
  width: 800,
  height: 600,
  maxSpeed: 100.0, // world units / s at full motor power — perceptible on canvas
  trackWidth: 0.5,
  tickRate: 60,
};

import { GoalArea, Obstacle, RobotPose } from "./types";

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

  // Physical collision footprint in world units (body + wheel boxes).
  public readonly shape: RobotShape;

  // Optional goal area from the world file. Null when the world has no goal;
  // only the robot's centre point is tested against it (never the footprint).
  public readonly goal: GoalArea | null;

  private readonly dt: number;
  private readonly maxSpeed: number;
  private readonly trackWidth: number;

  public pose: RobotPose;

  constructor(
    config: WorldConfig = DEFAULT_WORLD_CONFIG,
    startPose: RobotPose = DEFAULT_START_POSE,
    obstacles: Obstacle[] = [],
    shape: RobotShape = defaultRobotShape(),
    goal: GoalArea | null = null,
  ) {
    this.minX = -config.width / 2;
    this.maxX = config.width / 2;
    this.minY = -config.height / 2;
    this.maxY = config.height / 2;
    // Shallow copy so callers can't mutate our obstacle list through the
    // reference they passed in.
    this.obstacles = [...obstacles];
    this.shape = {
      width: shape.width,
      height: shape.height,
      wheels: shape.wheels.map((wheel) => ({ ...wheel })),
    };
    this.dt = 1 / config.tickRate;
    this.maxSpeed = config.maxSpeed;
    this.trackWidth = config.trackWidth;
    this.pose = { ...startPose };
    // Copy like obstacles/shape so callers can't mutate our goal afterwards.
    this.goal = goal === null ? null : { ...goal };
  }

  // Apply one tick of differential-drive motion with the given motor powers.
  // Returns flags indicating whether the footprint was stopped at a box edge
  // and whether the robot came into contact with an obstacle or box edge.
  apply(left: number, right: number): { clampedX: boolean; clampedY: boolean; collided: boolean } {
    const l = clamp(left, -1, 1);
    const r = clamp(right, -1, 1);

    // Wheel powers become m/s; angular rate is then physical (rad/s). Linear
    // speed is converted back to world units/s so travel speeds are unchanged.
    const vLeft = (l * this.maxSpeed) / WORLD_UNITS_PER_METRE;
    const vRight = (r * this.maxSpeed) / WORLD_UNITS_PER_METRE;
    const linear = ((vLeft + vRight) / 2) * WORLD_UNITS_PER_METRE; // world units / s
    const angular = (vRight - vLeft) / this.trackWidth;            // radians / s

    const oldHeading = this.pose.heading;
    const newHeading = oldHeading + angular * this.dt;
    // Integrate translation at the midpoint heading for smoother curves.
    const midHeading = oldHeading + angular * this.dt / 2;

    const px = this.pose.x;
    const py = this.pose.y;
    const targetX = px + Math.cos(midHeading) * linear * this.dt;
    const targetY = py + Math.sin(midHeading) * linear * this.dt;

    const bounds: WorldBounds = {
      minX: this.minX, maxX: this.maxX, minY: this.minY, maxY: this.maxY,
    };
    let next: RobotPose = { x: targetX, y: targetY, heading: newHeading };
    let clampedX = false;
    let clampedY = false;
    let collided = false;

    if (!isFootprintClear(next, this.shape, bounds, this.obstacles)) {
      collided = true;
      // Shorten the translation at the new heading; if rotating alone would
      // penetrate the footprint, retry the translation at the old heading.
      let resolved = this.resolveTranslation(px, py, targetX, targetY, newHeading, bounds);
      if (resolved === null) {
        resolved = this.resolveTranslation(px, py, targetX, targetY, oldHeading, bounds);
      }
      next = resolved ?? { x: px, y: py, heading: oldHeading };

      // A boundary contact is reported separately from an obstacle contact.
      const touching = footprintTouchesBounds(next, this.shape, bounds);
      clampedX = touching.x;
      clampedY = touching.y;
    }

    this.pose = next;
    return { clampedX, clampedY, collided };
  }

  // Furthest clear point on the segment from the current pose to the target,
  // evaluated at a fixed heading. Returns null when even zero translation is
  // blocked at that heading (the caller then tries the old heading / holds).
  private resolveTranslation(
    px: number,
    py: number,
    targetX: number,
    targetY: number,
    heading: number,
    bounds: WorldBounds,
  ): RobotPose | null {
    const at = (fraction: number): RobotPose => ({
      x: px + (targetX - px) * fraction,
      y: py + (targetY - py) * fraction,
      heading,
    });
    const clear = (fraction: number): boolean =>
      isFootprintClear(at(fraction), this.shape, bounds, this.obstacles);

    if (clear(1)) return at(1);
    if (!clear(0)) return null;

    let low = 0;
    let high = 1;
    for (let i = 0; i < 20; i++) {
      const mid = (low + high) / 2;
      if (clear(mid)) low = mid;
      else high = mid;
    }
    return at(low);
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

  // True when the robot's centre point lies inside the goal rectangle. Edges
  // count as inside. Heading and physical footprint are deliberately ignored:
  // in-goal is a pure centre-point containment test. Always false when the
  // world declares no goal.
  isInGoal(pose: RobotPose = this.pose): boolean {
    const goal = this.goal;
    if (goal === null) return false;
    return (
      pose.x >= goal.x - goal.width / 2 &&
      pose.x <= goal.x + goal.width / 2 &&
      pose.y >= goal.y - goal.height / 2 &&
      pose.y <= goal.y + goal.height / 2
    );
  }
}

// Ray (half-line) vs axis-aligned box intersection using the slab method.
//
// The ray is p(t) = (ox, oy) + t * (dirX, dirY) for t >= 0. The box is centred
// at (box.x, box.y) with full width/height box.width/box.height.
//
// Returns the distance t to the nearest entry face in front of the origin, or
// null if the ray misses the box. An origin on/inside the box that is moving
// outward has no entry face ahead, so it reports null rather than a hit at t=0
// (a t=0 hit used to freeze a robot sitting against a wall). An origin exactly
// on a face and pointing back into the box is a genuine t=0 contact.
export function rayVsAxisAlignedBox(
  ox: number,
  oy: number,
  dirX: number,
  dirY: number,
  box: Obstacle,
): number | null {
  const DIR_EPS = 1e-12;
  const BOUND_EPS = 1e-9;

  // Degenerate direction (not a real ray) — nothing to hit.
  if (Math.abs(dirX) < DIR_EPS && Math.abs(dirY) < DIR_EPS) {
    return null;
  }

  const minX = box.x - box.width / 2;
  const maxX = box.x + box.width / 2;
  const minY = box.y - box.height / 2;
  const maxY = box.y + box.height / 2;

  let tEnter = -Infinity;
  let tExit = Infinity;
  // Whether the origin is inside (or within BOUND_EPS of) every slab.
  let insideAllSlabs = true;
  // An origin on a face pointing back into the box really is entering at t=0.
  let enteringFromFace = false;

  const slab = (origin: number, min: number, max: number, dir: number): boolean => {
    if (Math.abs(dir) < DIR_EPS) {
      // Parallel to this axis: the ray stays in the slab only if the origin is
      // inside it (a point on the face counts as inside, epsilon included).
      return origin >= min - BOUND_EPS && origin <= max + BOUND_EPS;
    }
    const t1 = (min - origin) / dir;
    const t2 = (max - origin) / dir;
    tEnter = Math.max(tEnter, Math.min(t1, t2));
    tExit = Math.min(tExit, Math.max(t1, t2));

    if (origin < min - BOUND_EPS || origin > max + BOUND_EPS) {
      insideAllSlabs = false;
    }
    if (origin <= min && origin >= min - BOUND_EPS && dir > DIR_EPS) {
      enteringFromFace = true;
    }
    if (origin >= max && origin <= max + BOUND_EPS && dir < -DIR_EPS) {
      enteringFromFace = true;
    }
    return true;
  };

  if (!slab(ox, minX, maxX, dirX)) return null;
  if (!slab(oy, minY, maxY, dirY)) return null;

  // No common slab interval, or the whole box lies behind the ray.
  if (tEnter > tExit + BOUND_EPS || tExit < -BOUND_EPS) return null;

  if (insideAllSlabs && !enteringFromFace) {
    // Origin on/inside the box, moving outward (or grazing along a face):
    // there is no entry hit ahead, and certainly not one at t=0.
    return null;
  }

  // Normal entry in front of the origin, or a genuine re-entry from a face.
  // Normalise -0 to +0 so callers never see a negative-distance contact.
  return tEnter > BOUND_EPS ? tEnter : 0;
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isFiniteNumber(value: unknown): value is number {
  return typeof value === "number" && Number.isFinite(value);
}

// The result of loading and parsing a world JSON file.
export interface LoadedWorld {
  config: WorldConfig;
  startPose: RobotPose;
  obstacles: Obstacle[];
  goal: GoalArea | null;
}

// Parse and validate the contents of a world JSON file (see
// specs/WORLD_FORMAT.md iteration 2). Kept separate from the file read so
// validation can be tested without filesystem I/O.
//
// Width/height build a WorldConfig that keeps every other field from
// DEFAULT_WORLD_CONFIG. Obstacles are parsed before the robot pose so the
// start pose can be validated against them. A missing or empty obstacles list
// yields an empty world. An optional top-level goal is parsed as a centre-based
// box with positive extents, using the same convention as an obstacle; absent
// or null means the world has no goal. Throws with the file path in the message
// on any invalid input — callers (e.g. the server entry point) are expected to
// catch and fall back to defaults rather than crash.
export function parseWorld(
  raw: string,
  filePath: string,
  shape: RobotShape = defaultRobotShape(),
): LoadedWorld {
  const parsed = JSON.parse(raw);

  if (!isObject(parsed)) {
    throw new Error(`world file "${filePath}": top-level JSON must be an object`);
  }

  const { width, height } = parsed as { width: unknown; height: unknown };
  if (!isFiniteNumber(width) || !isFiniteNumber(height) || width <= 0 || height <= 0) {
    throw new Error(
      `world file "${filePath}": width and height must be positive numbers`,
    );
  }

  const config: WorldConfig = {
    ...DEFAULT_WORLD_CONFIG,
    width,
    height,
  };

  const obstacles: Obstacle[] = [];
  const rawObstacles = (parsed as { obstacles: unknown }).obstacles;
  if (rawObstacles !== undefined && rawObstacles !== null) {
    if (!Array.isArray(rawObstacles)) {
      throw new Error(`world file "${filePath}": obstacles must be an array`);
    }
    for (const o of rawObstacles) {
      if (
        !isObject(o) ||
        !isFiniteNumber(o.x) ||
        !isFiniteNumber(o.y) ||
        !isFiniteNumber(o.width) ||
        !isFiniteNumber(o.height) ||
        o.width <= 0 ||
        o.height <= 0
      ) {
        throw new Error(
          `world file "${filePath}": each obstacle must be { x, y, width, height } positive numbers`,
        );
      }
      obstacles.push({ x: o.x, y: o.y, width: o.width, height: o.height });
    }
  }

  const rawGoal = (parsed as { goal: unknown }).goal;
  let goal: GoalArea | null = null;
  if (rawGoal !== undefined && rawGoal !== null) {
    if (
      !isObject(rawGoal) ||
      !isFiniteNumber(rawGoal.x) ||
      !isFiniteNumber(rawGoal.y) ||
      !isFiniteNumber(rawGoal.width) ||
      !isFiniteNumber(rawGoal.height) ||
      rawGoal.width <= 0 ||
      rawGoal.height <= 0
    ) {
      throw new Error(
        `world file "${filePath}": goal must be { x, y, width, height } positive numbers`,
      );
    }
    goal = { x: rawGoal.x, y: rawGoal.y, width: rawGoal.width, height: rawGoal.height };
  }

  const robot = (parsed as { robot: unknown }).robot;
  if (
    !isObject(robot) ||
    !isFiniteNumber(robot.x) ||
    !isFiniteNumber(robot.y) ||
    !isFiniteNumber(robot.heading)
  ) {
    throw new Error(
      `world file "${filePath}": robot must be { x, y, heading } numbers`,
    );
  }
  const startPose: RobotPose = { x: robot.x, y: robot.y, heading: robot.heading };

  const bounds: WorldBounds = {
    minX: -width / 2,
    maxX: width / 2,
    minY: -height / 2,
    maxY: height / 2,
  };
  if (!footprintWithinBounds(startPose, shape, bounds)) {
    throw new Error(
      `world file "${filePath}": start pose (${startPose.x}, ${startPose.y}) is outside the box ` +
        `x[${bounds.minX},${bounds.maxX}] y[${bounds.minY},${bounds.maxY}]`,
    );
  }

  // The full footprint must fit. A face touching an obstacle is allowed (that
  // is where stop-dead contact leaves the robot); only overlap is unsafe.
  for (let i = 0; i < obstacles.length; i++) {
    if (!isFootprintClearOfObstacles(startPose, shape, [obstacles[i]])) {
      throw new Error(
        `world file "${filePath}": start pose (${startPose.x}, ${startPose.y}) is inside obstacle[${i}]`,
      );
    }
  }

  return { config, startPose, obstacles, goal };
}

// Read and validate a world JSON file (see specs/WORLD_FORMAT.md iteration 2).
export function loadWorldFile(filePath: string, shape: RobotShape = defaultRobotShape()): LoadedWorld {
  return parseWorld(fs.readFileSync(filePath, "utf8"), filePath, shape);
}
