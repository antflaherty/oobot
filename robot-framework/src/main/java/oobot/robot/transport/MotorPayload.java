package oobot.robot.transport;

/** Motor power values in [-1, 1]; negative = reverse. */
public record MotorPayload(double left, double right) {
    public MotorPayload {
        if (!Double.isFinite(left) || left < -1.0 || left > 1.0
                || !Double.isFinite(right) || right < -1.0 || right > 1.0) {
            throw new IllegalArgumentException("Motor power must be finite and in [-1, 1]");
        }
    }
}
