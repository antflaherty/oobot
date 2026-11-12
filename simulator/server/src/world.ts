// Bounded-box 2D world model (iteration 1).
//
// The world is a rectangle centred at the origin with no obstacles. The robot
// is a point with pose (x, y, heading) where heading is in radians and 0
// points along the +X axis.
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

  private readonly dt: number;
  private readonly maxSpeed: number;
  private readonly maxAngularSpeed: number;
  private readonly trackWidth: number;

  public pose: RobotPose;

  constructor(config: WorldConfig = DEFAULT_WORLD_CONFIG, startPose: RobotPose = DEFAULT_START_POSE) {
    this.minX = -config.width / 2;
    this.maxX = config.width / 2;
    this.minY = -config.height / 2;
    this.maxY = config.height / 2;
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
}
