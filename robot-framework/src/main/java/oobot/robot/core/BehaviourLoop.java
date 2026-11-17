package oobot.robot.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Sim-agnostic behaviour loop: runs a tick callback at a fixed ~20 Hz.
 * <p>
 * Knows nothing about WebSockets, motors, sensors, or the sim. The bridge
 * (e.g. {@link oobot.robot.bridge.sim.SimRobot}) supplies the tick callback
 * that delegates to the student's {@link oobot.robot.student.Brain}.
 * <p>
 * Behaviour exceptions are caught and logged — a misbehaving behaviour never
 * kills the loop.
 */
public class BehaviourLoop {
    private static final Logger log = LoggerFactory.getLogger(BehaviourLoop.class);
    private static final long PERIOD_MS = 50; // ~20 Hz

    private final Runnable tick;
    private final ScheduledExecutorService loop =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "robot-behaviour");
            t.setDaemon(true);
            return t;
        });
    private volatile boolean running = true;

    public BehaviourLoop(Runnable tick) {
        this.tick = tick;
    }

    /** Start the fixed-rate tick loop. */
    public void start() {
        loop.scheduleAtFixedRate(() -> {
            if (!running) {
                return;
            }
            try {
                tick.run();
            } catch (Throwable t) {
                // never let a behaviour exception kill the loop
                log.error("Behaviour threw an exception", t);
            }
        }, 0, PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    /** Stop the loop. */
    public void stop() {
        running = false;
        loop.shutdownNow();
    }

    public boolean isRunning() {
        return running;
    }
}
