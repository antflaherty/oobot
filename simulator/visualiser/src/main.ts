// ---------------------------------------------------------------------------
// Visualiser MVP.
//
// Renders `worldSnapshot` messages from the sim's visualiser channel
//   ws://localhost:VISUALISER_PORT
// with hold-last-frame + interpolate-to-next interpolation.
//
// With no live sim data the canvas is left empty and a "no connection" status
// message is shown instead of playing a hardcoded demo stream.
// ---------------------------------------------------------------------------

import { Renderer } from './renderer';
import { LiveStream } from './liveStream';

const LOG_EVERY_MS = 1000;

const canvas = document.getElementById('viz') as HTMLCanvasElement;
const status = document.getElementById('status') as HTMLElement;
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

let lastLogTime = performance.now();
let framesRendered = 0;

function logRender(source: string, tick: number | null): void {
  const now = performance.now();
  if (now - lastLogTime >= LOG_EVERY_MS) {
    console.log(
      `[visualiser] render source=${source} tick=${tick?.toFixed(0)} ` +
        `frames=${framesRendered}`,
    );
    framesRendered = 0;
    lastLogTime = now;
  }
  framesRendered++;
}

function frame(): void {
  const now = performance.now();
  const latestTick = liveStream.getLatestTick();
  const hasLiveData = liveStream.isConnected() && latestTick !== null;
  const result = hasLiveData ? liveStream.getPoseAt(now) : null;

  if (result) {
    status.hidden = true;
    renderer.render(
      result.pose,
      liveStream.getObstacles(),
      liveStream.getRobotShape(),
      liveStream.getToolStates(),
      liveStream.getGoal(),
    );
    logRender(result.holding ? 'live (holding)' : 'live', latestTick);
  } else {
    // No live snapshots: draw nothing and tell the user why.
    renderer.clear();
    if (status.hidden) {
      status.hidden = false;
      console.log('[visualiser] waiting for a live worldSnapshot stream');
    }
  }

  requestAnimationFrame(frame);
}

requestAnimationFrame(frame);
