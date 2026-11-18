package oobot.robot.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import oobot.robot.student.Mover;
import oobot.robot.student.Sensor;
import oobot.robot.student.Tool;

/**
 * Behaviour-level tests: no sim, no sockets. Fake sensors let us feed exact
 * drift patterns and check how aggressively the behaviour corrects heading.
 * Front distance is varied slightly between updates so the tests do not look
 * like the frozen-reading stall the behaviour now recovers from.
 */
class WallFollowRightBehaviourTest {

    private enum Command { FORWARD, BACKWARD, TURN_LEFT, TURN_RIGHT, STOP }

    private static final class RecordingMover implements Mover {
        final List<Command> commands = new ArrayList<>();

        @Override public void forward() { commands.add(Command.FORWARD); }
        @Override public void backward() { commands.add(Command.BACKWARD); }
        @Override public void turnRight() { commands.add(Command.TURN_RIGHT); }
        @Override public void turnLeft() { commands.add(Command.TURN_LEFT); }
        @Override public void stop() { commands.add(Command.STOP); }

        void clear() { commands.clear(); }
    }

    private static final class FakeSensor implements Sensor<Double> {
        private final String id;
        private double distance = Double.POSITIVE_INFINITY;
        private long tick;

        FakeSensor(String id) { this.id = id; }

        void set(double distance, long tick) {
            this.distance = distance;
            this.tick = tick;
        }

        @Override public String id() { return id; }
        @Override public long latestSimTick() { return tick; }
        @Override public Double value() { return distance; }
    }

    /** Records the tool state set at the end of each update. */
    private static final class RecordingTool implements Tool {
        private boolean active;

        @Override public String id() { return "front-light"; }
        @Override public void activate() { active = true; }
        @Override public void deactivate() { active = false; }

        boolean active() { return active; }
    }

    /** Drives the behaviour out of SEARCH into DRIVE at the target offset. */
    private static final class Harness {
        final RecordingMover mover = new RecordingMover();
        final FakeSensor front = new FakeSensor("front-sensor");
        final FakeSensor right = new FakeSensor("right-sensor");
        final RecordingTool tool = new RecordingTool();
        final WallFollowRightBehaviour behaviour =
                new WallFollowRightBehaviour(mover, front, right, tool);
        long tick = 3;

        Harness() {
            behaviour.init();
            front.set(300.0, tick);
            right.set(55.0, tick);
            behaviour.update(); // SEARCH -> DRIVE
            mover.clear();
        }

        void step(double frontDistance, double rightDistance) {
            tick += 3; // sim runs at ~60 Hz, behaviour at ~20 Hz
            front.set(frontDistance, tick);
            right.set(rightDistance, tick);
            behaviour.update();
        }
    }

    @Test
    void stableRightDistanceDrivesForwardWithoutTurns() {
        Harness h = new Harness();
        for (int i = 1; i <= 20; i++) {
            h.step(300.0 - i, 55.0);
        }
        assertEquals(20, h.mover.commands.size());
        assertTrue(h.mover.commands.stream().allMatch(c -> c == Command.FORWARD));
    }

    @Test
    void slowDriftUsesGentleDutyCycleInsteadOfEveryTick() {
        Harness h = new Harness();
        for (int i = 1; i <= 6; i++) {
            h.step(300.0 - i, 55.0 + 0.6 * i);
        }
        h.mover.clear();
        // Hold the new distance: no second correction after the burst.
        for (int i = 0; i < 8; i++) {
            h.step(294.0 - i, 58.6);
        }
        assertEquals(List.of(
                Command.FORWARD, Command.FORWARD, Command.FORWARD, Command.TURN_RIGHT,
                Command.FORWARD, Command.FORWARD, Command.FORWARD, Command.TURN_RIGHT),
                h.mover.commands);
    }

    @Test
    void negativeDriftCorrectsLeft() {
        Harness h = new Harness();
        for (int i = 1; i <= 6; i++) {
            h.step(300.0 - i, 55.0 - 0.6 * i);
        }
        h.mover.clear();
        for (int i = 0; i < 8; i++) {
            h.step(294.0 - i, 51.4);
        }
        assertEquals(2, h.mover.commands.stream().filter(c -> c == Command.TURN_LEFT).count());
        assertTrue(h.mover.commands.stream().noneMatch(c -> c == Command.TURN_RIGHT));
    }

    @Test
    void strongDriftNeverTurnsOnConsecutiveUpdates() {
        Harness h = new Harness();
        for (int i = 1; i <= 6; i++) {
            h.step(300.0 - i, 55.0 + 3.0 * i); // 18 units over the window
        }
        h.mover.clear();
        for (int i = 0; i < 8; i++) {
            h.step(294.0 - i, 73.0);
        }
        assertEquals(4, h.mover.commands.stream().filter(c -> c == Command.TURN_RIGHT).count());
        for (int i = 1; i < h.mover.commands.size(); i++) {
            assertTrue(!(h.mover.commands.get(i) == Command.TURN_RIGHT
                    && h.mover.commands.get(i - 1) == Command.TURN_RIGHT));
        }
    }

    @Test
    void unchangedSimTickDoesNotCloseDriftWindow() {
        Harness h = new Harness();
        for (int i = 1; i <= 12; i++) {
            h.tick = 3; // no sim progress: snapshots are stale/frozen
            h.front.set(300.0, h.tick);
            h.right.set(55.0 + i, h.tick);
            h.behaviour.update();
        }
        assertTrue(h.mover.commands.stream().allMatch(c -> c == Command.FORWARD));
        assertEquals(12, h.mover.commands.size());
    }

    @Test
    void frozenReadingsTriggerBackupThenTurn() {
        Harness h = new Harness();
        for (int i = 0; i < 4; i++) {
            h.step(300.0, 55.0);
        }
        h.mover.clear();
        for (int i = 0; i < 5; i++) {
            h.step(300.0, 55.0);
        }
        // Backing up cannot change either reading here, so it aborts after
        // three ticks and goes straight to the duty-cycled recovery turn.
        assertEquals(List.of(Command.BACKWARD, Command.BACKWARD, Command.STOP,
                Command.TURN_LEFT, Command.STOP), h.mover.commands);
    }

    @Test
    void backupContinuesWhenReversingActuallyMovesTheRobot() {
        Harness h = new Harness();
        for (int i = 0; i < 4; i++) {
            h.step(300.0, 55.0); // trigger stall
        }
        h.mover.clear();
        for (int i = 0; i < 6; i++) {
            h.step(300.0 - 2.0 * i, 55.0 + 10.0 * i);
        }
        assertEquals(4, h.mover.commands.stream().filter(c -> c == Command.BACKWARD).count());
        assertTrue(h.mover.commands.stream().anyMatch(
                c -> c == Command.TURN_LEFT || c == Command.STOP));
    }

    @Test
    void avoidStartsAt130AndNeedsFrontToStayClearBeforeResuming() {
        Harness h = new Harness();

        h.step(120.0, 55.0);
        assertEquals(List.of(Command.TURN_LEFT), h.mover.commands);
        h.mover.clear();

        h.step(129.0, 55.0); // still blocked; turn is duty-cycled with STOP
        assertTrue(h.mover.commands.stream().allMatch(
                c -> c == Command.TURN_LEFT || c == Command.STOP));
        h.mover.clear();

        for (int i = 0; i < 3; i++) {
            h.step(131.0, 55.0);
            assertTrue(!h.mover.commands.isEmpty()); // still avoiding
            h.mover.clear();
        }
        h.step(131.0, 55.0); // fourth clear tick exits
        assertTrue(h.mover.commands.isEmpty());

        h.step(300.0, 55.0);
        assertEquals(List.of(Command.FORWARD), h.mover.commands);
    }

    @Test
    void toolFollowsFinalStateThroughDriveAndAvoid() {
        Harness h = new Harness();
        assertTrue(h.tool.active(), "DRIVE should light the tool");

        h.step(120.0, 55.0);
        assertFalse(h.tool.active(), "AVOID should darken the tool");

        for (int i = 0; i < 3; i++) {
            h.step(131.0, 55.0);
            assertFalse(h.tool.active(), "still AVOID");
        }
        h.step(131.0, 55.0); // fourth clear tick exits AVOID on an early return
        assertTrue(h.tool.active(), "back in DRIVE");
    }

    @Test
    void toolIsInactiveInSearchAndActiveInApproach() {
        RecordingMover mover = new RecordingMover();
        FakeSensor front = new FakeSensor("front-sensor");
        FakeSensor right = new FakeSensor("right-sensor");
        RecordingTool tool = new RecordingTool();
        WallFollowRightBehaviour behaviour = new WallFollowRightBehaviour(mover, front, right, tool);

        behaviour.init();
        assertFalse(tool.active(), "init must deactivate the tool");

        front.set(300.0, 3);
        right.set(300.0, 3);
        behaviour.update();
        assertFalse(tool.active(), "SEARCH should darken the tool");

        front.set(299.0, 6);
        right.set(80.0, 6);
        behaviour.update();
        assertTrue(tool.active(), "APPROACH should light the tool");
    }

    @Test
    void toolIsActiveAtWallEnd() {
        Harness h = new Harness();
        h.step(299.0, 55.0);
        h.step(298.0, 55.0);
        h.step(297.0, 200.0); // wall vanished -> TURN_RIGHT_AT_END
        assertTrue(h.tool.active());
    }

    @Test
    void toolIsInactiveDuringStallRecovery() {
        Harness h = new Harness();
        for (int i = 0; i < 4; i++) {
            h.step(300.0, 55.0);
        }
        assertFalse(h.tool.active(), "RECOVER_BACKUP should darken the tool");
        for (int i = 0; i < 6; i++) {
            h.step(300.0, 55.0);
        }
        assertFalse(h.tool.active(), "RECOVER_TURN should darken the tool");
    }

    @Test
    void initDeactivatesAnActiveTool() {
        Harness h = new Harness();
        assertTrue(h.tool.active(), "DRIVE should light the tool");

        h.behaviour.init();

        assertFalse(h.tool.active());
    }
}
