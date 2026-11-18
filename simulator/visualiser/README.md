# simulator/visualiser

Vanilla TypeScript + HTML5 canvas browser application (vite).

## Responsibilities

- Connects to the visualiser WebSocket channel (`ws://localhost:8081` by default).
- Renders the bounded box, robot and the obstacles supplied in
  `worldSnapshot.payload.obstacles`; it does not read the world or manifest files.
- Holds the last received `worldSnapshot` and interpolates to the next on each
  render tick. State (snapshot buffer, interpolation clock, trail) resets on
  every connect/reconnect and when `simTick` goes backwards (D-5).
- With no live sim data, leaves the canvas empty and shows a "no connection"
  status message; it does not play a demo stream.
- Does not drive or block simulation timing.

## Build and test

```bash
npm test   # npm run build:test (tsc → dist-test/) + node --test
npm run build # vite build + tsc --noEmit
```

Tests in `test/**` cover interpolation maths and the WebSocket client
(fragmented lines, invalid snapshots, reconnect reset and `stop()`).

See `specs/WIRE_PROTOCOL.md` for the wire contract.
