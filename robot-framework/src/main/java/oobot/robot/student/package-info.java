/**
 * Student robot code area — <b>this is where student code lives.</b>
 *
 * <p>Students write three things in this package:
 * <ul>
 *   <li>{@link RobotBehaviour} — implement {@code init()} / {@code update()}
 *       (~20 Hz): read sensors, command movement.</li>
 *   <li>{@link Mover} — implement the wheel-to-movement abstraction:
 *       translate high-level commands ({@code forward()}, {@code turnRight()},
 *       ...) into per-wheel actions.</li>
 *   <li>{@link Brain} — the DI/wiring layer: assemble a {@link Mover},
 *       {@link Sensors}, and an initial {@link RobotBehaviour}, then hand the
 *       brain to the behaviour loop.</li>
 * </ul>
 *
 * <p>{@link Sensors} and {@link Wheel} are the student-facing sensor/wheel
 * interfaces; the sim bridge ({@code oobot.robot.bridge.sim}) provides their
 * implementations ({@link oobot.robot.bridge.sim.SimSensors},
 * {@link oobot.robot.bridge.sim.SimWheel}). Robot code never touches sockets,
 * JSON, motor power values, or wheel mounting directly.
 *
 * <p>Framework-provided pieces live below this layer and are not student code:
 * <ul>
 *   <li>{@code oobot.robot.core} — behaviour tick loop (~20 Hz)</li>
 *   <li>{@code oobot.robot.bridge.sim} — sim-backed hardware adapters</li>
 *   <li>{@code oobot.robot.transport} — WebSocket + NDJSON framing</li>
 *   <li>{@code oobot.robot.example} — example implementations and reference
 *       {@link oobot.robot.example.WheeledMover} Mover and {@link
 *       oobot.robot.example.DefaultBrain} Brain implementations</li>
 * </ul>
 *
 * <p>To add a new behaviour, create a class implementing {@link
 * RobotBehaviour}, provide or choose a {@link Mover}, wire them into your own
 * {@link Brain}, and register everything in {@link oobot.robot.RobotController}.
 * Rebuild the JAR.
 */
package oobot.robot.student;
