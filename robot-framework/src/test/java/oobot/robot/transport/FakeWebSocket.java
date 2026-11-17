package oobot.robot.transport;

import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** In-memory {@link WebSocket} for lifecycle tests; records sends and closes in order. */
public final class FakeWebSocket implements WebSocket {
    private final List<String> events = Collections.synchronizedList(new ArrayList<>());
    private volatile WebSocketListener listener;

    public void attach(WebSocketListener listener) {
        this.listener = listener;
    }

    public WebSocketListener listener() {
        return listener;
    }

    /** All recorded events in order: {@code send <frame>} and {@code close <code>}. */
    public List<String> events() {
        synchronized (events) {
            return List.copyOf(events);
        }
    }

    public List<String> sentFrames() {
        return events().stream().filter(e -> e.startsWith("send ")).map(e -> e.substring(5)).toList();
    }

    @Override
    public Request request() {
        return new Request.Builder().url("http://localhost/").build();
    }

    @Override
    public long queueSize() {
        return 0;
    }

    @Override
    public boolean send(String text) {
        events.add("send " + text.trim());
        return true;
    }

    @Override
    public boolean send(ByteString bytes) {
        events.add("send <bytes>");
        return true;
    }

    @Override
    public boolean close(int code, String reason) {
        events.add("close " + code);
        return true;
    }

    @Override
    public void cancel() {
        events.add("cancel");
    }
}
