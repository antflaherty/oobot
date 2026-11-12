// ---------------------------------------------------------------------------
// Visualiser MVP.
//
// Renders `worldSnapshot` messages from the sim's visualiser channel
//   ws://localhost:VISUALISER_PORT
// with hold-last-frame + interpolate-to-next interpolation.
//
// Falls back to a hardcoded snapshot stream when no sim is connected, so the
// visualiser can be developed and demonstrated independently.
// ---------------------------------------------------------------------------

import type { WorldSnapshot, RobotPose } from './types';
import { SNAPSHOTS, FINAL_SNAPSHOT_TICK } from './snapshotStream';
import { angleLerp } from './math';
import { Renderer } from './renderer';
import { LiveStream, PoseResult } from './liveStream';

// ---------------------------------------------------------------------------
// Playback driver (hardcoded fallback).
//
// Advances a local "playback simTick" at the sim's target rate (~60 ticks /
// second of real time). Used only when no live sim is connected.
// ---------------------------------------------------------------------------

const TICKS_PER_SECOND = 60;
const LOG_EVERY_MS = 1000;

const canvas = document.getElementById('viz') as HTMLCanvasElement;
const renderer = new Renderer(canvas);

const liveStream = new LiveStream();
liveStream.start();

let startTime = performance.now();
let lastLogTime = startTime;
let framesRendered = 0;

/**
 * Interpolate between two snapshots bracketing `tick`.
 *
 * - `from`: latest snapshot with simTick <= tick (the held frame)
 * - `to`:   next snapshot with simTick > tick, or null at end — hold `from`
 *           forever (no extrapolation).
 */
function interpolate(
  snapshots: WorldSnapshot[],
  tick: number,
): PoseResult | null {
  if (snapshots.length === 0) return null;

  let fromIndex = 0;
  for (let i = 0; i < snapshots.length; i++) {
    if (snapshots[i].simTick <= tick) {
      fromIndex = i;
    } else {
      break;
    }
  }

  const from = snapshots[fromIndex];
  const to = snapshots[fromIndex + 1];

  if (!to) {
    return { pose: { ...from.payload.robot }, holding: true };
  }

  const span = to.simTick - from.simTick;
  const s = span > 0 ? (tick - from.simTick) / span : 1;

  return {
    pose: {
      x: from.payload.robot.x + (to.payload.robot.x - from.payload.robot.x) * s,
      y: from.payload.robot.y + (to.payload.robot.y - from.payload.robot.y) * s,
      heading: angleLerp(from.payload.robot.heading, to.payload.robot.heading, s),
    },
    holding: false,
  };
}

function logRender(pose: RobotPose, source: string, holding: boolean): void {
  const now = performance.now();
  if (now - lastLogTime >= LOG_EVERY_MS) {
    console.log(
      `[visualiser] render source=${source} tick=${poseTick?.toFixed(0)} ` +
        `pose=(${pose.x.toFixed(2)},${pose.y.toFixed(2)},heading=${pose.heading.toFixed(3)}) ` +
        `${holding ? 'holding' : 'interpolating'} frames=${framesRendered}`,
    );
    framesRendered = 0;
    lastLogTime = now;
  }
  framesRendered++;
  poseTick = null;
}

let poseTick: number | null = null;

function frame(): void {
  const now = performance.now();

  // Prefer live sim data; fall back to the hardcoded stream.
  let result: PoseResult | null = null;
  let source = 'none';

  const latestTick = liveStream.getLatestTick();
  if (latestTick !== null && liveStream.isConnected()) {
    const liveResult = liveStream.getPoseAt(latestTick);
    if (liveResult) {
      result = liveResult;
      source = result.holding ? 'live (holding)' : 'live';
      poseTick = latestTick;
    }
  } else {
    const elapsedSec = (now - startTime) / 1000;
    const playbackTick = elapsedSec * TICKS_PER_SECOND;
    result = interpolate(SNAPSHOTS, playbackTick);
    source =
      playbackTick >= FINAL_SNAPSHOT_TICK ? 'hardcoded (holding)' : 'hardcoded';
    poseTick = playbackTick;
  }

  if (result) {
    renderer.render(result.pose);
    logRender(result.pose, source, result.holding);
  }

  requestAnimationFrame(frame);
}

requestAnimationFrame(frame);
