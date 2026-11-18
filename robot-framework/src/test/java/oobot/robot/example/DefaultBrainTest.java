package oobot.robot.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import oobot.robot.student.Mover;
import oobot.robot.student.RobotBehaviour;
import oobot.robot.student.Sensor;
import oobot.robot.student.Tool;

import org.junit.jupiter.api.Test;

/** Reference-brain wiring tests: no sim or sockets involved. */
class DefaultBrainTest {

    @Test
    void exposesInjectedComponentsAndLegacyConstructorsUseNoopToolAndFalseGoal() {
        Mover mover = new RecordingMover();
        Sensor<Double> front = new FakeSensor<>("front-sensor", Double.POSITIVE_INFINITY);
        Sensor<Double> right = new FakeSensor<>("right-sensor", Double.POSITIVE_INFINITY);
        Sensor<Boolean> goal = new FakeSensor<>("goal-sensor", Boolean.TRUE);
        RecordingTool tool = new RecordingTool();

        DefaultBrain wired = new DefaultBrain(mover, front, right, goal, tool);
        DefaultBrain legacyTool = new DefaultBrain(mover, front, right, tool);
        DefaultBrain legacy = new DefaultBrain(mover, front, right);

        assertSame(mover, wired.mover());
        assertSame(front, wired.frontSensor());
        assertSame(right, wired.rightSensor());
        assertSame(goal, wired.goalSensor());
        assertSame(tool, wired.tool());

        assertSame(Tool.NOOP, legacy.tool());
        assertSame(tool, legacyTool.tool());
        for (DefaultBrain brain : new DefaultBrain[] {legacy, legacyTool}) {
            assertFalse(brain.goalSensor().value(), "old constructors must use a false goal");
            assertEquals(0L, brain.goalSensor().latestSimTick(),
                    "old constructors must use a stale goal stand-in");
        }
    }

    @Test
    void clearingBehaviourStopsMoverAndDeactivatesTool() {
        RecordingMover mover = new RecordingMover();
        RecordingTool tool = new RecordingTool();
        tool.activate();
        DefaultBrain brain = new DefaultBrain(mover,
                new FakeSensor<>("front-sensor", Double.POSITIVE_INFINITY),
                new FakeSensor<>("right-sensor", Double.POSITIVE_INFINITY),
                new FakeSensor<>("goal-sensor", Boolean.FALSE), tool);
        brain.setBehaviour(new RobotBehaviour() {
            @Override public void init() { }
            @Override public void update() { }
        });
        assertTrue(tool.active());

        brain.setBehaviour(null);

        assertEquals(1, mover.stops, "mover.stop() must be called");
        assertFalse(tool.active(), "tool.deactivate() must be called");
    }

    @Test
    void tickDelegatesToCurrentBehaviourUpdate() {
        RecordingMover mover = new RecordingMover();
        DefaultBrain brain = new DefaultBrain(mover,
                new FakeSensor<>("front-sensor", Double.POSITIVE_INFINITY),
                new FakeSensor<>("right-sensor", Double.POSITIVE_INFINITY));
        int[] updates = {0};
        brain.setBehaviour(new RobotBehaviour() {
            @Override public void init() { }
            @Override public void update() { updates[0]++; }
        });

        brain.tick();
        brain.tick();

        assertEquals(2, updates[0]);
    }

    @Test
    void freshGoalStopsLightsToolAndStopsDrivingTheBehaviour() {
        RecordingMover mover = new RecordingMover();
        RecordingTool tool = new RecordingTool();
        MutableSensor<Boolean> goal = new MutableSensor<>("goal-sensor", Boolean.TRUE, 12L);
        DefaultBrain brain = brain(mover, tool, goal);
        int[] updates = {0};
        brain.setBehaviour(countingBehaviour(updates));

        brain.tick();
        brain.tick();

        assertEquals(2, mover.stops, "arrival re-asserts stop on every tick");
        assertTrue(tool.active(), "arrival lights the tool");
        assertEquals(0, updates[0], "the behaviour is no longer driven after arrival");
    }

    @Test
    void latchSurvivesTheGoalReadingFlippingBackToFalse() {
        RecordingMover mover = new RecordingMover();
        RecordingTool tool = new RecordingTool();
        MutableSensor<Boolean> goal = new MutableSensor<>("goal-sensor", Boolean.TRUE, 12L);
        DefaultBrain brain = brain(mover, tool, goal);
        int[] updates = {0};
        brain.setBehaviour(countingBehaviour(updates));

        brain.tick();
        goal.setValue(Boolean.FALSE);
        goal.setTick(13L);
        brain.tick();

        assertEquals(2, mover.stops);
        assertTrue(tool.active(), "arrival is a latch, not a live sensor test");
        assertEquals(0, updates[0]);
    }

    @Test
    void staleOrFalseGoalNeverLatches() {
        RecordingMover mover = new RecordingMover();
        RecordingTool tool = new RecordingTool();
        MutableSensor<Boolean> goal = new MutableSensor<>("goal-sensor", Boolean.TRUE, 0L);
        DefaultBrain brain = brain(mover, tool, goal);
        int[] updates = {0};
        brain.setBehaviour(countingBehaviour(updates));

        brain.tick();
        assertEquals(1, updates[0], "stale data is 'not arrived'");
        assertEquals(0, mover.stops);
        assertFalse(tool.active());

        goal.setValue(Boolean.FALSE);
        goal.setTick(5L);
        brain.tick();
        assertEquals(2, updates[0]);
        assertFalse(tool.active());
    }

    @Test
    void swappingBehaviourClearsTheArrivalLatch() {
        RecordingMover mover = new RecordingMover();
        RecordingTool tool = new RecordingTool();
        MutableSensor<Boolean> goal = new MutableSensor<>("goal-sensor", Boolean.TRUE, 3L);
        DefaultBrain brain = brain(mover, tool, goal);
        int[] first = {0};
        int[] second = {0};
        brain.setBehaviour(countingBehaviour(first));

        brain.tick();
        assertTrue(tool.active());
        assertEquals(1, mover.stops);

        goal.setValue(Boolean.FALSE);
        goal.setTick(4L);
        brain.setBehaviour(countingBehaviour(second));
        brain.tick();

        assertEquals(0, first[0], "the old behaviour never got a tick (goal was already true)");
        assertEquals(1, second[0], "the new behaviour drives again after a swap");
        assertFalse(tool.active(), "the new run starts with no arrival latch");
    }

    @Test
    void clearingBehaviourClearsTheArrivalLatch() {
        RecordingMover mover = new RecordingMover();
        RecordingTool tool = new RecordingTool();
        MutableSensor<Boolean> goal = new MutableSensor<>("goal-sensor", Boolean.TRUE, 3L);
        DefaultBrain brain = brain(mover, tool, goal);
        int[] updates = {0};
        brain.setBehaviour(countingBehaviour(updates));

        brain.tick();
        assertTrue(tool.active());
        brain.setBehaviour(null);
        assertFalse(tool.active());

        goal.setValue(Boolean.FALSE);
        goal.setTick(4L);
        brain.setBehaviour(countingBehaviour(updates));
        brain.tick();

        assertEquals(1, updates[0], "clearing the behaviour resets arrival state");
        assertFalse(tool.active());
    }

    private static DefaultBrain brain(RecordingMover mover, RecordingTool tool,
                                      Sensor<Boolean> goal) {
        return new DefaultBrain(mover,
                new FakeSensor<>("front-sensor", Double.POSITIVE_INFINITY),
                new FakeSensor<>("right-sensor", Double.POSITIVE_INFINITY), goal, tool);
    }

    private static RobotBehaviour countingBehaviour(int[] updates) {
        return new RobotBehaviour() {
            @Override public void init() { }
            @Override public void update() { updates[0]++; }
        };
    }

    private static final class MutableSensor<T> implements Sensor<T> {
        private final String id;
        private T value;
        private long tick;

        MutableSensor(String id, T value, long tick) {
            this.id = id;
            this.value = value;
            this.tick = tick;
        }

        @Override public String id() { return id; }
        @Override public long latestSimTick() { return tick; }
        @Override public T value() { return value; }

        void setValue(T value) { this.value = value; }
        void setTick(long tick) { this.tick = tick; }
    }

    private static final class RecordingMover implements Mover {
        int stops;

        @Override public void forward() { }
        @Override public void backward() { }
        @Override public void turnRight() { }
        @Override public void turnLeft() { }
        @Override public void stop() { stops++; }
    }

    private static final class FakeSensor<T> implements Sensor<T> {
        private final String id;
        private final T value;

        FakeSensor(String id, T value) {
            this.id = id;
            this.value = value;
        }

        @Override public String id() { return id; }
        @Override public long latestSimTick() { return 0L; }
        @Override public T value() { return value; }
    }

    private static final class RecordingTool implements Tool {
        private boolean active;

        @Override public String id() { return "front-light"; }
        @Override public void activate() { active = true; }
        @Override public void deactivate() { active = false; }

        boolean active() { return active; }
    }
}
