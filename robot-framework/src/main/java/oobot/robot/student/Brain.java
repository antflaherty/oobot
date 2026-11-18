package oobot.robot.student;

/**
 * The robot's "brain": holds the injected components and sensors, and owns the
 * currently active behaviour which it can swap at runtime — a bit like a state
 * machine.
 * <p>
 * This is a <b>student-facing interface</b>. Students write their own
 * {@code Brain} implementation to perform dependency injection: assemble a
 * {@link Mover}, named {@link ProximitySensor}s, a {@link Tool}, and an initial
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
 * <p>
 * <b>Tool (generic single reference).</b> {@link #tool()} does not use a fixed
 * id: the controller injects the one {@code tool} declared in the manifest, or
 * {@link Tool#NOOP} if there is none. A manifest declaring more than one tool is
 * a startup error because this API can drive only one.
 */
public interface Brain {
    /** High-level movement commands (forward, backward, turnLeft, turnRight, stop). */
    Mover mover();

    /** Front proximity sensor: the component with manifest id {@code front-sensor}. */
    ProximitySensor frontSensor();

    /** Right proximity sensor: the component with manifest id {@code right-sensor}. */
    ProximitySensor rightSensor();

    /**
     * The single manifest-declared tool, or {@link Tool#NOOP} if the manifest
     * declares none. The controller performs this injection; it is not a
     * fixed-id lookup.
     */
    Tool tool();

    /**
     * Swap the active behaviour. Invokes {@link RobotBehaviour#init()} on the
     * new behaviour so it can reset its internal state.
     *
     * @param behaviour the new behaviour, or {@code null} to clear the current
     *                  one, stop all motion and deactivate the tool
     */
    void setBehaviour(RobotBehaviour behaviour);

    /** One behaviour tick: delegates to the current behaviour's {@link RobotBehaviour#update()}. */
    void tick();
}
