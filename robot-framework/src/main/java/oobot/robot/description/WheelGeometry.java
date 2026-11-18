package oobot.robot.description;

/**
 * Physical dimensions of one wheel, in metres, from the manifest's
 * {@code body.wheels} block.
 *
 * <p>{@code length} is the wheel's extent along the rolling direction and
 * {@code width} its extent across it. Both must be finite and positive.
 */
public record WheelGeometry(double length, double width) {

    /** Applied when {@code body.wheels} (or a member of it) is omitted. */
    public static final WheelGeometry DEFAULT = new WheelGeometry(0.3, 0.1);
}
