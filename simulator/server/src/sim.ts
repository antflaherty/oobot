// Simulation engine: authoritative owner of the simulated world.
//
// Owns the world model, the latest motor command (persistent actuator state),
// the simTick logical clock, and the fixed ~60 Hz timestep loop. Applies motor
// commands each tick, integrates the robot pose, clamps at box edges
// (stop-dead), and publishes snapshots to both channels without ever blocking
// on the robot or the visualiser.
//
// Wires into the encapsulated Transport interface (specs/COMMS.md §2). The
// concrete WebSocketTransport is constructed in index.ts.

import { Transport, RobotPose } from "./types";
import {
  Channel,
  Handlers,
  IncomingEnvelope,
  MotorCommandPayload,
  OutgoingEnvelope,
} from "./types";
import {
  WorldConfig,
  WorldModel,
  DEFAULT_WORLD_CONFIG,
  Obstacle,
  DEFAULT_START_POSE,
} from "./world";
import { castFrontRay } from "./sensor";

const TICK_MS = 1000 / 60; // target ~60 Hz
const LOG_HZ_INTERVAL_MS = 5000;

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isMotorCommandPayload(value: unknown): value is MotorCommandPayload {
  const record = value as Record<string, unknown>;
  return (
    isObject(value) &&
    typeof record.left === "number" &&
    typeof record.right === "number"
  );
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), max);
}

function log(...args: unknown[]): void {
  const stamp = new Date().toISOString().slice(11, 19);
  console.log(`[sim] ${stamp}`, ...args);
}

export class SimEngine {
  private readonly transport: Transport;
  private readonly world: WorldModel;

  // Authoritative logical clock — incremented once per sim step.
  private simTick = 0;

  // Persistent actuator state: latest command per robot (single robot,
  // implicit ID — channel implies the participant). Motors keep running at
  // the last commanded speed until overridden; the sim never waits for the
  // robot.
  private latestCommand: MotorCommandPayload = { left: 0, right: 0 };

  // Single-slot sensor state: latest front-proximity reading, overwritten
  // each tick (no queue). distance is null when nothing is detected.
  private latestSensor: { distance: number | null; simTick: number } | null =
    null;

  private robotConnectionId: string | null = null;

  private running = false;
  private timer: ReturnType<typeof setTimeout> | null = null;

  // Achieved-Hz observability (DESIGN_REVIEW 2.3; COMPONENTS §1.5).
  private ticksSinceLog = 0;
  private logStartTime = Date.now();

  constructor(
    transport: Transport,
    worldConfig?: WorldConfig,
    startPose: RobotPose = DEFAULT_START_POSE,
    obstacles: Obstacle[] = [],
  ) {
    this.transport = transport;
    this.world = new WorldModel(worldConfig, startPose, obstacles);
  }

  start(): void {
    this.transport.setHandlers({
      onConnect: (connectionId: string, channel: Channel) => {
        if (channel === "robot") {
          this.robotConnectionId = connectionId;
          log(`robot connected (${connectionId}) — awaiting motorCommand`);
        } else {
          log(`visualiser connected (${connectionId})`);
        }
      },
      onMessage: (_connectionId: string, envelope: IncomingEnvelope) => {
        if (envelope.type === "motorCommand") {
          this.handleMotorCommand(envelope);
        } else {
          log(`ignoring unexpected message type "${envelope.type}"`);
        }
      },
      onDisconnect: (connectionId: string, channel: Channel) => {
        if (channel === "robot") {
          log(`robot disconnected (${connectionId})`);
          if (connectionId === this.robotConnectionId) {
            this.robotConnectionId = null;
            this.stopMotors(); // safe stop — motors cannot keep running
          }
        } else {
          log(`visualiser disconnected (${connectionId})`);
        }
      },
    });

    this.running = true;
    log(
      `simulation starting: box x[${this.world.minX},${this.world.maxX}] ` +
        `y[${this.world.minY},${this.world.maxY}] ` +
        `pose=(${this.world.pose.x.toFixed(2)},${this.world.pose.y.toFixed(2)},heading=${this.world.pose.heading.toFixed(2)}) ` +
        `obstacles=${this.world.obstacles.length}`
    );
    this.loop();
  }

  stop(): void {
    this.running = false;
    if (this.timer) {
      clearTimeout(this.timer);
      this.timer = null;
    }
    this.stopMotors();
    log("simulation stopped");
  }

  // ---- Robot channel input ----------------------------------------------

  private handleMotorCommand(envelope: IncomingEnvelope): void {
    const payload = envelope.payload;
    if (!isMotorCommandPayload(payload)) {
      log("ignoring malformed motorCommand payload:", JSON.stringify(payload).slice(0, 120));
      return;
    }

    const left = clamp(payload.left, -1, 1);
    const right = clamp(payload.right, -1, 1);
    this.latestCommand = { left, right };

    if (left !== payload.left || right !== payload.right) {
      log(
        `motorCommand clamped to [-1,1]: { left: ${left.toFixed(3)}, right: ${right.toFixed(3)} } (tick ${this.simTick})`
      );
    } else {
      log(
        `motorCommand { left: ${left.toFixed(3)}, right: ${right.toFixed(3)} } (tick ${this.simTick})`
      );
    }
  }

  // Safe stop: transition actuators to a safe state (WIRE_PROTOCOL §7,
  // COMPONENTS §1.9). Called on robot WebSocket close and on shutdown.
  private stopMotors(): void {
    this.latestCommand = { left: 0, right: 0 };
    log("safe stop: motors stopped");
  }

  // ---- Fixed timestep loop ----------------------------------------------

  private loop(): void {
    if (!this.running) return;

    const tickStart = Date.now();
    this.tick();
    const elapsed = Date.now() - tickStart;

    this.timer = setTimeout(() => this.loop(), Math.max(0, TICK_MS - elapsed));
  }

  private tick(): void {
    this.simTick++;

    // 1. Apply the latest motor command (persistent actuator state — reused
    //    if the robot is slow; sim never waits for a new command).
    this.world.apply(this.latestCommand.left, this.latestCommand.right);

    // 2. Compute the front-proximity sensor reading (iteration 2): cast the
    //    front ray against the world box edges, single-slot, overwritten
    //    each tick.
    const reading = castFrontRay(this.world.pose, {
      minX: this.world.minX,
      maxX: this.world.maxX,
      minY: this.world.minY,
      maxY: this.world.maxY,
    });
    this.latestSensor = { distance: reading, simTick: this.simTick };

    // 3. Publish sensorSnapshot to the robot channel every tick.
    //    Single-slot (no queue); payload carries the latest distance.
    const sensorEnvelope: OutgoingEnvelope = {
      type: "sensorSnapshot",
      seq: 0, // assigned per-connection by the transport on send
      simTick: this.simTick,
      payload: { distance: this.latestSensor.distance },
    };
    if (this.robotConnectionId) {
      this.transport.send(this.robotConnectionId, sensorEnvelope);
    }

    // 3. Publish worldSnapshot to the visualiser channel every tick.
    //    Drop-oldest backpressure per client; never block on a visualiser.
    const worldEnvelope: OutgoingEnvelope = {
      type: "worldSnapshot",
      seq: 0, // assigned per-connection by the transport on send
      simTick: this.simTick,
      payload: { robot: { ...this.world.pose } },
    };
    this.transport.broadcast("visualiser", worldEnvelope);

    // Log pose and command every second for debugging the full pipeline.
    this.ticksSinceLog++;
    const now = Date.now();
    if (now - this.logStartTime >= LOG_HZ_INTERVAL_MS) {
      const hz = (this.ticksSinceLog / ((now - this.logStartTime) / 1000)).toFixed(1);
      const p = this.world.pose;
      const c = this.latestCommand;
      log(
        `achieved ${hz} Hz (tick ${this.simTick}) | pose=(${p.x.toFixed(2)},${p.y.toFixed(2)},heading=${p.heading.toFixed(3)}) | command={ left: ${c.left.toFixed(2)}, right: ${c.right.toFixed(2)} } | robotConn=${this.robotConnectionId ? 'yes' : 'no'}`,
      );
      this.ticksSinceLog = 0;
      this.logStartTime = now;
    }
  }

  // ---- Read-only access (useful for dev tooling / tests) ----------------

  public getState() {
    return {
      simTick: this.simTick,
      pose: { ...this.world.pose },
      command: { ...this.latestCommand },
    };
  }
}
