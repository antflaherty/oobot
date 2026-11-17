package oobot.robot.bridge.sim;

import oobot.robot.bridge.RobotPlatform;
import oobot.robot.description.ComponentDescription;
import oobot.robot.description.ComponentRegistry;
import oobot.robot.description.RobotDescription;
import oobot.robot.student.Brain;
import oobot.robot.student.Wheel;
import oobot.robot.core.BehaviourLoop;
import oobot.robot.transport.ComponentReading;
import oobot.robot.transport.ComponentSnapshotMessage;
import oobot.robot.transport.ComponentSnapshotPayload;
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
import java.util.Objects;
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
 *   <li>Translate wheel value changes into {@code componentCommand} NDJSON
 *       messages over the WebSocket.</li>
 *   <li>Parse incoming {@code componentSnapshot} messages and update the bound
 *       proximity sensor handles; unknown ids are ignored.</li>
 *   <li>On every disconnect/shutdown, write one explicit zero command for all
 *       wheels <em>before</em> closing the socket (D-2 layer B), and invalidate
 *       all sensor readings. The sim independently stops motors on close
 *       (layer A).</li>
 * </ul>
 * <p>
 * This is the layer that would be swapped for {@code bridge.hardware} when
 * controlling real hardware. The student-facing API and behaviour loop never
 * change.
 */
public class SimRobot implements RobotPlatform {
    private static final Logger log = LoggerFactory.getLogger(SimRobot.class);
    private static final long RECONNECT_DELAY_MS = 2000;
    private static final long STOP_JOIN_TIMEOUT_MS = 1000;

    /** Injectable connection factory; the default creates a real OkHttp client. */
    @FunctionalInterface
    public interface ConnectionFactory {
        WsConnection create(String host, int port, WsListener listener);
    }

    private final String host;
    private final int port;
    private final ConnectionFactory connectionFactory;
    private final AtomicLong seq = new AtomicLong(0);
    private final AtomicLong connectionIds = new AtomicLong(0);

    private final ComponentRegistry registry;
    private final Map<String, SimProximitySensor> sensorsById = new LinkedHashMap<>();

    private final Object connectionLock = new Object();
    private final Object closeLock = new Object();
    private volatile WsConnection connection;
    private volatile WsConnection connectingConnection;
    private volatile long activeConnectionId = -1L;
    private volatile BehaviourLoop loop;
    private volatile boolean running = true;
    /** True only while an active socket may accept behaviour commands. */
    private volatile boolean commandsEnabled = false;
    private volatile Thread reconnectThread;

    // Persistent actuator state: latest per-wheel value in [-1, 1].
    private final Map<String, Double> wheelValues = new LinkedHashMap<>();
    private volatile long latestSimTick = 0L;

    public SimRobot(String host, int port, RobotDescription description) {
        this(host, port, description, WsConnection::new);
    }

    SimRobot(String host, int port, RobotDescription description, ConnectionFactory connectionFactory) {
        this.host = host;
        this.port = port;
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
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

    /** Package-private test seam for verifying a bounded join in {@link #stop()}. */
    Thread reconnectThread() {
        return reconnectThread;
    }

    /** Package-private test seam: true only when an active socket accepts commands. */
    boolean isConnected() {
        return connection != null && commandsEnabled;
    }

    /**
     * Start the behaviour loop driving the given brain (~20 Hz, calling
     * {@link Brain#tick()} each tick). Non-blocking.
     */
    @Override
    public void run(Brain brain) {
        Objects.requireNonNull(brain, "brain");
        BehaviourLoop newLoop = new BehaviourLoop(() -> {
            if (running) {
                brain.tick();
            }
        });
        BehaviourLoop previous = loop;
        loop = newLoop;
        if (previous != null) {
            previous.stop();
        }
        newLoop.start();
    }

    /** Stop the behaviour loop, write the final zero command, close the socket. */
    @Override
    public void stop() {
        running = false;
        BehaviourLoop currentLoop = loop;
        if (currentLoop != null) {
            currentLoop.stop();
        }
        synchronized (closeLock) {
            closeLock.notifyAll();
        }

        WsConnection c;
        String zeroFrame;
        synchronized (connectionLock) {
            commandsEnabled = false;
            c = connection != null ? connection : connectingConnection;
            zeroFrame = nextZeroCommandFrameLocked();
            clearSensorStateLocked();
            connection = null;
            connectingConnection = null;
            activeConnectionId = -1L;
        }
        if (c != null) {
            c.closeWithFinalFrame(zeroFrame);
            log.debug("Wrote final zero command before closing WebSocket");
        }

        Thread t = reconnectThread;
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();
            try {
                t.join(STOP_JOIN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
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
        List<ComponentValue> commands;
        long sequence;
        long tick;
        WsConnection c;
        synchronized (connectionLock) {
            if (!commandsEnabled || connection == null) {
                return;
            }
            commands = new ArrayList<>();
            synchronized (wheelValues) {
                for (Map.Entry<String, Double> entry : wheelValues.entrySet()) {
                    commands.add(new ComponentValue(entry.getKey(), entry.getValue()));
                }
            }
            sequence = seq.getAndIncrement();
            tick = latestSimTick;
            c = connection;
        }
        String line = Json.writeComponentCommand(sequence, tick, commands);
        c.sendText(line);
        log.debug("Sent componentCommand {}", line.trim());
    }

    /**
     * Build the exact safe-stop frame: one entry for every bound wheel id at
     * 0.0, using the next unused seq and the last seen simTick. Caller holds
     * {@link #connectionLock}.
     */
    private String nextZeroCommandFrameLocked() {
        List<ComponentValue> zeros = new ArrayList<>();
        synchronized (wheelValues) {
            for (Wheel wheel : registry.wheels()) {
                String id = wheel.id();
                wheelValues.put(id, 0.0);
                zeros.add(new ComponentValue(id, 0.0));
            }
        }
        return Json.writeComponentCommand(seq.getAndIncrement(), latestSimTick, zeros);
    }

    /* ---------------- sim -> componentSnapshot ---------------- */

    /** Called by the transport when a componentSnapshot arrives. */
    private void onComponentSnapshot(long connectionId, long simTick, List<ComponentReading> readings) {
        synchronized (connectionLock) {
            if (!commandsEnabled || connectionId != activeConnectionId) {
                return; // stale socket: ignore everything it sends
            }
            if (simTick < 0) {
                log.debug("Ignoring snapshot with negative simTick {}", simTick);
                return;
            }
            if (simTick < latestSimTick) {
                // Tick rollback is a new sim session: discard all old readings.
                clearSensorStateLocked();
            }
            latestSimTick = simTick;
            if (readings == null) {
                return;
            }
            for (ComponentReading reading : readings) {
                if (reading == null || reading.id() == null) {
                    continue;
                }
                SimProximitySensor sensor = sensorsById.get(reading.id());
                if (sensor != null) {
                    sensor.update(simTick, reading.value());
                }
            }
        }
    }

    /** Clear all sensor state and the last seen tick. Caller holds connectionLock. */
    private void clearSensorStateLocked() {
        for (SimProximitySensor sensor : sensorsById.values()) {
            sensor.update(0L, null); // distance() -> Infinity
        }
        latestSimTick = 0L;
    }

    /* ---------------- connection lifecycle ---------------- */

    private void startReconnectThread() {
        Thread t = new Thread(this::reconnectLoop, "robot-reconnect");
        t.setDaemon(true);
        reconnectThread = t;
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
            waitForCloseOrShutdown();
            if (!running) {
                break;
            }
            // onWebSocketClose() has already safe-stopped and cleared state.
            sleep(RECONNECT_DELAY_MS);
        }
    }

    private boolean tryConnect() {
        long id = connectionIds.incrementAndGet();
        WsConnection c = connectionFactory.create(host, port, new WsListener() {
            @Override
            public void onOpen() {
                log.info("WebSocket connected to ws://{}:{}", host, port);
            }

            @Override
            public void onMessage(String line) {
                handleIncoming(id, line);
            }

            @Override
            public void onClose() {
                onWebSocketClose(id);
            }

            @Override
            public void onError(Throwable t) {
                log.warn("WebSocket error: {}", t.getMessage());
            }
        });

        synchronized (connectionLock) {
            // Register before the blocking handshake so stop() can abort it.
            connectingConnection = c;
        }
        try {
            c.connect();
        } catch (Exception e) {
            if (running) {
                log.warn("Connect failed: {}", e.getMessage());
            }
            synchronized (connectionLock) {
                if (connectingConnection == c) {
                    connectingConnection = null;
                }
            }
            c.close();
            return false;
        }

        synchronized (connectionLock) {
            if (connectingConnection == c) {
                connectingConnection = null;
            }
            if (!running || !c.isOpen()) {
                c.close();
                return false;
            }
            // Reset before publishing: behaviour commands cannot observe the
            // new socket until seq is back at 0 and sensors are invalidated.
            seq.set(0);
            clearSensorStateLocked();
            connection = c;
            activeConnectionId = id;
            commandsEnabled = true;
        }
        return true;
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

    private void onWebSocketClose(long connectionId) {
        synchronized (closeLock) {
            closeLock.notifyAll();
        }
        WsConnection c;
        String zeroFrame;
        synchronized (connectionLock) {
            if (connectionId != activeConnectionId || connection == null) {
                return; // stale connection: never touch the active robot's state
            }
            commandsEnabled = false;
            c = connection;
            zeroFrame = nextZeroCommandFrameLocked();
            clearSensorStateLocked();
            connection = null;
            activeConnectionId = -1L;
        }
        // WsConnection delivers onClose before clearing 'open', so this write
        // happens on a socket that is still marked open.
        c.closeWithFinalFrame(zeroFrame);
        log.info("WebSocket closed; safe-stopped motors");
    }

    private void handleIncoming(long connectionId, String line) {
        WireMessage msg = Json.parseMessage(line);
        if (msg == null) {
            return; // malformed JSON: log and ignore
        }
        if (msg instanceof ComponentSnapshotMessage snapshot) {
            ComponentSnapshotPayload payload = snapshot.payload();
            onComponentSnapshot(connectionId, snapshot.simTick(), payload == null ? null : payload.readings());
        } else {
            log.debug("Ignoring unexpected inbound wire message: {}", msg.getClass().getSimpleName());
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
