package oobot.robot.bridge.sim;

import oobot.robot.student.Sensor;

/**
 * Sim-backed implementation of {@link Sensor}{@code <Boolean>}. One instance
 * per goal sensor, identified by its component id. Receives {@code
 * componentSnapshot} updates from the bridge (via {@link SimRobot}) and exposes
 * whether the robot centre is inside the goal area.
 * <p>
 * The wire encodes the reading as {@code 1}, {@code 0} or {@code null};
 * {@link #value()} is never {@code null} and reports {@code false} for
 * {@code 0}, {@code null}, a missing reading and a Boolean {@code false}.
 * Staleness uses the inherited {@link Sensor#MAX_STALE_TICKS} threshold.
 */
class SimGoalSensor implements Sensor<Boolean> {
    private final String id;
    private volatile long lastSimTick = 0L;
    private volatile Boolean value = Boolean.FALSE;

    SimGoalSensor(String id) {
        this.id = id;
    }

    @Override
    public String id() {
        return id;
    }

    /** Sim tick of the most recent reading, or {@code 0} before/reset. */
    @Override
    public long latestSimTick() {
        return lastSimTick;
    }

    /** Inside-goal flag, never {@code null}: {@code false} when no reading is available. */
    @Override
    public Boolean value() {
        return value;
    }

    /** Whether the newest reading is older than {@link Sensor#MAX_STALE_TICKS}. */
    boolean isStale(long currentSimTick) {
        return currentSimTick - lastSimTick > Sensor.MAX_STALE_TICKS;
    }

    /**
     * Called by the bridge when a componentSnapshot arrives. Accepts the numeric
     * wire encoding ({@code 1} = inside, {@code 0} = outside), a Boolean, or
     * {@code null} (world has no goal / no reading).
     */
    void update(long simTick, Object value) {
        this.lastSimTick = simTick;
        if (value instanceof Boolean b) {
            this.value = b;
        } else if (value instanceof Number n) {
            this.value = n.doubleValue() != 0.0;
        } else {
            this.value = Boolean.FALSE;
        }
    }
}
