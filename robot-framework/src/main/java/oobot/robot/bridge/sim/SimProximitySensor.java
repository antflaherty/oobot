package oobot.robot.bridge.sim;

import oobot.robot.student.Sensor;

/**
 * Sim-backed implementation of {@link Sensor}{@code <Double>}. One instance per
 * proximity sensor, identified by its component id. Receives {@code
 * componentSnapshot} updates from the bridge (via {@link SimRobot}) and exposes
 * the latest reading.
 * <p>
 * This class holds no reference to the bridge: all updates arrive through
 * {@link #update(long, Double)}. The staleness threshold is inherited from
 * {@link Sensor#MAX_STALE_TICKS}.
 */
class SimProximitySensor implements Sensor<Double> {
    private final String id;
    private volatile long lastSimTick = 0L;
    private volatile Double value = null;

    SimProximitySensor(String id) {
        this.id = id;
    }

    @Override
    public String id() {
        return id;
    }

    /**
     * Sim tick of the most recent reading applied to this sensor, or {@code 0}
     * when no reading is available (before the first snapshot and after a
     * disconnect, reconnect or sim tick rollback).
     * <p>
     * <strong>Max-staleness policy.</strong> Behaviour code can detect stale
     * sensor data by comparing this value with the newest sim tick it has
     * observed (for example, one obtained from another sensor or tracked across
     * its own update loop). A reading is stale when
     * {@code currentTick - latestSimTick() > Sensor.MAX_STALE_TICKS}; the sim
     * bridge uses the inherited {@link Sensor#MAX_STALE_TICKS} constant. Stale
     * readings should be ignored even though {@link #value()} still returns
     * their last numeric value. On a fresh session (reconnect or tick rollback)
     * the bridge resets both the tick and the value, so {@code latestSimTick()}
     * returns {@code 0} and {@code value()} returns infinity.
     */
    @Override
    public long latestSimTick() {
        return lastSimTick;
    }

    /** The latest distance reading, never {@code null}: {@code +Infinity} if none. */
    @Override
    public Double value() {
        return value == null ? Double.POSITIVE_INFINITY : value;
    }

    /**
     * Package-private alias retained for sim-package callers still using the
     * old name; new code should use {@link #value()}.
     */
    double distance() {
        return value();
    }

    /**
     * Whether the newest reading is more than {@link Sensor#MAX_STALE_TICKS} sim
     * ticks behind {@code currentSimTick}. Package-private: behaviour code uses
     * the public {@link #latestSimTick()} value and the policy documented there.
     */
    boolean isStale(long currentSimTick) {
        return currentSimTick - lastSimTick > Sensor.MAX_STALE_TICKS;
    }

    /** Called by the bridge when a componentSnapshot arrives. */
    void update(long simTick, Double value) {
        this.lastSimTick = simTick;
        this.value = value;
    }
}
