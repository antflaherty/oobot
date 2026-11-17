import { test } from 'node:test';
import assert from 'node:assert/strict';

import { wrapAngle, angleLerp, clamp01 } from '../src/math';

const EPS = 1e-12;

test('wrapAngle wraps angles into [-PI, PI]', () => {
  assert.ok(Math.abs(wrapAngle(0)) < EPS);
  assert.ok(Math.abs(wrapAngle(2 * Math.PI)) < EPS);
  assert.ok(Math.abs(wrapAngle(-2 * Math.PI)) < EPS);
  assert.ok(Math.abs(wrapAngle(Math.PI / 2) - Math.PI / 2) < EPS);
  assert.ok(Math.abs(wrapAngle(Math.PI + 0.1) - (-Math.PI + 0.1)) < EPS);
  assert.ok(Math.abs(wrapAngle(-Math.PI - 0.1) - (Math.PI - 0.1)) < EPS);
});

test('clamp01 clamps to the unit interval', () => {
  assert.equal(clamp01(-10), 0);
  assert.equal(clamp01(0), 0);
  assert.equal(clamp01(0.25), 0.25);
  assert.equal(clamp01(1), 1);
  assert.equal(clamp01(10), 1);
});

test('angleLerp takes the shortest path across the wrap', () => {
  const result = angleLerp((350 * Math.PI) / 180, (10 * Math.PI) / 180, 0.5);
  assert.ok(Math.abs(result) < EPS, `expected ~0, got ${result}`);
});
