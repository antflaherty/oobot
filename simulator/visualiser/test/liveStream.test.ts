import { test } from 'node:test';
import assert from 'node:assert/strict';

import { LiveStream } from '../src/liveStream';
import type { WorldSnapshot } from '../src/types';

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
  };
}

function snapshotLine(tick: number, x: number, y: number): string {
  const msg: WorldSnapshot = {
    type: 'worldSnapshot',
    seq: tick,
    simTick: tick,
    payload: {
      robot: { x, y, heading: 0 },
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
  assert.equal(trailResets, 1, 'reset() must clear the trail callback effects');
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
  // Note: `stop()` cancelling timers scheduled by a synchronous onclose is
  // V2.1's reconnect-hardening scope; it is deliberately not asserted here.
});
