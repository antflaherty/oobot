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
  ToolState,
} from "./types";
import {
  WorldConfig,
  WorldModel,
  DEFAULT_WORLD_CONFIG,
  DEFAULT_START_POSE,
} from "./world";
import { castFrontRay } from "./sensor";
import { RobotShape, toRobotShape } from "./geometry";
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

// S2.2: high-frequency events (a componentCommand per wheel at ~20 Hz, and a
// collision every 60 Hz tick during steady wall contact) are aggregated into
// counters instead of being logged individually. Totals are appended to the
// existing 5 s achieved-Hz line and exposed via getState() for tests/tooling.
interface EventCounters {
  commandsApplied: number; // accepted componentCommand envelopes
  commandsIgnored: number; // componentCommand envelopes/entries rejected
  commandsClamped: number; // individual wheel values clamped into [-1, 1]
  collisions: number; // ticks whose world.apply reported a collision
}

function emptyCounters(): EventCounters {
  return { commandsApplied: 0, commandsIgnored: 0, commandsClamped: 0, collisions: 0 };
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

// D-4: seq and simTick must be finite, non-negative integers. This rejects
// NaN, Infinity, fractional values, negatives and (via the type check) missing
// simTick before any command can touch actuator state.
function isNonNegativeInteger(value: unknown): value is number {
  return (
    typeof value === "number" &&
    Number.isFinite(value) &&
    Number.isInteger(value) &&
    value >= 0
  );
}

function log(...args: unknown[]): void {
  const stamp = new Date().toISOString().slice(11, 19);
  console.log(`[sim] ${stamp}`, ...args);
}

export class SimEngine {
  private readonly transport: Transport;
  private readonly world: WorldModel;
  private readonly registry: ComponentRegistry;
  private readonly shape: RobotShape;

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

  // Authoritative tool state, keyed by component id. Initialised false for
  // every declared tool; independent of wheel/motor safe-stop state.
  private readonly toolStates = new Map<string, boolean>();

  private robotConnectionId: string | null = null;

  // D-4: sequence/freshness state for the currently active robot connection.
  // Reset whenever the active robot slot changes (new connection or disconnect).
  private lastAppliedSeq = -1;
  private lastSeenTick: number | null = null;

  private running = false;
  private timer: ReturnType<typeof setTimeout> | null = null;

  // Achieved-Hz observability (DESIGN_REVIEW 2.3; COMPONENTS §1.5).
  private ticksSinceLog = 0;
  private logStartTime = Date.now();

  // S2.2: cumulative event totals. Kept monotonic so tooling can assert on
  // them without scraping stdout; reported with the 5 s achieved-Hz line.
  private readonly counters: EventCounters = emptyCounters();

  // Emit the (accurate) collision detail line at most once per report window.
  private collisionLoggedThisWindow = false;

  constructor(
    transport: Transport,
    description: RobotDescription,
    worldConfig?: WorldConfig,
    startPose: RobotPose = DEFAULT_START_POSE,
    obstacles: Obstacle[] = [],
  ) {
    this.transport = transport;
    this.registry = new ComponentRegistry(description);
    for (const tool of this.registry.tools()) {
      this.toolStates.set(tool.id, false);
    }
    this.shape = toRobotShape(description.body, description.components);
    this.world = new WorldModel(
      { ...(worldConfig ?? DEFAULT_WORLD_CONFIG), trackWidth: this.registry.trackWidth() },
      startPose,
      obstacles,
      this.shape,
    );
  }

  start(): void {
    this.transport.setHandlers({
      onConnect: (connectionId: string, channel: Channel) => {
        if (channel === "robot") {
          const previous = this.robotConnectionId;
          // Claim the slot (and reset D-4 tracking) before closing the old
          // connection: its synchronous disconnect must not clear the new slot.
          this.robotConnectionId = connectionId;
          this.resetRobotCommandState();
          if (previous && previous !== connectionId) {
            log(`robot slot taken by ${connectionId}; closing previous robot ${previous}`);
            this.transport.close(previous);
          }
          log(`robot connected (${connectionId}) — awaiting componentCommand`);
        } else {
          log(`visualiser connected (${connectionId})`);
        }
      },
      onMessage: (
        connectionId: string,
        channel: Channel,
        envelope: IncomingEnvelope,
      ) => {
        if (envelope.type === "componentCommand") {
          if (channel !== "robot" || connectionId !== this.robotConnectionId) {
            this.counters.commandsIgnored++;
            log(
              `ignoring componentCommand from non-active connection ` +
                `${channel}/${connectionId} (active robot: ${this.robotConnectionId ?? "none"})`,
            );
            return;
          }
          this.handleComponentCommand(connectionId, envelope);
        } else {
          log(`ignoring unexpected message type "${envelope.type}"`);
        }
      },
      onDisconnect: (connectionId: string, channel: Channel) => {
        if (channel === "robot") {
          log(`robot disconnected (${connectionId})`);
          if (connectionId === this.robotConnectionId) {
            this.robotConnectionId = null;
            this.resetRobotCommandState();
            this.stopMotors(); // safe stop — motors cannot keep running
            this.clearToolStates(); // tools must not stay on with no controller
          } else {
            log(`stale robot disconnect ignored (${connectionId})`);
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
    this.clearToolStates();
    log("simulation stopped");
  }

  // ---- Robot channel input ----------------------------------------------

  private resetRobotCommandState(): void {
    this.lastAppliedSeq = -1;
    this.lastSeenTick = null;
  }

  private handleComponentCommand(connectionId: string, envelope: IncomingEnvelope): void {
    // Ownership was checked by onMessage; keep this guard so the invariant is
    // enforced at the point where actuator state is about to be touched.
    if (envelope.type !== "componentCommand" || connectionId !== this.robotConnectionId) {
      this.counters.commandsIgnored++;
      log(`ignoring componentCommand from non-active connection ${connectionId}`);
      return;
    }

    const { seq, simTick } = envelope;
    // D-4 step 2: finite, non-negative integers only.
    if (!isNonNegativeInteger(seq) || !isNonNegativeInteger(simTick)) {
      this.counters.commandsIgnored++;
      log(
        `ignoring componentCommand with invalid seq/simTick from ${connectionId} ` +
          `(seq=${String(seq)}, simTick=${String(simTick)})`,
      );
      return;
    }

    // D-4 step 3: duplicate / out-of-order seq.
    if (seq <= this.lastAppliedSeq) {
      this.counters.commandsIgnored++;
      log(
        `ignoring duplicate/stale componentCommand seq=${seq} ` +
          `(lastAppliedSeq=${this.lastAppliedSeq}) from ${connectionId}`,
      );
      return;
    }

    // D-4 step 4: one tick of tolerance for benign reordering; older is stale.
    if (this.lastSeenTick !== null && simTick < this.lastSeenTick - 1) {
      this.counters.commandsIgnored++;
      log(
        `ignoring stale componentCommand simTick=${simTick} ` +
          `(lastSeenTick=${this.lastSeenTick}) from ${connectionId}`,
      );
      return;
    }

    const payload = envelope.payload;
    if (!isComponentCommandPayload(payload)) {
      this.counters.commandsIgnored++;
      log("ignoring malformed componentCommand payload:", JSON.stringify(payload).slice(0, 120));
      return;
    }

    // Accepted: advance tracking state only now, before touching actuators.
    this.lastAppliedSeq = seq;
    this.lastSeenTick = Math.max(this.lastSeenTick ?? simTick, simTick);
    this.counters.commandsApplied++;

    for (const command of payload.commands) {
      let component: ComponentDescription;
      try {
        component = this.registry.get(command.id);
      } catch {
        this.counters.commandsIgnored++;
        log(`ignoring command for unknown component id "${command.id}" (tick ${this.simTick})`);
        continue;
      }
      if (component.type === "tool") {
        // Tool values are strict: exactly 0 = inactive, 1 = active. Anything
        // else is ignored (never clamped) and reported as an ignored command.
        if (command.value !== 0 && command.value !== 1) {
          this.counters.commandsIgnored++;
          log(
            `ignoring tool command value ${command.value} for "${command.id}" ` +
              `(tick ${this.simTick}) — expected 0 or 1`,
          );
          continue;
        }
        this.toolStates.set(component.id, command.value === 1);
        continue;
      }

      if (component.type !== "wheel") {
        this.counters.commandsIgnored++;
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
        this.counters.commandsClamped++;
      }
      // S2.2: do not log every accepted command (2 wheels × ~20 Hz). The
      // aggregate is reported with the 5 s achieved-Hz line and via getState().
    }
  }

  // Safe stop: transition actuators to a safe state (WIRE_PROTOCOL §7,
  // COMPONENTS §1.9). Called on robot WebSocket close and on shutdown.
  private stopMotors(): void {
    this.latestCommand = { left: 0, right: 0 };
    log("safe stop: motors stopped");
  }

  // Tool safe stop: independent of the D-2 motor safe stop. Called on active
  // robot disconnect and on sim shutdown so a tool cannot stay on unattended.
  private clearToolStates(): void {
    for (const tool of this.registry.tools()) {
      this.toolStates.set(tool.id, false);
    }
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
      this.counters.collisions++;
      // S2.2: wall contact can report a collision every 60 Hz tick. Keep the
      // accurate detail line, but emit it at most once per report window; the
      // running total is reported with the 5 s achieved-Hz line.
      if (!this.collisionLoggedThisWindow) {
        const p = this.world.pose;
        log(
          `collision at tick ${this.simTick}: pose=(${p.x.toFixed(2)},${p.y.toFixed(2)}) ` +
            `stopped against obstacle/boundary`,
        );
        this.collisionLoggedThisWindow = true;
      }
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
    //    Tool states are authoritative sim state, one per declared tool in
    //    manifest order, rebuilt from the map on every snapshot.
    const toolStates: ToolState[] = this.registry.tools().map((tool) => ({
      id: tool.id,
      active: this.toolStates.get(tool.id) ?? false,
    }));
    const worldEnvelope: OutgoingEnvelope = {
      type: "worldSnapshot",
      seq: 0, // assigned per-connection by the transport on send
      simTick: this.simTick,
      payload: {
        robot: { ...this.world.pose, shape: this.shape, toolStates },
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
      const counts = this.counters;
      log(
        `achieved ${hz} Hz (tick ${this.simTick}) | pose=(${p.x.toFixed(2)},${p.y.toFixed(2)},heading=${p.heading.toFixed(3)}) | command={ left: ${c.left.toFixed(2)}, right: ${c.right.toFixed(2)} } | robotConn=${this.robotConnectionId ? 'yes' : 'no'}` +
          ` | events(applied=${counts.commandsApplied},ignored=${counts.commandsIgnored},clamped=${counts.commandsClamped},collisions=${counts.collisions})`,
      );
      this.ticksSinceLog = 0;
      this.logStartTime = now;
      this.collisionLoggedThisWindow = false;
    }
  }

  // ---- Read-only access (useful for dev tooling / tests) ----------------

  public getState() {
    return {
      simTick: this.simTick,
      pose: { ...this.world.pose },
      command: { ...this.latestCommand },
      toolStates: Object.fromEntries(this.toolStates),
      counters: { ...this.counters },
    };
  }
}
