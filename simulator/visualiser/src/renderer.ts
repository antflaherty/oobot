import type { RobotPose, Obstacle, RobotShape, ToolState, GoalArea } from "./types";
import { lerp } from "./math";

// World is an 800x600 unit box centred at origin; canvas is 800x600 px.
export const WORLD_WIDTH = 800;
export const WORLD_HEIGHT = 600;

// Default robot geometry (50x50 body, two wheels at +/-pi/2), used when a
// snapshot omits the optional shape field. The sim always sends its own shape.
export const DEFAULT_SHAPE: RobotShape = {
  width: 50,
  height: 50,
  wheels: [
    { angle: Math.PI / 2, offset: 25, length: 30, width: 10 },
    { angle: -Math.PI / 2, offset: 25, length: 30, width: 10 },
  ],
};

// Canvas coordinate mapping (MVP decision, documented here):
//
//   world (x, y)      -> canvas pixel (x + 400, 300 - y)  1 unit = 1 pixel
//   heading (radians) -> canvas rotation        0 = points right (+X axis)
//                                                 positive = counter-clockwise
//                                                 (mathematical convention)
//
// The world is an 800x600 unit box centred at origin (x ∈ [-400,400],
// y ∈ [-300,300]) and the canvas is 800x600 px. World origin (0,0) maps to
// canvas centre (400,300). World +Y is up (mathematical convention) and is
// flipped to screen +Y down when mapping to canvas pixels.
//
// The renderer negates the heading before calling ctx.rotate() to convert
// from mathematical (counter-clockwise-positive) to screen convention
// (clockwise-positive with +Y down).
//
// This is a rendering convention only; the wire protocol itself is
// coordinate-system agnostic.

const ARROW_LENGTH = 18;
const ARROW_WIDTH = 4;

const BOX_STROKE = "#0f172a"; // dark boundary (canvas background is set in index.html)
const BODY_FILL = "#2563eb";
const BODY_STROKE = "#1e40af";
const WHEEL_FILL = "#334155";
const WHEEL_STROKE = "#0f172a";
const ARROW_FILL = "#ffffff";
const OBSTACLE_FILL = "#1e293b"; // dark obstacles
const OBSTACLE_STROKE = "#0f172a";
const GOAL_FILL = "rgba(34, 197, 94, 0.25)"; // translucent green goal zone
const GOAL_STROKE = "#15803d"; // darker green outline

// Front-light tool indicator (id from the shared robot manifest; the
// visualiser does not read the manifest itself, just the published states).
export const FRONT_LIGHT_ID = "front-light";
const FRONT_LIGHT_RADIUS = 4; // px; world units == pixels at 1:1
const FRONT_LIGHT_ACTIVE_FILL = "#ef4444"; // red while lit
const FRONT_LIGHT_INACTIVE_FILL = "#1d4ed8"; // darker than the blue body
const FRONT_LIGHT_STROKE = "#0f172a"; // same dark outline as wheels/obstacles

/** A wheel rectangle in body-local CANVAS coordinates (X right, Y down),
 * before the heading rotation is applied. Dimensions are full extents. */
export interface WheelRect {
  x: number;
  y: number;
  length: number; // along the body's local X axis (the heading)
  width: number;  // along the body's local Y axis
}

/** Pure geometry: mount each wheel at offset*(cos angle, sin angle) in world
 * body coordinates, flipped to canvas Y-down. The renderer rotates the whole
 * body by -heading, so the wheel's long axis stays aligned with the heading. */
export function robotWheelRects(shape: RobotShape): WheelRect[] {
  return shape.wheels.map((wheel) => ({
    x: wheel.offset * Math.cos(wheel.angle),
    y: -wheel.offset * Math.sin(wheel.angle),
    length: wheel.length,
    width: wheel.width,
  }));
}

/** Canvas-space rectangle for a centre-based goal area (world units == px). */
export interface GoalRect {
  x: number;
  y: number;
  width: number;
  height: number;
}

/** Map a world goal area (centre + full extents) to canvas pixels, using the
 * same world-to-canvas convention as obstacles: world +Y is up, canvas +Y down. */
export function goalRect(goal: GoalArea): GoalRect {
  return {
    x: goal.x - goal.width / 2 + WORLD_WIDTH / 2,
    y: WORLD_HEIGHT / 2 - (goal.y + goal.height / 2),
    width: goal.width,
    height: goal.height,
  };
}

/** Circle for the front-light indicator, in body-local CANVAS coordinates
 * (local +X forward, +Y down after the world-to-canvas flip).
 *
 * The circle is tangent to the front edge (local +X = width/2) and offset
 * toward +Y so it stays clear of the white heading arrow, which sits at
 * |y| <= ARROW_WIDTH near the nose. Both axes are clamped so the circle stays
 * on/inside the body outline even for unusually small shapes.
 */
export function frontLightCircle(shape: RobotShape): {
  x: number;
  y: number;
  radius: number;
} {
  const radius = Math.min(FRONT_LIGHT_RADIUS, shape.width / 2, shape.height / 2);
  const x = shape.width / 2 - radius;
  const y = Math.min(shape.height / 4, shape.height / 2 - radius);
  return { x, y, radius };
}

/** Fill colour for the front-light indicator (pure, so it can be tested without a canvas). */
export function frontLightFill(active: boolean): string {
  return active ? FRONT_LIGHT_ACTIVE_FILL : FRONT_LIGHT_INACTIVE_FILL;
}

/** True only when a published tool state says the front light is active.
 * Missing or unknown ids default to inactive. */
export function isFrontLightActive(toolStates: ToolState[]): boolean {
  return toolStates.some((tool) => tool.id === FRONT_LIGHT_ID && tool.active === true);
}

// ---------------------------------------------------------------------------
// Robot trail configuration.
//
// The trail keeps a short history of the robot's world positions and draws
// them as a fading polyline behind the robot. Tweak `maxPoints` to change
// how long the trail is (at ~60 fps, 100 points ≈ 1.7 seconds of history).
// ---------------------------------------------------------------------------
export interface TrailConfig {
  maxPoints: number; // max number of trail points to retain
  minAlpha: number; // alpha for the oldest segment
  maxAlpha: number; // alpha for the newest segment
  color: string; // base trail colour (hex)
}

const DEFAULT_TRAIL_CONFIG: TrailConfig = {
  maxPoints: 100,
  minAlpha: 0.15,
  maxAlpha: 0.9,
  color: "#3b82f6", // blue — visible against the light canvas background
};

// Ignore points closer than this (world units) to avoid duplicate points
// while the robot is stationary / the renderer is holding a frame.
const TRAIL_DEDUP_EPSILON = 0.5;

export class Renderer {
  private readonly ctx: CanvasRenderingContext2D;
  private readonly canvas: HTMLCanvasElement;
  private pxPerUnit: number;
  private trail: Array<{ x: number; y: number }> = [];
  private trailConfig: TrailConfig;

  constructor(
    canvas: HTMLCanvasElement,
    trailConfig: TrailConfig = DEFAULT_TRAIL_CONFIG,
  ) {
    const ctx = canvas.getContext("2d");
    if (!ctx) {
      throw new Error("Could not obtain 2D rendering context from canvas");
    }
    this.ctx = ctx;
    this.canvas = canvas;
    // 1:1 world-to-pixel mapping; canvas size == world size.
    canvas.width = WORLD_WIDTH;
    canvas.height = WORLD_HEIGHT;
    this.pxPerUnit = 1;
    this.trailConfig = trailConfig;
  }

  /** Clear the whole canvas. */
  clear(): void {
    const { ctx } = this;
    ctx.clearRect(0, 0, WORLD_WIDTH, WORLD_HEIGHT);
  }

  /** Record a robot world position in the trail history. */
  addTrailPoint(x: number, y: number): void {
    const last = this.trail[this.trail.length - 1];
    if (
      last &&
      Math.abs(last.x - x) < TRAIL_DEDUP_EPSILON &&
      Math.abs(last.y - y) < TRAIL_DEDUP_EPSILON
    ) {
      return;
    }
    this.trail.push({ x, y });
    if (this.trail.length > this.trailConfig.maxPoints) {
      this.trail = this.trail.slice(-this.trailConfig.maxPoints);
    }
  }

  /** Clear the trail history (e.g. on sim reset). */
  clearTrail(): void {
    this.trail = [];
  }

  /** Replace the trail config at runtime (length/duration/colour). */
  setTrailConfig(config: Partial<TrailConfig>): void {
    this.trailConfig = { ...this.trailConfig, ...config };
  }

  /** Draw the fading trail polyline behind the robot. */
  drawTrail(): void {
    const { ctx, trail, trailConfig, pxPerUnit } = this;
    const n = trail.length;
    if (n < 2) return;

    // World -> canvas space (same mapping as drawRobot).
    const pts = trail.map((p) => ({
      x: (p.x + WORLD_WIDTH / 2) * pxPerUnit,
      y: (WORLD_HEIGHT / 2 - p.y) * pxPerUnit,
    }));

    // Draw segment by segment, fading from minAlpha (oldest) to maxAlpha (newest).
    const denom = n - 2;
    for (let i = 0; i < n - 1; i++) {
      const t = denom > 0 ? i / denom : 1;
      const alpha = lerp(trailConfig.minAlpha, trailConfig.maxAlpha, t);

      ctx.save();
      ctx.globalAlpha = alpha;
      ctx.strokeStyle = trailConfig.color;
      ctx.lineWidth = 2;
      ctx.lineCap = "round";
      ctx.lineJoin = "round";
      ctx.beginPath();
      ctx.moveTo(pts[i].x, pts[i].y);
      ctx.lineTo(pts[i + 1].x, pts[i + 1].y);
      ctx.stroke();
      ctx.restore();
    }
  }

  /** Draw the bounded box boundary. */
  drawWorld(): void {
    const { ctx } = this;
    ctx.strokeStyle = BOX_STROKE;
    ctx.lineWidth = 3;
    ctx.beginPath();
    ctx.rect(0, 0, WORLD_WIDTH, WORLD_HEIGHT);
    ctx.stroke();
  }

  /** Draw the goal area as a translucent filled rectangle. Null means no goal. */
  drawGoal(goal: GoalArea | null): void {
    if (!goal) return;
    const { ctx } = this;
    const rect = goalRect(goal);

    ctx.save();
    ctx.fillStyle = GOAL_FILL;
    ctx.strokeStyle = GOAL_STROKE;
    ctx.lineWidth = 2;
    ctx.fillRect(rect.x, rect.y, rect.width, rect.height);
    ctx.strokeRect(rect.x, rect.y, rect.width, rect.height);
    ctx.restore();
  }

  /** Draw static obstacles as filled rectangles. */
  drawObstacles(obstacles: Obstacle[]): void {
    const { ctx } = this;
    for (const obs of obstacles) {
      const cx = obs.x - obs.width / 2 + WORLD_WIDTH / 2;
      const cy = WORLD_HEIGHT / 2 - (obs.y + obs.height / 2);
      const w = obs.width;
      const h = obs.height;

      ctx.save();
      ctx.fillStyle = OBSTACLE_FILL;
      ctx.strokeStyle = OBSTACLE_STROKE;
      ctx.lineWidth = 1;
      ctx.fillRect(cx, cy, w, h);
      ctx.strokeRect(cx, cy, w, h);
      ctx.restore();
    }
  }

  /** Draw the robot as a rectangle body, front-light indicator and heading arrow. */
  drawRobot(
    pose: RobotPose,
    shape: RobotShape | null = DEFAULT_SHAPE,
    toolStates: ToolState[] = [],
  ): void {
    const { ctx } = this;
    const bodyShape = shape ?? DEFAULT_SHAPE;
    // World origin (0,0) maps to canvas centre (400,300). World +Y is up, so
    // flip it to screen +Y down.
    const cx = (pose.x + WORLD_WIDTH / 2) * this.pxPerUnit;
    const cy = (WORLD_HEIGHT / 2 - pose.y) * this.pxPerUnit;

    ctx.save();
    ctx.translate(cx, cy);
    // Canvas rotation is clockwise-positive (+Y points down on screen).
    // Snapshot headings use mathematical convention (counter-clockwise-positive,
    // +Y up), so negate to convert. heading 0 = points right (+X axis).
    ctx.rotate(-pose.heading);

    // Wheels first so the body overlaps their inner halves: each is a darker
    // rectangle at its rotated mount point, long axis along local X (heading).
    for (const wheel of robotWheelRects(bodyShape)) {
      ctx.save();
      ctx.translate(wheel.x, wheel.y);
      const left = -wheel.length / 2;
      const top = -wheel.width / 2;
      ctx.fillStyle = WHEEL_FILL;
      ctx.strokeStyle = WHEEL_STROKE;
      ctx.lineWidth = 1;
      ctx.fillRect(left, top, wheel.length, wheel.width);
      ctx.strokeRect(left, top, wheel.length, wheel.width);
      ctx.restore();
    }

    // Body: filled square/rectangle centred on the pose.
    ctx.beginPath();
    ctx.rect(
      -bodyShape.width / 2,
      -bodyShape.height / 2,
      bodyShape.width,
      bodyShape.height,
    );
    ctx.fillStyle = BODY_FILL;
    ctx.fill();
    ctx.strokeStyle = BODY_STROKE;
    ctx.lineWidth = 1.5;
    ctx.stroke();

    // Front-light tool indicator: after the body so it is not covered, before
    // the heading arrow so the arrow stays legible on top of it.
    const light = frontLightCircle(bodyShape);
    ctx.beginPath();
    ctx.arc(light.x, light.y, light.radius, 0, Math.PI * 2);
    ctx.fillStyle = frontLightFill(isFrontLightActive(toolStates));
    ctx.fill();
    ctx.strokeStyle = FRONT_LIGHT_STROKE;
    ctx.lineWidth = 1;
    ctx.stroke();

    // Heading arrow: a filled triangle pointing along the local +X axis
    // (i.e. in the direction of `heading`).
    ctx.beginPath();
    ctx.moveTo(ARROW_LENGTH, 0);
    ctx.lineTo(ARROW_LENGTH - ARROW_WIDTH, -ARROW_WIDTH);
    ctx.lineTo(ARROW_LENGTH - ARROW_WIDTH, ARROW_WIDTH);
    ctx.closePath();
    ctx.fillStyle = ARROW_FILL;
    ctx.fill();

    ctx.restore();
  }

  /** Render one frame: box + goal + obstacles + trail + robot at the given
   * (possibly interpolated) pose.
   *
   * Trail points are recorded by LiveStream as snapshots arrive, not here per
   * render frame, so the trail keeps tracing while the tab is hidden
   * (requestAnimationFrame pauses) instead of drawing one jump line on return.
   */
  render(
    pose: RobotPose,
    obstacles: Obstacle[] = [],
    shape: RobotShape | null = DEFAULT_SHAPE,
    toolStates: ToolState[] = [],
    goal: GoalArea | null = null,
  ): void {
    this.clear();
    this.drawTrail();
    this.drawWorld();
    this.drawGoal(goal);
    this.drawObstacles(obstacles);
    this.drawRobot(pose, shape, toolStates);
  }
}
