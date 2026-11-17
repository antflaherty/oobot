package oobot.robot;

import oobot.robot.student.Brain;
import oobot.robot.student.Mover;
import oobot.robot.student.RobotBehaviour;
import oobot.robot.student.Sensors;
import oobot.robot.bridge.sim.SimRobot;
import oobot.robot.example.CircleTraceBehaviour;
import oobot.robot.example.DefaultBrain;
import oobot.robot.example.HeartTraceBehaviour;
import oobot.robot.example.SquareTraceBehaviour;
import oobot.robot.example.WallAvoidBehaviour;
import oobot.robot.example.WallFollowRightBehaviour;
import oobot.robot.example.WheeledMover;

/**
 * Entry point for the robot controller process.
 * <p>
 * Usage: {@code java -jar robot.jar --host localhost --port <ROBOT_PORT>
 * [--behaviour square|wall-avoid|wall-follow-right|circle|heart]}
 *
 * <p>
 * Defaults: {@code --host localhost --port 8080 --behaviour square}. Pass the
 * sim's robot-channel port via {@code --port}.
 */
public class RobotController {
    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 8080;
    private static final String DEFAULT_BEHAVIOUR = "square";

    public static void main(String[] args) {
        String host = DEFAULT_HOST;
        int port = DEFAULT_PORT;
        String behaviourName = DEFAULT_BEHAVIOUR;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host":
                    if (i + 1 < args.length) {
                        host = args[++i];
                    }
                    break;
                case "--port":
                    if (i + 1 < args.length) {
                        try {
                            port = Integer.parseInt(args[++i]);
                        } catch (NumberFormatException e) {
                            System.err.println("[robot-controller] invalid port: " + args[i]);
                            System.exit(2);
                        }
                    }
                    break;
                case "--behaviour":
                    if (i + 1 < args.length) {
                        behaviourName = args[++i];
                    }
                    break;
                default:
                    System.err.println("[robot-controller] unknown argument: " + args[i]);
                    System.exit(2);
            }
        }

        System.out.println("[robot-controller] starting; connecting to ws://" + host + ":" + port);

        SimRobot robot = new SimRobot(host, port);

        // Build the robot-side mover from the sim bridge's wheels.
        Mover mover = new WheeledMover(robot.leftWheel(), robot.rightWheel());
        Sensors sensors = robot.sensors();

        RobotBehaviour behaviour = createBehaviour(behaviourName, mover, sensors);

        Brain brain = new DefaultBrain(mover, sensors);
        brain.setBehaviour(behaviour);

        Runtime.getRuntime().addShutdownHook(new Thread(robot::stop, "robot-shutdown"));

        robot.run(brain);
        System.out.println("[robot-controller] behaviour loop running at ~20 Hz");

        while (robot.isRunning()) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        System.out.println("[robot-controller] shutdown complete");
    }

    private static RobotBehaviour createBehaviour(String name, Mover mover, Sensors sensors) {
        switch (name) {
            case "square":
                return new SquareTraceBehaviour(mover, sensors);
            case "wall-avoid":
                return new WallAvoidBehaviour(mover, sensors);
            case "wall-follow-right":
                return new WallFollowRightBehaviour(mover, sensors);
            case "circle":
                return new CircleTraceBehaviour(mover, sensors);
            case "heart":
                return new HeartTraceBehaviour(mover, sensors);
            default:
                System.err.println("[robot-controller] unknown behaviour: " + name);
                System.exit(2);
                return null; // unreachable
        }
    }
}
