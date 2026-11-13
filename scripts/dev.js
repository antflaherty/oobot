// Dev startup script: starts sim server first, waits for it to be ready,
// then starts the visualiser and robot controller.
// This avoids the robot seeing "Connection refused" because the sim
// (ts-node compilation) takes longer to start than the Java process.
const { spawn } = require('child_process');
const net = require('net');
const path = require('path');

const root = path.resolve(__dirname, '..');

function waitForPort(port, host = 'localhost', interval = 500) {
  return new Promise((resolve) => {
    const tryConnect = () => {
      const socket = new net.Socket();
      const timeout = setTimeout(() => {
        socket.destroy();
        tryConnect();
      }, 3000);
      socket.once('connect', () => {
        clearTimeout(timeout);
        socket.destroy();
        resolve();
      });
      socket.once('error', () => {
        clearTimeout(timeout);
        socket.destroy();
        setTimeout(tryConnect, interval);
      });
      socket.connect(port, host);
    };
    tryConnect();
  });
}

async function main() {
  const processes = [];

  function spawnCmd(name, cmd, args, cwd) {
    console.log(`[dev] [${name}] starting: ${cmd} ${args.join(' ')}`);
    const proc = spawn(cmd, args, { cwd, stdio: 'inherit', shell: true });
    proc.name = name;
    processes.push(proc);
    return proc;
  }

  // 1. Start the sim server first (slowest to start due to ts-node compilation)
  const sim = spawnCmd('sim', 'npm', ['run', 'dev'], path.join(root, 'simulator/server'));

  // 2. Wait for the sim to accept connections on the robot channel
  console.log('[dev] waiting for sim server on port 8080...');
  await waitForPort(8080);
  console.log('[dev] sim server is up.');

  // 3. Start the visualiser dev server
  const viz = spawnCmd('viz', 'npm', ['run', 'dev'], path.join(root, 'simulator/visualiser'));

  // 4. Start the robot controller (now that the sim is ready)
  const robot = spawnCmd(
    'robot',
    'java',
    ['-jar', 'robot-framework/robot.jar', '--host', 'localhost', '--port', '8080', '--behaviour', 'wall-follow-right'],
    root
  );

  function shutdown(signal) {
    console.log(`\n[dev] received ${signal}, shutting down...`);
    for (const proc of processes) {
      try {
        proc.kill('SIGTERM');
      } catch {}
    }
    process.exit(0);
  }

  process.on('SIGINT', () => shutdown('SIGINT'));
  process.on('SIGTERM', () => shutdown('SIGTERM'));

  // If any child exits, shut down everything.
  Promise.all(
    processes.map(
      (p) =>
        new Promise((resolve) => {
          p.on('exit', () => resolve(p.name));
        })
    )
  ).then((name) => {
    console.log(`[dev] ${name} process exited, shutting down all.`);
    shutdown('exit');
  });
}

main().catch((err) => {
  console.error('[dev] fatal:', err);
  process.exit(1);
});
