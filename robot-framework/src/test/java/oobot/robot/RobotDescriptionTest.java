package oobot.robot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import oobot.robot.description.ComponentDescription;
import oobot.robot.description.ComponentType;
import oobot.robot.description.RobotBody;
import oobot.robot.description.RobotDescription;
import oobot.robot.description.WheelGeometry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Pure unit tests for the shared component manifest; no sim or sockets involved. */
class RobotDescriptionTest {

    /**
     * Resolve the manifest from the Gradle project directory, never the process CWD.
     *
     * <p>T0.2 specifies {@code ../../robots/default.json}; that path is relative to
     * {@code robot-framework/src}, so it is resolved against the project dir here.
     */
    private static Path defaultManifest() {
        Path manifest = Path.of(projectDir()).resolve("src/../../robots/default.json").normalize();
        assertTrue(Files.isRegularFile(manifest), "manifest not found at " + manifest);
        return manifest;
    }

    private static Path fixture(String file) {
        Path path = Path.of(projectDir()).resolve("src/test/resources/fixtures").resolve(file).normalize();
        assertTrue(Files.isRegularFile(path), "fixture not found at " + path);
        return path;
    }

    private static String projectDir() {
        String projectDir = System.getProperty("oobot.projectDir");
        if (projectDir == null || projectDir.isBlank()) {
            throw new IllegalStateException("Missing oobot.projectDir system property (set by build.gradle.kts)");
        }
        return projectDir;
    }

    @Test
    void loadsDefaultManifestWithSixComponentsIncludingToolAndGoalSensor() {
        RobotDescription description = RobotDescription.load(defaultManifest());

        List<ComponentDescription> components = description.components();
        assertEquals(6, components.size());
        assertTrue(components.stream().anyMatch(c ->
                c.id().equals("left-wheel") && c.type() == ComponentType.WHEEL));
        assertTrue(components.stream().anyMatch(c ->
                c.id().equals("right-wheel") && c.type() == ComponentType.WHEEL));
        assertTrue(components.stream().anyMatch(c ->
                c.id().equals("front-sensor") && c.type() == ComponentType.PROXIMITY_SENSOR));
        assertTrue(components.stream().anyMatch(c ->
                c.id().equals("right-sensor") && c.type() == ComponentType.PROXIMITY_SENSOR));
        ComponentDescription tool = components.stream()
                .filter(c -> c.id().equals("front-light"))
                .findFirst().orElseThrow();
        assertEquals(ComponentType.TOOL, tool.type());
        assertEquals(0.0, tool.angle(), 1e-9);
        assertEquals(0.0, tool.offset(), 1e-9);
    }

    @Test
    void defaultManifestKeepsFrontLightAtIndexFourAndAppendsGoalSensorAtIndexFive() {
        RobotDescription description = RobotDescription.load(defaultManifest());

        List<ComponentDescription> components = description.components();
        assertEquals(6, components.size());
        assertEquals("front-light", components.get(4).id());
        assertEquals(ComponentType.TOOL, components.get(4).type());

        ComponentDescription goalSensor = components.get(5);
        assertEquals("goal-sensor", goalSensor.id());
        assertEquals(ComponentType.GOAL_SENSOR, goalSensor.type());
        assertEquals(0.0, goalSensor.angle(), 1e-9);
        assertEquals(0.0, goalSensor.offset(), 1e-9);
    }

    @Test
    void toolMustNotDeclareOffset(@TempDir Path dir) throws Exception {
        Path manifest = manifestWithToolReplacement(dir, "\"angle\": 0.0 }",
                "\"angle\": 0.0, \"offset\": 0.25 }");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> RobotDescription.load(manifest));
        assertNotNull(error.getMessage());
        assertTrue(error.getMessage().contains(manifest.toString()), error.getMessage());
        assertTrue(error.getMessage().contains("component[4]"), error.getMessage());
    }

    @Test
    void toolAngleMustBeFinite(@TempDir Path dir) throws Exception {
        Path manifest = manifestWithToolReplacement(dir, "\"angle\": 0.0 }", "\"angle\": 1e999 }");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> RobotDescription.load(manifest));
        assertNotNull(error.getMessage());
        assertTrue(error.getMessage().contains("component[4]"), error.getMessage());
    }

    @Test
    void unknownComponentTypeIsStillRejected(@TempDir Path dir) throws Exception {
        Path manifest = manifestWithToolReplacement(dir, "\"type\": \"tool\"", "\"type\": \"laser\"");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> RobotDescription.load(manifest));
        assertNotNull(error.getMessage());
        assertTrue(error.getMessage().contains("component[4]"), error.getMessage());
    }

    @Test
    void derivesHalfMetreTrackWidthFromManifest() {
        RobotDescription description = RobotDescription.load(defaultManifest());

        // y_left - y_right = offset_left*sin(angle_left) - offset_right*sin(angle_right)
        assertEquals(0.5, derivedTrackWidth(description), 1e-9);
    }

    @Test
    void bodyUsesDefaultsWhenBlockIsAbsent() {
        // This older fixture has no "body" key at all.
        RobotDescription description = RobotDescription.load(fixture("accept-proximity-sensor-spelling.json"));

        assertEquals(RobotBody.DEFAULT, description.body());
    }

    @Test
    void bodyReadsExplicitDimensions() {
        RobotDescription description = RobotDescription.load(fixture("accept-body.json"));

        assertEquals(new RobotBody(0.6, 0.4, new WheelGeometry(0.25, 0.12)), description.body());
    }

    @Test
    void sharedDefaultManifestDeclaresDefaultBody() {
        RobotDescription description = RobotDescription.load(defaultManifest());

        assertEquals(RobotBody.DEFAULT, description.body());
    }

    @Test
    void twoArgumentConstructorUsesDefaultBody() {
        RobotDescription description = new RobotDescription("test-layout", List.of());

        assertEquals(RobotBody.DEFAULT, description.body());
    }

    @Test
    void bodyFailuresNameManifestPathAndOffendingKey() {
        assertBodyFailure("reject-body-width-zero.json", "body.width");
        assertBodyFailure("reject-body-wheels-non-finite.json", "body.wheels.length");
        assertBodyFailure("reject-body-not-object.json", "body");
    }

    private static void assertBodyFailure(String file, String token) {
        Path path = fixture(file);
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> RobotDescription.load(path),
                file + " must be rejected");
        assertNotNull(error.getMessage());
        assertTrue(error.getMessage().contains(path.toString()),
                file + ": error must name the manifest path; was: " + error.getMessage());
        assertTrue(error.getMessage().contains(token),
                file + ": error must name " + token + "; was: " + error.getMessage());
    }

    /** Copy the updated default fixture, changing one token in the tool entry only. */
    private static Path manifestWithToolReplacement(Path dir, String from, String to) throws Exception {
        String content = Files.readString(fixture("valid-default.json"));
        int toolIndex = content.indexOf("\"front-light\"");
        assertTrue(toolIndex >= 0, "tool entry not found in fixture");
        int at = content.indexOf(from, toolIndex);
        assertTrue(at >= 0, "replacement target not found in tool entry: " + from);
        Path manifest = dir.resolve("manifest.json");
        Files.writeString(manifest,
                content.substring(0, at) + to + content.substring(at + from.length()));
        return manifest;
    }

    private static double derivedTrackWidth(RobotDescription description) {
        double leftY = 0.0;
        double rightY = 0.0;
        for (ComponentDescription c : description.components()) {
            if (c.type() != ComponentType.WHEEL) {
                continue;
            }
            double y = c.offset() * Math.sin(c.angle());
            if (y > 0.0) {
                leftY = y;
            } else {
                rightY = y;
            }
        }
        return leftY - rightY;
    }
}
