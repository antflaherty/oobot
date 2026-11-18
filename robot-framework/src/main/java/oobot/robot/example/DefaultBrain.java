package oobot.robot.example;

import oobot.robot.student.Brain;
import oobot.robot.student.Mover;
import oobot.robot.student.RobotBehaviour;
import oobot.robot.student.Sensor;
import oobot.robot.student.Tool;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reference {@link Brain} implementation.
 * <p>
 * This is the default DI/wiring layer: it holds a {@link Mover}, the two named
 * proximity {@link Sensor}s, one boolean goal {@link Sensor}, a {@link Tool},
 * and the active {@link RobotBehaviour}. It also owns the goal-arrival policy:
 * on every {@link #tick()} it checks the goal sensor <em>before</em> delegating
 * to the behaviour. A fresh {@code true} reading latches "arrived": the mover
 * is stopped, the tool activated, and the behaviour is no longer updated, so
 * arrival wins regardless of which behaviour is running. Stale or missing
 * goal data ({@code latestSimTick() == 0}) never counts as arrival.
 * <p>
 * Calling {@link #setBehaviour(RobotBehaviour)} with {@code null} clears the
 * behaviour, stops all motion, deactivates the tool and clears the arrival
 * latch. Installing a behaviour also resets the latch, so each run starts
 * fresh. Students write their own {@link Brain} implementation (this is where
 * dependency injection happens) and can copy this class as a starting point.
 * <p>
 * The 3- and 4-argument constructors keep their old shape for callers that do
 * not wire a goal sensor: in that case {@link #goalSensor()} returns a
 * constant-{@code false} stand-in with tick 0, so the brain never mistakes
 * missing data for "goal reached".
 * <p>
 * {@code RobotController} wires this up with sim-provided hardware and the
 * selected example behaviour (by default {@code wall-follow-right}).
 */
public class DefaultBrain implements Brain {
    private static final Logger log = LoggerFactory.getLogger(DefaultBrain.class);

    /** Stand-in for a manifest without a goalSensor: never fresh, always false. */
    private static final Sensor<Boolean> CONSTANT_FALSE_GOAL = new Sensor<Boolean>() {
        @Override
        public String id() {
            return "goal-sensor";
        }

        @Override
        public long latestSimTick() {
            return 0L;
        }

        @Override
        public Boolean value() {
            return Boolean.FALSE;
        }
    };

    private final Mover mover;
    private final Sensor<Double> frontSensor;
    private final Sensor<Double> rightSensor;
    private final Sensor<Boolean> goalSensor;
    private final Tool tool;
    private RobotBehaviour behaviour;
    private boolean goalReached;

    /** Backwards-compatible constructor for manifests with no tool or goal sensor. */
    public DefaultBrain(Mover mover, Sensor<Double> frontSensor, Sensor<Double> rightSensor) {
        this(mover, frontSensor, rightSensor, CONSTANT_FALSE_GOAL, Tool.NOOP);
    }

    /** Backwards-compatible constructor for manifests with no goal sensor. */
    public DefaultBrain(Mover mover, Sensor<Double> frontSensor, Sensor<Double> rightSensor,
                        Tool tool) {
        this(mover, frontSensor, rightSensor, CONSTANT_FALSE_GOAL, tool);
    }

    public DefaultBrain(Mover mover, Sensor<Double> frontSensor, Sensor<Double> rightSensor,
                        Sensor<Boolean> goalSensor, Tool tool) {
        this.mover = Objects.requireNonNull(mover, "mover");
        this.frontSensor = Objects.requireNonNull(frontSensor, "frontSensor");
        this.rightSensor = Objects.requireNonNull(rightSensor, "rightSensor");
        this.goalSensor = Objects.requireNonNull(goalSensor, "goalSensor");
        this.tool = Objects.requireNonNull(tool, "tool");
    }

    /** Convenience overload with the tool last, matching the existing 4-arg shape. */
    public DefaultBrain(Mover mover, Sensor<Double> frontSensor, Sensor<Double> rightSensor,
                        Tool tool, Sensor<Boolean> goalSensor) {
        this(mover, frontSensor, rightSensor, goalSensor, tool);
    }

    @Override
    public Mover mover() {
        return mover;
    }

    @Override
    public Sensor<Double> frontSensor() {
        return frontSensor;
    }

    @Override
    public Sensor<Double> rightSensor() {
        return rightSensor;
    }

    @Override
    public Sensor<Boolean> goalSensor() {
        return goalSensor;
    }

    @Override
    public Tool tool() {
        return tool;
    }

    @Override
    public void setBehaviour(RobotBehaviour behaviour) {
        // Either kind of swap starts a fresh run: discard a previous arrival
        // latch and the stopped/lit actuator state that the brain owned.
        if (goalReached) {
            goalReached = false;
            mover.stop();
            tool.deactivate();
        }
        if (behaviour == null) {
            // Clearing the behaviour must not leave the last movement command
            // or an active tool latched in the bridge's persistent actuator state.
            this.behaviour = null;
            mover.stop();
            tool.deactivate();
            return;
        }
        this.behaviour = Objects.requireNonNull(behaviour, "behaviour");
        behaviour.init();
    }

    @Override
    public void tick() {
        if (!goalReached && goalSensor.latestSimTick() > 0L
                && Boolean.TRUE.equals(goalSensor.value())) {
            goalReached = true;
            log.info("goal reached at sim tick {}; stopping and activating tool {}",
                    goalSensor.latestSimTick(), tool.id());
        }
        if (goalReached) {
            // Re-assert every tick: the bridge batches commands per behaviour
            // tick, and the latch must survive stale/flipped goal readings.
            mover.stop();
            tool.activate();
            return;
        }
        if (behaviour != null) {
            behaviour.update();
        }
    }
}
