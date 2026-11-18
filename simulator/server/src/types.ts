// Shared types for the sim server transport layer.
// The sim depends only on this abstraction (see specs/COMMS.md §2).
// The wire protocol envelope ({ type, seq, simTick, payload }) and NDJSON
// framing are locked by specs/WIRE_PROTOCOL.md — do not change them here.

import type { RobotShape } from "./geometry";

export type Channel = "robot" | "visualiser";

export interface IncomingEnvelope {
  type: string;        // e.g. "componentCommand"
  seq: number;         // per-connection sender sequence
  simTick?: number;    // sim logical tick the message relates to (optional on input)
  payload: unknown;    // message-specific data
}

export interface OutgoingEnvelope {
  type: string;        // e.g. "componentSnapshot", "worldSnapshot"
  seq: number;         // per-connection sender sequence (assigned by transport on send)
  simTick: number;     // sim logical tick this snapshot describes
  payload: unknown;    // message-specific data
}

export interface TransportOptions {
  robotPort: number;      // ROBOT_PORT
  visualiserPort: number; // VISUALISER_PORT
}

export interface Handlers {
  onConnect: (connectionId: string, channel: Channel) => void;
  onMessage: (connectionId: string, channel: Channel, envelope: IncomingEnvelope) => void;
  onDisconnect: (connectionId: string, channel: Channel) => void;
}

export interface Transport {
  listen(options: TransportOptions): Promise<void>;
  setHandlers(handlers: Handlers): void;
  send(connectionId: string, envelope: OutgoingEnvelope): void;
  broadcast(channel: "visualiser", envelope: OutgoingEnvelope): void;
  close(connectionId: string): void;
  shutdown(): Promise<void>;
}

// Locked payload schemas (WIRE_PROTOCOL.md §6, iteration 1)

export interface ComponentValue { id: string; value: number }
export interface ComponentReading { id: string; value: number | null }
export interface ComponentCommandPayload { commands: ComponentValue[] }
export interface ComponentSnapshotPayload { readings: ComponentReading[] }

// Axis-aligned box obstacle (centre-based convention).
// (x, y) is the centre; width/height are full dimensions. Box spans
// [x - width/2, x + width/2] x [y - height/2, y + height/2].
export interface Obstacle {
  x: number;   // centre X, world units
  y: number;   // centre Y, world units
  width: number;  // full width, world units
  height: number; // full height, world units
}

// Axis-aligned goal area, using the same centre-based convention as Obstacle:
// (x, y) is the centre and width/height are the full extents. Only the robot's
// centre point is tested against it; the physical footprint is irrelevant.
export interface GoalArea {
  x: number;   // centre X, world units
  y: number;   // centre Y, world units
  width: number;  // full width, world units
  height: number; // full height, world units
}

export interface ToolState { id: string; active: boolean }

export interface WorldSnapshotPayload {
  // toolStates is authoritative sim state, one entry per declared tool in
  // manifest order; optional so older snapshots remain valid.
  robot: { x: number; y: number; heading: number; shape?: RobotShape; toolStates?: ToolState[] }; // heading: radians, 0 = +X axis
  obstacles: Obstacle[]; // static obstacles (iteration 2)
  // Sim always emits this; null when the world declares no goal. Optional so
  // older snapshots remain valid.
  goal?: GoalArea | null;
}

export interface RobotPose {
  x: number;
  y: number;
  heading: number;
}
