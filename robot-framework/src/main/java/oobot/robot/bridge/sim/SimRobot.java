package oobot.robot.bridge.sim;

import oobot.robot.student.Brain;
import oobot.robot.student.Sensors;
import oobot.robot.student.Wheel;
import oobot.robot.core.BehaviourLoop;
import oobot.robot.transport.Json;
import oobot.robot.transport.WireMessage;
import oobot.robot.transport.SensorSnapshotMessage;
import oobot.robot.transport.SensorPayload;
import oobot.robot.transport.WsConnection;
import oobot.robot.transport.WsListener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 *   <li>Create the sim-backed hardware: {@link SimWheel} (left/right),
 *       {@link SimSensors}.</li>
 *   <li>Expose that hardware via {@link #leftWheel()}, {@link #rightWheel()},
 *       and {@link #sensors()} so the student can build a {@link Brain} with a
 *       robot-side {@link oobot.robot.example.WheeledMover}.</li>
 *   <li>Translate wheel direction changes into {@code motorCommand} NDJSON
 *       messages over the WebSocket.</li>
 *   <li>Parse incoming {@code sensorSnapshot} messages into {@link SimSensors}.</li>
 *   <li>On WebSocket close: safe-stop the motors.</li>
 * </ul>
 * <p>
 * This is the layer that would be swapped for {@code bridge.hardware} when
 * controlling real hardware. The student-facing API and behaviour loop never
 * change.
 */
public class SimRobot {
    private static final Logger log = LoggerFactory.getLogger(SimRobot.class);
    private static final long RECONNECT_DELAY_MS = 2000;

    private final String host;
    private final int port;
    private final AtomicLong seq = new AtomicLong(0);

    private final SimWheel leftWheel;
    private final SimWheel rightWheel;
    private final SimSensors sensors;

    private final Object connectionLock = new Object();
    private final Object closeLock = new Object();
    private volatile WsConnection connection;
    private volatile BehaviourLoop loop;
    private volatile boolean running = true;

    // Persistent actuator state: latest per-wheel speed in [-1, 1].
    private volatile double leftWheelSpeed = 0.0;
    private volatile double rightWheelSpeed = 0.0;

    public SimRobot(String host, int port) {
        this.host = host;
        this.port = port;
        this.leftWheel = new SimWheel(this, true);
        this.rightWheel = new SimWheel(this, false);
        this.sensors = new SimSensors(this);
        startReconnectThread();
    }

    /** Left wheel. */
    public Wheel leftWheel() {
        return leftWheel;
    }

    /** Right wheel. */
    public Wheel rightWheel() {
        return rightWheel;
    }

    /** Proximity sensor readings (front, right). */
    public Sensors sensors() {
        return sensors;
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * Start the behaviour loop driving the given brain (~20 Hz, calling
     * {@link Brain#tick()} each tick). Non-blocking.
     */
    public void run(Brain brain) {
        loop = new BehaviourLoop(() -> brain.tick());
        loop.start();
    }

    /** Stop the behaviour loop and close the WebSocket connection. */
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

    /* ---------------- wheel -> motorCommand ---------------- */

    /** Called by a wheel when its direction changes — sends the combined motorCommand. */
    void wheelChanged() {
        sendMotorCommand();
    }

    void setLeftSpeed(double speed) {
        leftWheelSpeed = clamp(speed);
    }

    void setRightSpeed(double speed) {
        rightWheelSpeed = clamp(speed);
    }

    private static double clamp(double s) {
        if (!Double.isFinite(s)) return 0.0;
        if (s > 1.0) return 1.0;
        if (s < -1.0) return -1.0;
        return s;
    }

    private void sendMotorCommand() {
        long sequence = seq.getAndIncrement();
        long simTick = sensors.latestSimTick();
        String line = Json.writeMotorCommand(sequence, simTick, leftWheelSpeed, rightWheelSpeed);
        WsConnection c = connection;
        if (c != null) {
            c.sendText(line);
            log.debug("Sent motorCommand {}", line.trim());
        } else {
            log.debug("Not connected; dropping motorCommand {}", line.trim());
        }
    }

    /* ---------------- sim -> sensorSnapshot ---------------- */

    /** Called by the transport when a sensorSnapshot arrives. */
    void onSensorSnapshot(long simTick, Double front, Double right) {
        sensors.update(simTick, front, right);
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
        leftWheel.stop();
        rightWheel.stop();
        synchronized (connectionLock) {
            connection = null;
        }
    }

    private void handleIncoming(String line) {
        WireMessage msg = Json.parseMessage(line);
        if (msg == null) {
            return; // malformed JSON: log and ignore
        }
        if (msg instanceof SensorSnapshotMessage snapshot) {
            SensorPayload p = snapshot.payload();
            onSensorSnapshot(snapshot.simTick(), p.front(), p.right());
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
