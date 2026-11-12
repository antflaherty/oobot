import type { WorldSnapshot } from './types';

// ---------------------------------------------------------------------------
// Hardcoded stream of `worldSnapshot` messages (Phase 1 MVP).
//
// In Phase 3 this array will be replaced by messages received over the
// visualiser WebSocket channel. Until then it proves the visualiser side of
// the wire protocol without a sim, a socket, or Java.
// ---------------------------------------------------------------------------

// World dimensions (hardcoded, world units) — matches the box the renderer draws.
export const WORLD_WIDTH = 800;
export const WORLD_HEIGHT = 600;

// Keyframe poses the robot passes through. The snapshot stream below is
// sampled from these keyframes at regular simTick intervals so consecutive
// snapshots are spaced apart — which makes the visualiser's interpolation
// between them clearly visible (motion, not snapping).
//
// heading: radians, 0 = +X axis (see specs/WIRE_PROTOCOL.md §6.2).
const KEYFRAMES: Array<{
  tick: number;
  x: number;
  y: number;
  heading: number;
}> = [
  { tick: 0, x: 100, y: 300, heading: 0 },
  { tick: 40, x: 650, y: 300, heading: 0 },
  { tick: 80, x: 650, y: 300, heading: Math.PI / 2 },
  { tick: 120, x: 650, y: 100, heading: Math.PI / 2 },
  { tick: 160, x: 650, y: 100, heading: Math.PI },
  { tick: 200, x: 150, y: 100, heading: Math.PI },
  { tick: 240, x: 150, y: 100, heading: (3 * Math.PI) / 2 },
  { tick: 280, x: 150, y: 500, heading: (3 * Math.PI) / 2 },
  { tick: 320, x: 150, y: 500, heading: 0 },
  { tick: 360, x: 650, y: 500, heading: 0 },
  { tick: 400, x: 650, y: 500, heading: Math.PI / 4 },
];

// Emit one snapshot every N simTicks (~every 133ms at a 60Hz sim).
const SNAPSHOT_INTERVAL_TICKS = 8;

import { lerp, angleLerp } from './math';

function samplePose(tick: number): { x: number; y: number; heading: number } {
  const last = KEYFRAMES[KEYFRAMES.length - 1];
  if (tick <= KEYFRAMES[0].tick) return KEYFRAMES[0];
  if (tick >= last.tick) return last;

  // Find the keyframe segment [k0, k1] that contains `tick`.
  let k0 = KEYFRAMES[0];
  let k1 = last;
  for (let i = 0; i < KEYFRAMES.length - 1; i++) {
    if (tick >= KEYFRAMES[i].tick && tick <= KEYFRAMES[i + 1].tick) {
      k0 = KEYFRAMES[i];
      k1 = KEYFRAMES[i + 1];
      break;
    }
  }

  const span = k1.tick - k0.tick;
  const s = span > 0 ? (tick - k0.tick) / span : 0;
  return {
    x: lerp(k0.x, k1.x, s),
    y: lerp(k0.y, k1.y, s),
    heading: angleLerp(k0.heading, k1.heading, s),
  };
}

// Build the hardcoded snapshot array.
export const SNAPSHOTS: WorldSnapshot[] = (() => {
  const snaps: WorldSnapshot[] = [];
  const finalTick = KEYFRAMES[KEYFRAMES.length - 1].tick;
  let seq = 0;
  for (let tick = 0; tick <= finalTick; tick += SNAPSHOT_INTERVAL_TICKS) {
    const pose = samplePose(tick);
    snaps.push({
      type: 'worldSnapshot',
      seq: seq++,
      simTick: tick,
      payload: { robot: { x: pose.x, y: pose.y, heading: pose.heading } },
    });
  }
  return snaps;
})();

// Index of the final snapshot's simTick — used to know when the stream ends.
export const FINAL_SNAPSHOT_TICK = SNAPSHOTS[SNAPSHOTS.length - 1].simTick;
