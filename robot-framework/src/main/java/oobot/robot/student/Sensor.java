package oobot.robot.student;

/**
 * A generic, id-addressed sensor exposing a typed reading and the sim tick of
 * that reading (student-facing API).
 *
 * <p>{@code Double} sensors are the proximity sensors; {@code Boolean} sensors
 * are goal sensors. Implementations are injected (typically by the sim bridge).
 * Robot code never touches sockets directly.
 *
 * @param <T> the type of the sensor's value
 */
public interface Sensor<T> extends Component {
    /** Readings older than this many sim ticks are considered stale by the bridge. */
    long MAX_STALE_TICKS = 30L;

    /**
     * Return the {@code simTick} of the most recent sensor snapshot received
     * from the sim, or {@code 0} before the first reading / after a reset.
     */
    long latestSimTick();

    /**
     * The most recent reading. Never {@code null}: distance sensors report
     * {@link Double#POSITIVE_INFINITY} for "nothing detected", while goal
     * sensors report {@code false} for no reading / a world with no goal.
     */
    T value();
}
