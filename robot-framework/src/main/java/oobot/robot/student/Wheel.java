package oobot.robot.student;

/**
 * Single wheel / motor abstraction (student-facing API).
 * <p>
 * A wheel is a raw rotation-direction abstraction: it can be commanded to turn
 * clockwise or anti-clockwise at an unspecified static rate, or to stop.
 * {@code Wheel} has no concept of "forward" — that is a robot-level concept
 * determined by how the wheel is mounted on the chassis. Concrete
 * implementations (e.g. {@link oobot.robot.bridge.sim.SimWheel}) translate
 * clockwise/anti-clockwise into whatever the underlying hardware or sim
 * accepts, based on the wheel's mounting.
 * <p>
 * This is the piece students import. Behaviour code normally interacts with
 * wheels through {@link Mover} or {@link oobot.robot.student.Brain}, not directly.
 */
public interface Wheel extends Component {
    /**
     * Mount angle in radians, 0 = forward, + = CCW; left if sin &gt; 0, right if
     * sin &lt; 0.
     */
    double angle();

    /** Turn the wheel clockwise. */
    void clockwise();

    /** Turn the wheel anti-clockwise. */
    void antiClockwise();

    /** Stop the wheel. */
    void stop();
}
