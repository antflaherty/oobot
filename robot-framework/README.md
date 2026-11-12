# robot-framework

Java robot framework and example controller.

Responsibilities (MVP):
- Runs as a separate Java process (fat JAR)
- Provides the student-facing hardware API (initially Motors)
- Hides WebSocket connection, NDJSON framing, and concurrency from robot code
- Invokes a behaviour update method (~20 Hz)
- Example behaviour: square-trace robot (forward one tick, turn one tick)

See `specs/WIRE_PROTOCOL.md` for the wire contract.