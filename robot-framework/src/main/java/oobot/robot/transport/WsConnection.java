package oobot.robot.transport;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket client backed by OkHttp, with NDJSON framing on inbound messages.
 *
 * <p>Preserves the blocking {@link #connect()} / {@link #sendText(String)} /
 * {@link #isOpen()} / {@link #close()} API expected by the sim bridge, so the
 * bridge does not need to know which WebSocket library is in use.
 *
 * <p>For orderly shutdown the bridge uses {@link #closeWithFinalFrame(String)}:
 * the supplied frame is written while {@link #isOpen()} is still {@code true},
 * and only then is the socket closed (D-2 layer B). An inbound close delivers
 * {@link WsListener#onClose()} before {@code open} is cleared for the same
 * reason.
 */
public class WsConnection {
    private static final Logger log = LoggerFactory.getLogger(WsConnection.class);
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int CLOSE_CODE_NORMAL = 1000;
    private static final OkHttpClient SHARED_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build();

    /** Test seam: creates and returns a WebSocket with a listener attached. */
    @FunctionalInterface
    interface Connector {
        WebSocket connect(Request request, WebSocketListener listener);
    }

    private final String url;
    private final WsListener listener;
    private final Connector connector;
    private final CountDownLatch connectLatch = new CountDownLatch(1);
    private final Object closeLock = new Object();
    private final StringBuilder pending = new StringBuilder();
    private final Object pendingLock = new Object();

    private volatile WebSocket webSocket;
    private volatile boolean open = false;
    private volatile boolean closeDelivered = false;
    private volatile boolean finalFrameSent = false;
    private volatile boolean closeCalled = false;

    public WsConnection(String host, int port, WsListener listener) {
        this(host, port, listener, (request, wsListener) -> SHARED_CLIENT.newWebSocket(request, wsListener));
    }

    WsConnection(String host, int port, WsListener listener, Connector connector) {
        this.url = "ws://" + host + ":" + port;
        this.listener = Objects.requireNonNull(listener, "listener");
        this.connector = Objects.requireNonNull(connector, "connector");
    }

    /** Blocking: connects and waits for the WebSocket open (or failure/timeout). */
    public void connect() throws IOException {
        Request request = new Request.Builder().url(url).build();
        WebSocket created = connector.connect(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                synchronized (closeLock) {
                    if (closeCalled) {
                        // stop() won the race with the handshake; never report this as open.
                        ws.close(CLOSE_CODE_NORMAL, "shutdown");
                        return;
                    }
                    webSocket = ws;
                    open = true;
                }
                try {
                    // Run the bridge callback before releasing connect(): the bridge
                    // can then publish/reset per-connection state deterministically.
                    safeListener(listener::onOpen);
                } finally {
                    connectLatch.countDown();
                }
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
                // Deliver first: the bridge writes its zero command while open is
                // still true. Never send on a socket that we have already marked closed.
                deliverCloseOnce();
                markClosed();
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                try {
                    safeListener(() -> listener.onError(t));
                    deliverCloseOnce();
                } finally {
                    // Unblock a concurrent connect() even if a listener misbehaves.
                    connectLatch.countDown();
                    markClosed();
                }
            }
        });
        // Register the socket as soon as it exists, even before onOpen: stop()
        // must be able to close a connection whose handshake is still in flight.
        synchronized (closeLock) {
            if (!closeCalled) {
                webSocket = created;
            }
        }

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
        synchronized (closeLock) {
            if (!open || ws == null || finalFrameSent) {
                return;
            }
            ws.send(text);
        }
    }

    public boolean isOpen() {
        return open;
    }

    /**
     * Write one final frame while the socket is still open, then close it.
     * Idempotent: at most one final frame and one close are issued.
     */
    public void closeWithFinalFrame(String finalFrame) {
        WebSocket ws;
        synchronized (closeLock) {
            ws = webSocket;
            if (open && ws != null && !finalFrameSent) {
                if (finalFrame != null && !finalFrame.isEmpty()) {
                    ws.send(finalFrame);
                }
                finalFrameSent = true;
                open = false;
            }
            // A close may be racing an in-flight connect(); unblock it either way.
            connectLatch.countDown();
            if (ws != null && !closeCalled) {
                closeCalled = true;
                ws.close(CLOSE_CODE_NORMAL, "shutdown");
            }
        }
    }

    /** Close without a final frame (used for failed/aborted connects). */
    public void close() {
        closeWithFinalFrame(null);
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

    private void deliverCloseOnce() {
        boolean deliver;
        synchronized (closeLock) {
            deliver = !closeDelivered;
            closeDelivered = true;
        }
        if (deliver) {
            safeListener(listener::onClose);
        }
    }

    private void markClosed() {
        synchronized (closeLock) {
            open = false;
        }
    }

    private static void safeListener(Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            log.warn("WebSocket listener callback threw", e);
        }
    }
}
