package oobot.robot.transport;

import java.util.List;

/** Payload of an outbound {@code componentCommand} envelope: robot → sim. */
public record ComponentCommandPayload(List<ComponentValue> commands) {
}
