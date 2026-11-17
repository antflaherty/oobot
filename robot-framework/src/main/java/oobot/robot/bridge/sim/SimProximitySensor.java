package oobot.robot.bridge.sim;

import oobot.robot.student.ProximitySensor;

/**
 * Sim-backed implementation of {@link ProximitySensor}. One instance per
 * proximity sensor, identified by its component id. Receives {@code
 * sensorSnapshot} updates from the bridge (via {@link SimRobot}) and exposes
 * the latest reading.
 */
class SimProximitySensor implements ProximitySensor {
    private final SimRobot robot;
    private final String id;
    private volatile long lastSimTick = 0L;
    private volatile Double value = null;

    SimProximitySensor(SimRobot robot, String id) {
        this.robot = robot;
        this.id = id;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public long latestSimTick() {
        return lastSimTick;
    }

    @Override
    public double distance() {
        return value == null ? Double.POSITIVE_INFINITY : value;
    }

    /** Called by the bridge when a sensorSnapshot arrives. */
    void update(long simTick, Double value) {
        this.lastSimTick = simTick;
        this.value = value;
    }
}
