import type { RobotPose, WorldSnapshot } from './types';
import { WORLD_WIDTH, WORLD_HEIGHT } from './snapshotStream';

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

const BOX_STROKE = '#374151';
const BODY_FILL = '#2563eb';
const BODY_STROKE = '#1e40af';
const ARROW_FILL = '#ffffff';

export class Renderer {
  private readonly ctx: CanvasRenderingContext2D;
  private readonly canvas: HTMLCanvasElement;
  private pxPerUnit: number;

  constructor(canvas: HTMLCanvasElement) {
    const ctx = canvas.getContext('2d');
    if (!ctx) {
      throw new Error('Could not obtain 2D rendering context from canvas');
    }
    this.ctx = ctx;
    this.canvas = canvas;
    // 1:1 world-to-pixel mapping; canvas size == world size.
    canvas.width = WORLD_WIDTH;
    canvas.height = WORLD_HEIGHT;
    this.pxPerUnit = 1;
  }

  /** Clear the whole canvas. */
  clear(): void {
    const { ctx } = this;
    ctx.clearRect(0, 0, WORLD_WIDTH, WORLD_HEIGHT);
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
    ctx.strokeStyle = '#1f2937';
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.rect(2, 2, WORLD_WIDTH - 4, WORLD_HEIGHT - 4);
    ctx.stroke();
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

  /** Render one frame: box + robot at the given (possibly interpolated) pose. */
  render(pose: RobotPose): void {
    this.clear();
    this.drawWorld();
    this.drawRobot(pose);
  }
}
