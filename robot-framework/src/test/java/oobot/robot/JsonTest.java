package oobot.robot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import oobot.robot.transport.ComponentCommandMessage;
import oobot.robot.transport.ComponentValue;
import oobot.robot.transport.Json;
import oobot.robot.transport.WireMessage;
import org.junit.jupiter.api.Test;

/** Pure unit tests for command serialisation; no network or sim dependency. */
class JsonTest {

    @Test
    void writtenComponentCommandParsesBackWithSeqTickAndValues() {
        List<ComponentValue> commands = List.of(
                new ComponentValue("left-wheel", 1.0),
                new ComponentValue("right-wheel", -0.5));

        String line = Json.writeComponentCommand(7L, 42L, commands);

        assertTrue(line.endsWith("\n"), "one NDJSON line must end with a newline");
        WireMessage parsed = Json.parseMessage(line);
        ComponentCommandMessage message = assertInstanceOf(ComponentCommandMessage.class, parsed);
        assertEquals(7L, message.seq());
        assertEquals(42L, message.simTick());
        assertEquals(commands, message.payload().commands());
    }
}
