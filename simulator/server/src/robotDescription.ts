import { readFileSync } from 'node:fs';

export type ComponentType = 'wheel' | 'proximitySensor';

export interface ComponentDescription {
  id: string;
  type: ComponentType;
  angle: number;
  // Radial distance from the centre along the mount angle. Wheels default to
  // 0.25; sensors are point-mounted (0) and may not declare one.
  offset: number;
}

export interface RobotDescription {
  schemaVersion: number;
  name: string;
  components: ComponentDescription[];
}

const COMPONENT_TYPES: ReadonlySet<string> = new Set<ComponentType>(['wheel', 'proximitySensor']);
const DEFAULT_WHEEL_OFFSET = 0.25;

type WheelRole = 'left' | 'right';

// Lateral position derived from the mount angle: +sin = left, -sin = right.
// Mount angles near the robot's fore/aft axis are not valid wheel positions.
function deriveWheelRole(angle: number): WheelRole | null {
  const lateral = Math.sin(angle);
  if (lateral > 0.9) return 'left';
  if (lateral < -0.9) return 'right';
  return null;
}

function errorMessage(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

function fail(path: string, problem: string): never {
  throw new Error(`${path}: ${problem}`);
}

// JSON.parse normalises numeric literals ("2.0" and "2" both become the number
// 2), so the raw source token is inspected to enforce the integer literal 2.
function hasIntegerSchemaVersionLiteral(raw: string): boolean {
  const match = /"schemaVersion"\s*:\s*(-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?)/.exec(raw);
  return match !== null && match[1] === '2';
}

export function loadRobotDescription(path: string): RobotDescription {
  let raw: string;
  try {
    raw = readFileSync(path, 'utf8');
  } catch (err) {
    return fail(path, `cannot read file (${errorMessage(err)})`);
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(raw) as unknown;
  } catch (err) {
    return fail(path, `invalid JSON (${errorMessage(err)})`);
  }

  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    return fail(path, 'expected a JSON object');
  }

  const description = parsed as Record<string, unknown>;
  if (description.schemaVersion !== 2 || !hasIntegerSchemaVersionLiteral(raw)) {
    return fail(path, 'schemaVersion must be the integer literal 2');
  }
  if (typeof description.name !== 'string' || description.name.trim().length === 0) {
    return fail(path, 'name must be a non-blank string');
  }
  if (!Array.isArray(description.components)) {
    return fail(path, 'components must be an array');
  }

  const items = description.components as unknown[];
  const ids = new Set<string>();
  const wheelCounts: Record<WheelRole, number> = { left: 0, right: 0 };
  const components: ComponentDescription[] = [];

  for (const [index, value] of items.entries()) {
    const at = `component[${index}]`;

    if (typeof value !== 'object' || value === null || Array.isArray(value)) {
      return fail(path, `${at} must be an object`);
    }
    const component = value as Record<string, unknown>;

    if (typeof component.id !== 'string' || component.id.trim().length === 0) {
      return fail(path, `${at}.id must be a non-blank string`);
    }
    const id = component.id;
    if (ids.has(id)) {
      return fail(path, `${at}: duplicate component id "${id}"`);
    }
    ids.add(id);

    if (typeof component.type !== 'string' || !COMPONENT_TYPES.has(component.type)) {
      return fail(path, `${at}.type (id "${id}") must be "wheel" or "proximitySensor"`);
    }
    const type = component.type as ComponentType;

    if (typeof component.angle !== 'number' || !Number.isFinite(component.angle)) {
      return fail(path, `${at}.angle (id "${id}") must be a finite number`);
    }
    const angle = component.angle;

    if (Object.prototype.hasOwnProperty.call(component, 'side')) {
      return fail(path, `${at}.side (id "${id}") is not supported in schemaVersion 2`);
    }

    const declaresOffset = Object.prototype.hasOwnProperty.call(component, 'offset');
    if (type === 'wheel') {
      let offset = DEFAULT_WHEEL_OFFSET;
      if (declaresOffset) {
        if (
          typeof component.offset !== 'number' ||
          !Number.isFinite(component.offset) ||
          component.offset <= 0
        ) {
          return fail(path, `${at}.offset (wheel "${id}") must be a finite number > 0`);
        }
        offset = component.offset;
      }

      const role = deriveWheelRole(angle);
      if (role === null) {
        return fail(
          path,
          `${at}.angle (wheel "${id}") must place it left or right (sin(angle) > 0.9 or < -0.9)`,
        );
      }
      if (wheelCounts[role] > 0) {
        return fail(path, `${at}: more than one wheel derived as "${role}" (id "${id}")`);
      }
      wheelCounts[role]++;

      components.push({ id, type, angle, offset });
    } else {
      if (declaresOffset) {
        return fail(path, `${at}.offset must not be declared for proximitySensor "${id}"`);
      }
      components.push({ id, type, angle, offset: 0 });
    }
  }

  if (wheelCounts.left !== 1 || wheelCounts.right !== 1) {
    return fail(path, 'wheels must include exactly one left and one right (derived from angle)');
  }

  return { schemaVersion: 2, name: description.name, components };
}

export class ComponentRegistry {
  private readonly byId = new Map<string, ComponentDescription>();
  private readonly wheelComponents: ComponentDescription[] = [];
  private readonly sensorComponents: ComponentDescription[] = [];

  constructor(description: RobotDescription) {
    for (const component of description.components) {
      this.byId.set(component.id, component);
      if (component.type === 'wheel') {
        this.wheelComponents.push(component);
      } else {
        this.sensorComponents.push(component);
      }
    }
  }

  get(id: string): ComponentDescription {
    const component = this.byId.get(id);
    if (component === undefined) {
      throw new Error(`ComponentRegistry: unknown component id "${id}"`);
    }
    return component;
  }

  wheels(): ComponentDescription[] {
    return [...this.wheelComponents];
  }

  sensors(): ComponentDescription[] {
    return [...this.sensorComponents];
  }

  wheelPair(): { left: ComponentDescription; right: ComponentDescription } {
    let left: ComponentDescription | undefined;
    let right: ComponentDescription | undefined;
    for (const wheel of this.wheelComponents) {
      const role = deriveWheelRole(wheel.angle);
      if (role === 'left') left = wheel;
      else if (role === 'right') right = wheel;
    }
    if (left === undefined || right === undefined) {
      throw new Error(
        'ComponentRegistry: wheels must include exactly one left and one right (derived from angle)',
      );
    }
    return { left, right };
  }

  trackWidth(): number {
    const { left, right } = this.wheelPair();
    return left.offset * Math.sin(left.angle) - right.offset * Math.sin(right.angle);
  }
}
