package oobot.robot.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import oobot.robot.student.Mover;
import oobot.robot.student.RobotBehaviour;
import oobot.robot.student.Sensors;

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
 * {@link oobot.robot.bridge.sim.SimWheeledMover} implementation handles the
 * per-wheel translation. This is the decoupling boundary in action.
 */
public class WallAvoidBehaviour implements RobotBehaviour {
    private static final Logger log = LoggerFactory.getLogger(WallAvoidBehaviour.class);

    /** World units: react when something is this close or closer ahead. */
    private static final double THRESHOLD = 50.0;

    private final Mover mover;
    private final Sensors sensors;

    public WallAvoidBehaviour(Mover mover, Sensors sensors) {
        this.mover = mover;
        this.sensors = sensors;
    }

    @Override
    public void init() {
        mover.stop();
        log.info("init (stopped); threshold={}", THRESHOLD);
    }

    @Override
    public void update() {
        double distance = sensors.distance();

        if (distance < THRESHOLD) {
            // Something ahead (wall/obstacle): stop, then turn right to avoid it.
            mover.stop();
            mover.turnRight();
            log.debug("distance={} < threshold={} -> stop + turnRight", distance, THRESHOLD);
        } else {
            // Nothing close ahead: keep driving forward.
            mover.forward();
            log.debug("distance={} -> forward", distance);
        }
    }
}
