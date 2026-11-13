package oobot.robot;

/**
 * Example robot behaviour: wall avoidance using {@link Sensors#distance()}.
 * <p>
 * Reads the front-facing proximity sensor each {@code ~20 Hz} update and
 * reacts to the nearest hit ahead (obstacle or world box edge):
 * <ul>
 *   <li>{@code distance < threshold}: {@link Mover#stop()}, then
 *       {@link Mover#turnRight()} to steer away from what is in front.</li>
 *   <li>otherwise: {@link Mover#forward()}.</li>
 * </ul>
 * <p>
 * {@code Sensors.distance()} returns {@link Double#POSITIVE_INFINITY} when
 * nothing is detected (or when not connected), so in an empty world with no
 * obstacles the robot drives forward until it nears a box edge.
 * <p>
 * Commands {@link Mover} only: no motor power values are visible here — the
 * {@link WheeledMover} implementation handles the per-motor translation.
 * This is the decoupling boundary in action.
 */
public class WallAvoidBehaviour implements RobotBehaviour {
    /** World units: react when something is this close or closer ahead. */
    private static final double THRESHOLD = 50.0;

    private final Mover mover = new WheeledMover();

    @Override
    public void init() {
        mover.stop();
        System.out.println("[wall-avoid] init (stopped); threshold=" + THRESHOLD);
    }

    @Override
    public void update() {
        double distance = Sensors.distance();

        if (distance < THRESHOLD) {
            // Something ahead (wall/obstacle): stop, then turn right to avoid it.
            mover.stop();
            mover.turnRight();
            System.out.println("[wall-avoid] distance=" + distance + " < threshold=" + THRESHOLD
                + " -> stop + turnRight");
        } else {
            // Nothing close ahead: keep driving forward.
            mover.forward();
            System.out.println("[wall-avoid] distance=" + distance + " -> forward");
        }
    }
}
