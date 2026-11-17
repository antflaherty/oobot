package oobot.robot.transport;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket client backed by OkHttp, with NDJSON framing on inbound messages.
 *
 * <p>Preserves the blocking {@link #connect()} / {@link #sendText(String)} /
 * {@link #isOpen()} / {@link #close()} API expected by the sim bridge, so the
 * bridge does not need to know which WebSocket library is in use.
 */
public class WsConnection {
    private static final Logger log = LoggerFactory.getLogger(WsConnection.class);
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int CLOSE_CODE_NORMAL = 1000;
    private static final OkHttpClient SHARED_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build();

    private final String url;
    private final WsListener listener;
    private volatile WebSocket webSocket;
    private volatile boolean open = false;
    private final CountDownLatch connectLatch = new CountDownLatch(1);
    private final StringBuilder pending = new StringBuilder();
    private final Object pendingLock = new Object();

    public WsConnection(String host, int port, WsListener listener) {
        this.url = "ws://" + host + ":" + port;
        this.listener = listener;
    }

    /** Blocking: connects and waits for the WebSocket open (or failure/timeout). */
    public void connect() throws IOException {
        Request request = new Request.Builder().url(url).build();
        SHARED_CLIENT.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                webSocket = ws;
                open = true;
                connectLatch.countDown();
                safeListener(listener::onOpen);
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                deliverLines(text);
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                ws.close(code, reason);
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                open = false;
                safeListener(listener::onClose);
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                open = false;
                connectLatch.countDown();
                safeListener(() -> listener.onError(t));
                safeListener(listener::onClose);
            }
        });

        try {
            if (!connectLatch.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                close();
                throw new IOException("WebSocket connect timed out: " + url);
            }
            if (!open) {
                throw new IOException("WebSocket connect failed: " + url);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            close();
            throw new IOException("Interrupted while connecting to " + url, e);
        }
    }

    /** Send one NDJSON line as a WebSocket text message (thread-safe in OkHttp). */
    public void sendText(String text) {
        WebSocket ws = webSocket;
        if (open && ws != null) {
            ws.send(text);
        }
    }

    public boolean isOpen() {
        return open;
    }

    public void close() {
        open = false;
        WebSocket ws = webSocket;
        if (ws != null) {
            ws.close(CLOSE_CODE_NORMAL, "shutdown");
        }
    }

    /** Buffer text across WebSocket frames and deliver one complete NDJSON line at a time. */
    private void deliverLines(String text) {
        synchronized (pendingLock) {
            pending.append(text);
            int start = 0;
            int nl;
            while ((nl = pending.indexOf("\n", start)) != -1) {
                String line = pending.substring(start, nl).trim();
                start = nl + 1;
                if (!line.isEmpty()) {
                    final String message = line;
                    safeListener(() -> listener.onMessage(message));
                }
            }
            pending.delete(0, start);
        }
    }

    private static void safeListener(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            log.warn("WebSocket listener callback threw", t);
        }
    }
}
