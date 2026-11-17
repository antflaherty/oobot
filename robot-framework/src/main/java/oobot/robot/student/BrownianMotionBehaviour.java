package oobot.robot.student;

import oobot.robot.student.Mover;
import oobot.robot.student.RobotBehaviour;
import oobot.robot.student.Sensors;

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
 * visible to this class. Reads {@code sensorSnapshot} each tick to exercise
 * the sensor path (ignored for movement decisions in iteration 1).
 */
public class BrownianMotionBehaviour implements RobotBehaviour {
    private static final Logger log = LoggerFactory.getLogger(BrownianMotionBehaviour.class);

    private enum Command { FORWARD, TURN_LEFT, TURN_RIGHT, STOP }

    private final Mover mover;
    private final Sensors sensors;
    private final Random random;

    private Command command = Command.FORWARD;
    private int ticksRemaining = 0;

    private static final int MIN_TICKS = 10; // ~0.5 s at 20 Hz
    private static final int MAX_TICKS = 40; // ~2 s at 20 Hz

    public BrownianMotionBehaviour(Mover mover, Sensors sensors) {
        this.mover = mover;
        this.sensors = sensors;
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
        // Exercise the sensor path (reads latest sensorSnapshot); ignored for movement in iteration 1.
        long ignoredTick = sensors.latestSimTick();

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
