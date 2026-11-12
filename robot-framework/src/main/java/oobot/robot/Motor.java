package oobot.robot;

/**
 * Motor hardware abstraction (student-facing API).
 * <p>
 * {@link #setSpeed(double)} sends a {@code motorCommand} NDJSON message over
 * the WebSocket to the sim. Robot code never touches sockets directly.
 */
public final class Motor {
    private Motor() {}

    private static Framework framework;

    static void setFramework(Framework f) {
        framework = f;
    }

    /**
     * Set the speed of both motors.
     *
     * @param speed in {@code [-1, 1]}; {@code 1.0} = full forward,
     *              {@code -1.0} = full reverse, {@code 0} = stop.
     *              Values outside {@code [-1, 1]} are clamped; non-finite values
     *              are treated as {@code 0}.
     * @implNote The iteration-1 API exposes a single speed applied to both motors
     *           (wire payload {@code {left: speed, right: speed}}). A true
     *           in-place turn requires per-motor control, which is deferred
     *           beyond iteration 1.
     */
    public static void setSpeed(double speed) {
        double s = speed;
        if (!Double.isFinite(s)) {
            s = 0.0;
        } else if (s > 1.0) {
            s = 1.0;
        } else if (s < -1.0) {
            s = -1.0;
        }
        if (framework == null) {
            throw new IllegalStateException("Robot framework is not initialized");
        }
        framework.sendMotorCommand(s, s);
    }
}
