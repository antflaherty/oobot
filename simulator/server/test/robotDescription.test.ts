// Manifest-validation parity fixtures for S1.3.
//
// The fixture set and accept/reject decisions are specified verbatim in
// docs/DECISIONS.md ("Validation fixture set"). Every fixture is loaded through
// the same public loader used by the sim server, and each rejection is checked
// for the two error-message tokens required by that document: the manifest
// path and, for component-scoped failures, the 0-based component[index].

import assert from "node:assert/strict";
import { join } from "node:path";
import { test } from "node:test";

import { ComponentRegistry, loadRobotDescription } from "../src/robotDescription";

// Tests compile to dist/test -> dist-test/test; fixtures stay in source test/fixtures.
const FIXTURES = join(__dirname, "..", "..", "test", "fixtures");

interface FixtureCase {
  file: string;
  accept: boolean;
  components?: number;
  trackWidth?: number;
  // 0-based index that must appear as component[i] in a rejection message.
  componentIndex?: number;
}

const cases: FixtureCase[] = [
  { file: "valid-default.json", accept: true, components: 4, trackWidth: 0.5 },
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
  });
}
