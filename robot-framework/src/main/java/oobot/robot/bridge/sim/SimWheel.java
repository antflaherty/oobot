package oobot.robot.bridge.sim;

import oobot.robot.student.Wheel;

/**
 * Sim-backed implementation of {@link Wheel}. One instance per wheel,
 * identified by its component id and mount angle. {@code Wheel} is a raw
 * rotation-direction abstraction — clockwise / anti-clockwise does not mean
 * "forward" / "backward". The translation to sim speed ({@code [-1, 1]})
 * depends on how the wheel is mounted on the chassis, which this instance
 * knows via its mount angle.
 */
class SimWheel implements Wheel {
    private final SimRobot robot;
    private final String id;
    private final double angle;

    SimWheel(SimRobot robot, String id, double angle) {
        this.robot = robot;
        this.id = id;
        this.angle = angle;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public double angle() {
        return angle;
    }

    @Override
    public void clockwise() {
        apply(Math.sin(angle) > 0 ? 1.0 : -1.0);
    }

    @Override
    public void antiClockwise() {
        apply(Math.sin(angle) > 0 ? -1.0 : 1.0);
    }

    @Override
    public void stop() {
        apply(0.0);
    }

    private void apply(double speed) {
        robot.setWheelValue(id, speed);
        robot.wheelChanged();
    }
}
