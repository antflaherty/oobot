package oobot.robot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import oobot.robot.description.ComponentDescription;
import oobot.robot.description.ComponentRegistry;
import oobot.robot.description.ComponentType;
import oobot.robot.description.RobotDescription;
import oobot.robot.student.Sensor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Focused loader tests for the {@code goalSensor} manifest component. */
class GoalSensorDescriptionTest {

    private static final String GOAL_SENSOR_ENTRY =
            "{ \"id\": \"goal-sensor\", \"type\": \"goalSensor\", \"angle\": 0.0 }";

    @Test
    void parsesGoalSensorAfterTool(@TempDir Path dir) throws Exception {
        RobotDescription description = load(dir, GOAL_SENSOR_ENTRY);

        List<ComponentDescription> components = description.components();
        assertEquals(6, components.size());
        assertEquals("front-light", components.get(4).id());
        assertEquals(ComponentType.TOOL, components.get(4).type());

        ComponentDescription goal = components.get(5);
        assertEquals("goal-sensor", goal.id());
        assertEquals(ComponentType.GOAL_SENSOR, goal.type());
        assertEquals(0.0, goal.angle(), 1e-9);
        assertEquals(0.0, goal.offset(), 1e-9);
    }

    @Test
    void acceptsFiniteAngleAndIgnoresUnknownKeys(@TempDir Path dir) throws Exception {
        RobotDescription description = load(dir,
                "{ \"id\": \"goal-sensor\", \"type\": \"goalSensor\", "
                        + "\"angle\": 1.25, \"label\": \"goal\" }");

        ComponentDescription goal = description.components().get(5);
        assertEquals(ComponentType.GOAL_SENSOR, goal.type());
        assertEquals(1.25, goal.angle(), 1e-9);
        assertEquals(0.0, goal.offset(), 1e-9);
    }

    @Test
    void rejectsOffset(@TempDir Path dir) throws Exception {
        assertGoalFailure(dir,
                "{ \"id\": \"goal-sensor\", \"type\": \"goalSensor\", "
                        + "\"angle\": 0.0, \"offset\": 0.25 }",
                "offset");
    }

    @Test
    void rejectsSide(@TempDir Path dir) throws Exception {
        assertGoalFailure(dir,
                "{ \"id\": \"goal-sensor\", \"type\": \"goalSensor\", "
                        + "\"angle\": 0.0, \"side\": \"front\" }",
                "side");
    }

    @Test
    void rejectsNonFiniteAngle(@TempDir Path dir) throws Exception {
        assertGoalFailure(dir,
                "{ \"id\": \"goal-sensor\", \"type\": \"goalSensor\", \"angle\": 1e999 }",
                "angle");
    }

    @Test
    void rejectsMissingAngle(@TempDir Path dir) throws Exception {
        assertGoalFailure(dir,
                "{ \"id\": \"goal-sensor\", \"type\": \"goalSensor\" }",
                "angle");
    }

    @Test
    void rejectsWrongTypeSpelling(@TempDir Path dir) throws Exception {
        assertGoalFailure(dir,
                "{ \"id\": \"goal-sensor\", \"type\": \"goalsensor\", \"angle\": 0.0 }",
                "component[5]");
    }

    @Test
    void registryExposesTypedSensorAccessors(@TempDir Path dir) throws Exception {
        RobotDescription description = load(dir, GOAL_SENSOR_ENTRY);
        ComponentRegistry registry = new ComponentRegistry(description);
        registry.bind("front-sensor", new FakeSensor<>("front-sensor", 7L, 12.5));
        registry.bind("goal-sensor", new FakeSensor<>("goal-sensor", 8L, Boolean.TRUE));

        Sensor<Double> front = registry.sensor("front-sensor");
        assertEquals(7L, front.latestSimTick());
        assertEquals(12.5, front.value(), 1e-9);

        Sensor<Boolean> goal = registry.booleanSensor("goal-sensor");
        assertEquals(8L, goal.latestSimTick());
        assertTrue(goal.value());

        assertEquals(30L, Sensor.MAX_STALE_TICKS);
    }

    private static RobotDescription load(Path dir, String goalEntry) throws Exception {
        return RobotDescription.load(writeManifest(dir, goalEntry));
    }

    private static Path writeManifest(Path dir, String goalEntry) throws Exception {
        Path path = dir.resolve("manifest.json");
        Files.writeString(path, """
                {
                  "schemaVersion": 2,
                  "name": "goal-sensor-test",
                  "components": [
                    { "id": "left-wheel", "type": "wheel", "angle": 1.5707963267948966, "offset": 0.25 },
                    { "id": "right-wheel", "type": "wheel", "angle": -1.5707963267948966, "offset": 0.25 },
                    { "id": "front-sensor", "type": "proximitySensor", "angle": 0.0 },
                    { "id": "right-sensor", "type": "proximitySensor", "angle": -1.5707963267948966 },
                    { "id": "front-light", "type": "tool", "angle": 0.0 },
                    %s
                  ]
                }
                """.formatted(goalEntry));
        return path;
    }

    private static void assertGoalFailure(Path dir, String goalEntry, String token) throws Exception {
        Path path = writeManifest(dir, goalEntry);
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> RobotDescription.load(path));
        assertNotNull(error.getMessage());
        assertTrue(error.getMessage().contains(path.toString()), error.getMessage());
        assertTrue(error.getMessage().contains("component[5]"), error.getMessage());
        assertTrue(error.getMessage().contains(token), error.getMessage());
    }

    private static final class FakeSensor<T> implements Sensor<T> {
        private final String id;
        private final long tick;
        private final T value;

        FakeSensor(String id, long tick, T value) {
            this.id = id;
            this.tick = tick;
            this.value = value;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public long latestSimTick() {
            return tick;
        }

        @Override
        public T value() {
            return value;
        }
    }
}
