package oobot.robot.bridge;

public interface RobotPlatform extends AutoCloseable {
    oobot.robot.description.ComponentRegistry components();

    void run(oobot.robot.student.Brain brain);

    boolean isRunning();

    void stop();
}
