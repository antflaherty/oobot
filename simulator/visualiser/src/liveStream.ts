// ---------------------------------------------------------------------------
// Live sim snapshot stream over WebSocket (Phase 1.3).
//
// Connects to the sim's visualiser channel
//   ws://localhost:VISUALISER_PORT
// and buffers incoming `worldSnapshot` NDJSON messages with their arrival
// times so the renderer can interpolate between them on each render frame.
//
// Dependencies are injectable so the stream can be unit-tested in Node without
// a browser or a real socket. All defaults use the browser globals, so
// `new LiveStream()` in main.ts keeps working unchanged.
// ---------------------------------------------------------------------------

import type { WorldSnapshot, RobotPose, Obstacle, RobotShape, ToolState, GoalArea } from './types';
import { angleLerp, clamp01, lerp } from './math';

const VISUALISER_PORT = 8081;
const RECONNECT_DELAY_MS = 2000;
const MAX_BUFFERED = 120; // ~2 seconds at 60 Hz
const TICKS_PER_SECOND = 60;

export interface PoseResult {
  pose: RobotPose;
  holding: boolean;
}

/** Optional injectable dependencies. Every field defaults to a browser global. */
export interface LiveStreamDeps {
  createWebSocket?: (url: string) => WebSocket;
  now?: () => number;
  setTimeout?: (fn: () => void, ms: number) => number;
  clearTimeout?: (id: number) => void;
}

interface BufferedSnapshot {
  simTick: number;
  pose: RobotPose;
  receivedAt: number; // now() when received
}

function isFiniteNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value);
}

/** Defensively narrow an optional wire shape so malformed data cannot reach canvas draws. */
function parseRobotShape(value: unknown): RobotShape | null {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return null;
  const shape = value as {
    width?: unknown;
    height?: unknown;
    wheels?: unknown;
  };
  if (!isFiniteNumber(shape.width) || !isFiniteNumber(shape.height)) return null;
  if (!Array.isArray(shape.wheels) || shape.wheels.length === 0) return null;

  const wheelsValid = shape.wheels.every((wheel: unknown) => {
    if (typeof wheel !== 'object' || wheel === null || Array.isArray(wheel)) return false;
    const w = wheel as {
      angle?: unknown;
      offset?: unknown;
      length?: unknown;
      width?: unknown;
    };
    return (
      isFiniteNumber(w.angle) &&
      isFiniteNumber(w.offset) &&
      isFiniteNumber(w.length) &&
      isFiniteNumber(w.width)
    );
  });
  return wheelsValid ? (value as RobotShape) : null;
}

/** Defensively narrow the optional wire toolStates field.
 *
 * Policy: malformed *entries* are skipped individually, so one bad tool cannot
 * hide the state of the others. An absent or non-array field yields an empty
 * list, meaning every tool is shown as inactive (the renderer's default).
 * Entries are normalised to plain {id, active} objects rather than passing the
 * wire objects through to the renderer.
 */
function parseToolStates(value: unknown): ToolState[] {
  if (!Array.isArray(value)) return [];
  const result: ToolState[] = [];
  for (const entry of value) {
    if (typeof entry !== 'object' || entry === null || Array.isArray(entry)) continue;
    const tool = entry as { id?: unknown; active?: unknown };
    if (typeof tool.id !== 'string' || tool.id.trim().length === 0) continue;
    if (typeof tool.active !== 'boolean') continue;
    result.push({ id: tool.id, active: tool.active });
  }
  return result;
}

/** Defensively narrow the optional wire goal area. Absent, null or malformed
 * data yields null, meaning "no goal drawn". Extra keys are ignored. */
function parseGoal(value: unknown): GoalArea | null {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return null;
  const goal = value as {
    x?: unknown;
    y?: unknown;
    width?: unknown;
    height?: unknown;
  };
  if (
    !isFiniteNumber(goal.x) ||
    !isFiniteNumber(goal.y) ||
    !isFiniteNumber(goal.width) ||
    !isFiniteNumber(goal.height)
  ) {
    return null;
  }
  return value as GoalArea;
}

/** Narrow a parsed NDJSON line to a snapshot whose fields are safe to render. */
function isValidSnapshot(value: unknown): value is WorldSnapshot {
  if (typeof value !== 'object' || value === null) return false;
  const msg = value as {
    type?: unknown;
    simTick?: unknown;
    payload?: { robot?: { x?: unknown; y?: unknown; heading?: unknown } };
  };
  if (msg.type !== 'worldSnapshot' || !isFiniteNumber(msg.simTick)) return false;
  const robot = msg.payload?.robot;
  return (
    !!robot &&
    isFiniteNumber(robot.x) &&
    isFiniteNumber(robot.y) &&
    isFiniteNumber(robot.heading)
  );
}

export class LiveStream {
  private ws: WebSocket | null = null;
  private snapshots: BufferedSnapshot[] = []; // sorted by simTick ascending
  private latestTick = -1;
  private connected = false;
  /** Cleared by stop() before closing so onclose cannot schedule a reconnect. */
  private shouldReconnect = false;
  private reconnectTimer: number | null = null;
  private latestObstacles: Obstacle[] = [];
  private latestRobotShape: RobotShape | null = null;
  private latestToolStates: ToolState[] = [];
  private latestGoal: GoalArea | null = null;
  private onTrailPoint: ((x: number, y: number) => void) | null = null;
  private onTrailReset: (() => void) | null = null;

  // Partial NDJSON line carried across onmessage calls.
  private lineBuffer = '';

  // Reference point for the continuously advancing render clock.
  // renderTick = baseTick + (now - baseTime) * TICKS_PER_SECOND / 1000
  private baseTick = 0;
  private baseTime = 0;

  private readonly createWebSocket: (url: string) => WebSocket;
  private readonly now: () => number;
  private readonly scheduleTimeout: (fn: () => void, ms: number) => number;
  private readonly cancelTimeout: (id: number) => void;

  constructor(deps: LiveStreamDeps = {}) {
    this.createWebSocket = deps.createWebSocket ?? ((url) => new WebSocket(url));
    this.now = deps.now ?? (() => performance.now());
    this.scheduleTimeout =
      deps.setTimeout ?? ((fn, ms) => window.setTimeout(fn, ms));
    this.cancelTimeout = deps.clearTimeout ?? ((id) => window.clearTimeout(id));
  }

  start(): void {
    this.shouldReconnect = true;
    this.connect();
  }

  stop(): void {
    // Clear the reconnect flag first: closing the socket fires onclose
    // synchronously in some environments, and that must not schedule a new
    // connection for a stream the caller intentionally stopped.
    this.shouldReconnect = false;
    this.connected = false;
    if (this.reconnectTimer !== null) {
      this.cancelTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    const ws = this.ws;
    this.ws = null;
    if (ws) {
      ws.close();
    }
  }

  isConnected(): boolean {
    return this.connected;
  }

  getLatestTick(): number | null {
    return this.latestTick >= 0 ? this.latestTick : null;
  }

  /** Returns the latest static obstacle set (empty array if none). */
  getObstacles(): Obstacle[] {
    return this.latestObstacles;
  }

  /** Returns the latest valid robot shape, or null when absent/invalid. */
  getRobotShape(): RobotShape | null {
    return this.latestRobotShape;
  }

  /** Returns the latest parsed tool states (empty when absent/malformed). */
  getToolStates(): ToolState[] {
    return this.latestToolStates;
  }

  /** Returns the latest goal area, or null when absent/malformed. */
  getGoal(): GoalArea | null {
    return this.latestGoal;
  }

  /** True only when the given tool id is currently reported active. */
  isToolActive(id: string): boolean {
    return this.latestToolStates.some((tool) => tool.id === id && tool.active);
  }

  /** Register a callback invoked with each new snapshot's robot pose so the
   * trail can be recorded from the authoritative position stream rather than
   * from render frames (which pause while the tab is hidden). */
  setTrailPointCallback(cb: (x: number, y: number) => void): void {
    this.onTrailPoint = cb;
  }

  /** Register a callback invoked by reset() so the owner of the trail callback
   * can discard points recorded from snapshots that reset() just dropped.
   * The renderer trail itself is owned (and cleared) outside this class. */
  setTrailResetCallback(cb: () => void): void {
    this.onTrailReset = cb;
  }

  /** Discard all buffered snapshot/session state. Used on (re)connect and when
   * a sim tick rollback signals a new session. */
  reset(): void {
    this.snapshots = [];
    this.latestTick = -1;
    this.baseTick = 0;
    this.baseTime = 0;
    this.latestObstacles = [];
    this.latestRobotShape = null;
    this.latestToolStates = [];
    this.latestGoal = null;
    this.lineBuffer = '';
    this.onTrailReset?.();
  }

  /** Interpolate the robot pose at the given wall-clock render time. */
  getPoseAt(now: number): PoseResult | null {
    if (this.snapshots.length === 0) return null;

    // Advance the render clock continuously from the base reference.
    const elapsedMs = now - this.baseTime;
    const renderTick = this.baseTick + (elapsedMs * TICKS_PER_SECOND) / 1000;

    // Never extrapolate beyond the latest received snapshot.
    const cappedTick = Math.min(renderTick, this.latestTick);

    // Find the two snapshots bracketing cappedTick.
    let fromIndex = 0;
    for (let i = 0; i < this.snapshots.length; i++) {
      if (this.snapshots[i].simTick <= cappedTick) {
        fromIndex = i;
      } else {
        break;
      }
    }

    const from = this.snapshots[fromIndex];
    const to = this.snapshots[fromIndex + 1];

    // No newer snapshot yet: hold the last received frame, no extrapolation.
    if (!to) {
      return { pose: { ...from.pose }, holding: true };
    }

    const span = to.simTick - from.simTick;
    const s = span > 0 ? clamp01((cappedTick - from.simTick) / span) : 1;

    return {
      pose: {
        x: lerp(from.pose.x, to.pose.x, s),
        y: lerp(from.pose.y, to.pose.y, s),
        heading: angleLerp(from.pose.heading, to.pose.heading, s),
      },
      holding: false,
    };
  }

  /** Rebase the render clock when a new snapshot arrives.
   *
   * The render clock lags the latest snapshot by one tick so renderTick falls
   * between two buffered snapshots (`from`/`to`) instead of being pinned at
   * `latestTick`, which would hold the last frame forever. Sim and render both
   * advance at 60 Hz, so renderTick sweeps [T-1, T) across each tick interval
   * while snapshots T-1 and T are buffered: continuous interpolation.
   */
  private rebase(newest: BufferedSnapshot): void {
    this.baseTick = newest.simTick - 1;
    this.baseTime = newest.receivedAt;
  }

  private connect(): void {
    const url = `ws://localhost:${VISUALISER_PORT}`;
    this.ws = this.createWebSocket(url);

    this.ws.onopen = () => {
      // Every connection (first or reconnect) starts from a clean slate:
      // stale snapshots, interpolation clock, obstacles and trail must not
      // carry over from a previous session.
      this.reset();
      this.connected = true;
      console.log(`[visualiser] WebSocket connected to ${url}`);
    };

    this.ws.onmessage = (event: MessageEvent) => {
      const text =
        typeof event.data === 'string' ? event.data : new TextDecoder().decode(event.data);
      // NDJSON: one JSON object per line. A line may be split across frames,
      // so carry the trailing partial line into the next onmessage call.
      this.lineBuffer += text;
      const lines = this.lineBuffer.split('\n');
      this.lineBuffer = lines.pop() ?? '';
      for (const line of lines) {
        const trimmed = line.trim();
        if (!trimmed) continue;
        let msg: unknown;
        try {
          msg = JSON.parse(trimmed);
        } catch {
          console.warn('[visualiser] ignoring malformed NDJSON line:', trimmed);
          continue;
        }
        if (isValidSnapshot(msg)) {
          this.handleSnapshot(msg);
        } else if (
          typeof msg === 'object' &&
          msg !== null &&
          (msg as { type?: unknown }).type === 'worldSnapshot'
        ) {
          console.warn('[visualiser] ignoring invalid worldSnapshot:', trimmed);
        }
      }
    };

    this.ws.onclose = () => {
      this.connected = false;
      this.ws = null;
      // Drop trail points recorded for the session that just ended.
      this.onTrailReset?.();
      if (!this.shouldReconnect) {
        console.log('[visualiser] WebSocket closed; staying stopped');
        return;
      }
      console.log('[visualiser] WebSocket closed; reconnecting in 2s');
      this.scheduleReconnect();
    };

    this.ws.onerror = () => {
      // Connection errors are handled by onclose.
    };
  }

  private scheduleReconnect(): void {
    if (!this.shouldReconnect || this.reconnectTimer !== null) return;
    this.reconnectTimer = this.scheduleTimeout(() => {
      this.reconnectTimer = null;
      this.connect();
    }, RECONNECT_DELAY_MS);
  }

  private handleSnapshot(snapshot: WorldSnapshot): void {
    // A decreasing tick means the sim restarted. Drop the old session's state
    // once, then apply this snapshot as the first frame of the new session.
    if (snapshot.simTick < this.latestTick) {
      this.reset();
    }
    const buffered: BufferedSnapshot = {
      simTick: snapshot.simTick,
      pose: { ...snapshot.payload.robot },
      receivedAt: this.now(),
    };
    this.snapshots.push(buffered);
    this.latestTick = snapshot.simTick;

    // Obstacles are static but re-cache on each snapshot in case the world
    // changes (e.g. reload). Default to empty if absent.
    this.latestObstacles = snapshot.payload.obstacles ?? [];
    // Shape is optional on the wire: absent or malformed data clears the cache
    // so the renderer falls back to its default geometry instead of drawing NaN.
    this.latestRobotShape = parseRobotShape(snapshot.payload.robot.shape);
    // Tool states follow the same rule as the shape: absent or malformed data
    // clears the cache, so an older/newer sim that stops publishing a tool
    // cannot leave a stale indicator lit.
    this.latestToolStates = parseToolStates(snapshot.payload.robot.toolStates);
    // Goal is optional on the wire: absent, null or malformed clears the cache
    // so an older/newer sim cannot leave a stale goal rectangle on screen.
    this.latestGoal = parseGoal(snapshot.payload.goal);

    // Record the trail point from the authoritative snapshot stream so the
    // path keeps tracing even while requestAnimationFrame is paused (tab hidden).
    this.onTrailPoint?.(snapshot.payload.robot.x, snapshot.payload.robot.y);

    // Rebase the render clock to the newest snapshot.
    this.rebase(buffered);

    // Keep the buffer bounded so memory doesn't grow unboundedly.
    if (this.snapshots.length > MAX_BUFFERED) {
      this.snapshots = this.snapshots.slice(-MAX_BUFFERED);
    }
  }
}
