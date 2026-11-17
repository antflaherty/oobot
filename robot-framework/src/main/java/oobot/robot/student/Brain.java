package oobot.robot.student;

/**
 * The robot's "brain": holds the injected components and sensors, and owns the
 * currently active behaviour which it can swap at runtime — a bit like a state
 * machine.
 * <p>
 * This is a <b>student-facing interface</b>. Students write their own
 * {@code Brain} implementation to perform dependency injection: assemble a
 * {@link Mover}, named {@link ProximitySensor}s, and an initial
 * {@link RobotBehaviour}, then hand the brain to the behaviour loop. The loop repeatedly calls
 * {@link #tick()}, which delegates to the current behaviour's
 * {@link RobotBehaviour#update()}.
 * <p>
 * Calling {@link #setBehaviour(RobotBehaviour)} swaps the active behaviour and
 * invokes {@link RobotBehaviour#init()} on the new one, so a behaviour can
 * reset its internal state when it becomes active. Passing {@code null} clears
 * the behaviour and stops all motion (zeroes the wheels).
 * <p>
 * <b>Fixed sensor ids (hardening constraint).</b> The component model is
 * id-addressed, but these two accessors are <em>not</em> generic:
 * {@link #frontSensor()} must resolve the manifest id {@code front-sensor} and
 * {@link #rightSensor()} must resolve {@code right-sensor}. The controller
 * validates that both are declared as {@code proximitySensor} components at
 * startup and fails with usage output if either is missing. A manifest may
 * declare other sensors, but this API cannot address them.
 */
public interface Brain {
    /** High-level movement commands (forward, backward, turnLeft, turnRight, stop). */
    Mover mover();

    /** Front proximity sensor: the component with manifest id {@code front-sensor}. */
    ProximitySensor frontSensor();

    /** Right proximity sensor: the component with manifest id {@code right-sensor}. */
    ProximitySensor rightSensor();

    /**
     * Swap the active behaviour. Invokes {@link RobotBehaviour#init()} on the
     * new behaviour so it can reset its internal state.
     *
     * @param behaviour the new behaviour, or {@code null} to clear the current
     *                  one and stop all motion
     */
    void setBehaviour(RobotBehaviour behaviour);

    /** One behaviour tick: delegates to the current behaviour's {@link RobotBehaviour#update()}. */
    void tick();
}
