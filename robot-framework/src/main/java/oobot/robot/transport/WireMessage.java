package oobot.robot.transport;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Sealed hierarchy of wire-protocol envelopes. Jackson uses the {@code type}
 * property to select the concrete record when deserialising an NDJSON line.
 *
 * <p>Wire format: {@code {"type":"...", "seq":N, "simTick":N, "payload":{...}}}
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = MotorCommandMessage.class, name = "motorCommand"),
    @JsonSubTypes.Type(value = SensorSnapshotMessage.class, name = "sensorSnapshot")
})
public sealed interface WireMessage permits MotorCommandMessage, SensorSnapshotMessage {
    long seq();
    long simTick();
}
