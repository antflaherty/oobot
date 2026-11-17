package oobot.robot.example;

import oobot.robot.student.Brain;
import oobot.robot.student.Mover;
import oobot.robot.student.RobotBehaviour;
import oobot.robot.student.Sensors;

/**
 * Reference {@link Brain} implementation.
 * <p>
 * This is the default DI/wiring layer: it holds a {@link Mover}, {@link
 * Sensors}, and the active {@link RobotBehaviour}, delegating each tick to the
 * behaviour's {@link RobotBehaviour#update()}. Students write their own {@link
 * Brain} implementation (this is where dependency injection happens) and can
 * copy this class as a starting point.
 * <p>
 * {@link RobotController} wires this up with sim-provided hardware and the
 * selected example behaviour.
 */
public class DefaultBrain implements Brain {
    private final Mover mover;
    private final Sensors sensors;
    private RobotBehaviour behaviour;

    public DefaultBrain(Mover mover, Sensors sensors) {
        this.mover = mover;
        this.sensors = sensors;
    }

    @Override
    public Mover mover() {
        return mover;
    }

    @Override
    public Sensors sensors() {
        return sensors;
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
