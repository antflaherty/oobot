package oobot.robot.student;

/**
 * High-level movement abstraction (student-facing API).
 * <p>
 * Robot behaviour code commands movement through {@link Mover} without any
 * knowledge of motors, power levels, wheel mounting, or differential drive. A
 * concrete implementation (e.g. {@link oobot.robot.example.WheeledMover}) translates
 * high-level commands into per-wheel rotations.
 * <p>
 * This is the decoupling boundary: behaviour code knows <em>what</em> to do
 * (move forward, turn right), not <em>how</em> (left wheel clockwise, right
 * wheel anti-clockwise). Swapping the implementation (e.g. for a legged
 * robot, or for physical hardware) requires no change to behaviour code.
 */
public interface Mover {
    /** Move forward at full speed. */
    void forward();

    /** Move backward at full speed. */
    void backward();

    /** Turn right in place. */
    void turnRight();

    /** Turn left in place. */
    void turnLeft();

    /** Stop all movement. */
    void stop();
}
