package oobot.robot.transport;

import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/** Builds {@link WsConnection} instances backed by {@link FakeWebSocket}; no sockets. */
public final class FakeConnections {
    @FunctionalInterface
    public interface ConnectorHook {
        WebSocket connect(WebSocketListener listener, FakeWebSocket socket);
    }

    private FakeConnections() {}

    /** A connection whose fake socket is opened synchronously during connect(). */
    public static WsConnection connected(WsListener listener, FakeWebSocket socket) {
        return create(listener, socket, (wsListener, fake) -> {
            fake.attach(wsListener);
            wsListener.onOpen(fake, null);
            return fake;
        });
    }

    public static WsConnection create(WsListener listener, FakeWebSocket socket, ConnectorHook hook) {
        return new WsConnection("localhost", 8080, listener,
                (request, wsListener) -> hook.connect(wsListener, socket));
    }
}
