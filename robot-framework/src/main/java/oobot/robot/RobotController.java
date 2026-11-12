package oobot.robot;

/**
 * Entry point for the robot controller process.
 * <p>
 * Usage: {@code java -jar robot.jar --host localhost --port <ROBOT_PORT>}
 *
 * <p>
 * Defaults: {@code --host localhost --port 8080}. Pass the sim's robot-channel
 * port via {@code --port}.
 */
public class RobotController {
    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 8080;

    public static void main(String[] args) {
        String host = DEFAULT_HOST;
        int port = DEFAULT_PORT;

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
                default:
                    System.err.println("[robot-controller] unknown argument: " + args[i]);
                    System.exit(2);
            }
        }

        System.out.println("[robot-controller] starting; connecting to ws://" + host + ":" + port);

        RobotBehaviour behaviour = new SquareTraceBehaviour();
        // RobotBehaviour behaviour = new CircleTraceBehaviour(); // easy swap back to circle
        // RobotBehaviour behaviour = new HeartTraceBehaviour();
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
