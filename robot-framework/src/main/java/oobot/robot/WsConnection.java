package oobot.robot;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Random;

/**
 * Minimal RFC 6455 WebSocket client over a raw TCP socket.
 * <p>
 * Used by the robot framework so robot code never touches sockets directly.
 * Only the features needed by the MVP are implemented:
 * <ul>
 *   <li>HTTP upgrade handshake (Sec-WebSocket-Key / Version 13)</li>
 *   <li>client-to-server masked text frames (one NDJSON line per frame)</li>
 *   <li>server-to-client text frames, ping/pong, close</li>
 * </ul>
 * All client-to-server frames are masked, as required by RFC 6455.
 */
final class WsConnection {
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int HANDSHAKE_TIMEOUT_MS = 10000;

    private final String host;
    private final int port;
    private final WsListener listener;
    private final Random random = new Random();

    private Socket socket;
    private OutputStream out;
    private InputStream in;
    private final Object sendLock = new Object();
    private volatile boolean open = false;
    private volatile boolean closed = false;

    WsConnection(String host, int port, WsListener listener) {
        this.host = host;
        this.port = port;
        this.listener = listener;
    }

    /**
     * Blocking: performs TCP connect + WebSocket handshake, then starts the
     * read loop on its own thread.
     */
    void connect() throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), HANDSHAKE_TIMEOUT_MS);
        try {
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            out = socket.getOutputStream();
            in = socket.getInputStream();
            doHandshake();
            socket.setSoTimeout(0); // block on reads until data or close
            open = true;
            safeListener(listener::onOpen);
            new Thread(this::readLoop, "robot-ws-read-" + port).start();
        } catch (Throwable t) {
            close();
            throw t;
        }
    }

    /** Send one masked text frame (client-to-server frames must be masked). */
    void sendText(String text) {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        synchronized (sendLock) {
            if (!open) {
                return;
            }
            try {
                ByteArrayOutputStream frame = new ByteArrayOutputStream();
                frame.write(0x81); // FIN=1, opcode=1 (text)
                int len = payload.length;
                if (len < 126) {
                    frame.write(0x80 | len); // MASK=1
                } else if (len <= 0xFFFF) {
                    frame.write(0x80 | 126);
                    frame.write((len >>> 8) & 0xFF);
                    frame.write(len & 0xFF);
                } else {
                    frame.write(0x80 | 127);
                    for (int i = 7; i >= 0; i--) {
                        frame.write((len >>> (8 * i)) & 0xFF);
                    }
                }
                byte[] mask = new byte[4];
                random.nextBytes(mask);
                frame.write(mask);
                for (int i = 0; i < len; i++) {
                    frame.write((byte) (payload[i] ^ mask[i % 4]));
                }
                out.write(frame.toByteArray());
                out.flush();
            } catch (IOException e) {
                close();
            }
        }
    }

    boolean isOpen() {
        return open;
    }

    void close() {
        synchronized (sendLock) {
            open = false;
            closed = true;
        }
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
    }

    /* ---------------- handshake ---------------- */

    private void doHandshake() throws IOException {
        byte[] keyBytes = new byte[16];
        random.nextBytes(keyBytes);
        String key = Base64.getEncoder().encodeToString(keyBytes);
        String accept;
        try {
            accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + GUID).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IOException("SHA-1 unavailable", e);
        }
        String request = "GET / HTTP/1.1\r\n"
            + "Host: " + host + ":" + port + "\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: Upgrade\r\n"
            + "Sec-WebSocket-Key: " + key + "\r\n"
            + "Sec-WebSocket-Version: 13\r\n"
            + "\r\n";
        out.write(request.getBytes(StandardCharsets.UTF_8));
        out.flush();

        ByteArrayOutputStream response = new ByteArrayOutputStream();
        while (true) {
            int x = in.read();
            if (x == -1) {
                throw new IOException("EOF while reading WebSocket handshake response");
            }
            response.write(x);
            byte[] b = response.toByteArray();
            int n = b.length;
            if (n >= 4 && b[n - 4] == '\r' && b[n - 3] == '\n'
                && b[n - 2] == '\r' && b[n - 1] == '\n') {
                break;
            }
        }
        String[] lines = response.toString(StandardCharsets.UTF_8).split("\r\n");
        if (lines.length == 0 || !lines[0].contains(" 101 ")) {
            throw new IOException("Expected HTTP 101, got: " + lines[0]);
        }
        String headerAccept = null;
        for (String l : lines) {
            if (l.regionMatches(true, 0, "Sec-WebSocket-Accept:", 0, 19)) {
                headerAccept = l.substring(l.indexOf(':') + 1).trim();
            }
        }
        if (headerAccept == null || !headerAccept.equals(accept)) {
            throw new IOException("Sec-WebSocket-Accept mismatch");
        }
    }

    /* ---------------- read loop ---------------- */

    private void readLoop() {
        StringBuilder pending = new StringBuilder();
        int pendingOpcode = -1;
        try {
            while (!closed) {
                Frame fr = readFrame();
                if (fr == null) {
                    break; // EOF -> connection closed
                }
                switch (fr.opcode) {
                    case 0x1: // text
                        if (fr.fin) {
                            deliver(new StringBuilder()
                                .append(new String(fr.payload, StandardCharsets.UTF_8)));
                        } else {
                            pendingOpcode = 0x1;
                            pending.append(new String(fr.payload, StandardCharsets.UTF_8));
                        }
                        break;
                    case 0x2: // binary: ignored by MVP
                        break;
                    case 0x0: // continuation
                        if (pendingOpcode != -1) {
                            pending.append(new String(fr.payload, StandardCharsets.UTF_8));
                            if (fr.fin) {
                                deliver(pending);
                                pending.setLength(0);
                                pendingOpcode = -1;
                            }
                        }
                        break;
                    case 0x8: // close
                        sendCloseFrame(fr.status);
                        closed = true;
                        break;
                    case 0x9: // ping -> pong
                        sendFrame(0xA, fr.payload);
                        break;
                    case 0xA: // pong
                        break;
                    default:
                        break;
                }
            }
        } catch (IOException e) {
            // connection dropped
        } finally {
            close();
            safeListener(listener::onClose);
        }
    }

    /** Split buffered text on newlines and deliver each complete NDJSON line. */
    private void deliver(StringBuilder text) {
        int i = 0;
        while (i < text.length()) {
            int j = text.indexOf("\n", i);
            if (j == -1) {
                break;
            }
            String line = text.substring(i, j).trim();
            i = j + 1;
            if (!line.isEmpty()) {
                safeListener(() -> listener.onMessage(line));
            }
        }
        if (i > 0) {
            text.delete(0, i);
        }
    }

    private void readFully(byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n == -1) {
                throw new EOFException("EOF after " + total + "/" + buf.length + " bytes");
            }
            total += n;
        }
    }

    private Frame readFrame() throws IOException {
        int b0 = in.read();
        int b1 = in.read();
        if (b0 == -1 || b1 == -1) {
            return null;
        }
        boolean fin = (b0 & 0x80) != 0;
        int opcode = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;
        if (len == 126) {
            byte[] ext = new byte[2];
            readFully(ext);
            len = ((ext[0] & 0xFFL) << 8) | (ext[1] & 0xFFL);
        } else if (len == 127) {
            byte[] ext = new byte[8];
            readFully(ext);
            long h = 0;
            for (byte b : ext) {
                h = (h << 8) | (b & 0xFFL);
            }
            len = h;
        }
        byte[] mask = null;
        if (masked) {
            mask = new byte[4];
            readFully(mask);
        }
        long cap = Math.min(len, 1 << 20); // guard against absurd length fields
        byte[] payload = new byte[(int) cap];
        readFully(payload);
        if (masked) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= mask[i % 4];
            }
        }
        int status = -1;
        if (opcode == 0x8 && payload.length >= 2) {
            status = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
        }
        return new Frame(fin, opcode, payload, status);
    }

    /* ---------------- outbound framing ---------------- */

    private void sendFrame(int opcode, byte[] payload) {
        synchronized (sendLock) {
            if (!open) {
                return;
            }
            try {
                ByteArrayOutputStream frame = new ByteArrayOutputStream();
                frame.write(0x80 | opcode); // FIN=1
                int len = payload.length;
                if (len < 126) {
                    frame.write(0x80 | len); // MASK=1 (client->server)
                } else if (len <= 0xFFFF) {
                    frame.write(0x80 | 126);
                    frame.write((len >>> 8) & 0xFF);
                    frame.write(len & 0xFF);
                } else {
                    frame.write(0x80 | 127);
                    for (int i = 7; i >= 0; i--) {
                        frame.write((len >>> (8 * i)) & 0xFF);
                    }
                }
                byte[] mask = new byte[4];
                random.nextBytes(mask);
                frame.write(mask);
                for (int i = 0; i < len; i++) {
                    frame.write((byte) (payload[i] ^ mask[i % 4]));
                }
                out.write(frame.toByteArray());
                out.flush();
            } catch (IOException e) {
                close();
            }
        }
    }

    private void sendCloseFrame(int status) {
        byte[] payload;
        if (status >= 0) {
            payload = new byte[] {
                (byte) ((status >>> 8) & 0xFF),
                (byte) (status & 0xFF)
            };
        } else {
            payload = new byte[0];
        }
        sendFrame(0x8, payload);
    }

    private static void safeListener(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            // a listener must never kill the read thread
        }
    }

    private static final class Frame {
        final boolean fin;
        final int opcode;
        final byte[] payload;
        final int status;

        Frame(boolean fin, int opcode, byte[] payload, int status) {
            this.fin = fin;
            this.opcode = opcode;
            this.payload = payload;
            this.status = status;
        }
    }
}
