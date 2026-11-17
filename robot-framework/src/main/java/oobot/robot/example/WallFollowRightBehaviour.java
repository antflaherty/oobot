package oobot.robot.example;

import oobot.robot.student.Mover;
import oobot.robot.student.RobotBehaviour;
import oobot.robot.student.Sensors;

/**
 * Example robot behaviour: follow the right wall using two proximity sensors.
 * <p>
 * Reads the front-facing and right-facing proximity sensors each {@code ~20 Hz}
 * update and steers to keep a target distance from whatever is on the right
 * (obstacle or world box edge):
 * <ul>
 *   <li>{@code frontDistance < FRONT_STOP}: something ahead — enter avoidance,
 *       turn left until the front is clear, then drive forward to separate
 *       from the wall before resuming wall-following.</li>
 *   <li>{@code rightDistance > RIGHT_TARGET + MARGIN}: too far from the right
 *       wall — turn right toward it.</li>
 *   <li>{@code rightDistance < RIGHT_TARGET - MARGIN}: too close to the right
 *       wall — turn left away from it.</li>
 *   <li>otherwise: hold distance — drive forward.</li>
 * </ul>
 * <p>
 * The {@code AVOID} state is sticky: once the front is blocked the robot keeps
 * turning left until the front clears. This prevents the left/right
 * alternation that happens when avoidance and wall-following fight for the
 * same tick on a head-on approach. The subsequent {@code MOVE_AWAY} state
 * drives forward so the robot actually separates from the wall instead of
 * spinning in place next to it.
 * <p>
 * {@link Sensors#distance()} and {@link Sensors#rightDistance()} return
 * {@link Double#POSITIVE_INFINITY} when nothing is detected, so in open space
 * the robot turns right until it finds a wall, then tracks alongside it.
 * <p>
 * Commands {@link Mover} only: no motor power values are visible here — the
 * {@link oobot.robot.bridge.sim.SimWheeledMover} implementation handles the
 * per-wheel translation. This is the decoupling boundary in action.
 */
public class WallFollowRightBehaviour implements RobotBehaviour {
    /** Target world units to keep from the wall on the right. */
    private static final double RIGHT_TARGET = 100.0;
    /** Hysteresis around the target so the robot doesn't chatter left/right. */
    private static final double MARGIN = 30.0;
    /** World units: don't drive into something this close or closer ahead. */
    private static final double FRONT_STOP = 60.0;
    /** Ticks to drive forward after avoiding a head-on obstacle (~0.6s at 20 Hz). */
    private static final int MOVE_AWAY_TICKS = 12;

    private enum State { FOLLOW, AVOID, MOVE_AWAY }
    private State state = State.FOLLOW;
    private int ticksInState = 0;

    private final Mover mover;
    private final Sensors sensors;

    public WallFollowRightBehaviour(Mover mover, Sensors sensors) {
        this.mover = mover;
        this.sensors = sensors;
    }

    @Override
    public void init() {
        mover.stop();
        state = State.FOLLOW;
        ticksInState = 0;
        System.out.println("[wall-follow-right] init (stopped); target=" + RIGHT_TARGET);
    }

    @Override
    public void update() {
        double front = sensors.distance();
        double right = sensors.rightDistance();
        ticksInState++;

        switch (state) {
            case FOLLOW:
                if (front < FRONT_STOP) {
                    // Something directly ahead: commit to avoidance.
                    state = State.AVOID;
                    ticksInState = 0;
                    System.out.println("[wall-follow-right] front=" + front
                        + " < " + FRONT_STOP + " -> AVOID");
                }
                break;

            case AVOID:
                // Keep turning left until the front is clear, then separate
                // from the wall by driving forward.
                if (front >= FRONT_STOP) {
                    state = State.MOVE_AWAY;
                    ticksInState = 0;
                    System.out.println("[wall-follow-right] front cleared -> MOVE_AWAY");
                }
                break;

            case MOVE_AWAY:
                // Drive forward to create separation from the wall we just
                // avoided, then resume normal wall-following.
                if (ticksInState >= MOVE_AWAY_TICKS) {
                    state = State.FOLLOW;
                    ticksInState = 0;
                    System.out.println("[wall-follow-right] moved away -> FOLLOW");
                }
                break;
        }

        switch (state) {
            case AVOID:
                mover.turnLeft();
                System.out.println("[wall-follow-right] state=AVOID front=" + front + " -> turnLeft");
                break;
            case MOVE_AWAY:
                mover.forward();
                System.out.println("[wall-follow-right] state=MOVE_AWAY -> forward");
                break;
            case FOLLOW:
                if (right > RIGHT_TARGET + MARGIN) {
                    // Too far from the right wall: steer toward it.
                    mover.turnRight();
                    System.out.println("[wall-follow-right] state=FOLLOW right=" + right
                        + " > " + (RIGHT_TARGET + MARGIN) + " -> turnRight");
                } else if (right < RIGHT_TARGET - MARGIN) {
                    // Too close to the right wall: steer away from it.
                    mover.turnLeft();
                    System.out.println("[wall-follow-right] state=FOLLOW right=" + right
                        + " < " + (RIGHT_TARGET - MARGIN) + " -> turnLeft");
                } else {
                    // Holding target distance from the right wall.
                    mover.forward();
                    System.out.println("[wall-follow-right] state=FOLLOW right=" + right + " -> forward");
                }
                break;
        }
    }
}
