// Launch the Java controller with the shared manifest resolved from this
// script's location, so `npm run dev:robot` works from any directory.
// No shell is used: argument handling is then identical on Windows and POSIX,
// and the Java process is a direct child that can be signalled cleanly.
const { spawn } = require('child_process');
const path = require('path');

const root = path.resolve(__dirname, '..');
const jar = path.join(root, 'robot-framework', 'robot.jar');
const robotManifest = path.join(root, 'robots', 'default.json');

const args = [
  '-jar',
  jar,
  '--robot',
  robotManifest,
  ...process.argv.slice(2),
];

const child = spawn('java', args, { cwd: root, stdio: 'inherit' });

child.on('error', (err) => {
  console.error(`[runRobot] could not start java: ${err.message}`);
  process.exit(1);
});

child.on('exit', (code) => {
  process.exit(code === null ? 1 : code);
});
