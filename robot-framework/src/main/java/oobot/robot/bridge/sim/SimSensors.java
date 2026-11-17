package oobot.robot.bridge.sim;

import oobot.robot.student.Sensors;

/**
 * Sim-backed implementation of {@link Sensors}. Receives {@code
 * sensorSnapshot} updates from the bridge (via {@link SimRobot}) and exposes
 * the latest front/right proximity readings.
 */
class SimSensors implements Sensors {
    private final SimRobot robot;
    private volatile long lastSimTick = 0L;
    private volatile Double frontDistance = null;
    private volatile Double rightDistance = null;

    SimSensors(SimRobot robot) {
        this.robot = robot;
    }

    /** Called by the bridge when a sensorSnapshot arrives. */
    void update(long simTick, Double front, Double right) {
        this.lastSimTick = simTick;
        this.frontDistance = front;
        this.rightDistance = right;
    }

    @Override
    public long latestSimTick() {
        return lastSimTick;
    }

    @Override
    public double distance() {
        return frontDistance == null ? Double.POSITIVE_INFINITY : frontDistance;
    }

    @Override
    public double rightDistance() {
        return rightDistance == null ? Double.POSITIVE_INFINITY : rightDistance;
    }
}
