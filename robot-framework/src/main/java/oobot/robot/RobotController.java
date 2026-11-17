package oobot.robot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import oobot.robot.bridge.RobotPlatform;
import oobot.robot.bridge.sim.SimRobot;
import oobot.robot.description.ComponentRegistry;
import oobot.robot.description.ComponentType;
import oobot.robot.description.RobotDescription;
import oobot.robot.example.CircleTraceBehaviour;
import oobot.robot.example.DefaultBrain;
import oobot.robot.example.HeartTraceBehaviour;
import oobot.robot.example.SquareTraceBehaviour;
import oobot.robot.example.WallAvoidBehaviour;
import oobot.robot.example.WallFollowRightBehaviour;
import oobot.robot.example.WheeledMover;
import oobot.robot.student.Brain;
import oobot.robot.student.BrownianMotionBehaviour;
import oobot.robot.student.Mover;
import oobot.robot.student.ProximitySensor;
import oobot.robot.student.RobotBehaviour;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * Entry point for the robot controller process.
 *
 * <p>Usage: {@code java -jar robot.jar [--host localhost] [--port 8080]
 * [--robot ../robots/default.json]
 * [--behaviour square|wall-avoid|wall-follow-right|circle|heart|brownian]}
 *
 * <p>The default {@code --robot} path is relative to the framework directory
 * (the JAR's parent), so the controller can be launched from any working
 * directory. Startup validation failures are reported by picocli as usage
 * errors with exit code 2.
 */
@Command(name = "robot", version = "oobot robot-controller 1.0.0",
         mixinStandardHelpOptions = true,
         description = "Java robot controller for the oobot simulation")
public class RobotController implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(RobotController.class);
    private static final Set<String> VALID_BEHAVIOURS = Set.of(
            "square", "wall-avoid", "wall-follow-right", "circle", "heart", "brownian");

    /**
     * Default manifest, resolved against the framework directory (see
     * {@link #frameworkDirectory()}). Explicit {@code --robot} paths are used as
     * given; only this default is rebased.
     */
    private static final String DEFAULT_ROBOT_PATH = "../robots/default.json";

    /** Fixed ids required by the non-generic {@link Brain} sensor accessors (D-3). */
    private static final String FRONT_SENSOR_ID = "front-sensor";
    private static final String RIGHT_SENSOR_ID = "right-sensor";

    @Spec
    private CommandSpec spec;

    @Option(names = "--host", defaultValue = "localhost",
            description = "Sim server host (default: ${DEFAULT-VALUE})")
    private String host;

    @Option(names = "--port", defaultValue = "8080",
            description = "Sim robot-channel port (default: ${DEFAULT-VALUE})")
    private int port;

    @Option(names = "--behaviour", defaultValue = "wall-follow-right",
            description = "Behaviour: square|wall-avoid|wall-follow-right|circle|heart|brownian (default: ${DEFAULT-VALUE})")
    private String behaviourName;

    @Option(names = "--robot",
            description = "Robot component manifest path. The default "
                    + DEFAULT_ROBOT_PATH + " is resolved against the framework directory "
                    + "(normally robot-framework/, the JAR's parent); explicit paths are "
                    + "used as given")
    private Path robotPath;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new RobotController()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        // Validate before opening any connections or starting threads. Every
        // failure below is a ParameterException so picocli prints usage and
        // exits 2 without a stack trace (no half-started robot process).
        validateBehaviourName();
        Path manifest = requireManifest();
        RobotDescription desc = loadManifest(manifest);
        requireFixedSensors(desc, manifest);

        log.info("Starting; connecting to ws://{}:{}", host, port);
        log.info("Loaded robot manifest {} ({} components)", manifest, desc.components().size());
        RobotPlatform platform = new SimRobot(host, port, desc);
        ComponentRegistry reg = platform.components();

        // Build the robot-side mover from the manifest-declared wheels.
        Mover mover = new WheeledMover(reg.wheels());
        ProximitySensor front = reg.sensor(FRONT_SENSOR_ID);
        ProximitySensor right = reg.sensor(RIGHT_SENSOR_ID);

        RobotBehaviour behaviour = createBehaviour(behaviourName, mover, front, right);

        Brain brain = new DefaultBrain(mover, front, right);
        brain.setBehaviour(behaviour);

        Runtime.getRuntime().addShutdownHook(new Thread(platform::stop, "robot-shutdown"));

        platform.run(brain);
        log.info("Behaviour loop running at ~20 Hz");

        while (platform.isRunning()) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.info("Shutdown complete");
    }

    private void validateBehaviourName() {
        if (!VALID_BEHAVIOURS.contains(behaviourName)) {
            throw failure("Unknown behaviour: " + behaviourName
                    + " (expected one of " + String.join(", ", VALID_BEHAVIOURS) + ")");
        }
    }

    private Path requireManifest() {
        Path manifest = robotPath == null
                ? frameworkDirectory().resolve(DEFAULT_ROBOT_PATH).normalize()
                : robotPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(manifest)) {
            throw failure("Robot manifest not found: " + manifest
                    + ". Pass --robot <path> pointing to a v2 component manifest "
                    + "(the default " + DEFAULT_ROBOT_PATH
                    + " is resolved against the framework directory).");
        }
        return manifest;
    }

    private RobotDescription loadManifest(Path manifest) {
        try {
            return RobotDescription.load(manifest);
        } catch (IllegalArgumentException e) {
            throw failure(e.getMessage());
        }
    }

    /**
     * D-3: the {@link Brain} sensor accessors are fixed-id, so fail fast with a
     * message naming both the missing id and the manifest path.
     */
    private void requireFixedSensors(RobotDescription desc, Path manifest) {
        requireFixedSensor(desc, manifest, FRONT_SENSOR_ID, "frontSensor");
        requireFixedSensor(desc, manifest, RIGHT_SENSOR_ID, "rightSensor");
    }

    private void requireFixedSensor(RobotDescription desc, Path manifest,
                                    String id, String accessor) {
        boolean present = desc.components().stream()
                .anyMatch(c -> c.id().equals(id) && c.type() == ComponentType.PROXIMITY_SENSOR);
        if (!present) {
            throw failure("Manifest " + manifest + " must declare a proximitySensor with id '"
                    + id + "' (required by Brain." + accessor + "()).");
        }
    }

    private CommandLine.ParameterException failure(String message) {
        return new CommandLine.ParameterException(spec.commandLine(), message);
    }

    /**
     * Base directory for the default manifest: the ancestor of this class's
     * code source that directly contains the sibling {@code robots/} directory.
     * For the fat JAR that is the JAR's parent ({@code robot-framework/}); for
     * the Gradle class directory we walk up to the same ancestor. If neither
     * applies, the working directory is the documented fallback and
     * {@link #requireManifest()} still reports a clear error.
     */
    private static Path frameworkDirectory() {
        try {
            Path source = Path.of(RobotController.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path start = Files.isDirectory(source) ? source : source.getParent();
            for (Path candidate = start; candidate != null; candidate = candidate.getParent()) {
                if (Files.isDirectory(candidate.resolve("../robots").normalize())) {
                    return candidate;
                }
            }
            return start;
        } catch (URISyntaxException | RuntimeException e) {
            return Path.of("").toAbsolutePath();
        }
    }

    private RobotBehaviour createBehaviour(String name, Mover mover,
                                           ProximitySensor front, ProximitySensor right) {
        return switch (name) {
            case "square" -> new SquareTraceBehaviour(mover, front, right);
            case "wall-avoid" -> new WallAvoidBehaviour(mover, front, right);
            case "wall-follow-right" -> new WallFollowRightBehaviour(mover, front, right);
            case "circle" -> new CircleTraceBehaviour(mover, front, right);
            case "heart" -> new HeartTraceBehaviour(mover, front, right);
            case "brownian" -> new BrownianMotionBehaviour(mover, front, right);
            default -> throw failure("Unknown behaviour: " + name);
        };
    }
}
