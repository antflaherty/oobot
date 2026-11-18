package oobot.robot.description;

/**
 * Declarative description of one component from a robot manifest.
 *
 * <p>{@code angle} is the component's mount angle in radians (0 = forward,
 * + = CCW) and is required and finite for every type. {@code offset} is the
 * radial distance from the robot centre; it is meaningful only for wheels,
 * which may declare a finite positive value (or omit it to use the default).
 * Proximity sensors and tools must not declare an offset and carry {@code 0.0}
 * here.
 */
public record ComponentDescription(String id, ComponentType type, double angle, double offset) {}
