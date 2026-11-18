import type { WorldSnapshot, RobotShape } from './types';

// ---------------------------------------------------------------------------
// Hardcoded stream of `worldSnapshot` messages (fallback).
//
// Used when no live sim is connected to the visualiser WebSocket channel.
// Proves the visualiser side of the wire protocol without a sim or socket.
// ---------------------------------------------------------------------------

// World dimensions (hardcoded, world units) — matches the box the renderer draws.
export const WORLD_WIDTH = 800;
export const WORLD_HEIGHT = 600;

// Default robot geometry from the sim (50x50 body, two wheels at +/-pi/2).
// Used by fallback snapshots and whenever a live snapshot has no valid shape.
export const DEFAULT_SHAPE: RobotShape = {
  width: 50,
  height: 50,
  wheels: [
    { angle: Math.PI / 2, offset: 25, length: 30, width: 10 },
    { angle: -Math.PI / 2, offset: 25, length: 30, width: 10 },
  ],
};

// Mirrors simulator/server/worlds/maze.json so the no-sim fallback preview
// shows the same world as the live sim. Obstacles are centre-based, matching
// the sim's Obstacle convention. This is an open arena of chunky blocks:
// three wall spurs and two free-standing islands, with >=120-unit passages.
export const MAZE_OBSTACLES = [
  // Bottom wall spur, just right of the start lane.
  { x: -20, y: -260, width: 160, height: 80 },
  // Top-right corner spur.
  { x: 350, y: 230, width: 100, height: 140 },
  // Upper-left island.
  { x: -180, y: 40, width: 120, height: 120 },
  // Top wall spur.
  { x: -60, y: 260, width: 160, height: 80 },
  // Lower-right island.
  { x: 230, y: -80, width: 140, height: 120 },
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
  // Start at the live world's start pose, heading right (+X) along the bottom.
  { tick: 0, x: -340, y: -240, heading: 0 },
  { tick: 40, x: -160, y: -240, heading: 0 },
  // Turn north around the left end of the bottom spur.
  { tick: 80, x: -160, y: -240, heading: Math.PI / 2 },
  { tick: 120, x: -160, y: -170, heading: Math.PI / 2 },
  // Turn east, passing below the lower-right island.
  { tick: 160, x: -160, y: -170, heading: 0 },
  { tick: 200, x: 100, y: -170, heading: 0 },
  // Turn north through the gap between the two islands.
  { tick: 240, x: 100, y: -170, heading: Math.PI / 2 },
  { tick: 280, x: 100, y: 40, heading: Math.PI / 2 },
  // Turn west above the upper-left island.
  { tick: 320, x: 100, y: 40, heading: Math.PI },
  { tick: 360, x: -60, y: 40, heading: Math.PI },
  // Turn north toward the top spur, then west along the top lane.
  { tick: 400, x: -60, y: 40, heading: Math.PI / 2 },
  { tick: 440, x: -60, y: 160, heading: Math.PI / 2 },
  { tick: 480, x: -60, y: 160, heading: Math.PI },
  { tick: 520, x: -340, y: 160, heading: Math.PI },
  // Turn south down the left lane and finish back at the start pose.
  { tick: 560, x: -340, y: 160, heading: -Math.PI / 2 },
  { tick: 600, x: -340, y: -240, heading: -Math.PI / 2 },
  { tick: 640, x: -340, y: -240, heading: 0 },
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
      payload: {
        robot: {
          x: pose.x,
          y: pose.y,
          heading: pose.heading,
          shape: {
            width: DEFAULT_SHAPE.width,
            height: DEFAULT_SHAPE.height,
            wheels: DEFAULT_SHAPE.wheels.map((wheel) => ({ ...wheel })),
          },
        },
        obstacles: MAZE_OBSTACLES,
      },
    });
  }
  return snaps;
})();

// Index of the final snapshot's simTick — used to know when the stream ends.
export const FINAL_SNAPSHOT_TICK = SNAPSHOTS[SNAPSHOTS.length - 1].simTick;
