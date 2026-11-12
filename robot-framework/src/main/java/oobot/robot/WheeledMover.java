package oobot.robot;

/**
 * Differential-drive implementation of {@link Mover}.
 * <p>
 * Translates high-level movement commands into per-motor power values
 * ({@code left}, {@code right} in {@code [-1, 1]}) and sends them as
 * {@code motorCommand} messages to the sim.
 * <p>
 * This is the <em>only</em> place that knows about per-motor power values.
 * Behaviour code never sees them.
 */
public class WheeledMover implements Mover {

    private static Framework framework;

    static void setFramework(Framework f) {
        framework = f;
    }

    private static void requireFramework() {
        if (framework == null) {
            throw new IllegalStateException("Robot framework is not initialized");
        }
    }

    @Override
    public void forward() {
        requireFramework();
        framework.sendMotorCommand(1.0, 1.0);
    }

    @Override
    public void turnRight() {
        requireFramework();
        framework.sendMotorCommand(1.0, -1.0);
    }

    @Override
    public void turnLeft() {
        requireFramework();
        framework.sendMotorCommand(-1.0, 1.0);
    }

    @Override
    public void stop() {
        requireFramework();
        framework.sendMotorCommand(0.0, 0.0);
    }
}
