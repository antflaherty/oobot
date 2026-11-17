package oobot.robot.transport;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Sealed hierarchy of wire-protocol envelopes. Jackson uses the {@code type}
 * property to select the concrete record when deserialising an NDJSON line.
 *
 * <p>Wire format: {@code {"type":"...", "seq":N, "simTick":N, "payload":{...}}}
 * with the fields in exactly that order (see {@code specs/WIRE_PROTOCOL.md}).
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ComponentCommandMessage.class, name = "componentCommand"),
    @JsonSubTypes.Type(value = ComponentSnapshotMessage.class, name = "componentSnapshot")
})
public sealed interface WireMessage permits ComponentCommandMessage, ComponentSnapshotMessage {
    long seq();
    long simTick();
}
