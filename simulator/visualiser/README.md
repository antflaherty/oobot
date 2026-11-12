# simulator/visualiser

Vanilla TypeScript + HTML5 canvas browser application.

Responsibilities (MVP):
- Connects to the visualiser WebSocket channel
- Renders the bounded box, robot, and obstacles
- Holds the last received `worldSnapshot` and interpolates to the next on each render tick
- Does not drive or block simulation timing

See `specs/WIRE_PROTOCOL.md` for the wire contract.