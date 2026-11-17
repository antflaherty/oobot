package oobot.robot.transport;

/** Inbound {@code componentSnapshot} envelope: sim → robot. */
public record ComponentSnapshotMessage(long seq, long simTick, ComponentSnapshotPayload payload)
        implements WireMessage {
}
