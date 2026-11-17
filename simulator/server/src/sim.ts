// Simulation engine: authoritative owner of the simulated world.
//
// Owns the world model, the latest component command (persistent actuator
// state), the simTick logical clock, and the fixed ~60 Hz timestep loop. Applies
// wheel commands each tick, integrates the robot pose, clamps at box edges
// (stop-dead), and publishes snapshots to both channels without ever blocking
// on the robot or the visualiser.
//
// Wires into the encapsulated Transport interface (specs/COMMS.md §2). The
// concrete WebSocketTransport is constructed in index.ts.

import { Obstacle, Transport, RobotPose } from "./types";
import {
  Channel,
  Handlers,
  IncomingEnvelope,
  ComponentReading,
  ComponentCommandPayload,
  ComponentSnapshotPayload,
  OutgoingEnvelope,
} from "./types";
import {
  WorldConfig,
  WorldModel,
  DEFAULT_WORLD_CONFIG,
  DEFAULT_START_POSE,
} from "./world";
import { castFrontRay } from "./sensor";
import {
  ComponentDescription,
  ComponentRegistry,
  RobotDescription,
} from "./robotDescription";

const TICK_MS = 1000 / 60; // target ~60 Hz
const LOG_HZ_INTERVAL_MS = 5000;

// Internal actuator state is addressed by wheel side; the wire protocol is
// addressed by component id and normalised through the registry.
interface WheelLevels {
  left: number;
  right: number;
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isComponentCommandPayload(value: unknown): value is ComponentCommandPayload {
  if (!isObject(value) || !Array.isArray(value.commands)) return false;
  return value.commands.every(
    (entry) =>
      isObject(entry) &&
      typeof entry.id === "string" &&
      typeof entry.value === "number" &&
      Number.isFinite(entry.value),
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
  private readonly registry: ComponentRegistry;

  // Authoritative logical clock — incremented once per sim step.
  private simTick = 0;

  // Persistent actuator state: latest command per wheel side (single robot,
  // implicit ID — channel implies the participant). Wheels keep running at
  // the last commanded level until overridden; the sim never waits for the
  // robot.
  private latestCommand: WheelLevels = { left: 0, right: 0 };

  // Single-slot component sensor state: one reading per declared proximity
  // sensor, overwritten each tick (no queue). A value is null when nothing is
  // detected along that sensor's declared ray.
  private latestReadings: ComponentReading[] = [];

  private robotConnectionId: string | null = null;

  private running = false;
  private timer: ReturnType<typeof setTimeout> | null = null;

  // Achieved-Hz observability (DESIGN_REVIEW 2.3; COMPONENTS §1.5).
  private ticksSinceLog = 0;
  private logStartTime = Date.now();

  constructor(
    transport: Transport,
    description: RobotDescription,
    worldConfig?: WorldConfig,
    startPose: RobotPose = DEFAULT_START_POSE,
    obstacles: Obstacle[] = [],
  ) {
    this.transport = transport;
    this.registry = new ComponentRegistry(description);
    this.world = new WorldModel(
      { ...(worldConfig ?? DEFAULT_WORLD_CONFIG), trackWidth: this.registry.trackWidth() },
      startPose,
      obstacles,
    );
  }

  start(): void {
    this.transport.setHandlers({
      onConnect: (connectionId: string, channel: Channel) => {
        if (channel === "robot") {
          this.robotConnectionId = connectionId;
          log(`robot connected (${connectionId}) — awaiting componentCommand`);
        } else {
          log(`visualiser connected (${connectionId})`);
        }
      },
      onMessage: (_connectionId: string, envelope: IncomingEnvelope) => {
        if (envelope.type === "componentCommand") {
          this.handleComponentCommand(envelope);
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

  private handleComponentCommand(envelope: IncomingEnvelope): void {
    const payload = envelope.payload;
    if (!isComponentCommandPayload(payload)) {
      log("ignoring malformed componentCommand payload:", JSON.stringify(payload).slice(0, 120));
      return;
    }

    for (const command of payload.commands) {
      let component: ComponentDescription;
      try {
        component = this.registry.get(command.id);
      } catch {
        log(`ignoring command for unknown component id "${command.id}" (tick ${this.simTick})`);
        continue;
      }
      if (component.type !== "wheel") {
        log(`ignoring command for non-wheel component "${command.id}" (tick ${this.simTick})`);
        continue;
      }

      const value = clamp(command.value, -1, 1);
      const pair = this.registry.wheelPair();
      const role: keyof WheelLevels | undefined =
        component.id === pair.left.id ? "left"
        : component.id === pair.right.id ? "right"
        : undefined;
      if (role !== undefined) {
        this.latestCommand[role] = value;
      }

      if (value !== command.value) {
        log(
          `componentCommand clamped to [-1,1]: { id: "${command.id}", value: ${value.toFixed(3)} } (tick ${this.simTick})`
        );
      } else {
        log(
          `componentCommand { id: "${command.id}", value: ${value.toFixed(3)} } (tick ${this.simTick})`
        );
      }
    }
  }

  // Safe stop: transition actuators to a safe state (WIRE_PROTOCOL §7,
  // COMPONENTS §1.9). Called on robot WebSocket close and on shutdown.
  private stopMotors(): void {
    this.latestCommand = { left: 0, right: 0 };
    log("safe stop: motors stopped");
  }

  // Nearest hit along a given heading among world box edges and obstacles.
  // Complements the box-edge ray cast: box edges are treated as walls per
  // specs/SENSORS.md §9.1.
  private nearestHit(heading: number): number | null {
    const boxDistance = castFrontRay(this.world.pose, {
      minX: this.world.minX,
      maxX: this.world.maxX,
      minY: this.world.minY,
      maxY: this.world.maxY,
    }, heading);
    const obstacleDistance = this.world.nearestObstacleHit(
      this.world.pose.x,
      this.world.pose.y,
      heading,
    );
    // Nearest hit among box edges and obstacles wins.
    let reading: number | null = null;
    if (boxDistance !== null && (reading === null || boxDistance < reading)) {
      reading = boxDistance;
    }
    if (
      obstacleDistance !== null &&
      (reading === null || obstacleDistance < reading)
    ) {
      reading = obstacleDistance;
    }
    return reading;
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

    // 1. Apply the latest component command (persistent actuator state —
    //    reused if the robot is slow; sim never waits for a new command).
    const collision = this.world.apply(this.latestCommand.left, this.latestCommand.right);
    if (collision.collided) {
      const p = this.world.pose;
      log(
        `collision at tick ${this.simTick}: pose=(${p.x.toFixed(2)},${p.y.toFixed(2)}) ` +
          `stopped against obstacle/boundary`,
      );
    }

    // 2. Every declared proximity sensor casts from the robot centre along
    //    pose.heading + its own declared angle. Take the nearest hit against
    //    box edges and obstacles; single-slot, overwritten each tick.
    const pose = this.world.pose;
    this.latestReadings = this.registry.sensors().map((component) => ({
      id: component.id,
      value: this.nearestHit(pose.heading + component.angle),
    }));

    // 3. Publish one componentSnapshot to the robot channel every tick.
    //    Single-slot (no queue); payload carries one reading per declared
    //    proximity sensor (null when nothing is detected on that ray).
    const snapshotPayload: ComponentSnapshotPayload = { readings: this.latestReadings };
    const snapshotEnvelope: OutgoingEnvelope = {
      type: "componentSnapshot",
      seq: 0, // assigned per-connection by the transport on send
      simTick: this.simTick,
      payload: snapshotPayload,
    };
    if (this.robotConnectionId) {
      this.transport.send(this.robotConnectionId, snapshotEnvelope);
    }

    // 4. Publish worldSnapshot to the visualiser channel every tick.
    //    Drop-oldest backpressure per client; never block on a visualiser.
    const worldEnvelope: OutgoingEnvelope = {
      type: "worldSnapshot",
      seq: 0, // assigned per-connection by the transport on send
      simTick: this.simTick,
      payload: {
        robot: { ...this.world.pose },
        obstacles: this.world.obstacles,
      },
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
