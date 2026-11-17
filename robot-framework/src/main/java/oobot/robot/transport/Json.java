package oobot.robot.transport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JSON serialisation for the locked wire protocol, backed by Jackson.
 *
 * <p>Handles one NDJSON line at a time: writes {@code componentCommand} envelopes
 * and parses inbound envelopes into sealed {@link WireMessage} records.
 * Malformed input returns {@code null} (log and ignore; never crash).
 */
public final class Json {
    private static final Logger log = LoggerFactory.getLogger(Json.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {}

    /** Serialise a componentCommand envelope as one NDJSON line (with trailing newline). */
    public static String writeComponentCommand(long seq, long simTick, List<ComponentValue> commands) {
        try {
            return MAPPER.writeValueAsString(
                    new ComponentCommandMessage(seq, simTick, new ComponentCommandPayload(commands))) + "\n";
        } catch (JsonProcessingException e) {
            log.error("Failed to serialise componentCommand", e);
            return "";
        }
    }

    /**
     * Parse one NDJSON line into a sealed {@link WireMessage}.
     * Returns {@code null} if the line is malformed or of an unknown type.
     */
    public static WireMessage parseMessage(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(line, WireMessage.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.debug("Malformed wire message ignored: {}", line, e);
            return null;
        }
    }
}
