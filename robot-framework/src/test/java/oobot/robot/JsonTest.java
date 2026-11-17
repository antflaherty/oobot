package oobot.robot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.AbstractList;
import java.util.Iterator;
import java.util.List;
import oobot.robot.transport.ComponentCommandMessage;
import oobot.robot.transport.ComponentReading;
import oobot.robot.transport.ComponentSnapshotMessage;
import oobot.robot.transport.ComponentValue;
import oobot.robot.transport.Json;
import oobot.robot.transport.WireMessage;
import org.junit.jupiter.api.Test;

/** Pure unit tests for wire JSON; no network or sim dependency. */
class JsonTest {

    @Test
    void writtenComponentCommandParsesBackWithSeqTickAndValues() {
        List<ComponentValue> commands = List.of(
                new ComponentValue("left-wheel", 1.0),
                new ComponentValue("right-wheel", -0.5));

        String line = Json.writeComponentCommand(7L, 42L, commands);

        assertEquals(
                "{\"type\":\"componentCommand\",\"seq\":7,\"simTick\":42,\"payload\":{\"commands\":["
                        + "{\"id\":\"left-wheel\",\"value\":1.0},"
                        + "{\"id\":\"right-wheel\",\"value\":-0.5}]}}\n",
                line, "envelope fields must be in wire-protocol order");
        assertTrue(line.endsWith("\n"), "one NDJSON line must end with a newline");
        WireMessage parsed = Json.parseMessage(line);
        ComponentCommandMessage message = assertInstanceOf(ComponentCommandMessage.class, parsed);
        assertEquals("componentCommand", message.type());
        assertEquals(7L, message.seq());
        assertEquals(42L, message.simTick());
        assertEquals(commands, message.payload().commands());
    }

    @Test
    void componentCommandWithUnknownFieldsStillParses() {
        // Round-trip of a written command with spec-permitted extra fields injected.
        String line = "{\"type\":\"componentCommand\",\"seq\":7,\"simTick\":42,"
                + "\"futureEnvelope\":true,"
                + "\"payload\":{\"commands\":[{\"id\":\"left-wheel\",\"value\":1.0,\"futureEntry\":true}],"
                + "\"futurePayload\":42}}";

        WireMessage parsed = Json.parseMessage(line);

        ComponentCommandMessage message = assertInstanceOf(ComponentCommandMessage.class, parsed);
        assertEquals("componentCommand", message.type());
        assertEquals(7L, message.seq());
        assertEquals(42L, message.simTick());
        assertEquals(List.of(new ComponentValue("left-wheel", 1.0)), message.payload().commands());
    }

    @Test
    void snapshotWithUnknownFieldsStillParsesReadings() {
        String line = """
                {"type":"componentSnapshot","seq":3,"simTick":99,"futureEnvelope":true,
                 "payload":{"readings":[{"id":"front-sensor","value":3.5,"futureEntry":true},
                                        {"id":"right-sensor","value":null}],
                            "futurePayload":42}}
                """;

        WireMessage parsed = Json.parseMessage(line);

        ComponentSnapshotMessage snapshot = assertInstanceOf(ComponentSnapshotMessage.class, parsed);
        assertEquals("componentSnapshot", snapshot.type());
        assertEquals(3L, snapshot.seq());
        assertEquals(99L, snapshot.simTick());
        assertEquals(
                List.of(new ComponentReading("front-sensor", 3.5), new ComponentReading("right-sensor", null)),
                snapshot.payload().readings());
    }

    @Test
    void serialisationFailureThrowsInsteadOfEmittingEmptyFrame() {
        List<ComponentValue> broken = new AbstractList<>() {
            @Override
            public ComponentValue get(int index) {
                throw new AssertionError("not used");
            }

            @Override
            public int size() {
                return 1;
            }

            @Override
            public Iterator<ComponentValue> iterator() {
                throw new IllegalStateException("simulated serialisation failure");
            }
        };

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> Json.writeComponentCommand(1L, 2L, broken));
        assertTrue(thrown.getMessage().contains("Failed to serialise componentCommand"));
        assertNotNull(thrown.getCause(), "the original serialisation failure must be preserved");
    }
}
