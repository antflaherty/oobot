import type { WorldSnapshot } from './types';

// ---------------------------------------------------------------------------
// Hardcoded stream of `worldSnapshot` messages (fallback).
//
// Used when no live sim is connected to the visualiser WebSocket channel.
// Proves the visualiser side of the wire protocol without a sim or socket.
// ---------------------------------------------------------------------------

// World dimensions (hardcoded, world units) — matches the box the renderer draws.
export const WORLD_WIDTH = 800;
export const WORLD_HEIGHT = 600;

// Mirrors simulator/server/worlds/maze.json so the no-sim fallback preview
// shows the same maze as the live sim. Obstacles are centre-based, matching
// the sim's Obstacle convention.
export const MAZE_OBSTACLES = [
  // Wall 1 (y=-160): spans left edge → near right edge, gap on the RIGHT.
  { x: -60, y: -160, width: 680, height: 20 },
  // Wall 2 (y=0): spans near left edge → right edge, gap on the LEFT.
  { x: 60, y: 0, width: 680, height: 20 },
  // Wall 3 (y=160): spans left edge → near right edge, gap on the RIGHT.
  { x: -60, y: 160, width: 680, height: 20 },
  // Slalom sliver in corridor 2, near the left side.
  { x: -300, y: -80, width: 20, height: 100 },
  // Central pillar in corridor 2.
  { x: 0, y: -80, width: 60, height: 60 },
];

// Keyframe poses the robot passes through. The snapshot stream below is
// sampled from these keyframes at regular simTick intervals so consecutive
// snapshots are spaced apart — which makes the visualiser's interpolation
// between them clearly visible (motion, not snapping).
//
// Every pose is inside the drawn world (x ∈ [-400,400], y ∈ [-300,300]) with
// room for the robot body and heading arrow. Each segment is either a pure
// translation at constant heading or a turn in place, so the arrow always
// points along the direction of travel (or the robot is stationary).
//
// heading: radians, 0 = +X axis, + = CCW (see specs/WIRE_PROTOCOL.md §6.2).
const KEYFRAMES: Array<{
  tick: number;
  x: number;
  y: number;
  heading: number;
}> = [
  // Above wall 3, heading right (+X).
  { tick: 0, x: -200, y: 210, heading: 0 },
  { tick: 40, x: 300, y: 210, heading: 0 },
  // Turn to face down (-Y), then pass through wall 3's right-hand gap.
  { tick: 80, x: 300, y: 210, heading: -Math.PI / 2 },
  { tick: 120, x: 300, y: 100, heading: -Math.PI / 2 },
  // Turn to face left (-X), then cross the upper corridor.
  { tick: 160, x: 300, y: 100, heading: Math.PI },
  { tick: 200, x: -330, y: 100, heading: Math.PI },
  // Turn down and use wall 2's left-hand gap (clear of the slalom sliver).
  { tick: 240, x: -330, y: 100, heading: -Math.PI / 2 },
  { tick: 280, x: -330, y: -135, heading: -Math.PI / 2 },
  // Turn right (+X) below the central pillar, then down through wall 1's gap.
  { tick: 320, x: -330, y: -135, heading: 0 },
  { tick: 360, x: 320, y: -135, heading: 0 },
  { tick: 400, x: 320, y: -135, heading: -Math.PI / 2 },
  { tick: 440, x: 320, y: -210, heading: -Math.PI / 2 },
  // Turn left (-X) and finish along the bottom of the box.
  { tick: 480, x: 320, y: -210, heading: Math.PI },
  { tick: 520, x: -200, y: -210, heading: Math.PI },
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
      payload: { robot: { x: pose.x, y: pose.y, heading: pose.heading }, obstacles: MAZE_OBSTACLES },
    });
  }
  return snaps;
})();

// Index of the final snapshot's simTick — used to know when the stream ends.
export const FINAL_SNAPSHOT_TICK = SNAPSHOTS[SNAPSHOTS.length - 1].simTick;
