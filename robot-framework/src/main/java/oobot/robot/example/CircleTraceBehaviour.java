package oobot.robot.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import oobot.robot.student.Mover;
import oobot.robot.student.RobotBehaviour;
import oobot.robot.student.ProximitySensor;

/**
 * Example robot behaviour: circle trace.
 * <p>
 * State machine: {@code FORWARD -> TURN_RIGHT -> FORWARD -> ...}. Each state
 * lasts a fixed number of behaviour update ticks (~100 ms each at 20 Hz).
 * Alternating short straight segments with small in-place turns produces a
 * stop-start polygon that reads as a circle.
 * <p>
 * Commands {@link Mover} only: {@code forward()} on FORWARD ticks,
 * {@code turnRight()} on TURN ticks. No motor power values are visible to
 * this class — the {@link oobot.robot.example.WheeledMover}
 * implementation handles the per-wheel translation. This is the decoupling
 * boundary in action.
 * <p>
 * Reads {@code sensorSnapshot} each tick (ignored in iteration 1 - no sensors)
 * to exercise the sensor path.
 */
public class CircleTraceBehaviour implements RobotBehaviour {
    private static final Logger log = LoggerFactory.getLogger(CircleTraceBehaviour.class);

    private enum State { FORWARD, TURN_RIGHT }
    private State state = State.FORWARD;
    private int ticksInState = 0;
    private static final int TICKS_PER_FORWARD = 2; // ~100 ms at 20 Hz (~10 units per segment)
    private static final int TICKS_PER_TURN = 2;    // ~100 ms at 20 Hz (~9 degrees per turn)

    private final Mover mover;
    private final ProximitySensor front;
    private final ProximitySensor right;

    public CircleTraceBehaviour(Mover mover, ProximitySensor front, ProximitySensor right) {
        this.mover = mover;
        this.front = front;
        this.right = right;
    }

    @Override
    public void init() {
        mover.stop();
        log.info("init (stopped)");
    }

    @Override
    public void update() {
        // Exercise the sensor path (reads latest sensorSnapshot); ignored in iteration 1.
        long ignoredTick = front.latestSimTick();

        switch (state) {
            case FORWARD:
                mover.forward();
                break;
            case TURN_RIGHT:
                mover.turnRight();
                break;
        }

        ticksInState++;
        if (state == State.FORWARD && ticksInState >= TICKS_PER_FORWARD) {
            state = State.TURN_RIGHT;
            ticksInState = 0;
        } else if (state == State.TURN_RIGHT && ticksInState >= TICKS_PER_TURN) {
            state = State.FORWARD;
            ticksInState = 0;
        }

        log.debug("state={} ignoredSensorTick={}", state, ignoredTick);
    }
}
