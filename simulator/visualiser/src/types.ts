// Wire protocol envelope — see specs/WIRE_PROTOCOL.md §4
// Every message: { type, seq, simTick, payload }
export interface WireEnvelope<P = unknown> {
  type: string;
  seq: number;
  simTick: number;
  payload: P;
}

// Axis-aligned box obstacle (centre-based convention). Matches the sim's
// simulator/server/src/types.ts Obstacle interface.
export interface Obstacle {
  x: number;   // centre X, world units
  y: number;   // centre Y, world units
  width: number;  // full width, world units
  height: number; // full height, world units
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
  obstacles: Obstacle[]; // static obstacles (iteration 2)
}

export type WorldSnapshot = WireEnvelope<WorldSnapshotPayload>;

// Interpolated robot pose handed to the renderer.
export interface RobotPose {
  x: number;
  y: number;
  heading: number;
}
