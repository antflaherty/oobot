// ---------------------------------------------------------------------------
// Live sim snapshot stream over WebSocket (Phase 1.3).
//
// Connects to the sim's visualiser channel
//   ws://localhost:VISUALISER_PORT
// and buffers incoming `worldSnapshot` NDJSON messages with their arrival
// times so the renderer can interpolate between them on each render frame.
// ---------------------------------------------------------------------------

import type { WorldSnapshot, RobotPose, Obstacle } from './types';
import { angleLerp, clamp01, lerp } from './math';

const VISUALISER_PORT = 8081;
const RECONNECT_DELAY_MS = 2000;
const MAX_BUFFERED = 120; // ~2 seconds at 60 Hz
const TICKS_PER_SECOND = 60;

export interface PoseResult {
  pose: RobotPose;
  holding: boolean;
}

interface BufferedSnapshot {
  simTick: number;
  pose: RobotPose;
  receivedAt: number; // performance.now() when received
}

export class LiveStream {
  private ws: WebSocket | null = null;
  private snapshots: BufferedSnapshot[] = []; // sorted by simTick ascending
  private latestTick = -1;
  private connected = false;
  private reconnectTimer: number | null = null;
  private latestObstacles: Obstacle[] = [];

  // Reference point for the continuously advancing render clock.
  // renderTick = baseTick + (now - baseTime) * TICKS_PER_SECOND / 1000
  private baseTick = 0;
  private baseTime = 0;

  start(): void {
    this.connect();
  }

  stop(): void {
    this.connected = false;
    if (this.reconnectTimer !== null) {
      window.clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    if (this.ws) {
      this.ws.close();
      this.ws = null;
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
    this.ws = new WebSocket(url);

    this.ws.onopen = () => {
      this.connected = true;
      console.log(`[visualiser] WebSocket connected to ${url}`);
    };

    this.ws.onmessage = (event: MessageEvent) => {
      const text =
        typeof event.data === 'string' ? event.data : new TextDecoder().decode(event.data);
      // NDJSON: one JSON object per line.
      const lines = text.split('\n');
      for (const line of lines) {
        const trimmed = line.trim();
        if (!trimmed) continue;
        try {
          const msg = JSON.parse(trimmed);
          if (msg && msg.type === 'worldSnapshot') {
            this.handleSnapshot(msg as WorldSnapshot);
          }
        } catch {
          // Malformed JSON: log and ignore (never crash/disconnect).
        }
      }
    };

    this.ws.onclose = () => {
      this.connected = false;
      this.ws = null;
      console.log('[visualiser] WebSocket closed; reconnecting in 2s');
      this.scheduleReconnect();
    };

    this.ws.onerror = () => {
      // Connection errors are handled by onclose.
    };
  }

  private scheduleReconnect(): void {
    if (this.reconnectTimer !== null) return;
    this.reconnectTimer = window.setTimeout(() => {
      this.reconnectTimer = null;
      this.connect();
    }, RECONNECT_DELAY_MS);
  }

  private handleSnapshot(snapshot: WorldSnapshot): void {
    const buffered: BufferedSnapshot = {
      simTick: snapshot.simTick,
      pose: { ...snapshot.payload.robot },
      receivedAt: performance.now(),
    };
    this.snapshots.push(buffered);
    this.latestTick = snapshot.simTick;

    // Obstacles are static but re-cache on each snapshot in case the world
    // changes (e.g. reload). Default to empty if absent.
    this.latestObstacles = snapshot.payload.obstacles ?? [];

    // Rebase the render clock to the newest snapshot.
    this.rebase(buffered);

    // Keep the buffer bounded so memory doesn't grow unboundedly.
    if (this.snapshots.length > MAX_BUFFERED) {
      this.snapshots = this.snapshots.slice(-MAX_BUFFERED);
    }
  }
}
