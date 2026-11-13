package oobot.robot;

/**
 * Sensor access (student-facing API).
 * <p>
 * Iteration 1 has no sensor readings, so {@code sensorSnapshot} carries an
 * empty payload. This API is present so behaviours can exercise the sensor
 * path and is ready for iteration 2 readings.
 */
public final class Sensors {
    private Sensors() {}

    private static Framework framework;

    static void setFramework(Framework f) {
        framework = f;
    }

    /**
     * Return the {@code simTick} of the most recent {@code sensorSnapshot}
     * received from the sim (iteration 1: no readings, just the tick).
     */
    public static long latestSimTick() {
        if (framework == null) {
            return 0L;
        }
        return framework.lastSensorSimTick();
    }

    /**
     * Distance in world units to the nearest obstacle/wall ahead of the robot,
     * along its front-facing ray.
     *
     * @return the distance, or {@code Double.POSITIVE_INFINITY} if nothing is
     *         detected within range, or if the framework is not connected.
     */
    public static double distance() {
        if (framework == null) {
            return Double.POSITIVE_INFINITY;
        }
        Double d = framework.lastSensorDistance();
        return d == null ? Double.POSITIVE_INFINITY : d;
    }

    /**
     * Distance in world units to the nearest obstacle/wall to the right of the
     * robot, along a ray at 90 degrees to its heading.
     *
     * @return the distance, or {@code Double.POSITIVE_INFINITY} if nothing is
     *         detected within range, or if the framework is not connected.
     */
    public static double rightDistance() {
        if (framework == null) {
            return Double.POSITIVE_INFINITY;
        }
        Double d = framework.lastRightDistance();
        return d == null ? Double.POSITIVE_INFINITY : d;
    }
}
