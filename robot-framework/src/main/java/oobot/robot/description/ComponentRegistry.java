package oobot.robot.description;

import oobot.robot.student.Component;
import oobot.robot.student.Sensor;
import oobot.robot.student.Tool;
import oobot.robot.student.Wheel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Binds runtime component objects to the ids declared in a {@link RobotDescription}
 * and resolves them by id (with typed accessors for wheels and both sensor kinds).
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
     * This is the only lenient lookup: {@link #wheel(String)},
     * {@link #sensor(String)} and {@link #booleanSensor(String)} fail fast
     * instead of returning {@code null}.
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

    /**
     * Return the proximity sensor bound to {@code id}, or throw if it is missing
     * or not a sensor. The sim bridge binds a {@code Sensor<Double>} for every
     * declared {@code proximitySensor} id.
     */
    @SuppressWarnings("unchecked")
    public Sensor<Double> sensor(String id) {
        Component c = require(id);
        if (!(c instanceof Sensor<?>)) {
            throw new IllegalArgumentException("Component '" + id + "' is not a Sensor");
        }
        return (Sensor<Double>) c;
    }

    /**
     * Return the boolean sensor bound to {@code id}, or throw if it is missing
     * or not a sensor. The sim bridge binds a {@code Sensor<Boolean>} for every
     * declared {@code goalSensor} id.
     */
    @SuppressWarnings("unchecked")
    public Sensor<Boolean> booleanSensor(String id) {
        Component c = require(id);
        if (!(c instanceof Sensor<?>)) {
            throw new IllegalArgumentException("Component '" + id + "' is not a Sensor");
        }
        return (Sensor<Boolean>) c;
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

    /**
     * All tools bound in this registry, in manifest declaration order. Unlike
     * {@link #wheels()}, this follows the declared {@code TOOL} entries rather
     * than a mount-angle sort, so callers see a stable, meaningful order.
     */
    public List<Tool> tools() {
        List<Tool> tools = new ArrayList<>();
        for (ComponentDescription component : description.components()) {
            if (component.type() != ComponentType.TOOL) {
                continue;
            }
            Component bound = components.get(component.id());
            if (!(bound instanceof Tool tool)) {
                throw new IllegalArgumentException("No Tool bound for id '" + component.id() + "'");
            }
            tools.add(tool);
        }
        return List.copyOf(tools);
    }

    private Component require(String id) {
        Component c = components.get(id);
        if (c == null) {
            throw new IllegalArgumentException("No component bound for id '" + id + "'");
        }
        return c;
    }
}
