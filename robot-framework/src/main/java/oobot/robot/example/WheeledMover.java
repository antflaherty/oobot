package oobot.robot.example;

import oobot.robot.student.Mover;
import oobot.robot.student.Wheel;

/**
 * Differential-drive implementation of {@link Mover} backed by two {@link
 * Wheel}s. Translates high-level movement commands into per-wheel clockwise /
 * anti-clockwise rotations.
 * <p>
 * This is the <em>only</em> place that knows how high-level commands map to
 * per-wheel actions, including the per-side mounting translation. Behaviour
 * code never sees wheels directly unless it chooses to via {@link
 * oobot.robot.student.Brain#leftWheel()} / {@link oobot.robot.student.Brain#rightWheel()}
 * (not currently exposed — Brain holds only Mover and Sensors).
 * <p>
 * Swap this for a {@code LeggedMover} or hardware-backed implementation
 * without touching behaviour code.
 */
public class WheeledMover implements Mover {
    private final Wheel left;
    private final Wheel right;

    public WheeledMover(Wheel left, Wheel right) {
        this.left = left;
        this.right = right;
    }

    @Override
    public void forward() {
        // Left wheel clockwise = +1 sim speed (forward on left side).
        // Right wheel anti-clockwise = +1 sim speed (forward on right side).
        left.clockwise();
        right.antiClockwise();
    }

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
