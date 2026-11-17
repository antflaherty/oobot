package oobot.robot.transport;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** Outbound {@code componentCommand} envelope: robot → sim. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"type", "seq", "simTick", "payload"})
public record ComponentCommandMessage(long seq, long simTick, ComponentCommandPayload payload)
        implements WireMessage {

    /** Wire discriminator; read-only so deserialisation relies on {@code @JsonTypeInfo}. */
    @JsonProperty(value = "type", access = JsonProperty.Access.READ_ONLY)
    public String type() {
        return "componentCommand";
    }
}
