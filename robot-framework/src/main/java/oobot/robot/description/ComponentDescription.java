package oobot.robot.description;

/**
 * Declarative description of one component from a robot manifest.
 *
 * <p>{@code angle} is the component's mount angle in radians (0 = forward,
 * + = CCW). {@code offset} is the radial distance from the robot centre; it is
 * meaningful only for wheels, which must declare a finite positive value (or
 * omit it to use the default). Proximity sensors must not declare an offset.
 */
public record ComponentDescription(String id, ComponentType type, double angle, double offset) {}
