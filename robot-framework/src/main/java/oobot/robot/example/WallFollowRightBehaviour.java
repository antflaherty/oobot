package oobot.robot.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import oobot.robot.student.Mover;
import oobot.robot.student.RobotBehaviour;
import oobot.robot.student.Sensor;
import oobot.robot.student.Tool;

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
 *   <li>{@code DRIVE}: drive forward, sampling {@code rightDistance} over a
 *       fixed window of <em>sim ticks</em>. If it drifts, correct with a short,
 *       duty-cycled burst of turns (a full-speed turn tick every few updates)
 *       instead of one full-speed nudge, so the robot weaves much less.</li>
 *   <li>{@code AVOID}: front blocked — rotate the front ray off the obstacle
 *       until the front clears. Absolute priority in every state.</li>
 *   <li>{@code TURN_RIGHT_AT_END}: the wall on the right ended — turn right for
 *       a fixed duration (~90°) to round the end, then reassess.</li>
 *   <li>{@code SEARCH}: no close wall on the right — drive a gentle right arc
 *       toward one, then {@code APPROACH} it until the offset is ~55 units
 *       before holding parallel. Obstacle turns are duty-cycled with STOP on
 *       the off ticks, so the robot rotates at half speed.</li>
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
 * {@link #FRONT_STOP} leaves room for the physical footprint: the robot is a
 * square body with wheels, whose furthest corner is ~35 world units from its
 * centre, and the sim suppresses a turn that would make that footprint overlap
 * an obstacle. Turning at 130 units instead of 50 also leaves room to climb
 * around obstacles that reach the wall being followed.
 * Sensor rays are unchanged — they still originate at the robot centre.
 * <p>
 * The injected {@link Tool} follows the state actually reached each update: it
 * is active in {@code APPROACH}, {@code DRIVE} and {@code TURN_RIGHT_AT_END},
 * and inactive in {@code SEARCH}, {@code AVOID}, {@code RECOVER_BACKUP} and
 * {@code RECOVER_TURN}.
 * <p>
 * Commands {@link Mover} only: no motor power values are visible here — the
 * {@link oobot.robot.example.WheeledMover} implementation handles the
 * per-wheel translation. This is the decoupling boundary in action.
 */
public class WallFollowRightBehaviour implements RobotBehaviour {
    private static final Logger log = LoggerFactory.getLogger(WallFollowRightBehaviour.class);

    /**
     * World units: start turning away well before contact. The footprint
     * reaches ~35 units from the centre, but an obstacle attached to the wall
     * being followed also needs room to climb around: 130 units gives enough
     * forward distance to turn left and clear its far edge. Also the distance
     * the front ray must clear before AVOID ends.
     */
    private static final double FRONT_STOP = 130.0;
    /** Sim ticks over which rightDistance is sampled to detect drift (~0.3 s). */
    private static final int DRIFT_WINDOW_SIM_TICKS = 18;
    /** Units of rightDistance change over the window that count as angled. */
    private static final double DRIFT_THRESHOLD = 3.0;
    /**
     * Units of rightDistance change in a single tick that count as a wall end
     * (the near wall vanished, only a far edge remains) rather than heading
     * drift or sensor noise. Such a step up triggers a right turn around the
     * wall end.
     */
    private static final double STEP_THRESHOLD = 40.0;
    /**
     * rightDistance at which a wall is considered found. Tight enough that the
     * robot does not happily follow a wall from 200 units away.
     */
    private static final double WALL_FOUND = 100.0;
    /** Once a wall is found, close to this offset before holding parallel. */
    private static final double TARGET_OFFSET = 55.0;
    /**
     * Fixed ticks to turn right at a wall end. Sim turns at ~4.5 deg per
     * behaviour tick at full differential, so ~20 ticks gives a ~90 degree
     * corner turn. Time-bounded so it cannot overshoot.
     */
    private static final int TURN_END_TICKS = 20;

    // Duty-cycled correction: a full-speed turn tick every few updates turns a
    // single ~11.5 degree nudge into a much gentler, less wobbly correction.
    private static final int PERIOD_GENTLE = 4;
    private static final int PERIOD_MEDIUM = 2;
    private static final int PERIOD_STRONG = 2;
    private static final double DRIFT_MEDIUM = 6.0;
    private static final double DRIFT_STRONG = 12.0;
    private static final int TURNS_GENTLE = 2;
    private static final int TURNS_MEDIUM = 4;
    private static final int TURNS_STRONG = 4;

    // Obstacle/recovery turns are also duty-cycled, with STOP (not full
    // forward) on the off ticks, so the robot rotates at half speed instead
    // of snapping 11.5 degrees per behaviour tick.
    private static final int TURN_PERIOD = 2;
    private static final int ARC_PERIOD = 3;
    /** Front must stay clear this long before AVOID hands back to driving. */
    private static final int AVOID_CLEAR_TICKS = 4;

    // A corner can wedge the footprint while neither ray sees an obstacle
    // ahead: both readings stay frozen even though wheels are commanded.
    private static final int STALL_TICKS = 4;
    private static final double STALL_EPSILON = 0.5;
    private static final int BACKUP_TICKS_BASE = 8;
    private static final int BACKUP_TICKS_MAX = 24;
    /** Give up on reversing this quickly if neither reading budges. */
    private static final int BACKUP_NO_PROGRESS_TICKS = 3;
    private static final double BACKUP_PROGRESS_EPSILON = 5.0;
    private static final double BACKUP_PROGRESS_TARGET = 25.0;
    private static final int RECOVER_TURN_TICKS = 24;
    private static final int RECOVER_TURN_MIN = 6;

    private enum State { DRIVE, AVOID, TURN_RIGHT_AT_END, SEARCH, APPROACH,
        RECOVER_BACKUP, RECOVER_TURN }
    private State state = State.SEARCH;
    private int turnEndTicks;

    // Stall detection (sensor-based: no pose or collision telemetry needed).
    private double lastFront = Double.NaN;
    private double lastRight = Double.NaN;
    private long lastStallTick;
    private int stallTicks;
    private int stallCount;
    private int recoverTicks;
    private int recoverTurned;
    private double backupStartFront;
    private double backupStartRight;
    private int backupTicksDone;
    private int forwardTicks;
    private int avoidPhase;
    private int avoidClearTicks;
    private int recoverPhase;
    private int arcPhase;

    // Drift window measured in sim ticks so behaviour-loop jitter does not
    // change how much travel the drift estimate covers.
    private boolean windowActive;
    private double windowStartRight;
    private long windowStartTick;
    private double prevRight;

    // Active duty-cycled heading correction.
    private int correctionRemaining;
    private int correctionPeriod;
    private int ticksSinceCorrectionTurn;
    private boolean correctionRight;

    private final Mover mover;
    private final Sensor<Double> front;
    private final Sensor<Double> right;
    private final Tool tool;

    /** Backwards-compatible constructor for brains with {@link Tool#NOOP}. */
    public WallFollowRightBehaviour(Mover mover, Sensor<Double> front, Sensor<Double> right) {
        this(mover, front, right, Tool.NOOP);
    }

    public WallFollowRightBehaviour(Mover mover, Sensor<Double> front, Sensor<Double> right,
                                    Tool tool) {
        this.mover = mover;
        this.front = front;
        this.right = right;
        this.tool = tool;
    }

    @Override
    public void init() {
        tool.deactivate();
        mover.stop();
        state = State.SEARCH;
        turnEndTicks = 0;
        resetWindow();
        resetCorrection();
        prevRight = 0.0;
        lastFront = Double.NaN;
        lastRight = Double.NaN;
        lastStallTick = 0;
        stallTicks = 0;
        stallCount = 0;
        recoverTicks = 0;
        recoverTurned = 0;
        backupStartFront = Double.NaN;
        backupStartRight = Double.NaN;
        backupTicksDone = 0;
        forwardTicks = 0;
        avoidPhase = 0;
        avoidClearTicks = 0;
        recoverPhase = 0;
        arcPhase = 0;
        log.info("init (stopped)");
    }

    @Override
    public void update() {
        try {
            updateState();
        } finally {
            // Always reflect the state actually reached, including early
            // returns and exceptions from the state machine.
            applyToolState();
        }
    }

    /** The state machine body; its final {@link #state} drives the tool. */
    private void updateState() {
        double frontDistance = this.front.value();
        double rightDistance = this.right.value();
        long tick = this.right.latestSimTick();

        trackStall(frontDistance, rightDistance, tick);

        // 0. Recovery outranks AVOID: frozen readings mean we are already
        //    wedged, so turning toward the obstacle would make it worse.
        if (state == State.RECOVER_BACKUP) {
            forwardTicks = 0;
            mover.backward();
            backupTicksDone++;
            double change = Math.max(
                    Math.abs(frontDistance - backupStartFront),
                    Math.abs(rightDistance - backupStartRight));
            // Reversing is only useful if it actually changes a reading. If
            // something is behind us too, stop wasting ticks and turn instead.
            boolean blocked = backupTicksDone >= BACKUP_NO_PROGRESS_TICKS
                    && change < BACKUP_PROGRESS_EPSILON;
            boolean enoughRoom = change >= BACKUP_PROGRESS_TARGET;
            if (blocked || enoughRoom || backupTicksDone >= recoverTicks) {
                state = State.RECOVER_TURN;
                recoverTicks = RECOVER_TURN_TICKS;
                recoverTurned = 0;
                recoverPhase = 0;
                restartStallWatch(frontDistance, rightDistance, tick);
                log.info("backup {} ({}): change={} -> turn left",
                        blocked ? "blocked" : "done", backupTicksDone, change);
            }
            return;
        }
        if (state == State.RECOVER_TURN) {
            recoverPhase++;
            issue(recoverPhase % TURN_PERIOD == 0 ? Motion.TURN_LEFT : Motion.STOP);
            recoverTurned++;
            boolean clear = recoverTurned >= RECOVER_TURN_MIN
                    && (!isFinite(frontDistance) || frontDistance >= FRONT_STOP);
            if (clear || --recoverTicks <= 0) {
                state = wallState(rightDistance);
                resetCorrection();
                resetWindow();
                prevRight = rightDistance;
                stallCount = 0;
                restartStallWatch(frontDistance, rightDistance, tick);
            }
            return;
        }

        // 1. Front obstacle has absolute priority in every state: rotate the
        //    front ray off the obstacle until the front clears.
        if (state == State.AVOID) {
            // A ray can clear a corner before the square body does. Keep
            // turning until the front has been clear for several ticks.
            avoidClearTicks = frontDistance >= FRONT_STOP ? avoidClearTicks + 1 : 0;
            if (avoidClearTicks >= AVOID_CLEAR_TICKS) {
                state = wallState(rightDistance);
                startWindow(rightDistance, tick);
                prevRight = rightDistance;
                avoidClearTicks = 0;
                return; // no extra turn tick on the exit tick
            }
            avoidPhase++;
            issue(avoidPhase % TURN_PERIOD == 0 ? Motion.TURN_LEFT : Motion.STOP);
            return;
        }
        if (frontDistance < FRONT_STOP) {
            state = State.AVOID;
            avoidPhase = 0;
            avoidClearTicks = 0;
            resetCorrection();
            resetWindow();
            prevRight = rightDistance;
            issue(Motion.TURN_LEFT);
            return;
        }

        // 2. TURN_RIGHT_AT_END: time-bounded right turn around a wall end.
        if (state == State.TURN_RIGHT_AT_END) {
            issue(Motion.TURN_RIGHT);
            turnEndTicks++;
            // Stop as soon as a wall is re-acquired — a full fixed 20-tick
            // turn would often spin straight past it in the new maze.
            boolean wallFound = turnEndTicks >= 4 && isFinite(rightDistance)
                    && rightDistance <= WALL_FOUND;
            if (wallFound || turnEndTicks >= TURN_END_TICKS) {
                state = wallState(rightDistance);
                resetCorrection();
                resetWindow();
                prevRight = rightDistance;
            }
            return;
        }

        // 3. SEARCH: no close wall on the right — drive a gentle right arc
        //    toward one instead of spinning in place.
        if (state == State.SEARCH) {
            issue(gentleRightArc());
            if (isFinite(rightDistance) && rightDistance <= WALL_FOUND) {
                state = wallState(rightDistance);
                resetCorrection();
                startWindow(rightDistance, tick);
                prevRight = rightDistance;
                arcPhase = 0;
            }
            return;
        }

        // 4. APPROACH: wall found but still far — ease in to TARGET_OFFSET.
        if (state == State.APPROACH) {
            if (!isFinite(rightDistance) || rightDistance > WALL_FOUND) {
                state = State.SEARCH;
                arcPhase = 0;
                return;
            }
            if (rightDistance <= TARGET_OFFSET) {
                state = State.DRIVE;
                resetCorrection();
                startWindow(rightDistance, tick);
                prevRight = rightDistance;
                arcPhase = 0;
                return;
            }
            issue(gentleRightArc());
            return;
        }

        // 5. DRIVE: advance along the wall, keeping heading parallel.
        driveAlongWall(rightDistance, tick);
    }

    /** Set the tool from the CURRENT final state of this update. */
    private void applyToolState() {
        boolean active = switch (state) {
            case APPROACH, DRIVE, TURN_RIGHT_AT_END -> true;
            case SEARCH, AVOID, RECOVER_BACKUP, RECOVER_TURN -> false;
        };
        if (active) {
            tool.activate();
        } else {
            tool.deactivate();
        }
    }

    private enum Motion { FORWARD, STOP, TURN_LEFT, TURN_RIGHT }

    private void driveAlongWall(double rightDistance, long tick) {
        Motion motion = Motion.FORWARD;

        // Detect a wall end: a sudden step UP in rightDistance means the near
        // wall vanished and only a far edge remains. Turn right around it.
        // A real wall end means the near wall vanished: rightDistance jumps
        // from close to far, and nothing blocks ahead. Ignore jumps while the
        // robot is still turning (they are just the rotating sensor ray).
        if (forwardTicks >= 3 && (!isFinite(lastFront) || lastFront >= FRONT_STOP) && windowActive
                && isFinite(rightDistance) && isFinite(prevRight)
                && rightDistance > WALL_FOUND
                && rightDistance - prevRight > STEP_THRESHOLD) {
            state = State.TURN_RIGHT_AT_END;
            turnEndTicks = 0;
            resetCorrection();
            resetWindow();
            prevRight = rightDistance;
            motion = Motion.TURN_RIGHT;
        }

        if (motion == Motion.FORWARD) {
            prevRight = rightDistance;

            // A correction burst replaces the forward command on its turn
            // ticks. Drift is not sampled during the burst: turning rotates the
            // right ray obliquely and would corrupt the heading estimate.
            if (correctionRemaining > 0) {
                ticksSinceCorrectionTurn++;
                if (ticksSinceCorrectionTurn >= correctionPeriod) {
                    motion = correctionRight ? Motion.TURN_RIGHT : Motion.TURN_LEFT;
                    correctionRemaining--;
                    ticksSinceCorrectionTurn = 0;
                    if (correctionRemaining == 0) {
                        // The next clean reading starts a fresh quiet window:
                        // no second correction until another full window.
                        resetWindow();
                    }
                }
            } else if (!isFinite(rightDistance) || tick <= 0) {
                resetWindow();
            } else if (!windowActive || tick < windowStartTick) {
                // No window yet, or the sim restarted: start one from now.
                startWindow(rightDistance, tick);
            } else if (tick - windowStartTick >= DRIFT_WINDOW_SIM_TICKS) {
                double delta = rightDistance - windowStartRight;
                startWindow(rightDistance, tick);
                double magnitude = Math.abs(delta);
                if (magnitude > DRIFT_THRESHOLD) {
                    correctionRight = delta > 0;
                    if (magnitude < DRIFT_MEDIUM) {
                        correctionPeriod = PERIOD_GENTLE;
                        correctionRemaining = TURNS_GENTLE;
                    } else if (magnitude < DRIFT_STRONG) {
                        correctionPeriod = PERIOD_MEDIUM;
                        correctionRemaining = TURNS_MEDIUM;
                    } else {
                        correctionPeriod = PERIOD_STRONG;
                        correctionRemaining = TURNS_STRONG;
                    }
                    ticksSinceCorrectionTurn = 0;
                    log.debug("rightDistance drift {} over {} sim ticks -> {} correction every {} updates",
                            delta, DRIFT_WINDOW_SIM_TICKS,
                            correctionRight ? "right" : "left", correctionPeriod);
                }
            }
        }

        issue(motion);
    }

    private void issue(Motion motion) {
        forwardTicks = motion == Motion.FORWARD ? forwardTicks + 1 : 0;
        switch (motion) {
            case TURN_RIGHT -> mover.turnRight();
            case TURN_LEFT -> mover.turnLeft();
            case FORWARD -> mover.forward();
            case STOP -> mover.stop();
        }
    }

    /**
     * Both rays originate at the centre, so a corner can stop the footprint
     * while both readings stay unchanged. Detect that from the snapshots: if
     * the sim tick advances but neither distance moves, the robot is stuck.
     */
    private void trackStall(double frontDistance, double rightDistance, long tick) {
        if (state == State.RECOVER_BACKUP || state == State.RECOVER_TURN) {
            restartStallWatch(frontDistance, rightDistance, tick);
            return;
        }
        if (!isFinite(frontDistance) || !isFinite(rightDistance) || tick <= lastStallTick) {
            restartStallWatch(frontDistance, rightDistance, tick);
            return;
        }
        boolean frozen = Math.abs(frontDistance - lastFront) < STALL_EPSILON
                && Math.abs(rightDistance - lastRight) < STALL_EPSILON;
        stallTicks = frozen ? stallTicks + 1 : 0;
        lastFront = frontDistance;
        lastRight = rightDistance;
        lastStallTick = tick;
        stallCount = frozen ? stallCount : 0;

        if (stallTicks >= STALL_TICKS) {
            state = State.RECOVER_BACKUP;
            recoverTicks = Math.min(BACKUP_TICKS_MAX, BACKUP_TICKS_BASE + 4 * stallCount);
            backupStartFront = frontDistance;
            backupStartRight = rightDistance;
            backupTicksDone = 0;
            stallCount++;
            stallTicks = 0;
            resetCorrection();
            resetWindow();
            log.info("stall: front={} right={} -> back up {} ticks then turn left",
                    frontDistance, rightDistance, recoverTicks);
        }
    }

    private void restartStallWatch(double frontDistance, double rightDistance, long tick) {
        lastFront = frontDistance;
        lastRight = rightDistance;
        lastStallTick = tick;
        stallTicks = 0;
    }

    private State wallState(double rightDistance) {
        if (!isFinite(rightDistance) || rightDistance > WALL_FOUND) return State.SEARCH;
        return rightDistance > TARGET_OFFSET ? State.APPROACH : State.DRIVE;
    }

    /** Forward with one gentle right nudge every few updates. */
    private Motion gentleRightArc() {
        arcPhase++;
        return arcPhase % ARC_PERIOD == 0 ? Motion.TURN_RIGHT : Motion.FORWARD;
    }

    private void startWindow(double rightDistance, long tick) {
        windowActive = true;
        windowStartRight = rightDistance;
        windowStartTick = tick;
    }

    private void resetWindow() {
        windowActive = false;
        windowStartRight = 0.0;
        windowStartTick = 0;
    }

    private void resetCorrection() {
        correctionRemaining = 0;
        correctionPeriod = 1;
        ticksSinceCorrectionTurn = 0;
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
