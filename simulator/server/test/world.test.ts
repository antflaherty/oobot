// Focused tests for the hardened WorldModel behaviour and world-file
// validation (D-1, collision ray fix, spawn validation). Pure model tests:
// no sockets and no file I/O.

import assert from "node:assert/strict";
import { test } from "node:test";

import {
  DEFAULT_WORLD_CONFIG,
  WORLD_UNITS_PER_METRE,
  WorldModel,
  parseWorld,
  rayVsAxisAlignedBox,
} from "../src/world";

const TOLERANCE = 1e-9;
const STEP = DEFAULT_WORLD_CONFIG.maxSpeed / DEFAULT_WORLD_CONFIG.tickRate; // 100 / 60
const FULL_TURN_RATE =
  (2 * DEFAULT_WORLD_CONFIG.maxSpeed) /
  WORLD_UNITS_PER_METRE /
  DEFAULT_WORLD_CONFIG.trackWidth; // 4 rad/s with the default config
const FULL_TURN_STEP = FULL_TURN_RATE / DEFAULT_WORLD_CONFIG.tickRate;

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

test("differential drive turns proportionally to wheel-power difference", () => {
  const turnLeft = new WorldModel();
  turnLeft.apply(-1, 1);
  const turnRight = new WorldModel();
  turnRight.apply(1, -1);

  approx(turnLeft.pose.x, 0, "pure-turn x (left)");
  approx(turnLeft.pose.y, 0, "pure-turn y (left)");
  approx(turnRight.pose.x, 0, "pure-turn x (right)");
  approx(turnRight.pose.y, 0, "pure-turn y (right)");
  approx(FULL_TURN_RATE, 4, "full-differential rate");
  approx(turnLeft.pose.heading, FULL_TURN_STEP, "pure-turn heading (left)");
  approx(turnRight.pose.heading, -FULL_TURN_STEP, "pure-turn heading (right)");

  const halfDifferential = new WorldModel();
  halfDifferential.apply(-0.5, 0.5);
  approx(halfDifferential.pose.heading, FULL_TURN_STEP / 2, "half-differential heading");

  const smallDifferential = new WorldModel();
  smallDifferential.apply(-0.05, 0.05);
  approx(
    smallDifferential.pose.heading,
    (FULL_TURN_RATE * 0.05) / DEFAULT_WORLD_CONFIG.tickRate,
    "small-differential heading",
  );
});

test("angular rate follows the metre-based formula with no independent clamp", () => {
  assert.equal(WORLD_UNITS_PER_METRE, 100);
  const leftPower = -0.8;
  const rightPower = 0.3;
  const world = new WorldModel();
  world.apply(leftPower, rightPower);

  const vLeft = (leftPower * DEFAULT_WORLD_CONFIG.maxSpeed) / WORLD_UNITS_PER_METRE;
  const vRight = (rightPower * DEFAULT_WORLD_CONFIG.maxSpeed) / WORLD_UNITS_PER_METRE;
  const expectedAngular =
    (vRight - vLeft) / DEFAULT_WORLD_CONFIG.trackWidth / DEFAULT_WORLD_CONFIG.tickRate;
  const expectedTravel =
    Math.abs(((vLeft + vRight) / 2) * WORLD_UNITS_PER_METRE) / DEFAULT_WORLD_CONFIG.tickRate;

  approx(world.pose.heading, expectedAngular, "angular rate from D-1 formula");
  approx(Math.hypot(world.pose.x, world.pose.y), expectedTravel, "linear travel unchanged");
  assert.ok(
    Math.abs(world.pose.heading) > Math.PI / 2 / DEFAULT_WORLD_CONFIG.tickRate,
    "rate exceeds the removed maxAngularSpeed clamp",
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
    assert.equal(flags.collided, true, `${edge.label} reports a boundary collision`);
  });
}

// Box spans x[9, 11], y[-10, 10].
const wall = { x: 10, y: 0, width: 2, height: 20 };

test("ray from a box face moving outward has no hit; inward is a t=0 contact", () => {
  assert.equal(rayVsAxisAlignedBox(9, 0, -1, 0, wall), null);
  assert.equal(rayVsAxisAlignedBox(11, 0, 1, 0, wall), null);

  const inward = rayVsAxisAlignedBox(9, 0, 1, 0, wall);
  assert.equal(inward, 0);
  assert.equal(Object.is(inward, -0), false, "contact distance is positive zero");
});

test("ray starting inside a box moving outward has no hit", () => {
  assert.equal(rayVsAxisAlignedBox(10, 0, 1, 0, wall), null);
  assert.equal(rayVsAxisAlignedBox(10, 0, -1, 0, wall), null);
});

test("epsilon-crossing at a face does not create a spurious hit", () => {
  assert.equal(rayVsAxisAlignedBox(9 - 1e-10, 0, -1, 0, wall), null);
  assert.equal(rayVsAxisAlignedBox(11 + 1e-10, 0, 1, 0, wall), null);
  assert.equal(rayVsAxisAlignedBox(9, 0, 0, 1, wall), null, "grazing along a face");
});

test("ray from outside hits the near face", () => {
  const hit = rayVsAxisAlignedBox(5, 0, 1, 0, wall);
  assert.ok(hit !== null, "expected a hit");
  approx(hit, 4, "near face distance");
});

test("robot backed away from a wall translates on the first tick after contact", () => {
  const world = new WorldModel(DEFAULT_WORLD_CONFIG, { x: 0, y: 0, heading: 0 }, [wall]);
  let flags = world.apply(1, 1);
  for (let tick = 0; tick < 100 && !flags.collided; tick++) {
    flags = world.apply(1, 1);
  }
  assert.equal(flags.collided, true, "forward motion eventually hits the wall");
  approx(world.pose.x, 9, "pose at the wall face");

  const reverseFlags = world.apply(-1, -1);
  approx(world.pose.x, 9 - STEP, "x after one reverse tick");
  assert.equal(reverseFlags.collided, false, "backing away is not a collision");
});

const WORLD_PATH = "/worlds/test-world.json";

function worldJson(robot: unknown, obstacles: unknown = []): string {
  return JSON.stringify({ width: 800, height: 600, robot, obstacles });
}

function validationError(raw: string): string {
  try {
    parseWorld(raw, WORLD_PATH);
  } catch (err) {
    return err instanceof Error ? err.message : String(err);
  }
  assert.fail("expected parseWorld to reject the world");
}

test("world loader accepts a start pose inside the box and outside obstacles", () => {
  const loaded = parseWorld(
    worldJson({ x: 0, y: 0, heading: 0 }, [{ x: 10, y: 0, width: 2, height: 2 }]),
    WORLD_PATH,
  );
  assert.deepEqual(loaded.startPose, { x: 0, y: 0, heading: 0 });
  assert.equal(loaded.config.width, 800);
  assert.equal(loaded.obstacles.length, 1);
});

test("world loader accepts a start pose on an obstacle face", () => {
  const loaded = parseWorld(
    worldJson({ x: 9, y: 0, heading: 0 }, [wall]),
    WORLD_PATH,
  );
  assert.deepEqual(loaded.startPose, { x: 9, y: 0, heading: 0 });
});

test("world loader rejects a start pose outside the box", () => {
  const message = validationError(worldJson({ x: 500, y: 0, heading: 0 }));
  assert.ok(message.includes(WORLD_PATH), `error names the world file: ${message}`);
  assert.ok(message.includes("outside the box"), `error explains the problem: ${message}`);
});

test("world loader rejects a start pose inside an obstacle", () => {
  const message = validationError(
    worldJson({ x: 10, y: 0, heading: 0 }, [wall]),
  );
  assert.ok(message.includes(WORLD_PATH), `error names the world file: ${message}`);
  assert.ok(message.includes("obstacle[0]"), `error names the obstacle: ${message}`);
});

test("obstacles are validated before the start pose", () => {
  const raw = JSON.stringify({
    width: 800,
    height: 600,
    robot: { x: 500, y: 0, heading: 0 },
    obstacles: [{ x: 10, y: 0, width: -2, height: 2 }],
  });
  const message = validationError(raw);
  assert.ok(message.includes("each obstacle must"), `obstacle error wins: ${message}`);
});
