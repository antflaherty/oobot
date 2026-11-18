package oobot.robot.bridge.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import oobot.robot.student.Sensor;

import org.junit.jupiter.api.Test;

/** The tick exposed by a sensor is what lets behaviour code detect stale data. */
class SimProximitySensorTest {

    @Test
    void latestSimTickMakesStalenessDetectable() {
        SimProximitySensor sensor = new SimProximitySensor("front-sensor");

        assertEquals(0L, sensor.latestSimTick());
        assertEquals(Double.POSITIVE_INFINITY, sensor.value().doubleValue());

        sensor.update(100L, 12.5);
        assertEquals(100L, sensor.latestSimTick());
        assertEquals(12.5, sensor.value().doubleValue());
        assertFalse(sensor.isStale(100L + Sensor.MAX_STALE_TICKS),
                "exactly MAX_STALE_TICKS old is still acceptable");
        assertTrue(sensor.isStale(101L + Sensor.MAX_STALE_TICKS),
                "older than MAX_STALE_TICKS must be detectable as stale");
        assertEquals(Sensor.MAX_STALE_TICKS, SimProximitySensor.MAX_STALE_TICKS,
                "threshold is inherited from Sensor");

        // A fresh session (reconnect / tick rollback) invalidates the reading.
        sensor.update(0L, null);
        assertEquals(0L, sensor.latestSimTick());
        assertEquals(Double.POSITIVE_INFINITY, sensor.value().doubleValue());
    }
}
