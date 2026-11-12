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

import { WebSocketTransport } from "./transport";
import { SimEngine } from "./sim";
import { TransportOptions } from "./types";

const ROBOT_PORT = Number(process.env.ROBOT_PORT ?? 8080);
const VISUALISER_PORT = Number(process.env.VISUALISER_PORT ?? 8081);

function log(...args: unknown[]): void {
  const stamp = new Date().toISOString().slice(11, 19);
  console.log(`[sim-server] ${stamp}`, ...args);
}

async function main(): Promise<void> {
  const options: TransportOptions = { robotPort: ROBOT_PORT, visualiserPort: VISUALISER_PORT };

  const transport = new WebSocketTransport();
  const sim = new SimEngine(transport);

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
