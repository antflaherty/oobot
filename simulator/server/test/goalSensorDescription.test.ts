// Focused manifest tests for the `goalSensor` component type.
//
// These use inline manifests written to a temporary dir: the committed
// fixtures are byte-identical across languages and are owned by the fixture
// parity suite, so goal-sensor coverage lives here instead.

import assert from "node:assert/strict";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";

import { ComponentRegistry, loadRobotDescription } from "../src/robotDescription";

const BASE_COMPONENTS = [
  { id: "left-wheel", type: "wheel", angle: 1.5707963267948966, offset: 0.25 },
  { id: "right-wheel", type: "wheel", angle: -1.5707963267948966, offset: 0.25 },
  { id: "front-sensor", type: "proximitySensor", angle: 0.0 },
  { id: "right-sensor", type: "proximitySensor", angle: -1.5707963267948966 },
];

function withInlineManifest<T>(extraComponents: string[], fn: (path: string) => T): T {
  const dir = mkdtempSync(join(tmpdir(), "oobot-goal-sensor-manifest-"));
  const path = join(dir, "manifest.json");
  try {
    const components = [...BASE_COMPONENTS.map((c) => JSON.stringify(c)), ...extraComponents];
    writeFileSync(
      path,
      `{"schemaVersion":2,"name":"goal-sensor-inline","components":[${components.join(",")}]}`,
    );
    return fn(path);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

test("goalSensor: loader accepts point-mounted goal sensors with any finite angle", () => {
  const description = withInlineManifest(
    [
      '{"id":"goal-sensor","type":"goalSensor","angle":0.0}',
      '{"id":"rear-goal","type":"goalSensor","angle":3.0}',
    ],
    (path) => loadRobotDescription(path),
  );
  assert.equal(description.components.length, 6);
  assert.deepEqual(
    description.components.filter((c) => c.type === "goalSensor"),
    [
      { id: "goal-sensor", type: "goalSensor", angle: 0.0, offset: 0 },
      { id: "rear-goal", type: "goalSensor", angle: 3.0, offset: 0 },
    ],
  );
});

test("goalSensor: unknown keys are ignored", () => {
  const description = withInlineManifest(
    ['{"id":"goal-sensor","type":"goalSensor","angle":1.25,"colour":"green"}'],
    (path) => loadRobotDescription(path),
  );
  assert.deepEqual(description.components[4], {
    id: "goal-sensor",
    type: "goalSensor",
    angle: 1.25,
    offset: 0,
  });
});

for (const [label, goalJson, token] of [
  ["offset", '{"id":"goal-sensor","type":"goalSensor","angle":0.0,"offset":0.25}', "offset"],
  ["non-finite angle", '{"id":"goal-sensor","type":"goalSensor","angle":1e999}', "angle"],
  ["missing angle", '{"id":"goal-sensor","type":"goalSensor"}', "angle"],
  ["side field", '{"id":"goal-sensor","type":"goalSensor","angle":0.0,"side":"front"}', "side"],
] as const) {
  test(`goalSensor: loader rejects a goal sensor with ${label}`, () => {
    withInlineManifest([goalJson], (path) => {
      assert.throws(
        () => loadRobotDescription(path),
        (err: unknown) => {
          assert.ok(err instanceof Error);
          assert.ok(err.message.includes(path), `error must name path: ${err.message}`);
          assert.ok(err.message.includes("component[4]"), `error must name index: ${err.message}`);
          assert.ok(err.message.includes(token), `error must name ${token}: ${err.message}`);
          return true;
        },
      );
    });
  });
}

test("goalSensor: registry exposes goal sensors separately from proximity sensors and tools", () => {
  const description = withInlineManifest(
    [
      '{"id":"front-light","type":"tool","angle":0.0}',
      '{"id":"goal-sensor","type":"goalSensor","angle":0.0}',
      '{"id":"second-goal","type":"goalSensor","angle":-1.5}',
    ],
    (path) => loadRobotDescription(path),
  );
  const registry = new ComponentRegistry(description);
  assert.deepEqual(
    registry.goalSensors().map((c) => c.id),
    ["goal-sensor", "second-goal"],
  );
  assert.deepEqual(
    registry.sensors().map((c) => c.id),
    ["front-sensor", "right-sensor"],
  );
  assert.deepEqual(
    registry.tools().map((c) => c.id),
    ["front-light"],
  );
  assert.deepEqual(
    registry.wheels().map((c) => c.id),
    ["left-wheel", "right-wheel"],
  );
  assert.equal(registry.get("goal-sensor").type, "goalSensor");
});

test("goalSensor: registry returns an empty list when no goal sensor is declared", () => {
  const description = withInlineManifest([], (path) => loadRobotDescription(path));
  assert.deepEqual(new ComponentRegistry(description).goalSensors(), []);
});

test("goalSensor: unknown component types are still rejected", () => {
  withInlineManifest(['{"id":"mystery","type":"laser","angle":0.0}'], (path) => {
    assert.throws(
      () => loadRobotDescription(path),
      (err: unknown) => {
        assert.ok(err instanceof Error);
        assert.ok(err.message.includes(path));
        assert.ok(err.message.includes("component[4]"));
        assert.ok(err.message.includes("type"));
        assert.ok(err.message.includes("goalSensor"));
        return true;
      },
    );
  });
});
