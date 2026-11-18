package oobot.robot.description;

/**
 * Physical body dimensions in metres, from the manifest's optional
 * {@code body} block.
 *
 * <p>{@code width} and {@code height} describe the square/rectangular chassis;
 * {@code wheels} describes a single wheel's geometry. Every dimension must be
 * finite and positive. Missing keys inherit {@link #DEFAULT}.
 */
public record RobotBody(double width, double height, WheelGeometry wheels) {

    /** Applied when the manifest omits {@code body} (or one of its members). */
    public static final RobotBody DEFAULT =
            new RobotBody(0.5, 0.5, WheelGeometry.DEFAULT);
}
