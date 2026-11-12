// Shared types for the sim server transport layer.
// The sim depends only on this abstraction (see specs/COMMS.md §2).
// The wire protocol envelope ({ type, seq, simTick, payload }) and NDJSON
// framing are locked by specs/WIRE_PROTOCOL.md — do not change them here.

export type Channel = "robot" | "visualiser";

export interface IncomingEnvelope {
  type: string;        // e.g. "motorCommand", "userControl"
  seq: number;         // per-connection sender sequence
  simTick?: number;    // sim logical tick the message relates to (optional on input)
  payload: unknown;    // message-specific data
}

export interface OutgoingEnvelope {
  type: string;        // e.g. "sensorSnapshot", "worldSnapshot"
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
  onMessage: (connectionId: string, envelope: IncomingEnvelope) => void;
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

export interface MotorCommandPayload {
  left: number;  // [-1, 1]
  right: number; // [-1, 1]
}

export interface WorldSnapshotPayload {
  robot: { x: number; y: number; heading: number }; // heading: radians, 0 = +X axis
}

// sensorSnapshot payload is {} for iteration 1

export interface RobotPose {
  x: number;
  y: number;
  heading: number;
}
