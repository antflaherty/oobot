package oobot.robot.bridge.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The tick exposed by a sensor is what lets behaviour code detect stale data. */
class SimProximitySensorTest {

    @Test
    void latestSimTickMakesStalenessDetectable() {
        SimProximitySensor sensor = new SimProximitySensor("front-sensor");

        assertEquals(0L, sensor.latestSimTick());
        assertEquals(Double.POSITIVE_INFINITY, sensor.distance());

        sensor.update(100L, 12.5);
        assertEquals(100L, sensor.latestSimTick());
        assertEquals(12.5, sensor.distance());
        assertFalse(sensor.isStale(100L + SimProximitySensor.MAX_STALE_TICKS),
                "exactly MAX_STALE_TICKS old is still acceptable");
        assertTrue(sensor.isStale(101L + SimProximitySensor.MAX_STALE_TICKS),
                "older than MAX_STALE_TICKS must be detectable as stale");

        // A fresh session (reconnect / tick rollback) invalidates the reading.
        sensor.update(0L, null);
        assertEquals(0L, sensor.latestSimTick());
        assertEquals(Double.POSITIVE_INFINITY, sensor.distance());
    }
}
