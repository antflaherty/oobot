package oobot.robot;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import oobot.robot.description.ComponentDescription;
import oobot.robot.description.ComponentType;
import oobot.robot.description.RobotDescription;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Manifest-validation parity between the Java loader and the TypeScript loader.
 *
 * <p>The fixture files are copied byte-for-byte from
 * {@code simulator/server/test/fixtures/} (owned by S1.3). The accept/reject
 * expectations below are the fixture set locked in {@code docs/DECISIONS.md}.
 */
class ManifestFixtureParityTest {

    /** {@code errorIndex} is the 0-based component index; {@code null} means manifest-level. */
    private record Fixture(String file, boolean accept, Integer errorIndex) {}

    private static final List<Fixture> FIXTURES = List.of(
            new Fixture("valid-default.json", true, null),
            new Fixture("accept-body.json", true, null),
            new Fixture("reject-body-width-zero.json", false, null),
            new Fixture("reject-body-wheels-non-finite.json", false, null),
            new Fixture("reject-body-not-object.json", false, null),
            new Fixture("accept-proximity-sensor-spelling.json", true, null),
            new Fixture("accept-sensor-without-offset.json", true, null),
            new Fixture("accept-alternative-angles.json", true, null),
            new Fixture("reject-schema-v1.json", false, null),
            new Fixture("reject-schema-float.json", false, null),
            new Fixture("reject-side-field.json", false, 0),
            new Fixture("reject-duplicate-ids.json", false, 1),
            new Fixture("reject-blank-id.json", false, 0),
            new Fixture("reject-blank-name.json", false, null),
            new Fixture("reject-non-finite-angle.json", false, 0),
            new Fixture("reject-wheel-offset-zero.json", false, 0),
            new Fixture("reject-wheel-offset-negative.json", false, 1),
            new Fixture("reject-two-left-wheels.json", false, null),
            new Fixture("reject-no-right-wheel.json", false, null),
            new Fixture("reject-sensor-with-offset.json", false, 2));

    private static Stream<Fixture> fixtures() {
        return FIXTURES.stream();
    }

    private static Path projectDir() {
        String projectDir = System.getProperty("oobot.projectDir");
        if (projectDir == null || projectDir.isBlank()) {
            throw new IllegalStateException("Missing oobot.projectDir system property (set by build.gradle.kts)");
        }
        return Path.of(projectDir);
    }

    private static Path fixturePath(String file) {
        Path path = projectDir().resolve("src/test/resources/fixtures").resolve(file).normalize();
        assertTrue(Files.isRegularFile(path), "fixture not found: " + path);
        return path;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void fixtureHasExpectedAcceptRejectDecisionAndErrorContent(Fixture fixture) {
        Path path = fixturePath(fixture.file());

        if (fixture.accept()) {
            RobotDescription description = assertDoesNotThrow(
                    () -> RobotDescription.load(path),
                    fixture.file() + " must be accepted");
            // The updated default fixture adds a tool and a goal sensor; the
            // older fixtures keep the original four components.
            int expected = fixture.file().equals("valid-default.json") ? 6 : 4;
            assertEquals(expected, description.components().size(), fixture.file());

            if (fixture.file().equals("valid-default.json")) {
                assertEquals(0.5, trackWidth(description), 1e-9);
            } else if (fixture.file().equals("accept-alternative-angles.json")) {
                // sin(2.0) ~= 0.9093 > 0.9, so 0.3*sin(2) - 0.3*sin(-2) ~= 0.5456.
                assertEquals(0.6 * Math.sin(2.0), trackWidth(description), 1e-9);
            }
            return;
        }

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> RobotDescription.load(path),
                fixture.file() + " must be rejected");
        String message = error.getMessage();
        assertNotNull(message, fixture.file() + ": error message must not be null");
        assertTrue(message.contains(path.toString()),
                fixture.file() + ": error must name the manifest path; was: " + message);

        // Component-scoped failures must also identify the offending array entry.
        // Both "component[i]" (docs/DECISIONS.md) and "components[i]" (S1.3) carry the index.
        Integer index = fixture.errorIndex();
        if (index != null) {
            Pattern indexToken = Pattern.compile("components?\\[" + index + "\\]");
            assertTrue(indexToken.matcher(message).find(),
                    fixture.file() + ": error must name component[" + index + "]; was: " + message);
        }
    }

    private static double trackWidth(RobotDescription description) {
        double leftY = 0.0;
        double rightY = 0.0;
        for (ComponentDescription component : description.components()) {
            if (component.type() != ComponentType.WHEEL) {
                continue;
            }
            double y = component.offset() * Math.sin(component.angle());
            if (y > 0.0) {
                leftY = y;
            } else {
                rightY = y;
            }
        }
        return leftY - rightY;
    }

    /**
     * Byte-for-byte parity: the Java copies must hash exactly like the TypeScript
     * originals. Skipped only when S1.3's fixtures are not present yet (e.g. a
     * partially landed W1 worktree); the W1 merge gate always has both trees.
     */
    @Test
    void javaFixturesAreByteIdenticalToTypeScriptFixtures() throws Exception {
        Path typeScriptDir = projectDir().resolve("../simulator/server/test/fixtures").normalize();
        Assumptions.assumeTrue(Files.isDirectory(typeScriptDir),
                "S1.3 fixtures not present yet: " + typeScriptDir);

        for (Fixture fixture : FIXTURES) {
            Path javaFixture = fixturePath(fixture.file());
            Path typeScriptFixture = typeScriptDir.resolve(fixture.file());
            assertTrue(Files.isRegularFile(typeScriptFixture),
                    "missing TypeScript fixture: " + typeScriptFixture);
            assertEquals(sha256(typeScriptFixture), sha256(javaFixture),
                    fixture.file() + " must be byte-identical in both fixture directories");
        }
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
    }
}
