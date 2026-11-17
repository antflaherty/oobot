package oobot.robot.student;

/**
 * Sensor access (student-facing API).
 * <p>
 * Iteration 1 has no physical sensor readings, so {@code sensorSnapshot}
 * carries an empty payload. This API is present so behaviours can exercise
 * the sensor path and is ready for iteration 2 readings.
 * <p>
 * Implementations are injected (typically by the sim bridge). Robot code
 * never touches sockets directly.
 */
public interface Sensors {
    /**
     * Return the {@code simTick} of the most recent {@code sensorSnapshot}
     * received from the sim (iteration 1: no readings, just the tick).
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

    /**
     * Distance in world units to the nearest obstacle/wall to the right of the
     * robot, along a ray at 90 degrees to its heading.
     *
     * @return the distance, or {@link Double#POSITIVE_INFINITY} if nothing is
     *         detected within range, or if not connected.
     */
    double rightDistance();
}
