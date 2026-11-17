package oobot.robot.transport;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** An addressed component command: component id and commanded value. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ComponentValue(String id, double value) {
}
