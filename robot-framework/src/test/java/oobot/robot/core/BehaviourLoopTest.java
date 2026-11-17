package oobot.robot.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BehaviourLoopTest {

    @Test
    void afterTickRunsEvenWhenBehaviourThrows() throws Exception {
        AtomicInteger ticks = new AtomicInteger();
        AtomicInteger flushes = new AtomicInteger();
        CountDownLatch threeTicks = new CountDownLatch(3);
        BehaviourLoop loop = new BehaviourLoop(
                () -> {
                    if (ticks.incrementAndGet() <= 2) {
                        throw new IllegalStateException("behaviour failure");
                    }
                },
                () -> {
                    flushes.incrementAndGet();
                    threeTicks.countDown();
                });
        try {
            loop.start();
            assertTrue(threeTicks.await(2, TimeUnit.SECONDS),
                    "an exception must not kill the loop or skip its post-tick hook");
            assertTrue(flushes.get() >= 3, "post-tick hook must run after every tick");
            assertTrue(ticks.get() >= 3, "loop must keep ticking after a behaviour exception");
        } finally {
            loop.stop();
        }
    }
}
