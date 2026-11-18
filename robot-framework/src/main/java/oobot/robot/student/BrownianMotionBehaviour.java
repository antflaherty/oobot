package oobot.robot.student;

import oobot.robot.student.Mover;
import oobot.robot.student.RobotBehaviour;
import oobot.robot.student.Sensor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;

/**
 * Student robot behaviour: brownian motion (random walk).
 * <p>
 * At each {@link #update()} tick (~20 Hz) the behaviour holds a movement
 * command for a random number of ticks, then picks a new command at random
 * ({@code forward}, {@code turnLeft}, {@code turnRight}, or {@code stop}).
 * This produces an unpredictable, wandering path — classic brownian motion.
 * <p>
 * Commands {@link Mover} only: no motor power values or wheel mounting is
 * visible to this class. Reads the fixed-id {@link Sensor} exposed by
 * {@link Brain} each tick (backed by {@code componentSnapshot}) to exercise the
 * sensor path; the reading is a staleness placeholder
 * ({@link Sensor#latestSimTick()}), ignored for movement decisions.
 */
public class BrownianMotionBehaviour implements RobotBehaviour {
    private static final Logger log = LoggerFactory.getLogger(BrownianMotionBehaviour.class);

    private enum Command { FORWARD, TURN_LEFT, TURN_RIGHT, STOP }

    private final Mover mover;
    private final Sensor<Double> front;
    private final Sensor<Double> right;
    private final Random random;

    private Command command = Command.FORWARD;
    private int ticksRemaining = 0;

    private static final int MIN_TICKS = 10; // ~0.5 s at 20 Hz
    private static final int MAX_TICKS = 40; // ~2 s at 20 Hz

    public BrownianMotionBehaviour(Mover mover, Sensor<Double> front, Sensor<Double> right) {
        this.mover = mover;
        this.front = front;
        this.right = right;
        this.random = new Random();
    }

    @Override
    public void init() {
        mover.stop();
        pickNewCommand();
        log.info("init (random walk)");
    }

    @Override
    public void update() {
        // Exercise the sensor path: front (Brain.frontSensor()) latestSimTick() is a staleness placeholder.
        long ignoredTick = front.latestSimTick();

        if (ticksRemaining-- <= 0) {
            pickNewCommand();
        }

        switch (command) {
            case FORWARD:
                mover.forward();
                break;
            case TURN_LEFT:
                mover.turnLeft();
                break;
            case TURN_RIGHT:
                mover.turnRight();
                break;
            case STOP:
                mover.stop();
                break;
        }

        log.debug("command={} ticksRemaining={} ignoredSensorTick={}", command, ticksRemaining, ignoredTick);
    }

    private void pickNewCommand() {
        command = Command.values()[random.nextInt(Command.values().length)];
        ticksRemaining = MIN_TICKS + random.nextInt(MAX_TICKS - MIN_TICKS);
    }
}
