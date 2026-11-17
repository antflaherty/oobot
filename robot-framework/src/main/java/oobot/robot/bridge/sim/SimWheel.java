package oobot.robot.bridge.sim;

import oobot.robot.student.Wheel;

/**
 * Sim-backed implementation of {@link Wheel}. One instance per wheel (left or
 * right). {@code Wheel} is a raw rotation-direction abstraction — clockwise /
 * anti-clockwise does not mean "forward" / "backward". The translation to sim
 * speed ({@code [-1, 1]}) depends on how the wheel is mounted on the chassis,
 * which this instance knows.
 * <p>
 * Turning clockwise sets that wheel's sim speed, anti-clockwise sets the
 * opposite, and stop sets zero. The combined {@code motorCommand} is sent to
 * the sim on every change.
 */
class SimWheel implements Wheel {
    private final SimRobot robot;
    private final boolean left;

    SimWheel(SimRobot robot, boolean left) {
        this.robot = robot;
        this.left = left;
    }

    @Override
    public void clockwise() {
        // On a left wheel, clockwise rotation translates to +1.0 sim speed.
        // On a right wheel, clockwise rotation translates to -1.0 sim speed
        // (mirror image). The sim interprets +1.0 as forward on each side.
        double speed = left ? 1.0 : -1.0;
        if (left) {
            robot.setLeftSpeed(speed);
        } else {
            robot.setRightSpeed(speed);
        }
        robot.wheelChanged();
    }

    @Override
    public void antiClockwise() {
        double speed = left ? -1.0 : 1.0;
        if (left) {
            robot.setLeftSpeed(speed);
        } else {
            robot.setRightSpeed(speed);
        }
        robot.wheelChanged();
    }

    @Override
    public void stop() {
        if (left) {
            robot.setLeftSpeed(0.0);
        } else {
            robot.setRightSpeed(0.0);
        }
        robot.wheelChanged();
    }
}
