package oobot.robot.example;

import oobot.robot.student.Brain;
import oobot.robot.student.Mover;
import oobot.robot.student.ProximitySensor;
import oobot.robot.student.RobotBehaviour;

/**
 * Reference {@link Brain} implementation.
 * <p>
 * This is the default DI/wiring layer: it holds a {@link Mover}, the two named
 * {@link ProximitySensor}s, and the active {@link RobotBehaviour}, delegating
 * each tick to the behaviour's {@link RobotBehaviour#update()}. Students write
 * their own {@link Brain} implementation (this is where dependency injection
 * happens) and can copy this class as a starting point.
 * <p>
 * {@code RobotController} wires this up with sim-provided hardware and the
 * selected example behaviour.
 */
public class DefaultBrain implements Brain {
    private final Mover mover;
    private final ProximitySensor frontSensor;
    private final ProximitySensor rightSensor;
    private RobotBehaviour behaviour;

    public DefaultBrain(Mover mover, ProximitySensor frontSensor, ProximitySensor rightSensor) {
        this.mover = mover;
        this.frontSensor = frontSensor;
        this.rightSensor = rightSensor;
    }

    @Override
    public Mover mover() {
        return mover;
    }

    @Override
    public ProximitySensor frontSensor() {
        return frontSensor;
    }

    @Override
    public ProximitySensor rightSensor() {
        return rightSensor;
    }

    @Override
    public void setBehaviour(RobotBehaviour behaviour) {
        this.behaviour = behaviour;
        if (behaviour != null) {
            behaviour.init();
        }
    }

    @Override
    public void tick() {
        if (behaviour != null) {
            behaviour.update();
        }
    }
}
