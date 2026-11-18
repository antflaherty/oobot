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

// Physical robot geometry published by the sim, in WORLD UNITS.
// Angles are radians relative to the robot heading (0 = forward, + = CCW).
// Wheel local centre = offset * (cos(angle), sin(angle)); the wheel's long axis
// follows the heading (it rolls tangentially).
export interface RobotShape {
  width: number;   // full body width, world units
  height: number;  // full body height, world units
  wheels: Array<{
    angle: number;
    offset: number;
    length: number; // long axis, along the heading
    width: number;  // short axis
  }>;
}

// worldSnapshot payload — see specs/WIRE_PROTOCOL.md §6.2
export interface WorldSnapshotPayload {
  robot: {
    // Robot position in world units
    x: number;
    y: number;
    // Robot heading in radians; 0 = +X axis
    heading: number;
    // Optional for older/fallback snapshots; the sim always sends it.
    shape?: RobotShape;
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
