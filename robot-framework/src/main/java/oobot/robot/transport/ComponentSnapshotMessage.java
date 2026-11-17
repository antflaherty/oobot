package oobot.robot.transport;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** Inbound {@code componentSnapshot} envelope: sim → robot. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"type", "seq", "simTick", "payload"})
public record ComponentSnapshotMessage(long seq, long simTick, ComponentSnapshotPayload payload)
        implements WireMessage {

    /** Wire discriminator; read-only so deserialisation relies on {@code @JsonTypeInfo}. */
    @JsonProperty(value = "type", access = JsonProperty.Access.READ_ONLY)
    public String type() {
        return "componentSnapshot";
    }
}
