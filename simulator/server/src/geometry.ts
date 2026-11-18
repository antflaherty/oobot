// Physical robot footprint geometry (metres -> world units, oriented boxes).
//
// The robot is a rectangular body plus one oriented box per wheel. Sensing is
// unaffected by this file: proximity rays still originate at the robot centre.
// Collision and world-file spawn validation use the helpers here instead.

import type { Obstacle, RobotPose } from "./types";
import type { ComponentDescription, RobotBody } from "./robotDescription";
import { DEFAULT_ROBOT_BODY } from "./robotDescription";

// Single conversion point for physical dimensions (also re-exported by world.ts
// so existing callers keep working).
export const WORLD_UNITS_PER_METRE = 100;

export interface RobotShape {
  // Full dimensions in world units.
  width: number;
  height: number;
  // One entry per wheel; angle/offset come from the manifest, offset and the
  // shared wheel dimensions are converted to world units.
  wheels: Array<{
    angle: number;
    offset: number;
    length: number;
    width: number;
  }>;
}

export interface Point {
  x: number;
  y: number;
}

export interface WorldBounds {
  minX: number;
  maxX: number;
  minY: number;
  maxY: number;
}

// A rectangle with a centre, full-axis half-extents, and a rotation (radians)
// of its local X axis from world +X. All values are in world units.
export interface OrientedBox {
  centre: Point;
  halfWidth: number;
  halfHeight: number;
  rotation: number;
}

// Contact counts as clear: stop-dead resolution leaves faces exactly touching.
const EPS = 1e-7;
// Binary-search resolution stops within ~1e-6 world units of a boundary, so
// boundary-contact detection needs a slightly wider tolerance than EPS.
const BOUND_CONTACT_EPS = 1e-4;

const DEFAULT_WHEELS: ReadonlyArray<Pick<ComponentDescription, "angle" | "offset">> = [
  { angle: Math.PI / 2, offset: 0.25 },
  { angle: -Math.PI / 2, offset: 0.25 },
];

export function toRobotShape(body: RobotBody, wheels: ComponentDescription[]): RobotShape {
  return {
    width: body.width * WORLD_UNITS_PER_METRE,
    height: body.height * WORLD_UNITS_PER_METRE,
    wheels: wheels
      .filter((wheel) => wheel.type === "wheel")
      .map((wheel) => ({
        angle: wheel.angle,
        offset: wheel.offset * WORLD_UNITS_PER_METRE,
        length: body.wheels.length * WORLD_UNITS_PER_METRE,
        width: body.wheels.width * WORLD_UNITS_PER_METRE,
      })),
  };
}

export function defaultRobotShape(): RobotShape {
  const wheels: ComponentDescription[] = DEFAULT_WHEELS.map((mount) => ({
    id: mount.angle > 0 ? "left-wheel" : "right-wheel",
    type: "wheel",
    angle: mount.angle,
    offset: mount.offset,
  }));
  return toRobotShape(DEFAULT_ROBOT_BODY, wheels);
}

// The three oriented boxes that make up the robot at a given pose: the body at
// the pose centre, and each wheel at its mounted radial offset. Wheel boxes
// keep their long axis along the robot's heading (they roll tangentially).
export function robotBoxes(pose: RobotPose, shape: RobotShape): OrientedBox[] {
  const boxes: OrientedBox[] = [
    {
      centre: { x: pose.x, y: pose.y },
      halfWidth: shape.width / 2,
      halfHeight: shape.height / 2,
      rotation: pose.heading,
    },
  ];

  for (const wheel of shape.wheels) {
    const mountedHeading = pose.heading + wheel.angle;
    boxes.push({
      centre: {
        x: pose.x + Math.cos(mountedHeading) * wheel.offset,
        y: pose.y + Math.sin(mountedHeading) * wheel.offset,
      },
      halfWidth: wheel.length / 2,
      halfHeight: wheel.width / 2,
      rotation: pose.heading,
    });
  }
  return boxes;
}

export function obstacleBox(obstacle: Obstacle): OrientedBox {
  return {
    centre: { x: obstacle.x, y: obstacle.y },
    halfWidth: obstacle.width / 2,
    halfHeight: obstacle.height / 2,
    rotation: 0,
  };
}

export function orientedBoxCorners(box: OrientedBox): Point[] {
  const cos = Math.cos(box.rotation);
  const sin = Math.sin(box.rotation);
  const corners: Point[] = [];
  for (const sx of [-1, 1]) {
    for (const sy of [-1, 1]) {
      const localX = sx * box.halfWidth;
      const localY = sy * box.halfHeight;
      corners.push({
        x: box.centre.x + localX * cos - localY * sin,
        y: box.centre.y + localX * sin + localY * cos,
      });
    }
  }
  return corners;
}

function separatingAxes(corners: Point[]): Point[] {
  // World axes are always safe to include: a separation on any axis proves the
  // polygons are disjoint. They also give degenerate (zero-sized) boxes axes.
  const axes: Point[] = [{ x: 1, y: 0 }, { x: 0, y: 1 }];
  for (let i = 0; i < corners.length; i++) {
    const from = corners[i];
    const to = corners[(i + 1) % corners.length];
    const edgeX = to.x - from.x;
    const edgeY = to.y - from.y;
    const length = Math.hypot(edgeX, edgeY);
    if (length > 1e-12) {
      axes.push({ x: -edgeY / length, y: edgeX / length });
    }
  }
  return axes;
}

function project(corners: Point[], axis: Point): { min: number; max: number } {
  let min = Infinity;
  let max = -Infinity;
  for (const corner of corners) {
    const value = corner.x * axis.x + corner.y * axis.y;
    if (value < min) min = value;
    if (value > max) max = value;
  }
  return { min, max };
}

// SAT overlap test. Touching faces are treated as clear so a robot resting
// against a wall is still in a valid pose and can back away.
export function orientedBoxesOverlap(a: OrientedBox, b: OrientedBox): boolean {
  const cornersA = orientedBoxCorners(a);
  const cornersB = orientedBoxCorners(b);
  for (const axis of [...separatingAxes(cornersA), ...separatingAxes(cornersB)]) {
    const rangeA = project(cornersA, axis);
    const rangeB = project(cornersB, axis);
    if (rangeA.max <= rangeB.min + EPS || rangeB.max <= rangeA.min + EPS) {
      return false;
    }
  }
  return true;
}

function allFootprintCorners(pose: RobotPose, shape: RobotShape): Point[] {
  return robotBoxes(pose, shape).flatMap(orientedBoxCorners);
}

export function footprintWithinBounds(
  pose: RobotPose,
  shape: RobotShape,
  bounds: WorldBounds,
): boolean {
  return allFootprintCorners(pose, shape).every(
    (point) =>
      point.x >= bounds.minX - EPS &&
      point.x <= bounds.maxX + EPS &&
      point.y >= bounds.minY - EPS &&
      point.y <= bounds.maxY + EPS,
  );
}

export function footprintTouchesBounds(
  pose: RobotPose,
  shape: RobotShape,
  bounds: WorldBounds,
): { x: boolean; y: boolean } {
  const touching = { x: false, y: false };
  for (const point of allFootprintCorners(pose, shape)) {
    if (
      point.x <= bounds.minX + BOUND_CONTACT_EPS ||
      point.x >= bounds.maxX - BOUND_CONTACT_EPS
    ) touching.x = true;
    if (
      point.y <= bounds.minY + BOUND_CONTACT_EPS ||
      point.y >= bounds.maxY - BOUND_CONTACT_EPS
    ) touching.y = true;
  }
  return touching;
}

export function isFootprintClearOfObstacles(
  pose: RobotPose,
  shape: RobotShape,
  obstacles: Obstacle[],
): boolean {
  const boxes = robotBoxes(pose, shape);
  for (const obstacle of obstacles) {
    const obstacleShape = obstacleBox(obstacle);
    for (const box of boxes) {
      if (orientedBoxesOverlap(box, obstacleShape)) return false;
    }
  }
  return true;
}

export function isFootprintClear(
  pose: RobotPose,
  shape: RobotShape,
  bounds: WorldBounds,
  obstacles: Obstacle[],
): boolean {
  return (
    footprintWithinBounds(pose, shape, bounds) &&
    isFootprintClearOfObstacles(pose, shape, obstacles)
  );
}
