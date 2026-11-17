package oobot.robot.transport;

/** Inbound {@code sensorSnapshot} envelope: sim → robot. */
public record SensorSnapshotMessage(long seq, long simTick, SensorPayload payload)
        implements WireMessage {
}
