package oobot.robot.bridge.sim;

import oobot.robot.student.ProximitySensor;

/**
 * Sim-backed implementation of {@link ProximitySensor}. One instance per
 * proximity sensor, identified by its component id. Receives {@code
 * componentSnapshot} updates from the bridge (via {@link SimRobot}) and exposes
 * the latest reading.
 * <p>
 * This class holds no reference to the bridge: all updates arrive through
 * {@link #update(long, Double)}.
 */
class SimProximitySensor implements ProximitySensor {
    /**
     * Maximum age, in sim ticks, that behaviour code should accept for a sensor
     * reading before treating it as stale. At the sim's ~60 Hz this is roughly
     * half a second — comfortably more than the ~3 sim ticks between two
     * behaviour updates, but short enough to notice a stalled snapshot stream.
     * See {@link #latestSimTick()} for the comparison behaviour code should use.
     */
    static final long MAX_STALE_TICKS = 30L;

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
     * {@code currentTick - latestSimTick() > MAX_STALE_TICKS}; the sim bridge
     * keeps this constant at {@value #MAX_STALE_TICKS} ticks. Stale readings
     * should be ignored even though {@link #distance()} still returns their
     * last numeric value. On a fresh session (reconnect or tick rollback) the
     * bridge resets both the tick and the value, so {@code latestSimTick()}
     * returns {@code 0} and {@code distance()} returns infinity.
     */
    @Override
    public long latestSimTick() {
        return lastSimTick;
    }

    @Override
    public double distance() {
        return value == null ? Double.POSITIVE_INFINITY : value;
    }

    /**
     * Whether the newest reading is more than {@link #MAX_STALE_TICKS} sim ticks
     * behind {@code currentSimTick}. Package-private: behaviour code uses the
     * public {@link #latestSimTick()} value and the policy documented there.
     */
    boolean isStale(long currentSimTick) {
        return currentSimTick - lastSimTick > MAX_STALE_TICKS;
    }

    /** Called by the bridge when a componentSnapshot arrives. */
    void update(long simTick, Double value) {
        this.lastSimTick = simTick;
        this.value = value;
    }
}
