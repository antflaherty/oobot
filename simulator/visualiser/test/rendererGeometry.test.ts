import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  DEFAULT_SHAPE,
  FRONT_LIGHT_ID,
  frontLightCircle,
  frontLightFill,
  isFrontLightActive,
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
