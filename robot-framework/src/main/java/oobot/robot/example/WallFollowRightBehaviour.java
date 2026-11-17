package oobot.robot.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import oobot.robot.student.Mover;
import oobot.robot.student.RobotBehaviour;
import oobot.robot.student.ProximitySensor;

/**
 * Example robot behaviour: follow the right wall using two proximity sensors.
 * <p>
 * Algorithm: "hold parallel, turn right at wall ends". Distance to the wall
 * only changes when the robot translates, and the robot can only translate
 * parallel to the wall if its heading is parallel to it. Since there is no
 * heading reference, the behaviour infers heading from {@code rightDistance}:
 * while driving straight, {@code rightDistance} is constant exactly when the
 * robot is parallel to the right wall, and it drifts whenever the robot is
 * angled.
 * <ul>
 *   <li>{@code DRIVE}: drive forward; if {@code rightDistance} is stable over a
 *       small window the robot is parallel — keep driving. If it drifts, correct
 *       the heading back toward parallel with a brief nudge.</li>
 *   <li>{@code CORRECT_RIGHT}/{@code CORRECT_LEFT}: brief steering nudge to
 *       counter detected drift, then back to {@code DRIVE}.</li>
 *   <li>{@code AVOID}: front blocked — rotate the front ray off the obstacle
 *       until the front clears. Absolute priority in every state.</li>
 *   <li>{@code TURN_RIGHT_AT_END}: the wall on the right ended — turn right for
 *       a fixed duration (~90°) to round the end, then reassess.</li>
 *   <li>{@code SEARCH}: no nearby wall on the right — spin right until one is
 *       found, then follow it.</li>
 * </ul>
 * <p>
 * Wall ends: inside the bounded box {@code rightDistance} is never infinite
 * (the box edges are always detectable), so a wall end does not show up as
 * {@code INF}. Instead it shows up as a sudden step up in {@code
 * rightDistance} (the near wall vanished, only a far edge remains). The
 * behaviour detects that step during {@code DRIVE} and turns right around the
 * end for a fixed duration (~90°, time-bounded so it cannot overshoot). After
 * the turn, if a wall was re-acquired it follows it; otherwise it keeps
 * searching right until one appears.
 * <p>
 * This deliberately avoids "turn toward/away until the distance matches a
 * target": turning rotates the right sensor ray obliquely, which makes
 * {@code rightDistance} read larger, so a naive distance-targeting loop ratchets
 * the robot into the wall. Holding parallel instead produces a small, bounded
 * weave around a constant offset — never a runaway diagonal.
 * <p>
 * The front and right {@link ProximitySensor#distance()} values return
 * {@link Double#POSITIVE_INFINITY} when nothing is detected (e.g. not
 * connected), so in open space the robot spins right until it finds a wall,
 * then tracks alongside it.
 * <p>
 * Commands {@link Mover} only: no motor power values are visible here — the
 * {@link oobot.robot.bridge.sim.SimWheeledMover} implementation handles the
 * per-wheel translation. This is the decoupling boundary in action.
 */
public class WallFollowRightBehaviour implements RobotBehaviour {
    private static final Logger log = LoggerFactory.getLogger(WallFollowRightBehaviour.class);

    /** World units: don't drive into something this close or closer ahead. */
    private static final double FRONT_STOP = 50.0;
    /** Forward ticks over which rightDistance is sampled to detect drift. */
    private static final int DRIFT_WINDOW = 6;
    /** Units of rightDistance change over {@link #DRIFT_WINDOW} that count as angled. */
    private static final double DRIFT_THRESHOLD = 3.0;
    /**
     * Units of rightDistance change in a single tick that count as a wall end
     * (the near wall vanished, only a far edge remains) rather than heading
     * drift or sensor noise. Such a step up triggers a right turn around the
     * wall end.
     */
    private static final double STEP_THRESHOLD = 40.0;
    /**
     * rightDistance at which a wall is considered close enough to follow
     * during {@code SEARCH} or after a wall-end turn.
     */
    private static final double WALL_FOUND = 200.0;
    /**
     * Fixed ticks to turn right at a wall end. Sim turns at ~4.5 deg per
     * behaviour tick at full differential, so ~20 ticks gives a ~90 degree
     * corner turn. Time-bounded so it cannot overshoot.
     */
    private static final int TURN_END_TICKS = 20;
    /** Behaviour ticks per heading-correction nudge (~50 ms at 20 Hz). */
    private static final int CORRECT_TICKS = 1;

    private enum State { DRIVE, CORRECT_RIGHT, CORRECT_LEFT, AVOID, TURN_RIGHT_AT_END, SEARCH }
    private State state = State.SEARCH;
    private int correctTicks;
    private int windowTicks;
    private double windowStartRight;
    private double prevRight;
    private int turnEndTicks;

    private final Mover mover;
    private final ProximitySensor front;
    private final ProximitySensor right;

    public WallFollowRightBehaviour(Mover mover, ProximitySensor front, ProximitySensor right) {
        this.mover = mover;
        this.front = front;
        this.right = right;
    }

    @Override
    public void init() {
        mover.stop();
        state = State.SEARCH;
        correctTicks = 0;
        windowTicks = 0;
        windowStartRight = 0.0;
        prevRight = 0.0;
        turnEndTicks = 0;
        log.info("init (stopped)");
    }

    @Override
    public void update() {
        double front = this.front.distance();
        double right = this.right.distance();

        // 1. Front obstacle has absolute priority in every state: rotate the
        //    front ray off the obstacle until the front clears.
        if (state == State.AVOID) {
            mover.turnLeft();
            if (front >= FRONT_STOP) {
                state = State.DRIVE;
                windowTicks = 0;
                windowStartRight = right;
                prevRight = right;
            }
            return;
        }
        if (front < FRONT_STOP) {
            state = State.AVOID;
            mover.turnLeft();
            return;
        }

        // 2. TURN_RIGHT_AT_END: time-bounded right turn around a wall end.
        if (state == State.TURN_RIGHT_AT_END) {
            mover.turnRight();
            if (++turnEndTicks >= TURN_END_TICKS) {
                // Turn complete: if a nearby wall was re-acquired, follow it;
                // otherwise keep searching right for a wall.
                if (right != Double.POSITIVE_INFINITY && right <= WALL_FOUND) {
                    state = State.DRIVE;
                    windowTicks = 0;
                    windowStartRight = right;
                    prevRight = right;
                } else {
                    state = State.SEARCH;
                }
            }
            return;
        }

        // 3. SEARCH: no nearby wall on the right — spin right until one is
        //    found. Inside the bounded box rightDistance is never infinite
        //    (box edges are always detectable), so "found" means close enough.
        if (state == State.SEARCH) {
            mover.turnRight();
            if (right != Double.POSITIVE_INFINITY && right <= WALL_FOUND) {
                state = State.DRIVE;
                windowTicks = 0;
                windowStartRight = right;
                prevRight = right;
            }
            return;
        }

        // 4. DRIVE: advance along the wall, keeping heading parallel.
        mover.forward();

        // Detect a wall end: a sudden step UP in rightDistance means the near
        // wall vanished and only a far edge remains. Turn right around it.
        if (windowTicks > 0) {
            double step = right - prevRight;
            if (step > STEP_THRESHOLD) {
                state = State.TURN_RIGHT_AT_END;
                turnEndTicks = 0;
                mover.turnRight();
                return;
            }
        }
        prevRight = right;

        windowTicks++;
        if (windowTicks >= DRIFT_WINDOW) {
            double delta = right - windowStartRight;
            if (delta > DRIFT_THRESHOLD) {
                // rightDistance growing: drifting away from the right wall,
                // i.e. angled left of parallel -> turn right.
                state = State.CORRECT_RIGHT;
                correctTicks = CORRECT_TICKS;
                windowTicks = 0;
                windowStartRight = right;
                prevRight = right;
                mover.turnRight();
            } else if (delta < -DRIFT_THRESHOLD) {
                // rightDistance shrinking: drifting toward the right wall,
                // i.e. angled right of parallel -> turn left.
                state = State.CORRECT_LEFT;
                correctTicks = CORRECT_TICKS;
                windowTicks = 0;
                windowStartRight = right;
                prevRight = right;
                mover.turnLeft();
            } else {
                // Parallel and stable: reset the window and keep driving.
                windowTicks = 0;
                windowStartRight = right;
                prevRight = right;
            }
        }
    }
}
