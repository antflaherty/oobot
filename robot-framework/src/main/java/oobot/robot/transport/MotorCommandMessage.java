package oobot.robot.transport;

/** Outbound {@code motorCommand} envelope: robot → sim. */
public record MotorCommandMessage(long seq, long simTick, MotorPayload payload)
        implements WireMessage {
}
