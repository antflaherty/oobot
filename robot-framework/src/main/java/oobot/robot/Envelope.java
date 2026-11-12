package oobot.robot;

/**
 * Incoming wire envelope: {@code { type, seq, simTick, payload }}.
 * The framework only needs {@code type} and {@code simTick} from the sim's
 * {@code sensorSnapshot} messages in iteration 1.
 */
public final class Envelope {
    public final String type;
    public final long seq;
    public final long simTick;
    public final Object payload;

    public Envelope(String type, long seq, long simTick, Object payload) {
        this.type = type;
        this.seq = seq;
        this.simTick = simTick;
        this.payload = payload;
    }
}
