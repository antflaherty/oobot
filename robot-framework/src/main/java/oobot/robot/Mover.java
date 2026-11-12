package oobot.robot;

/**
 * High-level movement abstraction (student-facing API).
 * <p>
 * Robot behaviour code commands movement through {@link Mover} without any
 * knowledge of motors, power levels, or differential drive. A concrete
 * implementation (e.g. {@link WheeledMover}) translates high-level commands
 * into {@code motorCommand} NDJSON messages over the WebSocket.
 * <p>
 * This is the decoupling boundary: behaviour code knows <em>what</em> to do
 * (move forward, turn right), not <em>how</em> (left motor power, right motor
 * power). Swapping the implementation (e.g. for a tracked robot, or for
 * physical hardware) requires no change to behaviour code.
 */
public interface Mover {
    /** Move forward at full speed. */
    void forward();

    /** Turn right in place. */
    void turnRight();

    /** Turn left in place. */
    void turnLeft();

    /** Stop all movement. */
    void stop();
}
