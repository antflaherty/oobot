package oobot.robot.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import oobot.robot.student.Mover;
import oobot.robot.student.RobotBehaviour;
import oobot.robot.student.ProximitySensor;

/**
 * Example robot behaviour: heart trace.
 * <p>
 * Approximates a parametric heart curve as a stop-start polygon. Each segment
 * is {@code turn -> forward}, cycling forever around the closed loop so the
 * robot keeps tracing hearts.
 * <p>
 * The heart is the classic parametric curve:
 * <pre>
 *   x = 16 * sin^3(t)
 *   y = 13*cos(t) - 5*cos(2t) - 2*cos(3t) - cos(4t),  t in [0, 2pi]
 * </pre>
 * sampled into {@link #SAMPLES} segments. Segment lengths map to forward ticks
 * (~5 units per behaviour tick at 20 Hz) and heading changes map to turn ticks
 * (~4.5 deg per turn tick at full differential). Only {@link Mover} commands
 * are used — no motor power values leak into behaviour code.
 * <p>
 * Reads the fixed-id {@link ProximitySensor} exposed by
 * {@link oobot.robot.student.Brain} each tick (backed by {@code componentSnapshot})
 * to exercise the sensor path; the reading is a staleness placeholder
 * ({@link ProximitySensor#latestSimTick()}) and does not influence movement.
 */
public class HeartTraceBehaviour implements RobotBehaviour {
    private static final Logger log = LoggerFactory.getLogger(HeartTraceBehaviour.class);

    private enum State { TURN, FORWARD }

    private static final int SAMPLES = 32;             // points around the heart
    private static final double SCALE = 10.0;          // world units per curve unit
    private static final double PERIOD_S = 0.05;       // ~20 Hz behaviour loop
    private static final double UNITS_PER_FORWARD_TICK = 5.0; // 100 units/s * 0.05 s
    private static final double RAD_PER_TURN_TICK = (Math.PI / 2) * PERIOD_S; // clamped ang. speed * dt

    private static final class Seg {
        final int turnDir;   // +1 left, -1 right, 0 none
        final int turnTicks;
        final int fwdTicks;  // always >= 1
        Seg(int turnDir, int turnTicks, int fwdTicks) {
            this.turnDir = turnDir;
            this.turnTicks = turnTicks;
            this.fwdTicks = fwdTicks;
        }
    }

    private final Seg[] path;
    private int segIdx = 0;
    private int ticksLeft = 0;
    private State state = State.TURN;

    private final Mover mover;
    private final ProximitySensor front;
    private final ProximitySensor right;

    public HeartTraceBehaviour(Mover mover, ProximitySensor front, ProximitySensor right) {
        this.mover = mover;
        this.front = front;
        this.right = right;
        this.path = buildPath();
    }

    /** Precompute one {@code turn + forward} segment per sampled heart point. */
    private static Seg[] buildPath() {
        Seg[] segs = new Seg[SAMPLES];
        double px = 0, py = 0, pHeading = 0;
        for (int i = 0; i < SAMPLES; i++) {
            double t = 2 * Math.PI * i / SAMPLES;
            double x = 16 * Math.pow(Math.sin(t), 3) * SCALE;
            double y = (13 * Math.cos(t)
                        - 5 * Math.cos(2 * t)
                        - 2 * Math.cos(3 * t)
                        -   Math.cos(4 * t)) * SCALE;

            double dx = x - px;
            double dy = y - py;
            double dist = Math.hypot(dx, dy);
            double heading = Math.atan2(dy, dx);

            double d = wrapToPi(heading - pHeading);
            int turnDir = d >= 0 ? +1 : -1; // CCW -> left, CW -> right
            int turnTicks = (int) Math.round(Math.abs(d) / RAD_PER_TURN_TICK);
            int fwdTicks = (int) Math.round(dist / UNITS_PER_FORWARD_TICK);
            if (fwdTicks < 1) fwdTicks = 1;

            segs[i] = new Seg(turnDir, turnTicks, fwdTicks);
            px = x;
            py = y;
            pHeading = heading;
        }
        return segs;
    }

    private static double wrapToPi(double a) {
        while (a > Math.PI) a -= 2 * Math.PI;
        while (a < -Math.PI) a += 2 * Math.PI;
        return a;
    }

    @Override
    public void init() {
        mover.stop();
        log.info("init (stopped)");
    }

    @Override
    public void update() {
        // Exercise the sensor path: front (Brain.frontSensor()) latestSimTick() is a staleness placeholder.
        long ignoredTick = front.latestSimTick();

        Seg seg = path[segIdx];

        switch (state) {
            case TURN:
                if (seg.turnTicks > 0) {
                    if (seg.turnDir > 0) {
                        mover.turnLeft();
                    } else {
                        mover.turnRight();
                    }
                    ticksLeft--;
                }
                if (ticksLeft <= 0) {
                    state = State.FORWARD;
                    ticksLeft = seg.fwdTicks;
                }
                break;
            case FORWARD:
                mover.forward();
                ticksLeft--;
                if (ticksLeft <= 0) {
                    state = State.TURN;
                    segIdx = (segIdx + 1) % SAMPLES;
                    ticksLeft = path[segIdx].turnTicks;
                }
                break;
        }

        log.debug("seg={} state={} ticksLeft={} ignoredSensorTick={}", segIdx, state, ticksLeft, ignoredTick);
    }
}
