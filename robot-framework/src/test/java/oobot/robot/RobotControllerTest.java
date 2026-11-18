package oobot.robot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * Pure unit tests for {@link RobotController}'s picocli option wiring.
 *
 * <p>These tests only parse arguments: they never call {@code run()}, so no
 * socket, manifest load or behaviour loop is started. The parsed value is read
 * from the private field via reflection, and the controller's own
 * behaviour-name validation is invoked reflectively to check rejection.
 */
class RobotControllerTest {

    @Test
    void defaultBehaviourIsWallFollowRight() {
        RobotController controller = parse();

        assertEquals("wall-follow-right", behaviourName(controller));
    }

    @Test
    void explicitBehaviourOverridesDefault() {
        RobotController controller = parse("--behaviour", "square");

        assertEquals("square", behaviourName(controller));
    }

    @Test
    void removedGoalSeekBehaviourIsRejected() {
        CommandLine.ParameterException thrown = assertThrows(
                CommandLine.ParameterException.class,
                () -> validateBehaviour(parse("--behaviour", "goal-seek")));

        assertTrue(thrown.getMessage().contains("goal-seek"));
    }

    @Test
    void invalidBehaviourNameIsRejected() {
        CommandLine.ParameterException thrown = assertThrows(
                CommandLine.ParameterException.class,
                () -> validateBehaviour(parse("--behaviour", "definitely-not-a-behaviour")));

        assertTrue(thrown.getMessage().contains("behaviour"),
                "rejection should explain that the behaviour name is invalid");
    }

    private static RobotController parse(String... args) {
        RobotController controller = new RobotController();
        new CommandLine(controller).parseArgs(args);
        return controller;
    }

    private static String behaviourName(RobotController controller) {
        try {
            Field field = RobotController.class.getDeclaredField("behaviourName");
            field.setAccessible(true);
            return (String) field.get(controller);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("RobotController.behaviourName field unavailable", e);
        }
    }

    /** Re-throws the controller's {@code ParameterException} cause unwrapped. */
    private static void validateBehaviour(RobotController controller) {
        try {
            Method validate = RobotController.class.getDeclaredMethod("validateBehaviourName");
            validate.setAccessible(true);
            validate.invoke(controller);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AssertionError("Unexpected validation failure", cause);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("RobotController.validateBehaviourName() unavailable", e);
        }
    }
}
