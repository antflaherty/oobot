package oobot.robot.student;

/**
 * Common contract for all addressable robot components (student-facing API).
 * <p>
 * The {@link #id()} identifies the component in the shared component manifest,
 * so behaviours and the sim can refer to a specific wheel or sensor without
 * depending on its concrete implementation.
 */
public interface Component {
    /** The stable component id used to address this component. */
    String id();
}
