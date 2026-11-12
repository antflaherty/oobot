import type { WorldSnapshot } from './types';
import { SNAPSHOTS } from './snapshotStream';
import { angleLerp } from './math';
import { Renderer } from './renderer';

// ---------------------------------------------------------------------------
// Playback driver.
//
// No sim, no WebSocket: we advance a local "playback simTick" at the sim's
// target rate (~60 ticks / second of real time) and feed the resulting pose
// to the renderer. On each render tick the renderer holds the last received
// snapshot and interpolates toward the next available one — hold-last-frame,
// interpolate-to-next, no extrapolation (specs/COMPONENTS.md §2.4).
// ---------------------------------------------------------------------------

const TICKS_PER_SECOND = 60;
const LOG_EVERY_MS = 1000;

const canvas = document.getElementById('viz') as HTMLCanvasElement;
const renderer = new Renderer(canvas);

let startTime = performance.now();
let lastLogTime = startTime;
let framesRendered = 0;

/**
 * Find the two snapshots bracketing `tick` and interpolate between them.
 *
 * - `from`: latest snapshot with simTick <= tick (the held frame)
 * - `to`:   next snapshot with simTick  > tick (the target), or null at the
 *           end of the stream — in which case we hold `from` forever
 *           (no extrapolation).
 *
 * Returns null only when the stream is empty.
 */
function interpolate(
  snapshots: WorldSnapshot[],
  tick: number,
): { pose: { x: number; y: number; heading: number }; holding: boolean } | null {
  if (snapshots.length === 0) return null;

  // Snapshots are emitted in increasing simTick order. Find the last one
  // whose simTick is <= tick.
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

  // End of stream: hold the last frame, do not extrapolate beyond it.
  if (!to) {
    return { pose: { ...from.payload.robot }, holding: true };
  }

  const span = to.simTick - from.simTick;
  const s = span > 0 ? (tick - from.simTick) / span : 1;

  return {
    pose: {
      x: from.payload.robot.x + (to.payload.robot.x - from.payload.robot.x) * s,
      y: from.payload.robot.y + (to.payload.robot.y - from.payload.robot.y) * s,
      // Shortest-path interpolation so heading wraps cleanly at +-PI.
      heading: angleLerp(from.payload.robot.heading, to.payload.robot.heading, s),
    },
    holding: false,
  };
}

function frame(): void {
  const now = performance.now();
  const elapsedSec = (now - startTime) / 1000;
  const playbackTick = elapsedSec * TICKS_PER_SECOND;

  const result = interpolate(SNAPSHOTS, playbackTick);
  if (result) {
    renderer.render(result.pose);
    framesRendered++;
  }

  // Periodic console log (specs/COMPONENTS.md §2.3: console log lines for
  // frames received / render ticks).
  if (now - lastLogTime >= LOG_EVERY_MS) {
    const lastTick = SNAPSHOTS[SNAPSHOTS.length - 1].simTick;
    console.log(
      `[visualiser] frames=${framesRendered} playbackTick=${playbackTick.toFixed(1)} ` +
        `(stream 0..${lastTick}) ` +
        `${result ? (result.holding ? 'holding last frame' : 'interpolating') : 'no snapshots'}`,
    );
    framesRendered = 0;
    lastLogTime = now;
  }

  requestAnimationFrame(frame);
}

requestAnimationFrame(frame);
