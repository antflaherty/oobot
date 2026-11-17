package oobot.robot.transport;

/** An addressed component reading: component id and observed value (nullable). */
public record ComponentReading(String id, Double value) {
}
