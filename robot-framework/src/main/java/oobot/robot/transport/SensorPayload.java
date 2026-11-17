package oobot.robot.transport;

/**
 * Proximity readings; {@code null} means nothing detected in that direction.
 */
public record SensorPayload(Double front, Double right) {
}
