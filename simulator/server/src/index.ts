// Sim server entry point.
//
// Starts a single Node.js process hosting two WebSocket channels from the
// encapsulated WebSocketTransport (MVP transport implementation per
// specs/COMMS.md §2.2) and runs the SimEngine on a fixed ~60 Hz timestep.
//
//   ws://localhost:ROBOT_PORT      robot channel   (motorCommand in, sensorSnapshot out)
//   ws://localhost:VISUALISER_PORT visualiser channel (worldSnapshot out)
//
// Ports default to 8080/8081 and can be overridden via ROBOT_PORT /
// VISUALISER_PORT environment variables. Localhost only, hardcoded ports,
// no discovery (DESIGN_REVIEW 6.1 / 6.4).
//
// By default the sim loads the maze world from simulator/server/worlds/maze.json.
// If the file is missing or unparseable a warning is logged and the sim falls
// back to an empty default world — it never crashes on a bad world file.

import { WebSocketTransport } from "./transport";
import { SimEngine } from "./sim";
import { loadRobotDescription } from "./robotDescription";
import { TransportOptions, RobotPose, Obstacle } from "./types";
import {
  loadWorldFile,
  WorldConfig,
} from "./world";
import * as path from "path";

const ROBOT_PORT = Number(process.env.ROBOT_PORT ?? 8080);
const VISUALISER_PORT = Number(process.env.VISUALISER_PORT ?? 8081);

// Resolved relative to this file's directory so it works regardless of cwd
// (ts-node runs src/index.ts, so go up one level to the server root).
const DEFAULT_WORLD_FILE = path.join(__dirname, "..", "worlds", "maze.json");

// --robot <path> selects the component manifest; default is robots/default.json
// resolved from the current working directory.
function robotDescriptionPath(): string {
  const flagIndex = process.argv.indexOf("--robot");
  const provided = flagIndex >= 0 ? process.argv[flagIndex + 1] : undefined;
  return provided
    ? path.resolve(provided)
    : path.resolve(process.cwd(), "robots", "default.json");
}

function log(...args: unknown[]): void {
  const stamp = new Date().toISOString().slice(11, 19);
  console.log(`[sim-server] ${stamp}`, ...args);
}

async function main(): Promise<void> {
  const options: TransportOptions = { robotPort: ROBOT_PORT, visualiserPort: VISUALISER_PORT };

  const transport = new WebSocketTransport();

  // Load the default world file; fall back to an empty world on any failure.
  let worldConfig: WorldConfig | undefined;
  let startPose: RobotPose = { x: 0, y: 0, heading: 0 };
  let obstacles: Obstacle[] = [];

  try {
    const loaded = loadWorldFile(DEFAULT_WORLD_FILE);
    worldConfig = loaded.config;
    startPose = loaded.startPose;
    obstacles = loaded.obstacles;
    log(
      `loaded world file: ${DEFAULT_WORLD_FILE} ` +
        `(box ${loaded.config.width}x${loaded.config.height}, ${obstacles.length} obstacles, ` +
        `start=(${startPose.x},${startPose.y},heading=${startPose.heading}))`,
    );
  } catch (err) {
    log(
      `warning: could not load world file "${DEFAULT_WORLD_FILE}": ${
        err instanceof Error ? err.message : String(err)
      } — falling back to empty default world`,
    );
  }

  const robotDescriptionPathResolved = robotDescriptionPath();
  const robotDescription = loadRobotDescription(robotDescriptionPathResolved);
  log(`loaded robot manifest: ${robotDescription.name} (${robotDescriptionPathResolved}) — ${robotDescription.components.length} components`);

  const sim = new SimEngine(transport, robotDescription, worldConfig, startPose, obstacles);

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
    `sim server running: robot channel ws://localhost:${ROBOT_PORT}, ` +
      `visualiser channel ws://localhost:${VISUALISER_PORT}`
  );
}

main().catch((err: unknown) => {
  console.error("sim server failed to start:", err instanceof Error ? err.message : err);
  process.exit(1);
});
