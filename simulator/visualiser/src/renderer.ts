import type { RobotPose, WorldSnapshot, Obstacle } from "./types";
import { WORLD_WIDTH, WORLD_HEIGHT } from "./snapshotStream";
import { lerp } from "./math";

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

const ROBOT_RADIUS = 7;
const ARROW_LENGTH = 18;
const ARROW_WIDTH = 4;

const BOX_STROKE = "#374151";
const BODY_FILL = "#2563eb";
const BODY_STROKE = "#1e40af";
const ARROW_FILL = "#ffffff";
const OBSTACLE_FILL = "#4b5563";
const OBSTACLE_STROKE = "#111827";

// ---------------------------------------------------------------------------
// Robot trail configuration.
//
// The trail keeps a short history of the robot's world positions and draws
// them as a fading polyline behind the robot. Tweak `maxPoints` to change
// how long the trail is (at ~60 fps, 120 points ≈ 2 seconds of history).
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
  color: "#93c5fd", // light blue
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
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.rect(0, 0, WORLD_WIDTH, WORLD_HEIGHT);
    ctx.stroke();

    // Subtle inner rect so the box edge is visible against dark backgrounds.
    ctx.strokeStyle = "#1f2937";
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.rect(2, 2, WORLD_WIDTH - 4, WORLD_HEIGHT - 4);
    ctx.stroke();
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

  /** Draw the robot as a body circle plus a heading arrow. */
  drawRobot(pose: RobotPose): void {
    const { ctx } = this;
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

    // Body: filled circle.
    ctx.beginPath();
    ctx.arc(0, 0, ROBOT_RADIUS, 0, 2 * Math.PI);
    ctx.fillStyle = BODY_FILL;
    ctx.fill();
    ctx.strokeStyle = BODY_STROKE;
    ctx.lineWidth = 1.5;
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

  /** Render one frame: box + obstacles + trail + robot at the given (possibly interpolated) pose.
   *
   * Trail points are recorded by the stream (LiveStream / snapshotStream) as snapshots
   * arrive, not here per render frame, so the trail keeps tracing while the tab is hidden
   * (requestAnimationFrame pauses) instead of drawing one jump line on return.
   */
  render(pose: RobotPose, obstacles: Obstacle[] = []): void {
    this.clear();
    this.drawTrail();
    this.drawWorld();
    this.drawObstacles(obstacles);
    this.drawRobot(pose);
  }
}
