// Sim server entry point.
//
// Starts a single Node.js process hosting two WebSocket channels from the
// encapsulated WebSocketTransport (MVP transport implementation per
// specs/COMMS.md §2.2) and runs the SimEngine on a fixed ~60 Hz timestep.
//
//   ws://localhost:ROBOT_PORT      robot channel   (componentCommand in, componentSnapshot out)
//   ws://localhost:VISUALISER_PORT visualiser channel (worldSnapshot out)
//
// Ports default to 8080/8081 and can be overridden via ROBOT_PORT /
// VISUALISER_PORT environment variables. Localhost only, hardcoded ports,
// no discovery (DESIGN_REVIEW 6.1 / 6.4).
//
// By default the sim loads the maze world from simulator/server/worlds/maze.json
// and the manifest from robots/default.json; --world <path> and --robot <path>
// override those. If the world file is missing or unparseable a warning is
// logged and the sim falls back to an empty default world.

import { WebSocketTransport } from "./transport";
import { SimEngine } from "./sim";
import { loadRobotDescription } from "./robotDescription";
import { toRobotShape } from "./geometry";
import { TransportOptions, RobotPose, Obstacle, GoalArea } from "./types";
import {
  loadWorldFile,
  WorldConfig,
} from "./world";
import * as path from "path";

function parsePort(name: string, raw: string | undefined, fallback: number): number {
  if (raw === undefined || raw === "") return fallback;
  const value = Number(raw);
  if (!Number.isInteger(value) || value < 1 || value > 65535) {
    throw new Error(`${name} must be an integer in 1..65535 (got "${raw}")`);
  }
  return value;
}

// Both defaults are resolved from this file's directory so the server works
// regardless of cwd (src/ under ts-node, dist/ when built).
const DEFAULT_WORLD_FILE = path.resolve(__dirname, "..", "worlds", "maze.json");
const DEFAULT_ROBOT_FILE = path.resolve(
  __dirname,
  "..",
  "..",
  "..",
  "robots",
  "default.json",
);

function optionPath(flag: string, fallback: string): string {
  const flagIndex = process.argv.indexOf(flag);
  if (flagIndex < 0) return fallback;
  const provided = process.argv[flagIndex + 1];
  if (!provided || provided.startsWith("--")) {
    throw new Error(`${flag} requires a path`);
  }
  return path.resolve(provided);
}

function log(...args: unknown[]): void {
  const stamp = new Date().toISOString().slice(11, 19);
  console.log(`[sim-server] ${stamp}`, ...args);
}

async function main(): Promise<void> {
  const robotPort = parsePort("ROBOT_PORT", process.env.ROBOT_PORT, 8080);
  const visualiserPort = parsePort("VISUALISER_PORT", process.env.VISUALISER_PORT, 8081);
  const options: TransportOptions = { robotPort, visualiserPort };
  // The manifest defines the physical footprint, so load it before the world
  // file: spawn validation needs the full robot shape.
  const robotDescriptionPathResolved = optionPath("--robot", DEFAULT_ROBOT_FILE);
  const robotDescription = loadRobotDescription(robotDescriptionPathResolved);
  const shape = toRobotShape(robotDescription.body, robotDescription.components);
  log(`loaded robot manifest: ${robotDescription.name} (${robotDescriptionPathResolved}) — ${robotDescription.components.length} components`);

  const worldFile = optionPath("--world", DEFAULT_WORLD_FILE);

  const transport = new WebSocketTransport();

  // Load the selected world file; fall back to an empty world on any failure.
  let worldConfig: WorldConfig | undefined;
  let startPose: RobotPose = { x: 0, y: 0, heading: 0 };
  let obstacles: Obstacle[] = [];
  let goal: GoalArea | null = null;

  try {
    const loaded = loadWorldFile(worldFile, shape);
    worldConfig = loaded.config;
    startPose = loaded.startPose;
    obstacles = loaded.obstacles;
    goal = loaded.goal;
    log(
      `loaded world file: ${worldFile} ` +
        `(box ${loaded.config.width}x${loaded.config.height}, ${obstacles.length} obstacles, ` +
        `start=(${startPose.x},${startPose.y},heading=${startPose.heading}))`,
    );
  } catch (err) {
    log(
      `warning: could not load world file "${worldFile}": ${
        err instanceof Error ? err.message : String(err)
      } — falling back to empty default world`,
    );
  }

  const sim = new SimEngine(transport, robotDescription, worldConfig, startPose, obstacles, goal);

  process.on("SIGINT", async () => {
    log("SIGINT received — shutting down");
    try {
      await sim.stop();
      await transport.shutdown();
    } finally {
      process.exit(0);
    }
  });
  process.on("SIGTERM", async () => {
    log("SIGTERM received — shutting down");
    try {
      await sim.stop();
      await transport.shutdown();
    } finally {
      process.exit(0);
    }
  });

  await transport.listen(options);
  sim.start();

  log(
    `sim server running: robot channel ws://localhost:${robotPort}, ` +
      `visualiser channel ws://localhost:${visualiserPort} (world: ${worldFile})`
  );
}

main().catch((err: unknown) => {
  console.error("sim server failed to start:", err instanceof Error ? err.message : err);
  process.exit(1);
});
