package oobot.robot.example;

import oobot.robot.description.RobotDescription;
import oobot.robot.student.Mover;
import oobot.robot.student.Wheel;

import java.util.List;
import java.util.Objects;

/**
 * Differential-drive implementation of {@link Mover} backed by two {@link
 * Wheel}s. Translates high-level movement commands into per-wheel clockwise /
 * anti-clockwise rotations.
 * <p>
 * This is the <em>only</em> place that knows how high-level commands map to
 * per-wheel actions, including the per-side mounting translation. Behaviour
 * code never sees wheels directly; it drives them through a {@link Mover}
 * supplied by the {@link oobot.robot.student.Brain}.
 * <p>
 * Swap this for a {@code LeggedMover} or hardware-backed implementation
 * without touching behaviour code.
 */
public class WheeledMover implements Mover {
    private final Wheel left;
    private final Wheel right;

    /**
     * Create a mover from the manifest wheels. Roles are derived from mount
     * angle with the same threshold the manifest loader uses:
     * {@code sin(angle) > 0.9} is left, {@code sin(angle) < -0.9} is right.
     *
     * @throws IllegalArgumentException if the list does not contain exactly one
     *         left and one right wheel
     */
    public WheeledMover(List<Wheel> wheels) {
        Objects.requireNonNull(wheels, "wheels");
        if (wheels.size() != 2) {
            throw new IllegalArgumentException(
                    "WheeledMover requires exactly two wheels, got " + wheels.size());
        }

        Wheel first = Objects.requireNonNull(wheels.get(0), "wheel");
        Wheel second = Objects.requireNonNull(wheels.get(1), "wheel");
        Role firstRole = roleOf(first);
        Role secondRole = roleOf(second);
        if (firstRole == Role.INVALID || secondRole == Role.INVALID || firstRole == secondRole) {
            throw new IllegalArgumentException(
                    "WheeledMover requires exactly one left and one right wheel "
                    + "(sin(angle) > " + RobotDescription.WHEEL_ROLE_LATERAL_THRESHOLD
                    + " or sin(angle) < -" + RobotDescription.WHEEL_ROLE_LATERAL_THRESHOLD + ")");
        }
        boolean firstLeft = firstRole == Role.LEFT;
        this.left = firstLeft ? first : second;
        this.right = firstLeft ? second : first;
    }

    private enum Role { LEFT, RIGHT, INVALID }

    private static Role roleOf(Wheel wheel) {
        double lateral = Math.sin(wheel.angle());
        if (lateral > RobotDescription.WHEEL_ROLE_LATERAL_THRESHOLD) {
            return Role.LEFT;
        }
        if (lateral < -RobotDescription.WHEEL_ROLE_LATERAL_THRESHOLD) {
            return Role.RIGHT;
        }
        return Role.INVALID;
    }

    @Override
    public void forward() {
        // Left wheel clockwise = +1 sim speed (forward on left side).
        // Right wheel anti-clockwise = +1 sim speed (forward on right side).
        left.clockwise();
        right.antiClockwise();
    }

    /** Move backward at full speed: left anti-clockwise, right clockwise. */
    @Override
    public void backward() {
        left.antiClockwise();
        right.clockwise();
    }

    @Override
    public void turnRight() {
        // Robot spins right: left forward, right backward.
        left.clockwise();
        right.clockwise();
    }

    @Override
    public void turnLeft() {
        // Robot spins left: left backward, right forward.
        left.antiClockwise();
        right.antiClockwise();
    }

    @Override
    public void stop() {
        left.stop();
        right.stop();
    }
}
