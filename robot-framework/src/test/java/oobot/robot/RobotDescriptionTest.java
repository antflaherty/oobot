package oobot.robot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import oobot.robot.description.ComponentDescription;
import oobot.robot.description.ComponentType;
import oobot.robot.description.RobotDescription;
import org.junit.jupiter.api.Test;

/** Pure unit tests for the shared component manifest; no sim or sockets involved. */
class RobotDescriptionTest {

    /**
     * Resolve the manifest from the Gradle project directory, never the process CWD.
     *
     * <p>T0.2 specifies {@code ../../robots/default.json}; that path is relative to
     * {@code robot-framework/src}, so it is resolved against the project dir here.
     */
    private static Path defaultManifest() {
        String projectDir = System.getProperty("oobot.projectDir");
        if (projectDir == null || projectDir.isBlank()) {
            throw new IllegalStateException("Missing oobot.projectDir system property (set by build.gradle.kts)");
        }
        Path manifest = Path.of(projectDir).resolve("src/../../robots/default.json").normalize();
        assertTrue(Files.isRegularFile(manifest), "manifest not found at " + manifest);
        return manifest;
    }

    @Test
    void loadsDefaultManifestWithFourComponents() {
        RobotDescription description = RobotDescription.load(defaultManifest());

        List<ComponentDescription> components = description.components();
        assertEquals(4, components.size());
        assertTrue(components.stream().anyMatch(c ->
                c.id().equals("left-wheel") && c.type() == ComponentType.WHEEL));
        assertTrue(components.stream().anyMatch(c ->
                c.id().equals("right-wheel") && c.type() == ComponentType.WHEEL));
        assertTrue(components.stream().anyMatch(c ->
                c.id().equals("front-sensor") && c.type() == ComponentType.PROXIMITY_SENSOR));
        assertTrue(components.stream().anyMatch(c ->
                c.id().equals("right-sensor") && c.type() == ComponentType.PROXIMITY_SENSOR));
    }

    @Test
    void derivesHalfMetreTrackWidthFromManifest() {
        RobotDescription description = RobotDescription.load(defaultManifest());

        // y_left - y_right = offset_left*sin(angle_left) - offset_right*sin(angle_right)
        assertEquals(0.5, derivedTrackWidth(description), 1e-9);
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
