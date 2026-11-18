package oobot.robot.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import oobot.robot.student.Mover;
import oobot.robot.student.ProximitySensor;
import oobot.robot.student.RobotBehaviour;
import oobot.robot.student.Tool;

import org.junit.jupiter.api.Test;

/** Reference-brain wiring tests: no sim or sockets involved. */
class DefaultBrainTest {

    @Test
    void exposesInjectedToolAndThreeArgConstructorUsesNoop() {
        Mover mover = new RecordingMover();
        ProximitySensor front = new FakeSensor("front-sensor");
        ProximitySensor right = new FakeSensor("right-sensor");
        RecordingTool tool = new RecordingTool();

        DefaultBrain explicit = new DefaultBrain(mover, front, right, tool);
        DefaultBrain legacy = new DefaultBrain(mover, front, right);

        assertSame(tool, explicit.tool());
        assertSame(Tool.NOOP, legacy.tool());
        assertSame(mover, explicit.mover());
        assertSame(front, explicit.frontSensor());
        assertSame(right, explicit.rightSensor());
    }

    @Test
    void clearingBehaviourStopsMoverAndDeactivatesTool() {
        RecordingMover mover = new RecordingMover();
        RecordingTool tool = new RecordingTool();
        tool.activate();
        DefaultBrain brain = new DefaultBrain(mover, new FakeSensor("front-sensor"),
                new FakeSensor("right-sensor"), tool);
        brain.setBehaviour(new RobotBehaviour() {
            @Override public void init() { }
            @Override public void update() { }
        });
        assertTrue(tool.active());

        brain.setBehaviour(null);

        assertEquals(1, mover.stops, "mover.stop() must be called");
        assertTrue(!tool.active(), "tool.deactivate() must be called");
    }

    private static final class RecordingMover implements Mover {
        int stops;

        @Override public void forward() { }
        @Override public void backward() { }
        @Override public void turnRight() { }
        @Override public void turnLeft() { }
        @Override public void stop() { stops++; }
    }

    private static final class FakeSensor implements ProximitySensor {
        private final String id;

        FakeSensor(String id) { this.id = id; }

        @Override public String id() { return id; }
        @Override public long latestSimTick() { return 0L; }
        @Override public double distance() { return Double.POSITIVE_INFINITY; }
    }

    private static final class RecordingTool implements Tool {
        private boolean active;

        @Override public String id() { return "front-light"; }
        @Override public void activate() { active = true; }
        @Override public void deactivate() { active = false; }

        boolean active() { return active; }
    }
}
