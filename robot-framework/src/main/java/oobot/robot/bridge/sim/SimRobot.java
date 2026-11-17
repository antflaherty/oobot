package oobot.robot.bridge.sim;

import oobot.robot.bridge.RobotPlatform;
import oobot.robot.description.ComponentDescription;
import oobot.robot.description.ComponentRegistry;
import oobot.robot.description.RobotDescription;
import oobot.robot.student.Brain;
import oobot.robot.core.BehaviourLoop;
import oobot.robot.transport.ComponentReading;
import oobot.robot.transport.ComponentSnapshotMessage;
import oobot.robot.transport.ComponentValue;
import oobot.robot.transport.Json;
import oobot.robot.transport.WireMessage;
import oobot.robot.transport.WsConnection;
import oobot.robot.transport.WsListener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sim bridge: the concrete, sim-specific piece that sits between the
 * student-facing API ({@link oobot.robot.student.*}) and the WebSocket-connected
 * simulation.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Connect to {@code ws://host:port} on the robot channel; reconnect with
 *       a fixed delay when the socket closes (hidden from robot code).</li>
 *   <li>Create sim-backed hardware handles from the supplied
 *       {@link RobotDescription} and bind them into a {@link ComponentRegistry}.</li>
 *   <li>Expose that registry via {@link #components()} so a {@link Brain} can
 *       be built against id-addressed components.</li>
 *   <li>Translate wheel value changes into one batched {@code componentCommand}
 *       NDJSON message over the WebSocket.</li>
 *   <li>Parse incoming {@code componentSnapshot} messages and update the bound
 *       proximity sensor handles; unknown ids are ignored.</li>
 *   <li>On WebSocket close: safe-stop every wheel.</li>
 * </ul>
 * <p>
 * This is the layer that would be swapped for {@code bridge.hardware} when
 * controlling real hardware. The student-facing API and behaviour loop never
 * change.
 */
public class SimRobot implements RobotPlatform {
    private static final Logger log = LoggerFactory.getLogger(SimRobot.class);
    private static final long RECONNECT_DELAY_MS = 2000;

    private final String host;
    private final int port;
    private final AtomicLong seq = new AtomicLong(0);

    private final ComponentRegistry registry;
    private final Map<String, SimProximitySensor> sensorsById = new LinkedHashMap<>();

    private final Object connectionLock = new Object();
    private final Object closeLock = new Object();
    private volatile WsConnection connection;
    private volatile BehaviourLoop loop;
    private volatile boolean running = true;

    // Persistent actuator state: latest per-wheel value in [-1, 1].
    private final Map<String, Double> wheelValues = new LinkedHashMap<>();
    private volatile long latestSimTick = 0L;

    public SimRobot(String host, int port, RobotDescription description) {
        this.host = host;
        this.port = port;
        this.registry = new ComponentRegistry(description);

        for (ComponentDescription component : description.components()) {
            switch (component.type()) {
                case WHEEL -> {
                    SimWheel wheel = new SimWheel(this, component.id(), component.angle());
                    synchronized (wheelValues) {
                        wheelValues.put(component.id(), 0.0);
                    }
                    registry.bind(component.id(), wheel);
                }
                case PROXIMITY_SENSOR -> {
                    SimProximitySensor sensor = new SimProximitySensor(this, component.id());
                    sensorsById.put(component.id(), sensor);
                    registry.bind(component.id(), sensor);
                }
            }
        }
        startReconnectThread();
    }

    @Override
    public ComponentRegistry components() {
        return registry;
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * Start the behaviour loop driving the given brain (~20 Hz, calling
     * {@link Brain#tick()} each tick). Non-blocking.
     */
    @Override
    public void run(Brain brain) {
        loop = new BehaviourLoop(() -> brain.tick());
        loop.start();
    }

    /** Stop the behaviour loop and close the WebSocket connection. */
    @Override
    public void stop() {
        running = false;
        synchronized (closeLock) {
            closeLock.notifyAll();
        }
        if (loop != null) {
            loop.stop();
        }
        WsConnection c = connection;
        if (c != null) {
            c.close();
        }
    }

    /** {@link AutoCloseable} alias for {@link #stop()}. */
    @Override
    public void close() {
        stop();
    }

    /* ---------------- wheel -> componentCommand ---------------- */

    /** Called by a wheel when its value changes — stores the clamped value. */
    void setWheelValue(String id, double value) {
        synchronized (wheelValues) {
            if (wheelValues.containsKey(id)) {
                wheelValues.put(id, clamp(value));
            }
        }
    }

    /** Called by a wheel when its value changes — sends one batched componentCommand. */
    void wheelChanged() {
        sendComponentCommand();
    }

    private static double clamp(double s) {
        if (!Double.isFinite(s)) return 0.0;
        if (s > 1.0) return 1.0;
        if (s < -1.0) return -1.0;
        return s;
    }

    private void sendComponentCommand() {
        List<ComponentValue> commands = new ArrayList<>();
        synchronized (wheelValues) {
            for (Map.Entry<String, Double> entry : wheelValues.entrySet()) {
                commands.add(new ComponentValue(entry.getKey(), entry.getValue()));
            }
        }
        long sequence = seq.getAndIncrement();
        String line = Json.writeComponentCommand(sequence, latestSimTick, commands);
        WsConnection c = connection;
        if (c != null) {
            c.sendText(line);
            log.debug("Sent componentCommand {}", line.trim());
        } else {
            log.debug("Not connected; dropping componentCommand {}", line.trim());
        }
    }

    /* ---------------- sim -> componentSnapshot ---------------- */

    /** Called by the transport when a componentSnapshot arrives. */
    private void onComponentSnapshot(long simTick, List<ComponentReading> readings) {
        latestSimTick = simTick;
        for (ComponentReading reading : readings) {
            SimProximitySensor sensor = sensorsById.get(reading.id());
            if (sensor != null) {
                sensor.update(simTick, reading.value());
            }
        }
    }

    /* ---------------- connection lifecycle ---------------- */

    private void startReconnectThread() {
        Thread t = new Thread(this::reconnectLoop, "robot-reconnect");
        t.setDaemon(true);
        t.start();
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
            log.info("WebSocket closed; safe-stopping motors");
            safeStop();
            sleep(RECONNECT_DELAY_MS);
        }
    }

    private boolean tryConnect() {
        try {
            WsConnection c = new WsConnection(host, port, new WsListener() {
                @Override
                public void onOpen() {
                    log.info("WebSocket connected to ws://{}:{}", host, port);
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
                    log.info("WebSocket closed by peer");
                    onWebSocketClose();
                }

                @Override
                public void onError(Throwable t) {
                    log.warn("WebSocket error: {}", t.getMessage());
                }
            });
            c.connect(); // blocking handshake
            synchronized (connectionLock) {
                connection = c;
            }
            return true;
        } catch (Throwable t) {
            log.warn("Connect failed: {}", t.getMessage());
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

    private void onWebSocketClose() {
        synchronized (closeLock) {
            closeLock.notifyAll();
        }
    }

    private void safeStop() {
        synchronized (wheelValues) {
            for (String id : wheelValues.keySet()) {
                wheelValues.put(id, 0.0);
            }
        }
        wheelChanged();
        synchronized (connectionLock) {
            connection = null;
        }
    }

    private void handleIncoming(String line) {
        WireMessage msg = Json.parseMessage(line);
        if (msg == null) {
            return; // malformed JSON: log and ignore
        }
        if (msg instanceof ComponentSnapshotMessage snapshot) {
            onComponentSnapshot(snapshot.simTick(), snapshot.payload().readings());
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
