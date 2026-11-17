package oobot.robot.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import oobot.robot.student.Mover;
import oobot.robot.student.RobotBehaviour;
import oobot.robot.student.Sensors;

/**
 * Example robot behaviour: square trace.
 * <p>
 * State machine: {@code FORWARD -> TURN_RIGHT -> FORWARD -> ...}. Each state
 * lasts a fixed number of behaviour update ticks (~1 second at 20 Hz). Reads
 * {@code sensorSnapshot} each tick (ignored in iteration 1 - no sensors) to
 * exercise the sensor path.
 * <p>
 * Commands {@link Mover} only: {@code forward()} on FORWARD ticks,
 * {@code turnRight()} on TURN ticks. No motor power values are visible to
 * this class — the {@link oobot.robot.bridge.sim.SimWheeledMover}
 * implementation handles the per-wheel translation. This is the decoupling
 * boundary in action.
 */
public class SquareTraceBehaviour implements RobotBehaviour {
    private static final Logger log = LoggerFactory.getLogger(SquareTraceBehaviour.class);

    private enum State { FORWARD, TURN_RIGHT }
    private State state = State.FORWARD;
    private int ticksInState = 0;
    private static final int TICKS_PER_STATE = 20; // ~1 second at 20 Hz

    private final Mover mover;
    private final Sensors sensors;

    public SquareTraceBehaviour(Mover mover, Sensors sensors) {
        this.mover = mover;
        this.sensors = sensors;
    }

    @Override
    public void init() {
        mover.stop();
        log.info("init (stopped)");
    }

    @Override
    public void update() {
        // Exercise the sensor path (reads latest sensorSnapshot); ignored in iteration 1.
        long ignoredTick = sensors.latestSimTick();

        switch (state) {
            case FORWARD:
                mover.forward();
                break;
            case TURN_RIGHT:
                mover.turnRight();
                break;
        }

        ticksInState++;
        if (ticksInState >= TICKS_PER_STATE) {
            state = (state == State.FORWARD) ? State.TURN_RIGHT : State.FORWARD;
            ticksInState = 0;
        }

        log.debug("state={} ignoredSensorTick={}", state, ignoredTick);
    }
}
