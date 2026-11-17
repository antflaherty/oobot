package oobot.robot.transport;

/** An addressed component command: component id and commanded value. */
public record ComponentValue(String id, double value) {
}
