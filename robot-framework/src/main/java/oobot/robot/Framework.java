package oobot.robot;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Robot framework: wires the WebSocket connection, NDJSON framing, the {@code
 * ~20 Hz} behaviour loop and safe-stop into one place, hiding all networking
 * and concurrency from robot code.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Connect to {@code ws://host:port} on startup; reconnect with a fixed
 *       delay when the socket closes (hidden from robot code).</li>
 *   <li>Frame outgoing {@code motorCommand} messages as NDJSON text frames;
 *       parse incoming {@code sensorSnapshot} NDJSON.</li>
 *   <li>Invoke {@link RobotBehaviour#init()} once and {@link
 *       RobotBehaviour#update()} at {@code ~20 Hz}.</li>
 *   <li>Keep the latest {@code sensorSnapshot} in a single slot (overwrite
 *       each tick) exposed via {@link Sensors}.</li>
 *   <li>On WebSocket close: perform safe-stop handling (best-effort zero-speed
 *       command) without crashing; the sim performs the actual motor stopping.</li>
 * </ul>
 */
public class Framework {
    private static final long BEHAVIOUR_PERIOD_MS = 50; // ~20 Hz
    private static final long RECONNECT_DELAY_MS = 2000;

    private final String host;
    private final int port;
    private final RobotBehaviour behaviour;
    private final ScheduledExecutorService behaviourLoop =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "robot-behaviour");
            t.setDaemon(true);
            return t;
        });
    private final Thread reconnectThread;
    private final Object connectionLock = new Object();
    private final Object closeLock = new Object();
    private final AtomicLong seq = new AtomicLong(0);
    private volatile WsConnection connection;
    private volatile long lastSensorSimTick = 0L;
    private volatile Double lastSensorDistance = null;
    private volatile Double lastRightDistance = null;
    private volatile boolean running = true;
    private volatile boolean behaviourInitialized = false;

    public Framework(String host, int port, RobotBehaviour behaviour) {
        this.host = host;
        this.port = port;
        this.behaviour = behaviour;
        this.reconnectThread = new Thread(this::reconnectLoop, "robot-reconnect");
        this.reconnectThread.setDaemon(true);
    }

    public void start() {
        behaviourLoop.scheduleAtFixedRate(this::runUpdate, 0, BEHAVIOUR_PERIOD_MS,
            TimeUnit.MILLISECONDS);
        reconnectThread.start();
    }

    private void runUpdate() {
        try {
            if (!behaviourInitialized) {
                behaviour.init();
                behaviourInitialized = true;
            }
            behaviour.update();
        } catch (Throwable t) {
            // never let a behaviour exception kill the loop
            System.err.println("[robot-framework] behaviour threw: " + t);
        }
    }

    /** Called by Motor.setSpeed; hides WebSocket/NDJSON from robot code. */
    void sendMotorCommand(double left, double right) {
        long sequence = seq.getAndIncrement();
        long simTick = lastSensorSimTick;
        String line = Json.writeMotorCommand(sequence, simTick, left, right);
        WsConnection c = connection;
        if (c != null) {
            c.sendText(line);
            System.out.println("[robot-framework] sent motorCommand " + line);
        } else {
            System.out.println("[robot-framework] not connected; dropping motorCommand " + line);
        }
    }

    long lastSensorSimTick() {
        return lastSensorSimTick;
    }

    Double lastSensorDistance() {
        return lastSensorDistance;
    }

    Double lastRightDistance() {
        return lastRightDistance;
    }

    void onSensorSnapshot(long simTick) {
        this.lastSensorSimTick = simTick;
    }

    void onWebSocketClose() {
        synchronized (closeLock) {
            closeLock.notifyAll();
        }
    }

    private void reconnectLoop() {
        while (running) {
            boolean connected = tryConnect();
            if (!running) {
                break;
            }
            if (!connected) {
                sleep(RECONNECT_DELAY_MS);
                continue;
            }
            // Connected: wait until the socket closes or we shut down.
            waitForCloseOrShutdown();
            if (!running) {
                break;
            }
            // WebSocket closed: safe stop, then reconnect.
            System.out.println("[robot-framework] WebSocket closed; safe-stopping motors");
            safeStop();
            sleep(RECONNECT_DELAY_MS);
        }
    }

    private boolean tryConnect() {
        try {
            WsConnection c = new WsConnection(host, port, new WsListener() {
                @Override
                public void onOpen() {
                    System.out.println("[robot-framework] WebSocket connected to ws://" + host + ":" + port);
                    synchronized (connectionLock) {
                        seq.set(0); // per-connection sequence restarts
                    }
                }

                @Override
                public void onMessage(String line) {
                    handleIncoming(line);
                }

                @Override
                public void onClose() {
                    System.out.println("[robot-framework] WebSocket closed by peer");
                    onWebSocketClose();
                }

                @Override
                public void onError(Throwable t) {
                    System.err.println("[robot-framework] WebSocket error: " + t);
                }
            });
            c.connect(); // blocking handshake
            synchronized (connectionLock) {
                connection = c;
            }
            return true;
        } catch (Throwable t) {
            System.err.println("[robot-framework] connect failed: " + t);
            return false;
        }
    }

    private void waitForCloseOrShutdown() {
        synchronized (closeLock) {
            try {
                while (running) {
                    WsConnection c = connection;
                    if (c == null || !c.isOpen()) {
                        break;
                    }
                    closeLock.wait(1000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void safeStop() {
        // Best-effort final zero-speed command; the sim performs the actual
        // motor stopping when the socket closes.
        sendMotorCommand(0.0, 0.0);
        synchronized (connectionLock) {
            connection = null;
        }
    }

    private void handleIncoming(String line) {
        Envelope env = Json.parseEnvelope(line);
        if (env == null) {
            return; // malformed JSON: log and ignore (never crash/disconnect)
        }
        if ("sensorSnapshot".equals(env.type)) {
            onSensorSnapshot(env.simTick);
            lastSensorDistance = parseSensorDistance(env.payload, "front");
            lastRightDistance = parseSensorDistance(env.payload, "right");
        }
    }

    /**
     * Read a numeric distance field from a {@code sensorSnapshot} payload.
     * Returns a {@code Double} when a number is present, {@code null} when the
     * field is absent or explicitly {@code null} (nothing detected).
     */
    private static Double parseSensorDistance(Object payload, String field) {
        if (!(payload instanceof Map)) {
            return null; // empty payload or malformed -> nothing detected
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> p = (Map<String, Object>) payload;
        Object d = p.get(field);
        if (d instanceof Number) {
            return ((Number) d).doubleValue();
        }
        return null; // null or absent -> nothing detected
    }

    public void stop() {
        running = false;
        synchronized (closeLock) {
            closeLock.notifyAll();
        }
        behaviourLoop.shutdownNow();
        WsConnection c = connection;
        if (c != null) {
            c.close();
        }
    }

    public boolean isRunning() {
        return running;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
