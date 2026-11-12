# simulator/server

Node.js + TypeScript simulation engine and WebSocket server.

Responsibilities (MVP):
- Owns the bounded-box 2D world and robot state
- Advances on a fixed ~60 Hz timestep
- Applies motor commands, calculates movement/collisions
- Publishes `worldSnapshot` on the visualiser WebSocket channel
- Handles `motorCommand` on the robot WebSocket channel
- Publishes `sensorSnapshot` (empty payload for iteration 1)
- Safe-stops motors on robot WebSocket close

See `specs/WIRE_PROTOCOL.md` for the wire contract.