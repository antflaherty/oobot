// Wire protocol envelope — see specs/WIRE_PROTOCOL.md §4
// Every message: { type, seq, simTick, payload }
export interface WireEnvelope<P = unknown> {
  type: string;
  seq: number;
  simTick: number;
  payload: P;
}

// worldSnapshot payload — see specs/WIRE_PROTOCOL.md §6.2
export interface WorldSnapshotPayload {
  robot: {
    // Robot position in world units
    x: number;
    y: number;
    // Robot heading in radians; 0 = +X axis
    heading: number;
  };
}

export type WorldSnapshot = WireEnvelope<WorldSnapshotPayload>;

// Interpolated robot pose handed to the renderer.
export interface RobotPose {
  x: number;
  y: number;
  heading: number;
}
