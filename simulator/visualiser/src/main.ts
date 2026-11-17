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
import { angleLerp, clamp01 } from './math';
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
// Record trail points from the authoritative snapshot stream so the path keeps
// tracing while the tab is hidden (render frames pause there).
liveStream.setTrailPointCallback((x, y) => renderer.addTrailPoint(x, y));
// LiveStream.reset() runs on every (re)connect and on a sim tick rollback.
// Clear the renderer trail there too so two sessions are never joined by a
// stray segment — see D-5 in docs/DECISIONS.md.
liveStream.setTrailResetCallback(() => renderer.clearTrail());

type PlaybackMode = 'live' | 'fallback';

let activeMode: PlaybackMode | null = null;
let fallbackStartTime = performance.now();
let lastLogTime = fallbackStartTime;
let framesRendered = 0;

/**
 * Switch between the authoritative live stream and the hardcoded fallback.
 *
 * The trail is cleared on every transition so a path recorded in one mode is
 * never concatenated with one from the other. Entering fallback also rewinds
 * the playback clock: otherwise a long live session leaves `fallbackStartTime`
 * stale and the fallback jumps straight to its final, held keyframe.
 */
function setMode(next: PlaybackMode, now: number): void {
  if (activeMode === next) return;
  renderer.clearTrail();
  if (next === 'fallback') {
    fallbackStartTime = now;
  }
  activeMode = next;
}

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
  const s = span > 0 ? clamp01((tick - from.simTick) / span) : 1;

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
  const usingLive = liveStream.isConnected() && latestTick !== null;
  setMode(usingLive ? 'live' : 'fallback', now);

  if (usingLive) {
    const liveResult = liveStream.getPoseAt(now);
    if (liveResult) {
      result = liveResult;
      source = result.holding ? 'live (holding)' : 'live';
      poseTick = latestTick;
    }
  } else {
    const elapsedSec = (now - fallbackStartTime) / 1000;
    const playbackTick = elapsedSec * TICKS_PER_SECOND;
    result = interpolate(SNAPSHOTS, playbackTick);
    source =
      playbackTick >= FINAL_SNAPSHOT_TICK ? 'hardcoded (holding)' : 'hardcoded';
    poseTick = playbackTick;
  }

  if (result) {
    // Fallback hardcoded stream isn't fed through LiveStream, so record a trail
    // point per rendered frame here (live path is recorded via the callback above).
    if (!usingLive) {
      renderer.addTrailPoint(result.pose.x, result.pose.y);
    }

    const obstacles = liveStream.getObstacles();
    renderer.render(result.pose, obstacles);
    logRender(result.pose, source, result.holding);
  }

  requestAnimationFrame(frame);
}

requestAnimationFrame(frame);
