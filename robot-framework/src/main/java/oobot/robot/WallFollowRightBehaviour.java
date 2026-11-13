package oobot.robot;

/**
 * Example robot behaviour: follow the right wall using two proximity sensors.
 * <p>
 * Reads the front-facing and right-facing proximity sensors each {@code ~20 Hz}
 * update and steers to keep a target distance from whatever is on the right
 * (obstacle or world box edge):
 * <ul>
 *   <li>{@code frontDistance < FRONT_STOP}: something ahead — turn left to avoid a head-on collision.</li>
 *   <li>{@code rightDistance > RIGHT_TARGET + MARGIN}: too far from the right wall — turn right toward it.</li>
 *   <li>{@code rightDistance < RIGHT_TARGET - MARGIN}: too close to the right wall — turn left away from it.</li>
 *   <li>otherwise: hold distance — drive forward.</li>
 * </ul>
 * <p>
 * {@link Sensors#distance()} and {@link Sensors#rightDistance()} return
 * {@link Double#POSITIVE_INFINITY} when nothing is detected, so in open space
 * the robot turns right until it finds a wall, then tracks alongside it.
 * <p>
 * Commands {@link Mover} only: no motor power values are visible here — the
 * {@link WheeledMover} implementation handles the per-motor translation.
 * This is the decoupling boundary in action.
 */
public class WallFollowRightBehaviour implements RobotBehaviour {
    /** Target world units to keep from the wall on the right. */
    private static final double RIGHT_TARGET = 100.0;
    /** Hysteresis around the target so the robot doesn't chatter left/right. */
    private static final double MARGIN = 30.0;
    /** World units: don't drive into something this close or closer ahead. */
    private static final double FRONT_STOP = 60.0;

    private final Mover mover = new WheeledMover();

    @Override
    public void init() {
        mover.stop();
        System.out.println("[wall-follow-right] init (stopped); target=" + RIGHT_TARGET);
    }

    @Override
    public void update() {
        double front = Sensors.distance();
        double right = Sensors.rightDistance();

        if (front < FRONT_STOP) {
            // Something directly ahead: turn left to steer away from it.
            mover.turnLeft();
            System.out.println("[wall-follow-right] front=" + front + " < " + FRONT_STOP + " -> turnLeft");
        } else if (right > RIGHT_TARGET + MARGIN) {
            // Too far from the right wall: steer toward it.
            mover.turnRight();
            System.out.println("[wall-follow-right] right=" + right + " > " + (RIGHT_TARGET + MARGIN) + " -> turnRight");
        } else if (right < RIGHT_TARGET - MARGIN) {
            // Too close to the right wall: steer away from it.
            mover.turnLeft();
            System.out.println("[wall-follow-right] right=" + right + " < " + (RIGHT_TARGET - MARGIN) + " -> turnLeft");
        } else {
            // Holding target distance from the right wall.
            mover.forward();
            System.out.println("[wall-follow-right] right=" + right + " -> forward");
        }
    }
}
