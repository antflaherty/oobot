import { test } from 'node:test';
import assert from 'node:assert/strict';

import { LiveStream } from '../src/liveStream';
import type { RobotShape } from '../src/types';

/** Minimal stand-in for the browser WebSocket, cast at the injection point. */
class FakeWebSocket {
  onopen: ((ev: Event) => void) | null = null;
  onmessage: ((ev: MessageEvent) => void) | null = null;
  onclose: ((ev: CloseEvent) => void) | null = null;
  onerror: ((ev: Event) => void) | null = null;
  readonly url: string;
  closed = false;

  constructor(url: string) {
    this.url = url;
  }

  open(): void {
    this.onopen?.({} as Event);
  }

  receive(data: string): void {
    this.onmessage?.({ data } as MessageEvent);
  }

  close(): void {
    this.closed = true;
    this.onclose?.({} as CloseEvent);
  }
}

interface Harness {
  stream: LiveStream;
  sockets: FakeWebSocket[];
  setNow: (ms: number) => void;
  pendingTimers: () => number;
  fireTimers: () => void;
}

function makeStream(): Harness {
  let nowMs = 0;
  let nextTimerId = 1;
  const timers = new Map<number, () => void>();
  const sockets: FakeWebSocket[] = [];

  const stream = new LiveStream({
    createWebSocket: (url: string) => {
      const ws = new FakeWebSocket(url);
      sockets.push(ws);
      return ws as unknown as WebSocket;
    },
    now: () => nowMs,
    setTimeout: (fn, ms) => {
      // The `ms` value is irrelevant for these tests; timers are fired manually.
      void ms;
      const id = nextTimerId++;
      timers.set(id, fn);
      return id;
    },
    clearTimeout: (id) => {
      timers.delete(id);
    },
  });

  return {
    stream,
    sockets,
    setNow: (ms) => {
      nowMs = ms;
    },
    pendingTimers: () => timers.size,
    fireTimers: () => {
      const callbacks = [...timers.values()];
      timers.clear();
      for (const fn of callbacks) fn();
    },
  };
}

const TEST_SHAPE: RobotShape = {
  width: 50,
  height: 50,
  wheels: [
    { angle: Math.PI / 2, offset: 25, length: 30, width: 10 },
    { angle: -Math.PI / 2, offset: 25, length: 30, width: 10 },
  ],
};

function snapshotLine(tick: number, x: number, y: number, shape?: unknown): string {
  const msg = {
    type: 'worldSnapshot',
    seq: tick,
    simTick: tick,
    payload: {
      robot: { x, y, heading: 0, ...(shape === undefined ? {} : { shape }) },
      obstacles: [{ x: 10, y: 20, width: 30, height: 40 }],
    },
  };
  return `${JSON.stringify(msg)}\n`;
}

test('a NDJSON line split across two frames emits exactly one snapshot', () => {
  const { stream, sockets, setNow } = makeStream();
  const trail: Array<[number, number]> = [];
  stream.setTrailPointCallback((x, y) => trail.push([x, y]));

  stream.start();
  const ws = sockets[0];
  assert.ok(ws, 'a WebSocket should have been created');
  assert.equal(ws.url, 'ws://localhost:8081');
  ws.open();
  assert.equal(stream.isConnected(), true);

  const line = snapshotLine(7, 12, -4);
  const cut = 15;
  ws.receive(line.slice(0, cut));
  assert.equal(trail.length, 0, 'a partial line must not emit a snapshot');

  setNow(50);
  ws.receive(line.slice(cut));

  assert.equal(trail.length, 1, 'the completed line must emit exactly one snapshot');
  assert.deepEqual(trail[0], [12, -4]);
  assert.equal(stream.getLatestTick(), 7);
  assert.deepEqual(stream.getObstacles(), [{ x: 10, y: 20, width: 30, height: 40 }]);

  setNow(100);
  const pose = stream.getPoseAt(100);
  assert.ok(pose);
  assert.equal(pose.pose.x, 12);
  assert.equal(pose.pose.y, -4);

  stream.stop();
  assert.equal(ws.closed, true);
  assert.equal(stream.isConnected(), false);
});

test('reset() clears buffered state and trail-reset effects', () => {
  const { stream, sockets, setNow, pendingTimers } = makeStream();
  const trail: Array<[number, number]> = [];
  let trailResets = 0;
  stream.setTrailPointCallback((x, y) => trail.push([x, y]));
  stream.setTrailResetCallback(() => {
    trailResets++;
  });

  stream.start();
  const ws = sockets[0];
  assert.ok(ws);
  ws.open();
  const resetsAfterOpen = trailResets; // onopen reset() clears the trail
  setNow(10);
  ws.receive(snapshotLine(3, 1, 2));

  assert.equal(stream.getLatestTick(), 3);
  assert.ok(stream.getPoseAt(10));
  assert.equal(stream.getObstacles().length, 1);
  assert.deepEqual(trail, [[1, 2]]);
  assert.equal(pendingTimers(), 0);

  stream.reset();

  assert.equal(stream.getLatestTick(), null);
  assert.equal(stream.getPoseAt(10), null, 'no pose can be produced with an empty buffer');
  assert.deepEqual(stream.getObstacles(), []);
  assert.equal(
    trailResets,
    resetsAfterOpen + 1,
    'reset() must clear the trail callback effects',
  );
  assert.deepEqual(trail, [[1, 2]], 'reset() itself must not emit trail points');

  // A partial line from before reset must not be completed by a later frame.
  ws.receive('{"type":"worldSnap');
  stream.reset();
  ws.receive('shot","seq":0,"simTick":0,"payload":{}}\n');
  assert.equal(stream.getLatestTick(), null);

  // The trail callback remains registered and works after a reset.
  setNow(20);
  ws.receive(snapshotLine(4, 5, 6));
  assert.equal(trail.length, 2);
  assert.deepEqual(trail[1], [5, 6]);

  stream.stop();
  // reconnect/stop behaviour is asserted in the tests below.
});

test('two NDJSON lines in one frame emit exactly two snapshots', () => {
  const { stream, sockets, setNow } = makeStream();
  const trail: Array<[number, number]> = [];
  stream.setTrailPointCallback((x, y) => trail.push([x, y]));

  stream.start();
  const ws = sockets[0];
  assert.ok(ws);
  ws.open();
  setNow(10);
  ws.receive(snapshotLine(1, 1, 1) + snapshotLine(2, 2, 2));

  assert.deepEqual(trail, [
    [1, 1],
    [2, 2],
  ]);
  assert.equal(stream.getLatestTick(), 2);

  stream.stop();
});

test('malformed NDJSON lines are logged and ignored', () => {
  const { stream, sockets } = makeStream();
  stream.start();
  const ws = sockets[0];
  assert.ok(ws);
  ws.open();

  const originalWarn = console.warn;
  const warnings: string[] = [];
  console.warn = (...args: unknown[]) => {
    warnings.push(args.map(String).join(' '));
  };
  try {
    ws.receive('{not-json\n');
    ws.receive(snapshotLine(9, 3, 4));
  } finally {
    console.warn = originalWarn;
  }

  assert.equal(
    stream.getLatestTick(),
    9,
    'a malformed line must not prevent later valid snapshots',
  );
  assert.ok(
    warnings.some((w) => w.includes('malformed NDJSON line')),
    'malformed lines must be logged',
  );

  stream.stop();
});

test('snapshots with non-finite simTick or pose fields are ignored', () => {
  const { stream, sockets } = makeStream();
  const trail: Array<[number, number]> = [];
  stream.setTrailPointCallback((x, y) => trail.push([x, y]));

  stream.start();
  const ws = sockets[0];
  assert.ok(ws);
  ws.open();

  const originalWarn = console.warn;
  console.warn = () => undefined;
  try {
    ws.receive(
      '{"type":"worldSnapshot","seq":0,"simTick":null,' +
        '"payload":{"robot":{"x":1,"y":2,"heading":0}}}\n',
    );
    ws.receive(
      '{"type":"worldSnapshot","seq":0,"simTick":3,' +
        '"payload":{"robot":{"x":null,"y":2,"heading":0}}}\n',
    );
    ws.receive(
      '{"type":"worldSnapshot","seq":0,"simTick":4,' +
        '"payload":{"robot":{"x":1,"y":2,"heading":1e999}}}\n',
    );
  } finally {
    console.warn = originalWarn;
  }

  assert.equal(stream.getLatestTick(), null);
  assert.deepEqual(trail, [], 'invalid snapshots must not record trail points');

  // A valid frame after the bad ones is still accepted.
  ws.receive(snapshotLine(5, 7, 8));
  assert.equal(stream.getLatestTick(), 5);
  assert.deepEqual(trail, [[7, 8]]);

  stream.stop();
});

test('reconnect clears buffered snapshots, obstacles, clock and trail', () => {
  const { stream, sockets, setNow, fireTimers, pendingTimers } = makeStream();
  const trail: Array<[number, number]> = [];
  let trailResets = 0;
  stream.setTrailPointCallback((x, y) => trail.push([x, y]));
  stream.setTrailResetCallback(() => {
    trailResets++;
  });

  stream.start();
  const ws1 = sockets[0];
  assert.ok(ws1);
  ws1.open();
  const resetsAfterOpen = trailResets; // onopen reset() clears the trail
  setNow(10);
  ws1.receive(snapshotLine(5, 10, 20));
  assert.equal(stream.getLatestTick(), 5);
  assert.equal(stream.getObstacles().length, 1);
  assert.deepEqual(trail, [[10, 20]]);

  ws1.close();
  assert.equal(stream.isConnected(), false);
  assert.equal(pendingTimers(), 1, 'a reconnect should be scheduled');
  assert.equal(trailResets, resetsAfterOpen + 1, 'close must cancel the trail');

  fireTimers();
  const ws2 = sockets[1];
  assert.ok(ws2, 'a replacement socket should be created');
  ws2.open();
  assert.equal(stream.isConnected(), true);
  assert.equal(stream.getLatestTick(), null, 'reconnect clears buffered snapshots');
  assert.equal(stream.getPoseAt(10), null, 'reconnect clears the interpolation clock');
  assert.deepEqual(stream.getObstacles(), [], 'reconnect clears stale obstacles');
  assert.ok(trailResets > resetsAfterOpen + 1, 'reconnect must clear the trail again');

  setNow(20);
  ws2.receive(snapshotLine(1, 30, 40));
  assert.equal(stream.getLatestTick(), 1);
  assert.deepEqual(trail[trail.length - 1], [30, 40]);

  stream.stop();
});

test('a decreasing simTick resets session state before applying the new snapshot', () => {
  const { stream, sockets, setNow } = makeStream();
  const trail: Array<[number, number]> = [];
  let trailResets = 0;
  stream.setTrailPointCallback((x, y) => trail.push([x, y]));
  stream.setTrailResetCallback(() => {
    trailResets++;
  });

  stream.start();
  const ws = sockets[0];
  assert.ok(ws);
  ws.open();
  const resetsAfterOpen = trailResets; // onopen reset() clears the trail
  setNow(0);
  ws.receive(snapshotLine(100, 100, 100));
  setNow(16);
  ws.receive(snapshotLine(101, 110, 110));
  assert.equal(stream.getLatestTick(), 101);

  // Sim restarts: the next snapshot's tick goes back to 0.
  setNow(32);
  ws.receive(snapshotLine(0, -5, -6));
  assert.equal(trailResets, resetsAfterOpen + 1, 'rollback must clear the old session trail');
  assert.equal(stream.getLatestTick(), 0);
  const afterRestart = stream.getPoseAt(32);
  assert.ok(afterRestart);
  assert.equal(afterRestart.pose.x, -5, 'pose must come from the new session');
  assert.equal(afterRestart.pose.y, -6);

  // The old ticks must not remain in the buffer: interpolation now sweeps
  // between the two new-session snapshots instead of freezing on old poses.
  setNow(48);
  ws.receive(snapshotLine(1, -10, -12));
  const interpolating = stream.getPoseAt(56);
  assert.ok(interpolating);
  assert.equal(interpolating.holding, false);
  assert.ok(interpolating.pose.x < -5 && interpolating.pose.x > -10);
  assert.deepEqual(trail, [
    [100, 100],
    [110, 110],
    [-5, -6],
    [-10, -12],
  ]);

  stream.stop();
});

test('stop() cancels a pending reconnect and creates no new socket', () => {
  const { stream, sockets, fireTimers, pendingTimers } = makeStream();
  stream.start();
  const ws1 = sockets[0];
  assert.ok(ws1);
  ws1.open();

  ws1.close();
  assert.equal(pendingTimers(), 1);

  stream.stop();
  assert.equal(pendingTimers(), 0, 'stop must clear the pending reconnect timer');
  assert.equal(ws1.closed, true);
  assert.equal(stream.isConnected(), false);

  fireTimers();
  assert.equal(sockets.length, 1, 'no new socket may be created after stop');
  assert.equal(pendingTimers(), 0);
});

test('a close caused by stop() does not schedule a reconnect', () => {
  const { stream, sockets, pendingTimers, fireTimers } = makeStream();
  stream.start();
  const ws = sockets[0];
  assert.ok(ws);
  ws.open();

  // FakeWebSocket.close() fires onclose synchronously; stop() must have
  // cleared the reconnect flag before calling it.
  stream.stop();
  assert.equal(ws.closed, true);
  assert.equal(pendingTimers(), 0);

  fireTimers();
  assert.equal(sockets.length, 1);
});

test('getRobotShape caches the latest valid shape from snapshots', () => {
  const { stream, sockets } = makeStream();
  stream.start();
  const ws = sockets[0];
  assert.ok(ws);
  ws.open();

  assert.equal(stream.getRobotShape(), null, 'no shape before any snapshot');
  ws.receive(snapshotLine(1, 0, 0, TEST_SHAPE));
  assert.deepEqual(stream.getRobotShape(), TEST_SHAPE);

  const revised: RobotShape = {
    ...TEST_SHAPE,
    width: 60,
    wheels: [{ angle: 0, offset: 10, length: 20, width: 8 }],
  };
  ws.receive(snapshotLine(2, 0, 0, revised));
  assert.deepEqual(stream.getRobotShape(), revised, 'the newest shape must win');

  stream.stop();
});

test('shape cache clears on absent shape, reset, reconnect and tick rollback', () => {
  const { stream, sockets, setNow, fireTimers } = makeStream();
  stream.start();
  const ws1 = sockets[0];
  assert.ok(ws1);
  ws1.open();

  ws1.receive(snapshotLine(1, 0, 0, TEST_SHAPE));
  assert.deepEqual(stream.getRobotShape(), TEST_SHAPE);

  ws1.receive(snapshotLine(2, 0, 0)); // no shape on this snapshot
  assert.equal(stream.getRobotShape(), null, 'a shapeless snapshot clears the cache');

  ws1.receive(snapshotLine(3, 0, 0, TEST_SHAPE));
  stream.reset();
  assert.equal(stream.getRobotShape(), null, 'reset() clears the cache');

  ws1.receive(snapshotLine(4, 0, 0, TEST_SHAPE));
  ws1.close();
  fireTimers();
  const ws2 = sockets[1];
  assert.ok(ws2);
  ws2.open();
  assert.equal(stream.getRobotShape(), null, 'reconnect clears the cache');

  setNow(10);
  ws2.receive(snapshotLine(100, 0, 0, TEST_SHAPE));
  setNow(20);
  ws2.receive(snapshotLine(0, 0, 0)); // rollback + no shape
  assert.equal(stream.getRobotShape(), null, 'rollback clears the previous session shape');

  // A rollback snapshot carrying a shape starts the new session with that shape.
  const revised: RobotShape = {
    ...TEST_SHAPE,
    width: 40,
    wheels: [{ angle: 0, offset: 5, length: 20, width: 8 }],
  };
  setNow(30);
  ws2.receive(snapshotLine(50, 0, 0, TEST_SHAPE));
  setNow(40);
  ws2.receive(snapshotLine(0, 0, 0, revised));
  assert.deepEqual(stream.getRobotShape(), revised);

  stream.stop();
});

test('malformed shapes are treated as absent without dropping snapshots', () => {
  const { stream, sockets, setNow } = makeStream();
  stream.start();
  const ws = sockets[0];
  assert.ok(ws);
  ws.open();

  function receiveShape(shapeJson: string, tick: number): void {
    ws.receive(
      `{"type":"worldSnapshot","seq":${tick},"simTick":${tick},` +
        `"payload":{"robot":{"x":1,"y":2,"heading":0,"shape":${shapeJson}},` +
        `"obstacles":[]}}\n`,
    );
  }

  const goodWheel = '{"angle":0,"offset":25,"length":30,"width":10}';
  const malformed = [
    'null',
    '[]',
    `{"width":1e999,"height":50,"wheels":[${goodWheel}]}`, // Infinity
    `{"width":50,"height":50,"wheels":[]}`, // no wheels
    `{"width":"50","height":50,"wheels":[${goodWheel}]}`, // non-number width
    `{"width":50,"height":50,"wheels":[{"angle":null,"offset":25,"length":30,"width":10}]}`,
    `{"width":50,"height":50,"wheels":[{"angle":0,"offset":25,"length":30}]}`, // missing width
    `{"width":50,"height":50,"wheels":[${goodWheel},null]}`,
  ];

  malformed.forEach((shapeJson, i) => {
    const tick = i + 1;
    receiveShape(shapeJson, tick);
    assert.equal(stream.getRobotShape(), null, `shape ${shapeJson} must be ignored`);
    assert.equal(stream.getLatestTick(), tick, 'the surrounding snapshot remains valid');
  });

  setNow(100);
  const pose = stream.getPoseAt(100);
  assert.ok(pose);
  assert.ok(
    Number.isFinite(pose.pose.x) && Number.isFinite(pose.pose.y) && Number.isFinite(pose.pose.heading),
    'a bad shape must not produce NaN pose data',
  );

  // A valid shape after the bad ones is still accepted.
  receiveShape(JSON.stringify(TEST_SHAPE), malformed.length + 1);
  assert.deepEqual(stream.getRobotShape(), TEST_SHAPE);

  stream.stop();
});
