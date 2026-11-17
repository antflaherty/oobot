package oobot.robot.transport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JSON serialisation for the locked wire protocol, backed by Jackson.
 *
 * <p>Handles one NDJSON line at a time: writes {@code componentCommand} envelopes
 * and parses inbound envelopes into sealed {@link WireMessage} records.
 * Malformed input returns {@code null} (log and ignore; never crash). Unknown
 * properties are tolerated so spec-permitted extra fields do not break parsing.
 */
public final class Json {
    private static final Logger log = LoggerFactory.getLogger(Json.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private Json() {}

    /**
     * Serialise a componentCommand envelope as one NDJSON line (with trailing newline).
     *
     * @throws IllegalStateException if the envelope cannot be serialised; callers
     *         that want to drop a failed frame must catch this explicitly
     */
    public static String writeComponentCommand(long seq, long simTick, List<ComponentValue> commands) {
        try {
            return MAPPER.writeValueAsString(
                    new ComponentCommandMessage(seq, simTick, new ComponentCommandPayload(commands))) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise componentCommand", e);
        }
    }

    /**
     * Parse one NDJSON line into a sealed {@link WireMessage}.
     * Returns {@code null} if the line is malformed or of an unknown type.
     *
     * <p>An inbound {@code componentCommand} is not consumed by the robot: it is
     * logged at debug here so callers can discard it explicitly rather than
     * silently (see {@code SimRobot.handleIncoming}).
     */
    public static WireMessage parseMessage(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        try {
            WireMessage message = MAPPER.readValue(line, WireMessage.class);
            if (message instanceof ComponentCommandMessage) {
                log.debug("Rejected inbound componentCommand (robot does not consume commands): {}", line.trim());
            }
            return message;
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.debug("Malformed wire message ignored: {}", line, e);
            return null;
        }
    }
}
