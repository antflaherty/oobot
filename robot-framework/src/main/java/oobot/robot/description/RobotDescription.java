package oobot.robot.description;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A parsed component manifest: a robot name, its physical body dimensions and
 * its declared components. Loaded once at startup from a JSON file via
 * {@link #load(Path)}.
 */
public record RobotDescription(String name, RobotBody body, List<ComponentDescription> components) {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final double DEFAULT_WHEEL_OFFSET = 0.25;

    /**
     * Convenience constructor for callers that do not care about the physical
     * body (e.g. tests of wheel layout); uses {@link RobotBody#DEFAULT}.
     */
    public RobotDescription(String name, List<ComponentDescription> components) {
        this(name, RobotBody.DEFAULT, components);
    }

    /**
     * Magnitude of {@code sin(angle)} above which a wheel takes the left role
     * (positive) or the right role (negative). Shared by the bridge and the
     * example mover so every layer derives roles with the same rule.
     */
    public static final double WHEEL_ROLE_LATERAL_THRESHOLD = 0.9;

    /**
     * Read and validate a component manifest from {@code path}.
     *
     * @throws IllegalArgumentException if the file cannot be read or violates the
     *                                  manifest schema; the message names the file,
     *                                  the {@code component[i]} index or the
     *                                  {@code body.*} key where applicable, and the
     *                                  problem.
     */
    public static RobotDescription load(Path path) {
        JsonNode root;
        try {
            root = MAPPER.readTree(path.toFile());
        } catch (IOException e) {
            throw problem(path, "cannot be read (" + e.getMessage() + ")");
        }
        if (root == null || !root.isObject()) {
            throw problem(path, "root must be a JSON object");
        }

        JsonNode version = root.get("schemaVersion");
        if (version == null || !version.isInt() || version.intValue() != 2) {
            throw problem(path, "schemaVersion must be 2");
        }

        JsonNode nameNode = root.get("name");
        if (nameNode == null || !nameNode.isTextual() || nameNode.textValue().isBlank()) {
            throw problem(path, "name must be a non-empty string");
        }
        String name = nameNode.textValue();

        RobotBody body = parseBody(root.get("body"), path);

        JsonNode componentsNode = root.get("components");
        if (componentsNode == null || !componentsNode.isArray()) {
            throw problem(path, "components must be an array");
        }

        List<ComponentDescription> components = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        int leftWheels = 0;
        int rightWheels = 0;

        for (int i = 0; i < componentsNode.size(); i++) {
            JsonNode node = componentsNode.get(i);
            if (!node.isObject()) {
                throw componentProblem(path, i, "must be a JSON object");
            }

            JsonNode idNode = node.get("id");
            if (idNode == null || !idNode.isTextual() || idNode.textValue().isBlank()) {
                throw componentProblem(path, i, "id must be a non-empty string");
            }
            String id = idNode.textValue();
            if (!ids.add(id)) {
                throw componentProblem(path, i, "duplicate id '" + id + "'");
            }

            if (node.has("side")) {
                throw componentProblem(path, i,
                        "component '" + id + "' must not declare 'side' in schemaVersion 2");
            }

            JsonNode typeNode = node.get("type");
            ComponentType type;
            if (typeNode == null || !typeNode.isTextual()) {
                throw componentProblem(path, i,
                        "component '" + id + "' has a missing or non-string type");
            }
            try {
                type = parseComponentType(typeNode.textValue());
            } catch (IllegalArgumentException e) {
                throw componentProblem(path, i,
                        "component '" + id + "' has unknown type '" + typeNode.textValue() + "'");
            }

            JsonNode angleNode = node.get("angle");
            if (angleNode == null || !angleNode.isNumber()) {
                throw componentProblem(path, i,
                        "component '" + id + "' has a missing or non-numeric angle");
            }
            double angle = angleNode.doubleValue();
            if (!Double.isFinite(angle)) {
                throw componentProblem(path, i, "component '" + id + "' has a non-finite angle");
            }

            boolean hasOffset = node.has("offset");
            // Sensors and tools have no radial offset; only wheels default to one.
            double offset = 0.0;
            if (type == ComponentType.PROXIMITY_SENSOR
                    || type == ComponentType.GOAL_SENSOR
                    || type == ComponentType.TOOL) {
                if (hasOffset) {
                    throw componentProblem(path, i, type == ComponentType.TOOL
                            ? "tool '" + id + "' must not declare an offset"
                            : "sensor '" + id + "' must not declare an offset");
                }
            } else { // WHEEL
                offset = DEFAULT_WHEEL_OFFSET;
                if (hasOffset) {
                    JsonNode offsetNode = node.get("offset");
                    if (!offsetNode.isNumber()) {
                        throw componentProblem(path, i,
                                "wheel '" + id + "' has a non-numeric offset");
                    }
                    offset = offsetNode.doubleValue();
                    if (!Double.isFinite(offset) || offset <= 0.0) {
                        throw componentProblem(path, i,
                                "wheel '" + id + "' offset must be finite and > 0");
                    }
                }

                double lateral = Math.sin(angle);
                if (lateral > WHEEL_ROLE_LATERAL_THRESHOLD) {
                    leftWheels++;
                } else if (lateral < -WHEEL_ROLE_LATERAL_THRESHOLD) {
                    rightWheels++;
                } else {
                    throw componentProblem(path, i,
                            "wheel '" + id + "' angle must place it on the left or right "
                            + "(sin(angle) > " + WHEEL_ROLE_LATERAL_THRESHOLD
                            + " or sin(angle) < -" + WHEEL_ROLE_LATERAL_THRESHOLD + ")");
                }
            }

            components.add(new ComponentDescription(id, type, angle, offset));
        }

        if (leftWheels != 1 || rightWheels != 1) {
            throw problem(path, "expected exactly one left and one right wheel, found "
                    + leftWheels + " left and " + rightWheels + " right");
        }

        return new RobotDescription(name, body, List.copyOf(components));
    }

    /**
     * Parse the optional physical body block. Missing keys inherit defaults;
     * supplied keys are validated individually and unknown keys are ignored.
     * Failures name the manifest path and the offending {@code body.*} key.
     */
    private static RobotBody parseBody(JsonNode node, Path path) {
        if (node == null) {
            return RobotBody.DEFAULT;
        }
        if (!node.isObject()) {
            throw problem(path, "body must be a non-null object");
        }

        double width = readBodyDimension(node.get("width"), "body.width", path,
                RobotBody.DEFAULT.width());
        double height = readBodyDimension(node.get("height"), "body.height", path,
                RobotBody.DEFAULT.height());

        JsonNode wheelsNode = node.get("wheels");
        WheelGeometry wheels;
        if (wheelsNode == null) {
            wheels = WheelGeometry.DEFAULT;
        } else {
            if (!wheelsNode.isObject()) {
                throw problem(path, "body.wheels must be a non-null object");
            }
            wheels = new WheelGeometry(
                    readBodyDimension(wheelsNode.get("length"), "body.wheels.length", path,
                            WheelGeometry.DEFAULT.length()),
                    readBodyDimension(wheelsNode.get("width"), "body.wheels.width", path,
                            WheelGeometry.DEFAULT.width()));
        }

        return new RobotBody(width, height, wheels);
    }

    /** A supplied dimension must be a finite number strictly greater than zero. */
    private static double readBodyDimension(JsonNode node, String label, Path path, double fallback) {
        if (node == null) {
            return fallback;
        }
        if (!node.isNumber()) {
            throw problem(path, label + " must be a finite number > 0");
        }
        double value = node.doubleValue();
        if (!Double.isFinite(value) || value <= 0.0) {
            throw problem(path, label + " must be a finite number > 0");
        }
        return value;
    }

    /** Accept only the exact schema spellings (same rule as the TypeScript loader). */
    private static ComponentType parseComponentType(String raw) {
        return switch (raw) {
            case "wheel" -> ComponentType.WHEEL;
            case "proximitySensor" -> ComponentType.PROXIMITY_SENSOR;
            case "goalSensor" -> ComponentType.GOAL_SENSOR;
            case "tool" -> ComponentType.TOOL;
            default -> throw new IllegalArgumentException("unknown type: " + raw);
        };
    }

    private static IllegalArgumentException problem(Path path, String detail) {
        return new IllegalArgumentException("Invalid component manifest " + path + ": " + detail);
    }

    /** Component-scoped failures name both the manifest path and the array index. */
    private static IllegalArgumentException componentProblem(Path path, int index, String detail) {
        return problem(path, "component[" + index + "]: " + detail);
    }
}
