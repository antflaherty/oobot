package oobot.robot.bridge.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import oobot.robot.core.BehaviourLoop;
import oobot.robot.description.ComponentDescription;
import oobot.robot.description.ComponentType;
import oobot.robot.description.RobotDescription;
import oobot.robot.student.Brain;
import oobot.robot.student.Mover;
import oobot.robot.student.ProximitySensor;
import oobot.robot.student.RobotBehaviour;
import oobot.robot.student.Wheel;
import oobot.robot.transport.ComponentCommandMessage;
import oobot.robot.transport.ComponentValue;
import oobot.robot.transport.FakeConnections;
import oobot.robot.transport.FakeWebSocket;
import oobot.robot.transport.Json;
import oobot.robot.transport.WireMessage;
import org.junit.jupiter.api.Test;

/** Lifecycle tests using a fake WebSocket; no network or sim process involved. */
class SimRobotLifecycleTest {

    @Test
    void remoteCloseSendsZeroForEveryWheelBeforeCloseAndClearsSensors() throws Exception {
        try (Harness h = new Harness()) {
            h.awaitConnected();
            FakeWebSocket socket = h.socket(0);
            socket.listener().onMessage(socket, snapshot(7, 2.5));
            ProximitySensor front = h.robot.components().sensor("front-sensor");
            assertEquals(2.5, front.distance());

            h.robot.components().wheel("left-wheel").clockwise();
            h.robot.components().wheel("right-wheel").antiClockwise();

            socket.listener().onClosed(socket, 1000, "peer");

            ComponentCommandMessage zero = lastCommand(socket);
            assertEquals(7L, zero.simTick());
            Map<String, Double> values = zero.payload().commands().stream()
                    .collect(Collectors.toMap(ComponentValue::id, ComponentValue::value));
            assertEquals(Set.of("left-wheel", "right-wheel"), values.keySet());
            assertEquals(0.0, values.get("left-wheel"));
            assertEquals(0.0, values.get("right-wheel"));

            List<String> events = socket.events();
            int firstClose = events.indexOf("close 1000");
            assertTrue(firstClose >= 0, "socket was closed");
            assertTrue(lastSendIndex(events) < firstClose,
                    "the zero command must be written before close()");

            assertEquals(Double.POSITIVE_INFINITY, front.distance());
            assertEquals(0L, front.latestSimTick());
        }
    }

    @Test
    void stopSendsZeroForEveryWheelBeforeClose() throws Exception {
        try (Harness h = new Harness()) {
            h.awaitConnected();
            FakeWebSocket socket = h.socket(0);
            h.robot.components().wheel("left-wheel").clockwise();

            h.robot.stop();

            ComponentCommandMessage zero = lastCommand(socket);
            Map<String, Double> values = zero.payload().commands().stream()
                    .collect(Collectors.toMap(ComponentValue::id, ComponentValue::value));
            assertEquals(Set.of("left-wheel", "right-wheel"), values.keySet());
            assertEquals(0.0, values.get("left-wheel"));
            assertEquals(0.0, values.get("right-wheel"));
            List<String> events = socket.events();
            assertTrue(lastSendIndex(events) < events.indexOf("close 1000"),
                    "the zero command must be written before shutdown closes the socket");
            assertFalse(h.robot.isRunning());
        }
    }

    @Test
    void seqRestartsAtZeroAndSensorsAreInvalidatedOnReconnect() throws Exception {
        try (Harness h = new Harness()) {
            h.awaitConnected();
            FakeWebSocket first = h.socket(0);
            first.listener().onMessage(first, snapshot(5, 9.0));
            ProximitySensor front = h.robot.components().sensor("front-sensor");
            assertEquals(9.0, front.distance());

            h.robot.components().wheel("left-wheel").clockwise();
            h.robot.flushCommands();
            long firstSeq = lastCommand(first).seq();

            first.listener().onClosed(first, 1000, "peer");
            long stopSeq = lastCommand(first).seq();

            h.awaitSocketCount(2);
            h.awaitConnected();
            FakeWebSocket second = h.socket(1);
            assertEquals(Double.POSITIVE_INFINITY, front.distance(), "stale reading survived reconnect");
            assertEquals(0L, front.latestSimTick(), "stale tick survived reconnect");
            first.listener().onMessage(first, snapshot(99, 42.0));
            assertEquals(Double.POSITIVE_INFINITY, front.distance(), "old socket updated a new-session sensor");

            h.robot.components().wheel("right-wheel").clockwise();
            h.robot.flushCommands();
            long secondSeq = lastCommand(second).seq();

            assertEquals(0L, firstSeq);
            assertEquals(firstSeq + 1, stopSeq);
            List<Long> firstConnectionSeqs = first.sentFrames().stream()
                    .map(Json::parseMessage)
                    .map(m -> assertInstanceOf(ComponentCommandMessage.class, m).seq())
                    .toList();
            assertEquals(List.of(firstSeq, stopSeq), firstConnectionSeqs, "seq must be unique per connection");
            assertEquals(0L, secondSeq, "seq must restart on every connection");
        }
    }

    @Test
    void stopDuringConnectClosesInFlightSocketAndJoinsReconnectThread() throws Exception {
        CountDownLatch connectStarted = new CountDownLatch(1);
        CountDownLatch releaseOpen = new CountDownLatch(1);
        FakeWebSocket socket = new FakeWebSocket();
        SimRobot robot = new SimRobot("localhost", 8080, description(), (host, port, listener) ->
                FakeConnections.create(listener, socket, (wsListener, fake) -> {
                    fake.attach(wsListener);
                    Thread opener = new Thread(() -> {
                        connectStarted.countDown();
                        try {
                            if (!releaseOpen.await(2, TimeUnit.SECONDS)) {
                                return;
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        wsListener.onOpen(fake, null);
                    }, "fake-opener");
                    opener.setDaemon(true);
                    opener.start();
                    return fake;
                }));

        Thread reconnectThread = robot.reconnectThread();
        assertTrue(connectStarted.await(2, TimeUnit.SECONDS), "connect never started");
        robot.stop();
        releaseOpen.countDown();
        reconnectThread.join(1000);

        assertFalse(reconnectThread.isAlive(), "stop() did not join the reconnect thread");
        assertFalse(robot.isRunning());
        assertTrue(socket.events().contains("close 1000"), "in-flight socket was not closed");
        assertTrue(socket.sentFrames().isEmpty(), "no final frame on a socket that never opened");
    }

    @Test
    void missingSnapshotPayloadOrReadingsAreIgnored() throws Exception {
        try (Harness h = new Harness()) {
            h.awaitConnected();
            FakeWebSocket socket = h.socket(0);
            socket.listener().onMessage(socket,
                    "{\"type\":\"componentSnapshot\",\"seq\":0,\"simTick\":3,\"payload\":null}\n");
            socket.listener().onMessage(socket,
                    "{\"type\":\"componentSnapshot\",\"seq\":0,\"simTick\":4,"
                            + "\"payload\":{\"readings\":null}}\n");

            assertEquals(Double.POSITIVE_INFINITY, h.robot.components().sensor("front-sensor").distance());
        }
    }

    @Test
    void wheelCommandsAreBatchedIntoOneFramePerTick() throws Exception {
        try (Harness h = new Harness()) {
            h.awaitConnected();
            FakeWebSocket socket = h.socket(0);

            h.robot.components().wheel("left-wheel").clockwise();
            h.robot.components().wheel("right-wheel").antiClockwise();
            assertTrue(socket.sentFrames().isEmpty(),
                    "wheels must only mutate local state during a tick");

            h.robot.flushCommands();
            assertEquals(1, socket.sentFrames().size(),
                    "two wheel changes in one tick must produce one frame");
            ComponentCommandMessage cmd = lastCommand(socket);
            Map<String, Double> values = cmd.payload().commands().stream()
                    .collect(Collectors.toMap(ComponentValue::id, ComponentValue::value));
            assertEquals(Set.of("left-wheel", "right-wheel"), values.keySet());
            assertEquals(1.0, values.get("left-wheel"));
            assertEquals(1.0, values.get("right-wheel"));
            assertEquals(0L, cmd.seq());

            h.robot.flushCommands();
            assertEquals(1, socket.sentFrames().size(), "an idle flush sends nothing");

            h.robot.components().wheel("left-wheel").stop();
            h.robot.flushCommands();
            assertEquals(2, socket.sentFrames().size());
            assertEquals(1L, lastCommand(socket).seq(), "seq advances once per flushed frame");
        }
    }

    @Test
    void oneFramePerTickRegardlessOfWheelCount() throws Exception {
        RobotDescription fourWheels = new RobotDescription("four-wheels", List.of(
                new ComponentDescription("left-front", ComponentType.WHEEL, Math.PI / 2, 0.25),
                new ComponentDescription("right-front", ComponentType.WHEEL, -Math.PI / 2, 0.25),
                new ComponentDescription("left-rear", ComponentType.WHEEL, Math.PI / 2, 0.25),
                new ComponentDescription("right-rear", ComponentType.WHEEL, -Math.PI / 2, 0.25),
                new ComponentDescription("front-sensor", ComponentType.PROXIMITY_SENSOR, 0.0, 0.0),
                new ComponentDescription("right-sensor", ComponentType.PROXIMITY_SENSOR, -Math.PI / 2, 0.0)));
        try (Harness h = new Harness(fourWheels)) {
            h.awaitConnected();
            FakeWebSocket socket = h.socket(0);

            for (Wheel wheel : h.robot.components().wheels()) {
                wheel.clockwise();
            }
            h.robot.flushCommands();

            assertEquals(1, socket.sentFrames().size(), "four wheels still produce one frame per tick");
            assertEquals(4, lastCommand(socket).payload().commands().size());
        }
    }

    @Test
    void repeatedRunStopsThePreviousTicker() throws Exception {
        try (Harness h = new Harness()) {
            h.awaitConnected();
            Brain brain = noOpBrain();

            h.robot.run(brain);
            BehaviourLoop first = h.robot.currentLoop();
            assertTrue(first.isRunning());

            h.robot.run(brain);
            BehaviourLoop second = h.robot.currentLoop();
            assertNotSame(first, second, "run() must create a fresh loop");
            assertFalse(first.isRunning(), "previous loop must be stopped by run()");
            awaitTerminated(first);
            assertTrue(first.isTerminated(), "previous ticker thread must terminate");

            h.robot.run(brain);
            BehaviourLoop third = h.robot.currentLoop();
            assertNotSame(second, third);
            assertFalse(second.isRunning(), "only the newest ticker may run");

            h.robot.stop();
            assertFalse(third.isRunning());
            awaitTerminated(third);
        }
    }

    /* ---------------- helpers ---------------- */

    private static Brain noOpBrain() {
        return new Brain() {
            @Override public Mover mover() { return null; }
            @Override public ProximitySensor frontSensor() { return null; }
            @Override public ProximitySensor rightSensor() { return null; }
            @Override public void setBehaviour(RobotBehaviour behaviour) { }
            @Override public void tick() { }
        };
    }

    private static void awaitTerminated(BehaviourLoop loop) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!loop.isTerminated() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    private static RobotDescription description() {
        return new RobotDescription("test-layout", List.of(
                new ComponentDescription("left-wheel", ComponentType.WHEEL, Math.PI / 2, 0.25),
                new ComponentDescription("right-wheel", ComponentType.WHEEL, -Math.PI / 2, 0.25),
                new ComponentDescription("front-sensor", ComponentType.PROXIMITY_SENSOR, 0.0, 0.0),
                new ComponentDescription("right-sensor", ComponentType.PROXIMITY_SENSOR, -Math.PI / 2, 0.0)));
    }

    private static String snapshot(long simTick, Double frontValue) {
        return "{\"type\":\"componentSnapshot\",\"seq\":0,\"simTick\":" + simTick
                + ",\"payload\":{\"readings\":[{\"id\":\"front-sensor\",\"value\":"
                + (frontValue == null ? "null" : frontValue) + "}]}}\n";
    }

    private static ComponentCommandMessage lastCommand(FakeWebSocket socket) {
        List<String> frames = socket.sentFrames();
        assertFalse(frames.isEmpty(), "no componentCommand was sent");
        WireMessage msg = Json.parseMessage(frames.get(frames.size() - 1));
        return assertInstanceOf(ComponentCommandMessage.class, msg);
    }

    private static int lastSendIndex(List<String> events) {
        for (int i = events.size() - 1; i >= 0; i--) {
            if (events.get(i).startsWith("send ")) {
                return i;
            }
        }
        return -1;
    }

    private static final class Harness implements AutoCloseable {
        private final List<FakeWebSocket> sockets = Collections.synchronizedList(new ArrayList<>());
        private final SimRobot robot;

        Harness() {
            this(description());
        }

        Harness(RobotDescription desc) {
            robot = new SimRobot("localhost", 8080, desc, (host, port, listener) -> {
                FakeWebSocket socket = new FakeWebSocket();
                sockets.add(socket);
                return FakeConnections.connected(listener, socket);
            });
        }

        FakeWebSocket socket(int index) {
            synchronized (sockets) {
                return sockets.get(index);
            }
        }

        void awaitSocketCount(int count) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while (System.nanoTime() < deadline) {
                synchronized (sockets) {
                    if (sockets.size() >= count) {
                        return;
                    }
                }
                Thread.sleep(10);
            }
            throw new AssertionError("expected " + count + " fake sockets, got " + sockets.size());
        }

        void awaitConnected() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!robot.isConnected() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(robot.isConnected(), "robot did not publish its connection");
        }

        @Override
        public void close() {
            robot.stop();
        }
    }
}
