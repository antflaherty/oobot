package oobot.robot.transport;

import java.util.List;

/** Payload of an inbound {@code componentSnapshot} envelope: sim → robot. */
public record ComponentSnapshotPayload(List<ComponentReading> readings) {
}
