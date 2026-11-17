package oobot.robot.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Pure unit tests for the connection close contract; no network or sim needed. */
class WsConnectionTest {

    @Test
    void closeWithFinalFrameWritesFrameBeforeCloseAndIsIdempotent() throws IOException {
        FakeWebSocket socket = new FakeWebSocket();
        WsConnection connection = FakeConnections.connected(noOpListener(), socket);
        connection.connect();
        assertTrue(connection.isOpen());

        connection.closeWithFinalFrame("final-frame\n");

        assertEquals(List.of("send final-frame", "close 1000"), socket.events());
        assertFalse(connection.isOpen());

        // A second close must not emit another frame or close.
        connection.closeWithFinalFrame("another-frame\n");
        assertEquals(List.of("send final-frame", "close 1000"), socket.events());
    }

    @Test
    void onCloseIsDeliveredBeforeOpenIsCleared() throws IOException {
        FakeWebSocket socket = new FakeWebSocket();
        AtomicReference<WsConnection> ref = new AtomicReference<>();
        AtomicBoolean openDuringClose = new AtomicBoolean(false);
        ref.set(FakeConnections.connected(new WsListener() {
            @Override public void onOpen() {}
            @Override public void onMessage(String line) {}
            @Override public void onClose() { openDuringClose.set(ref.get().isOpen()); }
            @Override public void onError(Throwable t) {}
        }, socket));
        ref.get().connect();

        socket.listener().onClosed(socket, 1000, "peer");

        assertTrue(openDuringClose.get(), "bridge must be able to send its zero command in onClose");
        assertFalse(ref.get().isOpen());
    }

    private static WsListener noOpListener() {
        return new WsListener() {
            @Override public void onOpen() {}
            @Override public void onMessage(String line) {}
            @Override public void onClose() {}
            @Override public void onError(Throwable t) {}
        };
    }
}
