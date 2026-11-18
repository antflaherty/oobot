package oobot.robot.bridge.sim;

import oobot.robot.student.Tool;

import java.util.Objects;

/**
 * Sim-backed implementation of {@link Tool}. One instance per tool component,
 * identified by its manifest id. The persistent on/off state lives in
 * {@link SimRobot} alongside the wheel state; this handle only translates
 * behaviour calls into state changes and exposes the state to tests.
 */
class SimTool implements Tool {
    private final SimRobot robot;
    private final String id;

    SimTool(SimRobot robot, String id) {
        this.robot = Objects.requireNonNull(robot, "robot");
        this.id = Objects.requireNonNull(id, "id");
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public void activate() {
        robot.setToolValue(id, true);
    }

    @Override
    public void deactivate() {
        robot.setToolValue(id, false);
    }

    /** Package-private test seam: the bridge's current state for this tool. */
    boolean active() {
        return robot.toolActive(id);
    }
}
