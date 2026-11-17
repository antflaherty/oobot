package oobot.robot.description;

import oobot.robot.student.Component;
import oobot.robot.student.ProximitySensor;
import oobot.robot.student.Wheel;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Binds runtime component objects to the ids declared in a {@link RobotDescription}
 * and resolves them by id (with typed accessors for wheels and sensors).
 *
 * <p>Wiring happens once at startup; no locking is provided.
 */
public final class ComponentRegistry {
    private final RobotDescription description;
    private final Map<String, Component> components = new HashMap<>();

    public ComponentRegistry(RobotDescription d) {
        this.description = Objects.requireNonNull(d, "description");
    }

    /** Bind a runtime component to a declared id. Called once at startup. */
    public void bind(String id, Component c) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Component id must be non-empty");
        }
        components.put(id, Objects.requireNonNull(c, "component"));
    }

    /**
     * Return the component bound to {@code id}, or {@code null} if none is bound.
     * This is the only lenient lookup: {@link #wheel(String)} and
     * {@link #sensor(String)} fail fast instead of returning {@code null}.
     */
    public Component component(String id) {
        return components.get(id);
    }

    /** Return the wheel bound to {@code id}, or throw if it is missing or not a wheel. */
    public Wheel wheel(String id) {
        Component c = require(id);
        if (!(c instanceof Wheel)) {
            throw new IllegalArgumentException("Component '" + id + "' is not a Wheel");
        }
        return (Wheel) c;
    }

    /** Return the sensor bound to {@code id}, or throw if it is missing or not a sensor. */
    public ProximitySensor sensor(String id) {
        Component c = require(id);
        if (!(c instanceof ProximitySensor)) {
            throw new IllegalArgumentException("Component '" + id + "' is not a ProximitySensor");
        }
        return (ProximitySensor) c;
    }

    /** All component descriptions declared in the manifest. */
    public List<ComponentDescription> all() {
        return description.components();
    }

    /** All wheels bound in this registry, sorted by ascending mount angle. */
    public List<Wheel> wheels() {
        return components.values().stream()
                .filter(Wheel.class::isInstance)
                .map(Wheel.class::cast)
                .sorted(Comparator.comparingDouble(Wheel::angle))
                .toList();
    }

    private Component require(String id) {
        Component c = components.get(id);
        if (c == null) {
            throw new IllegalArgumentException("No component bound for id '" + id + "'");
        }
        return c;
    }
}
