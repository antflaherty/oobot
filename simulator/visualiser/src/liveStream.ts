// ---------------------------------------------------------------------------
// Live sim snapshot stream over WebSocket (Phase 1.3).
//
// Connects to the sim's visualiser channel
//   ws://localhost:VISUALISER_PORT
// and buffers incoming `worldSnapshot` NDJSON messages so the renderer can
// interpolate between them on each render frame.
// ---------------------------------------------------------------------------

import type { WorldSnapshot, RobotPose } from './types';
import { angleLerp, clamp01, lerp } from './math';

const VISUALISER_PORT = 8081;
const RECONNECT_DELAY_MS = 2000;
const MAX_BUFFERED = 120; // ~2 seconds at 60 Hz

export interface PoseResult {
  pose: RobotPose;
  holding: boolean;
}

export class LiveStream {
  private ws: WebSocket | null = null;
  private snapshots: WorldSnapshot[] = []; // sorted by simTick ascending
  private latestTick = -1;
  private connected = false;
  private reconnectTimer: number | null = null;

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

  /** Interpolate the robot pose at the given simTick using buffered snapshots. */
  getPoseAt(tick: number): PoseResult | null {
    if (this.snapshots.length === 0) return null;

    // Find the last snapshot with simTick <= tick (the held frame).
    let fromIndex = 0;
    for (let i = 0; i < this.snapshots.length; i++) {
      if (this.snapshots[i].simTick <= tick) {
        fromIndex = i;
      } else {
        break;
      }
    }

    const from = this.snapshots[fromIndex];
    const to = this.snapshots[fromIndex + 1];

    // No newer snapshot yet: hold the last received frame, no extrapolation.
    if (!to) {
      return { pose: { ...from.payload.robot }, holding: true };
    }

    const span = to.simTick - from.simTick;
    const s = span > 0 ? clamp01((tick - from.simTick) / span) : 1;

    return {
      pose: {
        x: lerp(from.payload.robot.x, to.payload.robot.x, s),
        y: lerp(from.payload.robot.y, to.payload.robot.y, s),
        heading: angleLerp(from.payload.robot.heading, to.payload.robot.heading, s),
      },
      holding: false,
    };
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
    this.snapshots.push(snapshot);
    this.latestTick = snapshot.simTick;

    // Keep the buffer bounded so memory doesn't grow unboundedly.
    if (this.snapshots.length > MAX_BUFFERED) {
      this.snapshots = this.snapshots.slice(-MAX_BUFFERED);
    }
  }
}
