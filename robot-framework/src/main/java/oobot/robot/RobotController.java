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

import java.nio.file.Path;
import java.util.Set;

/**
 * Entry point for the robot controller process.
 *
 * <p>Usage: {@code java -jar robot.jar [--host localhost] [--port 8080]
 * [--robot ../robots/default.json]
 * [--behaviour square|wall-avoid|wall-follow-right|circle|heart|brownian]}
 */
@Command(name = "robot", version = "oobot robot-controller 1.0.0",
         mixinStandardHelpOptions = true,
         description = "Java robot controller for the oobot simulation")
public class RobotController implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(RobotController.class);
    private static final Set<String> VALID_BEHAVIOURS = Set.of(
            "square", "wall-avoid", "wall-follow-right", "circle", "heart", "brownian");

    @Spec
    private CommandSpec spec;

    @Option(names = "--host", defaultValue = "localhost",
            description = "Sim server host (default: ${DEFAULT-VALUE})")
    private String host;

    @Option(names = "--port", defaultValue = "8080",
            description = "Sim robot-channel port (default: ${DEFAULT-VALUE})")
    private int port;

    @Option(names = "--behaviour", defaultValue = "brownian",
            description = "Behaviour: square|wall-avoid|wall-follow-right|circle|heart|brownian (default: ${DEFAULT-VALUE})")
    private String behaviourName;

    @Option(names = "--robot", defaultValue = "../robots/default.json",
            description = "Robot component manifest path (default: ${DEFAULT-VALUE})")
    private Path robotPath;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new RobotController()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        // Validate before opening any connections or starting threads.
        if (!VALID_BEHAVIOURS.contains(behaviourName)) {
            throw new CommandLine.ParameterException(
                    spec.commandLine(), "Unknown behaviour: " + behaviourName);
        }

        log.info("Starting; connecting to ws://{}:{}", host, port);

        RobotDescription desc = RobotDescription.load(robotPath);
        log.info("Loaded robot manifest {} ({} components)", robotPath, desc.components().size());
        RobotPlatform platform = new SimRobot(host, port, desc);
        ComponentRegistry reg = platform.components();

        // Build the robot-side mover from the manifest-declared wheels.
        Mover mover = new WheeledMover(reg.wheels());
        ProximitySensor front = reg.sensor("front-sensor");
        ProximitySensor right = reg.sensor("right-sensor");

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

    private static RobotBehaviour createBehaviour(String name, Mover mover,
                                                  ProximitySensor front, ProximitySensor right) {
        return switch (name) {
            case "square" -> new SquareTraceBehaviour(mover, front, right);
            case "wall-avoid" -> new WallAvoidBehaviour(mover, front, right);
            case "wall-follow-right" -> new WallFollowRightBehaviour(mover, front, right);
            case "circle" -> new CircleTraceBehaviour(mover, front, right);
            case "heart" -> new HeartTraceBehaviour(mover, front, right);
            case "brownian" -> new BrownianMotionBehaviour(mover, front, right);
            default -> null;
        };
    }
}
