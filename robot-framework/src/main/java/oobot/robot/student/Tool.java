package oobot.robot.student;

/**
 * Generic on/off tool component (student-facing API).
 * <p>
 * A tool is a simple addressable actuator: behaviours switch it on or off via
 * {@link #activate()} / {@link #deactivate()}. It carries no notion of what the
 * tool physically is (a light, a buzzer, ...); that is decided by the manifest
 * and the bridge. Implementations are injected by the bridge, so robot code
 * never touches sockets or raw wire values.
 * <p>
 * {@link #NOOP} is a safe, do-nothing implementation for manifests that declare
 * no tool; it lets {@link Brain#tool()} always return a usable reference.
 */
public interface Tool extends Component {
    /** A no-op tool used when the manifest declares no tool. */
    Tool NOOP = new Tool() {
        @Override
        public String id() {
            return "none";
        }

        @Override
        public void activate() {
            // intentionally empty
        }

        @Override
        public void deactivate() {
            // intentionally empty
        }
    };

    /** Switch the tool on. Repeated calls are harmless. */
    void activate();

    /** Switch the tool off. Repeated calls are harmless. */
    void deactivate();
}
