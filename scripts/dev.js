// Dev startup script: starts sim server first, waits for it to be ready,
// then starts the visualiser and robot controller.
// This avoids the robot seeing "Connection refused" because the sim
// (ts-node compilation) takes longer to start than the Java process.
const { spawn } = require('child_process');
const net = require('net');
const path = require('path');

const root = path.resolve(__dirname, '..');
const robotManifest = path.resolve(root, 'robots', 'default.json');
const HOST = '127.0.0.1';
const SIM_START_TIMEOUT_MS = 30_000;
const CONNECT_TIMEOUT_MS = 3_000;
const RETRY_INTERVAL_MS = 500;

const processes = [];
let shuttingDown = false;
let simReady = false;

function readPort(name, fallback) {
  const raw = process.env[name];
  if (raw === undefined || raw === '') return fallback;
  const value = Number(raw);
  if (!Number.isInteger(value) || value < 1 || value > 65535) {
    throw new Error(`${name} must be an integer in 1..65535 (got "${raw}")`);
  }
  return value;
}

function aliveProcesses() {
  return processes.filter((p) => p.exitCode === null && p.signalCode === null);
}

function shutdown(reason, exitCode = 0) {
  if (shuttingDown) return;
  shuttingDown = true;
  console.log(`\n[dev] ${reason} — shutting down...`);

  for (const proc of aliveProcesses()) {
    try {
      proc.kill('SIGTERM');
    } catch {}
  }

  // Bounded fallback for children that ignore SIGTERM.
  const hardKill = setTimeout(() => {
    for (const proc of aliveProcesses()) {
      try {
        proc.kill('SIGKILL');
      } catch {}
    }
    process.exit(exitCode);
  }, 3_000);

  Promise.all(
    aliveProcesses().map(
      (proc) => new Promise((resolve) => proc.once('exit', resolve))
    )
  ).then(() => {
    clearTimeout(hardKill);
    process.exit(exitCode);
  });
}

// Registered before anything is spawned, so Ctrl-C during startup still cleans up.
process.on('SIGINT', () => shutdown('SIGINT received'));
process.on('SIGTERM', () => shutdown('SIGTERM received'));

function onChildExit(name, reason) {
  if (shuttingDown) return;
  if (name === 'sim' && !simReady) return; // waitForPort aborts startup with a clear message
  shutdown(`${name} process exited (${reason})`, 1);
}

function spawnCmd(name, cmd, args, cwd) {
  console.log(`[dev] [${name}] starting: ${cmd} ${args.join(' ')}`);
  // No shell: children are signalled directly and actually die on shutdown.
  const proc = spawn(cmd, args, { cwd, stdio: 'inherit' });
  proc.name = name;
  processes.push(proc);

  let rejectFailure;
  const failure = new Promise((_, reject) => {
    rejectFailure = reject;
  });
  failure.catch(() => {}); // also observed by waitForPort when this is the sim

  proc.once('exit', (code, signal) => {
    const reason = signal ? `killed by ${signal}` : `exit code ${code}`;
    rejectFailure(reason);
    onChildExit(name, reason);
  });
  proc.once('error', (err) => {
    const reason = `failed to start: ${err.message}`;
    rejectFailure(reason);
    onChildExit(name, reason);
  });
  return { proc, failure };
}

function waitForPort(port, host, failure) {
  return new Promise((resolve, reject) => {
    let settled = false;
    let currentSocket;
    let retryTimer;
    let overallTimer;

    const cleanup = () => {
      clearTimeout(overallTimer);
      clearTimeout(retryTimer);
      if (currentSocket) {
        currentSocket.removeAllListeners();
        currentSocket.destroy();
      }
    };
    const succeed = () => {
      if (settled) return;
      settled = true;
      cleanup();
      resolve();
    };
    const fail = (message) => {
      if (settled) return;
      settled = true;
      cleanup();
      reject(new Error(`sim did not start: ${message}`));
    };

    overallTimer = setTimeout(() => {
      fail(`no listener on ${host}:${port} after ${SIM_START_TIMEOUT_MS / 1000}s`);
    }, SIM_START_TIMEOUT_MS);
    failure.catch((reason) => fail(reason));

    const tryConnect = () => {
      if (settled) return;
      const socket = new net.Socket();
      currentSocket = socket;
      let attemptDone = false;
      let attemptTimer;
      const endAttempt = () => {
        if (attemptDone) return;
        attemptDone = true;
        clearTimeout(attemptTimer);
      };

      attemptTimer = setTimeout(() => {
        endAttempt();
        socket.destroy();
        if (!settled) retryTimer = setTimeout(tryConnect, RETRY_INTERVAL_MS);
      }, CONNECT_TIMEOUT_MS);

      socket.once('connect', () => {
        endAttempt();
        succeed();
      });
      socket.once('error', () => {
        endAttempt();
        socket.destroy();
        if (!settled) retryTimer = setTimeout(tryConnect, RETRY_INTERVAL_MS);
      });
      socket.connect(port, host);
    };
    tryConnect();
  });
}

async function main() {
  const robotPort = readPort('ROBOT_PORT', 8080);
  readPort('VISUALISER_PORT', 8081); // validate here too; sim validates independently

  // 1. Start the sim server first (slowest to start due to ts-node compilation)
  const { failure: simFailure } = spawnCmd(
    'sim',
    'npm',
    ['run', 'dev', '--', '--robot', robotManifest],
    path.join(root, 'simulator/server')
  );

  // 2. Wait for the sim to accept connections on the robot channel
  console.log(`[dev] waiting for sim server on ${HOST}:${robotPort}...`);
  await waitForPort(robotPort, HOST, simFailure);
  if (shuttingDown) return;
  simReady = true;
  console.log('[dev] sim server is up.');

  // 3. Start the visualiser dev server
  spawnCmd('viz', 'npm', ['run', 'dev'], path.join(root, 'simulator/visualiser'));

  // 4. Start the robot controller (now that the sim is ready)
  spawnCmd(
    'robot',
    'java',
    [
      '-jar',
      path.join(root, 'robot-framework', 'robot.jar'),
      '--host',
      HOST,
      '--port',
      String(robotPort),
      '--behaviour',
      'wall-follow-right',
      '--robot',
      robotManifest,
    ],
    root
  );
}

main().catch((err) => {
  console.error('[dev] fatal:', err instanceof Error ? err.message : err);
  shutdown('startup failed', 1);
});
