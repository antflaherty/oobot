package oobot.robot.student;

/**
 * Student-facing robot behaviour.
 * <p>
 * The behaviour loop invokes {@link #init()} once when the behaviour becomes
 * active and {@link #update()} repeatedly on the ~20 Hz tick. Robot code
 * never touches sockets: it commands movement via {@link Mover} / {@link
 * Wheel} and reads named sensors via {@link ProximitySensor}.
 */
public interface RobotBehaviour {
    /** Called once when this behaviour becomes active (e.g. on swap). */
    void init();

    /** Called repeatedly on the behaviour loop (~20 Hz): read sensors, command movement. */
    void update();
}
