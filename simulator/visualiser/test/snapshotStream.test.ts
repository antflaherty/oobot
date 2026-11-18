import { test } from 'node:test';
import assert from 'node:assert/strict';

import { SNAPSHOTS, WORLD_WIDTH, WORLD_HEIGHT, DEFAULT_SHAPE } from '../src/snapshotStream';
import { wrapAngle } from '../src/math';

// Renderer draws the body shape centred on the pose plus a heading arrow.
// Allow a small margin when checking the world box.
const BODY_MARGIN = 20;
const MOTION_EPS = 1e-6;
const HEADING_EPS = 1e-6;

interface Pose {
  x: number;
  y: number;
  heading: number;
}

function poseAt(i: number): Pose {
  return SNAPSHOTS[i].payload.robot;
}

test('every fallback snapshot pose stays inside the world box', () => {
  assert.ok(SNAPSHOTS.length > 0, 'the fallback stream must not be empty');
  const maxX = WORLD_WIDTH / 2 + BODY_MARGIN;
  const maxY = WORLD_HEIGHT / 2 + BODY_MARGIN;

  SNAPSHOTS.forEach((snapshot, i) => {
    const { x, y } = snapshot.payload.robot;
    assert.ok(
      Number.isFinite(x) && Math.abs(x) <= maxX,
      `snapshot[${i}] tick=${snapshot.simTick}: |x|=${Math.abs(x)} > ${maxX}`,
    );
    assert.ok(
      Number.isFinite(y) && Math.abs(y) <= maxY,
      `snapshot[${i}] tick=${snapshot.simTick}: |y|=${Math.abs(y)} > ${maxY}`,
    );
  });
});

test('every fallback snapshot carries the default robot shape', () => {
  assert.ok(SNAPSHOTS.length > 0);
  SNAPSHOTS.forEach((snapshot, i) => {
    assert.deepEqual(
      snapshot.payload.robot.shape,
      DEFAULT_SHAPE,
      `snapshot[${i}] tick=${snapshot.simTick} must include the default shape`,
    );
  });

  assert.equal(DEFAULT_SHAPE.width, 50);
  assert.equal(DEFAULT_SHAPE.height, 50);
  assert.equal(DEFAULT_SHAPE.wheels.length, 2);
});

test('heading matches direction of travel for every moving snapshot pair', () => {
  let movingPairs = 0;

  for (let i = 1; i < SNAPSHOTS.length; i++) {
    const a = poseAt(i - 1);
    const b = poseAt(i);
    const dx = b.x - a.x;
    const dy = b.y - a.y;
    if (Math.hypot(dx, dy) <= MOTION_EPS) continue; // turn-in-place pair

    movingPairs++;
    const travelHeading = Math.atan2(dy, dx);
    const ticks = `${SNAPSHOTS[i - 1].simTick}->${SNAPSHOTS[i].simTick}`;
    assert.ok(
      Math.abs(wrapAngle(a.heading - travelHeading)) <= HEADING_EPS,
      `pair ${ticks}: start heading ${a.heading} != travel heading ${travelHeading}`,
    );
    assert.ok(
      Math.abs(wrapAngle(b.heading - travelHeading)) <= HEADING_EPS,
      `pair ${ticks}: end heading ${b.heading} != travel heading ${travelHeading}`,
    );
  }

  assert.ok(movingPairs > 0, 'expected at least one translating snapshot pair');
});

test('turn-in-place keyframes show no translation', () => {
  let turningPairs = 0;

  for (let i = 1; i < SNAPSHOTS.length; i++) {
    const a = poseAt(i - 1);
    const b = poseAt(i);
    if (Math.abs(wrapAngle(b.heading - a.heading)) <= HEADING_EPS) continue;

    turningPairs++;
    const distance = Math.hypot(b.x - a.x, b.y - a.y);
    const ticks = `${SNAPSHOTS[i - 1].simTick}->${SNAPSHOTS[i].simTick}`;
    assert.ok(
      distance <= MOTION_EPS,
      `turning pair ${ticks} translated by ${distance} world units`,
    );
  }

  // The fallback path has six distinct turn-in-place keyframe segments.
  assert.ok(turningPairs >= 6, `expected at least six turning pairs, got ${turningPairs}`);
});
