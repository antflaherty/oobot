import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  DEFAULT_SHAPE,
  FRONT_LIGHT_ID,
  frontLightCircle,
  frontLightFill,
  goalRect,
  isFrontLightActive,
  Renderer,
  robotWheelRects,
} from '../src/renderer';
import type { WheelRect } from '../src/renderer';

const EPS = 1e-9;

function assertRect(actual: WheelRect, expected: WheelRect): void {
  assert.ok(Math.abs(actual.x - expected.x) < EPS, `x: ${actual.x} != ${expected.x}`);
  assert.ok(Math.abs(actual.y - expected.y) < EPS, `y: ${actual.y} != ${expected.y}`);
  assert.equal(actual.length, expected.length);
  assert.equal(actual.width, expected.width);
}

test('DEFAULT_SHAPE matches the sim defaults', () => {
  assert.equal(DEFAULT_SHAPE.width, 50);
  assert.equal(DEFAULT_SHAPE.height, 50);
  assert.deepEqual(
    DEFAULT_SHAPE.wheels.map((wheel) => [wheel.angle, wheel.offset, wheel.length, wheel.width]),
    [
      [Math.PI / 2, 25, 30, 10],
      [-Math.PI / 2, 25, 30, 10],
    ],
  );
});

test('default wheels mount left/right at the body sides in canvas coordinates', () => {
  const rects = robotWheelRects(DEFAULT_SHAPE);
  assert.equal(rects.length, 2);
  // World +Y (left wheel at +pi/2) maps to canvas -Y (up).
  assertRect(rects[0], { x: 0, y: -25, length: 30, width: 10 });
  assertRect(rects[1], { x: 0, y: 25, length: 30, width: 10 });
});

test('wheel mount geometry is rotated into canvas space without changing dimensions', () => {
  const angle = Math.PI / 4;
  const rects = robotWheelRects({
    width: 20,
    height: 10,
    wheels: [{ angle, offset: 12, length: 30, width: 8 }],
  });
  assert.equal(rects.length, 1);
  assertRect(rects[0], {
    x: 12 * Math.cos(angle),
    y: -12 * Math.sin(angle),
    length: 30,
    width: 8,
  });
});

test('frontLightCircle sits at the front edge, inside the body and clear of the heading arrow', () => {
  const circle = frontLightCircle(DEFAULT_SHAPE);
  assert.equal(circle.radius, 4);
  // Local +X is forward: the circle is tangent to the front body edge.
  assert.equal(circle.x + circle.radius, DEFAULT_SHAPE.width / 2);
  // It stays on/inside the body outline in both canvas axes.
  assert.ok(circle.x - circle.radius >= -DEFAULT_SHAPE.width / 2);
  assert.ok(circle.y - circle.radius >= -DEFAULT_SHAPE.height / 2);
  assert.ok(circle.y + circle.radius <= DEFAULT_SHAPE.height / 2);
  // The white arrow spans |y| <= 4 near the nose; the circle (offset to +Y,
  // canvas-down) must not overlap that band.
  assert.ok(circle.y - circle.radius > 4, `circle at y=${circle.y} overlaps the arrow`);
});

test('frontLightCircle shrinks and stays inside unusually small bodies', () => {
  const small = { width: 6, height: 6, wheels: DEFAULT_SHAPE.wheels };
  const circle = frontLightCircle(small);
  assert.equal(circle.radius, 3);
  assert.ok(circle.x - circle.radius >= -small.width / 2);
  assert.ok(circle.x + circle.radius <= small.width / 2);
  assert.ok(circle.y - circle.radius >= -small.height / 2);
  assert.ok(circle.y + circle.radius <= small.height / 2);
});

test('frontLightFill selects red when active and darker blue otherwise', () => {
  assert.equal(FRONT_LIGHT_ID, 'front-light');
  assert.equal(frontLightFill(true), '#ef4444');
  assert.equal(frontLightFill(false), '#1d4ed8');
});

test('isFrontLightActive only trusts an active front-light tool state', () => {
  assert.equal(isFrontLightActive([]), false);
  assert.equal(isFrontLightActive([{ id: 'other-tool', active: true }]), false);
  assert.equal(isFrontLightActive([{ id: 'front-light', active: false }]), false);
  assert.equal(isFrontLightActive([{ id: 'front-light', active: true }]), true);
  assert.equal(
    isFrontLightActive([
      { id: 'other-tool', active: true },
      { id: 'front-light', active: true },
    ]),
    true,
  );
});

test('goalRect maps the world goal centre and full extents into canvas pixels', () => {
  // Default world goal: centre (-220, -240), 80x80 world units.
  assert.deepEqual(goalRect({ x: -220, y: -240, width: 80, height: 80 }), {
    x: 140,
    y: 500,
    width: 80,
    height: 80,
  });
});

test('goalRect flips world +Y while preserving the goal size', () => {
  assert.deepEqual(goalRect({ x: 100, y: 50, width: 20, height: 60 }), {
    x: 490,
    y: 220,
    width: 20,
    height: 60,
  });
});

// Minimal 2D context stub so drawGoal can be exercised under node:test.
class GoalContextStub {
  fillStyle = '';
  strokeStyle = '';
  lineWidth = 0;
  readonly fills: Array<[number, number, number, number]> = [];
  readonly strokes: Array<[number, number, number, number]> = [];

  save(): void {}
  restore(): void {}
  fillRect(x: number, y: number, w: number, h: number): void {
    this.fills.push([x, y, w, h]);
  }
  strokeRect(x: number, y: number, w: number, h: number): void {
    this.strokes.push([x, y, w, h]);
  }
}

function rendererWith(ctx: GoalContextStub): Renderer {
  const canvas = {
    width: 0,
    height: 0,
    getContext: () => ctx,
  } as unknown as HTMLCanvasElement;
  return new Renderer(canvas);
}

test('drawGoal skips null and fills/strokes the mapped rectangle otherwise', () => {
  const ctx = new GoalContextStub();
  const renderer = rendererWith(ctx);

  renderer.drawGoal(null);
  assert.deepEqual(ctx.fills, []);
  assert.deepEqual(ctx.strokes, []);

  renderer.drawGoal({ x: -220, y: -240, width: 80, height: 80 });
  assert.deepEqual(ctx.fills, [[140, 500, 80, 80]]);
  assert.deepEqual(ctx.strokes, [[140, 500, 80, 80]]);
});
