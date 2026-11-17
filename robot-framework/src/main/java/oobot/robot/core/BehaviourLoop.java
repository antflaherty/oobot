package oobot.robot.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Sim-agnostic behaviour loop: runs a tick callback at a fixed ~20 Hz.
 * <p>
 * Knows nothing about WebSockets, motors, sensors, or the sim. The bridge
 * (e.g. {@link oobot.robot.bridge.sim.SimRobot}) supplies the tick callback
 * that delegates to the student's {@link oobot.robot.student.Brain}, plus an
 * optional post-tick callback it can use to flush deferred work such as
 * batched actuator commands.
 * <p>
 * Behaviour exceptions are caught and logged — a misbehaving behaviour never
 * kills the loop. The post-tick callback runs in a {@code finally} block so a
 * failing behaviour cannot skip it.
 */
public class BehaviourLoop {
    private static final Logger log = LoggerFactory.getLogger(BehaviourLoop.class);
    private static final long PERIOD_MS = 50; // ~20 Hz

    private final Runnable tick;
    private final Runnable afterTick;
    private final ScheduledExecutorService loop =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "robot-behaviour");
            t.setDaemon(true);
            return t;
        });
    private volatile boolean running = true;

    /**
     * Create a loop with no post-tick work.
     *
     * @param tick callback invoked once per tick (~20 Hz)
     */
    public BehaviourLoop(Runnable tick) {
        this(tick, () -> {});
    }

    /**
     * Create a loop with a post-tick callback.
     *
     * @param tick callback invoked once per tick (~20 Hz)
     * @param afterTick callback invoked after {@code tick}, including when the
     *                  tick threw; used by the bridge to flush batched commands
     */
    public BehaviourLoop(Runnable tick, Runnable afterTick) {
        this.tick = Objects.requireNonNull(tick, "tick");
        this.afterTick = Objects.requireNonNull(afterTick, "afterTick");
    }

    /** Start the fixed-rate tick loop. */
    public void start() {
        loop.scheduleAtFixedRate(() -> {
            if (!running) {
                return;
            }
            try {
                tick.run();
            } catch (Exception e) {
                // never let a behaviour exception kill the loop
                log.error("Behaviour threw an exception", e);
            } finally {
                try {
                    afterTick.run();
                } catch (Exception e) {
                    log.error("Post-tick action threw an exception", e);
                }
            }
        }, 0, PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    /** Stop the loop. */
    public void stop() {
        running = false;
        loop.shutdownNow();
    }

    /**
     * Whether this loop is still accepting ticks.
     *
     * @return {@code true} until {@link #stop()} is called
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Whether the underlying ticker has fully terminated after {@link #stop()}.
     *
     * @return {@code true} once the ticker thread has exited
     */
    public boolean isTerminated() {
        return loop.isTerminated();
    }
}
