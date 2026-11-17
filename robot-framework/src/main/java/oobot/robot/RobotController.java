package oobot.robot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import oobot.robot.bridge.sim.SimRobot;
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
import oobot.robot.student.RobotBehaviour;
import oobot.robot.student.Sensors;

import java.util.Set;

/**
 * Entry point for the robot controller process.
 *
 * <p>Usage: {@code java -jar robot.jar [--host localhost] [--port 8080]
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

        SimRobot robot = new SimRobot(host, port);

        // Build the robot-side mover from the sim bridge's wheels.
        Mover mover = new WheeledMover(robot.leftWheel(), robot.rightWheel());
        Sensors sensors = robot.sensors();

        RobotBehaviour behaviour = createBehaviour(behaviourName, mover, sensors);

        Brain brain = new DefaultBrain(mover, sensors);
        brain.setBehaviour(behaviour);

        Runtime.getRuntime().addShutdownHook(new Thread(robot::stop, "robot-shutdown"));

        robot.run(brain);
        log.info("Behaviour loop running at ~20 Hz");

        while (robot.isRunning()) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.info("Shutdown complete");
    }

    private static RobotBehaviour createBehaviour(String name, Mover mover, Sensors sensors) {
        return switch (name) {
            case "square" -> new SquareTraceBehaviour(mover, sensors);
            case "wall-avoid" -> new WallAvoidBehaviour(mover, sensors);
            case "wall-follow-right" -> new WallFollowRightBehaviour(mover, sensors);
            case "circle" -> new CircleTraceBehaviour(mover, sensors);
            case "heart" -> new HeartTraceBehaviour(mover, sensors);
            case "brownian" -> new BrownianMotionBehaviour(mover, sensors);
            default -> null;
        };
    }
}
