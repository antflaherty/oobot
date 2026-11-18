package oobot.robot.bridge.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import oobot.robot.student.Sensor;

import org.junit.jupiter.api.Test;

/** Goal readings are numeric on the wire but never null to behaviour code. */
class SimGoalSensorTest {

    @Test
    void decodesNumericWireValuesAndNeverReturnsNull() {
        SimGoalSensor sensor = new SimGoalSensor("goal-sensor");

        assertEquals("goal-sensor", sensor.id());
        assertEquals(0L, sensor.latestSimTick());
        assertNotNull(sensor.value());
        assertFalse(sensor.value(), "no reading means not in goal");

        sensor.update(100L, 1.0);
        assertEquals(100L, sensor.latestSimTick());
        assertTrue(sensor.value(), "1 means inside the goal");

        sensor.update(101L, 0.0);
        assertEquals(101L, sensor.latestSimTick());
        assertFalse(sensor.value(), "0 means outside the goal");

        sensor.update(102L, null);
        assertEquals(102L, sensor.latestSimTick());
        assertNotNull(sensor.value());
        assertFalse(sensor.value(), "null (world has no goal) means false");

        sensor.update(103L, Integer.valueOf(1));
        assertTrue(sensor.value(), "any Number 1 is accepted");

        sensor.update(104L, Boolean.TRUE);
        assertTrue(sensor.value(), "Boolean encoding is accepted too");
    }

    @Test
    void stalenessUsesInheritedThresholdAndResetClearsValue() {
        SimGoalSensor sensor = new SimGoalSensor("goal-sensor");

        sensor.update(200L, 1);
        assertFalse(sensor.isStale(200L + Sensor.MAX_STALE_TICKS));
        assertTrue(sensor.isStale(201L + Sensor.MAX_STALE_TICKS));
        assertEquals(Sensor.MAX_STALE_TICKS, SimGoalSensor.MAX_STALE_TICKS);

        // Fresh session: tick and value are both invalidated.
        sensor.update(0L, null);
        assertEquals(0L, sensor.latestSimTick());
        assertFalse(sensor.value());
    }
}
