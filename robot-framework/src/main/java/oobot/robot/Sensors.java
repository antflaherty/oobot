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
}
