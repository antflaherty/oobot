package oobot.robot;

/**
 * Student-facing robot behaviour.
 * <p>
 * The framework invokes {@link #init()} once on start and {@link #update()}
 * repeatedly on a {@code ~20 Hz} behaviour loop. Robot code never touches
 * sockets: it commands movement via {@link Mover} (e.g. {@code forward()},
 * {@code turnRight()}) and reads sensors via {@link Sensors}. All WebSocket
 * connection, NDJSON framing, reconnection and concurrency is hidden by the
 * framework.
 */
public interface RobotBehaviour {
    /** Called once when the robot program starts. */
    void init();

    /** Called repeatedly on the behaviour loop (~20 Hz): read sensors, command movement. */
    void update();
}
