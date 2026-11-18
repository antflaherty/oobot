// Manifest-validation parity fixtures for S1.3.
//
// The fixture set and accept/reject decisions are specified verbatim in
// docs/DECISIONS.md ("Validation fixture set"). Every fixture is loaded through
// the same public loader used by the sim server, and each rejection is checked
// for the two error-message tokens required by that document: the manifest
// path and, for component-scoped failures, the 0-based component[index].

import assert from "node:assert/strict";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";

import {
  ComponentRegistry,
  DEFAULT_ROBOT_BODY,
  RobotBody,
  RobotDescription,
  loadRobotDescription,
} from "../src/robotDescription";

// Tests compile to dist/test -> dist-test/test; fixtures stay in source test/fixtures.
const FIXTURES = join(__dirname, "..", "..", "test", "fixtures");

interface FixtureCase {
  file: string;
  accept: boolean;
  components?: number;
  trackWidth?: number;
  body?: RobotBody;
  // 0-based index that must appear as component[i] in a rejection message.
  componentIndex?: number;
  // Field path that must appear in a manifest-level rejection message.
  errorToken?: string;
}

const cases: FixtureCase[] = [
  { file: "valid-default.json", accept: true, components: 6, trackWidth: 0.5 },
  {
    file: "accept-body.json",
    accept: true,
    components: 4,
    trackWidth: 0.5,
    body: { width: 0.6, height: 0.4, wheels: { length: 0.25, width: 0.12 } },
  },
  { file: "accept-proximity-sensor-spelling.json", accept: true, components: 4 },
  { file: "accept-sensor-without-offset.json", accept: true, components: 4 },
  {
    file: "accept-alternative-angles.json",
    accept: true,
    components: 4,
    trackWidth: 0.6 * Math.sin(2.0),
  },
  { file: "reject-schema-v1.json", accept: false },
  { file: "reject-schema-float.json", accept: false },
  { file: "reject-side-field.json", accept: false, componentIndex: 0 },
  { file: "reject-duplicate-ids.json", accept: false, componentIndex: 1 },
  { file: "reject-blank-id.json", accept: false, componentIndex: 0 },
  { file: "reject-blank-name.json", accept: false },
  { file: "reject-non-finite-angle.json", accept: false, componentIndex: 0 },
  { file: "reject-body-width-zero.json", accept: false, errorToken: "body.width" },
  {
    file: "reject-body-wheels-non-finite.json",
    accept: false,
    errorToken: "body.wheels.length",
  },
  { file: "reject-body-not-object.json", accept: false, errorToken: "body must be" },
  { file: "reject-wheel-offset-zero.json", accept: false, componentIndex: 0 },
  { file: "reject-wheel-offset-negative.json", accept: false, componentIndex: 1 },
  { file: "reject-two-left-wheels.json", accept: false, componentIndex: 1 },
  { file: "reject-no-right-wheel.json", accept: false },
  { file: "reject-sensor-with-offset.json", accept: false, componentIndex: 2 },
];

for (const fixture of cases) {
  test(`${fixture.accept ? "accept" : "reject"}: ${fixture.file}`, () => {
    const path = join(FIXTURES, fixture.file);

    if (fixture.accept) {
      const description = loadRobotDescription(path);
      assert.equal(description.schemaVersion, 2, "schemaVersion");
      if (fixture.components !== undefined) {
        assert.equal(description.components.length, fixture.components, "component count");
      }
      if (fixture.trackWidth !== undefined) {
        const actual = new ComponentRegistry(description).trackWidth();
        assert.ok(
          Math.abs(actual - fixture.trackWidth) < 1e-9,
          `track width: expected ${fixture.trackWidth}, got ${actual}`,
        );
      }
      assert.deepEqual(
        description.body,
        fixture.body ?? DEFAULT_ROBOT_BODY,
        `${fixture.file}: body`,
      );
      return;
    }

    let thrown: unknown;
    try {
      loadRobotDescription(path);
    } catch (err) {
      thrown = err;
    }
    assert.ok(thrown instanceof Error, `${fixture.file}: expected loadRobotDescription to throw`);

    const message = (thrown as Error).message;
    assert.ok(message.includes(path), `${fixture.file}: error must name path ${path}: ${message}`);
    if (fixture.componentIndex !== undefined) {
      const token = `component[${fixture.componentIndex}]`;
      assert.ok(
        message.includes(token),
        `${fixture.file}: error must include ${token}: ${message}`,
      );
    }
    if (fixture.errorToken !== undefined) {
      assert.ok(
        message.includes(fixture.errorToken),
        `${fixture.file}: error must include ${fixture.errorToken}: ${message}`,
      );
    }
  });
}

test("body defaults to 0.5 x 0.5 with 0.3 x 0.1 wheels when omitted", () => {
  const description = loadRobotDescription(join(FIXTURES, "valid-default.json"));
  assert.deepEqual(description.body, {
    width: 0.5,
    height: 0.5,
    wheels: { length: 0.3, width: 0.1 },
  });
});

// Tool-specific validation uses inline manifests written to a temporary dir:
// the committed fixtures are byte-identical across languages and must not be
// extended just for a sim-only test.
const BASE_COMPONENTS = [
  { id: "left-wheel", type: "wheel", angle: 1.5707963267948966, offset: 0.25 },
  { id: "right-wheel", type: "wheel", angle: -1.5707963267948966, offset: 0.25 },
  { id: "front-sensor", type: "proximitySensor", angle: 0.0 },
  { id: "right-sensor", type: "proximitySensor", angle: -1.5707963267948966 },
];

function withInlineManifest<T>(toolComponents: string[], fn: (path: string) => T): T {
  const dir = mkdtempSync(join(tmpdir(), "oobot-tool-manifest-"));
  const path = join(dir, "manifest.json");
  try {
    const components = [...BASE_COMPONENTS.map((c) => JSON.stringify(c)), ...toolComponents];
    writeFileSync(
      path,
      `{"schemaVersion":2,"name":"tool-inline","components":[${components.join(",")}]}`,
    );
    return fn(path);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

test("tool: loader accepts point-mounted tools with any finite angle", () => {
  const description = withInlineManifest(
    [
      '{"id":"front-light","type":"tool","angle":0.0}',
      '{"id":"rear-light","type":"tool","angle":3.0}',
    ],
    (path) => loadRobotDescription(path),
  );
  assert.equal(description.components.length, 6);
  assert.deepEqual(
    description.components.filter((c) => c.type === "tool"),
    [
      { id: "front-light", type: "tool", angle: 0.0, offset: 0 },
      { id: "rear-light", type: "tool", angle: 3.0, offset: 0 },
    ],
  );
});

for (const [label, toolJson, token] of [
  ["offset", '{"id":"front-light","type":"tool","angle":0.0,"offset":0.25}', "offset"],
  ["non-finite angle", '{"id":"front-light","type":"tool","angle":1e999}', "angle"],
] as const) {
  test(`tool: loader rejects a tool with ${label}`, () => {
    withInlineManifest([toolJson], (path) => {
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

test("tool: unknown component types are still rejected", () => {
  withInlineManifest(['{"id":"mystery","type":"laser","angle":0.0}'], (path) => {
    assert.throws(
      () => loadRobotDescription(path),
      (err: unknown) => {
        assert.ok(err instanceof Error);
        assert.ok(err.message.includes(path));
        assert.ok(err.message.includes("component[4]"));
        assert.ok(err.message.includes("type"));
        return true;
      },
    );
  });
});

test("tool: registry keeps tools separate from wheels and sensors in manifest order", () => {
  const description: RobotDescription = loadRobotDescription(
    join(FIXTURES, "valid-default.json"),
  );
  const registry = new ComponentRegistry(description);
  assert.deepEqual(
    registry.tools().map((c) => c.id),
    ["front-light"],
  );
  assert.deepEqual(
    registry.sensors().map((c) => c.id),
    ["front-sensor", "right-sensor"],
  );
  assert.deepEqual(
    registry.wheels().map((c) => c.id),
    ["left-wheel", "right-wheel"],
  );
  assert.equal(registry.get("front-light").type, "tool");
});

test("default manifest keeps front-light at index 4 and appends goal-sensor at index 5", () => {
  const description = loadRobotDescription(join(FIXTURES, "valid-default.json"));

  assert.equal(description.components.length, 6, "component count");
  assert.deepEqual(description.components[4], {
    id: "front-light",
    type: "tool",
    angle: 0.0,
    offset: 0,
  });
  assert.deepEqual(description.components[5], {
    id: "goal-sensor",
    type: "goalSensor",
    angle: 0.0,
    offset: 0,
  });
});

test("registry keeps goal sensors separate from proximity sensors", () => {
  const description: RobotDescription = loadRobotDescription(
    join(FIXTURES, "valid-default.json"),
  );
  const registry = new ComponentRegistry(description);

  assert.deepEqual(
    registry.sensors().map((c) => c.id),
    ["front-sensor", "right-sensor"],
  );
  assert.deepEqual(
    registry.goalSensors().map((c) => c.id),
    ["goal-sensor"],
  );
});
