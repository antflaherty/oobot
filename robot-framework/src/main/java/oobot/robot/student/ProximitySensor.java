package oobot.robot.student;

/**
 * Front-facing proximity sensor (student-facing API).
 * <p>
 * Implementations are injected (typically by the sim bridge). Robot code
 * never touches sockets directly.
 */
public interface ProximitySensor extends Component {
    /**
     * Return the {@code simTick} of the most recent sensor snapshot received
     * from the sim.
     */
    long latestSimTick();

    /**
     * Distance in world units to the nearest obstacle/wall ahead of the robot,
     * along its front-facing ray.
     *
     * @return the distance, or {@link Double#POSITIVE_INFINITY} if nothing is
     *         detected within range, or if not connected.
     */
    double distance();
}
