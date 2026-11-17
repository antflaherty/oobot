// Characterization tests for today's WorldModel behaviour.
//
// These assertions deliberately pin the current kinematics (saturated angular
// rate, boundary-only clamped flags) so S1.1 can change them on purpose rather
// than by accident. Update this file as part of S1.1 when the physics is fixed.
// These are pure model tests: no sockets and no file I/O.

import assert from "node:assert/strict";
import { test } from "node:test";

import { DEFAULT_WORLD_CONFIG, WorldModel } from "../src/world";

const TOLERANCE = 1e-9;
const STEP = DEFAULT_WORLD_CONFIG.maxSpeed / DEFAULT_WORLD_CONFIG.tickRate; // 100 / 60
const SATURATED_TURN_STEP =
  DEFAULT_WORLD_CONFIG.maxAngularSpeed / DEFAULT_WORLD_CONFIG.tickRate;

function approx(actual: number, expected: number, label: string): void {
  assert.ok(
    Math.abs(actual - expected) < TOLERANCE,
    `${label}: expected ${expected}, got ${actual}`,
  );
}

test("straight motion at full power advances one max-speed step per tick", () => {
  const world = new WorldModel();
  const flags = world.apply(1, 1);

  approx(world.pose.x, STEP, "x after one tick");
  approx(world.pose.y, 0, "y after one tick");
  approx(world.pose.heading, 0, "heading after one tick");
  assert.deepEqual(flags, { clampedX: false, clampedY: false, collided: false });

  for (let tick = 1; tick < DEFAULT_WORLD_CONFIG.tickRate; tick++) {
    world.apply(1, 1);
  }
  approx(world.pose.x, DEFAULT_WORLD_CONFIG.maxSpeed, "x after one second");
  approx(world.pose.heading, 0, "heading after one second");
});

test("equal left/right powers drive straight at proportional speed", () => {
  const half = new WorldModel();
  half.apply(0.5, 0.5);
  approx(half.pose.x, STEP / 2, "half-power x");
  approx(half.pose.y, 0, "half-power y");
  approx(half.pose.heading, 0, "half-power heading");

  const reverse = new WorldModel();
  reverse.apply(-1, -1);
  approx(reverse.pose.x, -STEP, "full-reverse x");
  approx(reverse.pose.y, 0, "full-reverse y");
  approx(reverse.pose.heading, 0, "full-reverse heading");
});

test("symmetric left/right differentials produce mirror-image saturated turns", () => {
  const turnLeft = new WorldModel();
  turnLeft.apply(-1, 1);
  const turnRight = new WorldModel();
  turnRight.apply(1, -1);

  approx(turnLeft.pose.x, 0, "pure-turn x (left)");
  approx(turnLeft.pose.y, 0, "pure-turn y (left)");
  approx(turnRight.pose.x, 0, "pure-turn x (right)");
  approx(turnRight.pose.y, 0, "pure-turn y (right)");
  approx(turnLeft.pose.heading, SATURATED_TURN_STEP, "pure-turn heading (left)");
  approx(turnRight.pose.heading, -SATURATED_TURN_STEP, "pure-turn heading (right)");

  // Today even a small differential saturates the independent angular clamp.
  const smallDifferential = new WorldModel();
  smallDifferential.apply(-0.05, 0.05);
  approx(smallDifferential.pose.x, 0, "small-differential x");
  approx(smallDifferential.pose.y, 0, "small-differential y");
  approx(
    smallDifferential.pose.heading,
    SATURATED_TURN_STEP,
    "small-differential heading",
  );
});

test("zero power holds the current pose", () => {
  const fromRest = new WorldModel();
  assert.deepEqual(
    fromRest.apply(0, 0),
    { clampedX: false, clampedY: false, collided: false },
    "zero-power flags from rest",
  );
  approx(fromRest.pose.x, 0, "zero-power x from rest");
  approx(fromRest.pose.y, 0, "zero-power y from rest");
  approx(fromRest.pose.heading, 0, "zero-power heading from rest");

  const moving = new WorldModel();
  moving.apply(1, 1);
  moving.apply(-1, 1); // acquire a non-zero heading before stopping
  const held = { ...moving.pose };
  const flags = moving.apply(0, 0);
  approx(moving.pose.x, held.x, "held x");
  approx(moving.pose.y, held.y, "held y");
  approx(moving.pose.heading, held.heading, "held heading");
  assert.deepEqual(flags, { clampedX: false, clampedY: false, collided: false });
});

interface EdgeCase {
  label: string;
  start: { x: number; y: number; heading: number };
  left: number;
  right: number;
  expected: { x: number; y: number };
  clampedAxis: "clampedX" | "clampedY";
}

const maxX = DEFAULT_WORLD_CONFIG.width / 2;
const maxY = DEFAULT_WORLD_CONFIG.height / 2;

const edgeCases: EdgeCase[] = [
  {
    label: "right edge",
    start: { x: maxX - 1, y: 0, heading: 0 },
    left: 1,
    right: 1,
    expected: { x: maxX, y: 0 },
    clampedAxis: "clampedX",
  },
  {
    label: "left edge",
    start: { x: -maxX + 1, y: 0, heading: 0 },
    left: -1,
    right: -1,
    expected: { x: -maxX, y: 0 },
    clampedAxis: "clampedX",
  },
  {
    label: "top edge",
    start: { x: 0, y: maxY - 1, heading: Math.PI / 2 },
    left: 1,
    right: 1,
    expected: { x: 0, y: maxY },
    clampedAxis: "clampedY",
  },
  {
    label: "bottom edge",
    start: { x: 0, y: -maxY + 1, heading: -Math.PI / 2 },
    left: 1,
    right: 1,
    expected: { x: 0, y: -maxY },
    clampedAxis: "clampedY",
  },
];

for (const edge of edgeCases) {
  test(`box clamping: ${edge.label}`, () => {
    const world = new WorldModel(DEFAULT_WORLD_CONFIG, edge.start);
    const flags = world.apply(edge.left, edge.right);

    approx(world.pose.x, edge.expected.x, `${edge.label} x`);
    approx(world.pose.y, edge.expected.y, `${edge.label} y`);
    assert.equal(flags[edge.clampedAxis], true, `${edge.label} clamped flag`);
    const otherAxis = edge.clampedAxis === "clampedX" ? "clampedY" : "clampedX";
    assert.equal(flags[otherAxis], false, `${edge.label} other axis not clamped`);
    // Today only obstacles set collided; S1.1 may deliberately change this.
    assert.equal(flags.collided, false, `${edge.label} collided flag`);
  });
}
