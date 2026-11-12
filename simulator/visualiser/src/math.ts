// Small math helpers used by the visualiser.
// Kept pure and dependency-free so they are trivial to test.

const TAU = 2 * Math.PI;

// Wrap an angle (radians) to [-PI, PI].
export function wrapAngle(a: number): number {
  a = a % TAU;
  if (a > Math.PI) a -= TAU;
  if (a < -Math.PI) a += TAU;
  return a;
}

// Shortest-path linear interpolation between two angles (radians).
// Handles the wrap at +-PI so a turn from 350deg to 10deg goes the short way.
export function angleLerp(a: number, b: number, t: number): number {
  return wrapAngle(a + wrapAngle(b - a) * t);
}

// Clamp t to [0, 1].
export function clamp01(t: number): number {
  return Math.min(Math.max(t, 0), 1);
}

// Linear interpolate between two values.
export function lerp(a: number, b: number, t: number): number {
  return a + (b - a) * t;
}
