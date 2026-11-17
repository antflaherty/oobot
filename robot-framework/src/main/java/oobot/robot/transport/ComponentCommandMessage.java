package oobot.robot.transport;

/** Outbound {@code componentCommand} envelope: robot → sim. */
public record ComponentCommandMessage(long seq, long simTick, ComponentCommandPayload payload)
        implements WireMessage {
}
