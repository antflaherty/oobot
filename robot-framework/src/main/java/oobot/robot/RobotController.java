package oobot.robot;

/**
 * Entry point for the robot controller process.
 * <p>
 * Usage: {@code java -jar robot.jar --host localhost --port <ROBOT_PORT>
 * [--behaviour square|wall-avoid]}
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

        RobotBehaviour behaviour = new SquareTraceBehaviour();
        switch (behaviourName) {
            case "square":
                behaviour = new SquareTraceBehaviour();
                break;
            case "wall-avoid":
                behaviour = new WallAvoidBehaviour();
                break;
            case "wall-follow-right":
                behaviour = new WallFollowRightBehaviour();
                break;
            default:
                System.err.println("[robot-controller] unknown behaviour: " + behaviourName);
                System.exit(2);
        }
        Framework framework = new Framework(host, port, behaviour);
        WheeledMover.setFramework(framework);
        Sensors.setFramework(framework);

        Runtime.getRuntime().addShutdownHook(new Thread(framework::stop, "robot-shutdown"));

        framework.start();
        System.out.println("[robot-controller] behaviour loop running at ~20 Hz");

        while (framework.isRunning()) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        System.out.println("[robot-controller] shutdown complete");
    }
}
