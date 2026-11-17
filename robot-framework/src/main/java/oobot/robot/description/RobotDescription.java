package oobot.robot.description;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A parsed component manifest: a robot name plus its declared components.
 * Loaded once at startup from a JSON file via {@link #load(Path)}.
 */
public record RobotDescription(String name, List<ComponentDescription> components) {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final double DEFAULT_WHEEL_OFFSET = 0.25;
    private static final double LATERAL_ROLE_THRESHOLD = 0.9;

    /**
     * Read and validate a component manifest from {@code path}.
     *
     * @throws IllegalArgumentException if the file cannot be read or violates the
     *                                  manifest schema; the message names the file and the problem.
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

        JsonNode componentsNode = root.get("components");
        if (componentsNode == null || !componentsNode.isArray()) {
            throw problem(path, "components must be an array");
        }

        List<ComponentDescription> components = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        int leftWheels = 0;
        int rightWheels = 0;

        for (JsonNode node : componentsNode) {
            if (!node.isObject()) {
                throw problem(path, "each component must be a JSON object");
            }

            JsonNode idNode = node.get("id");
            if (idNode == null || !idNode.isTextual() || idNode.textValue().isBlank()) {
                throw problem(path, "component id must be a non-empty string");
            }
            String id = idNode.textValue();
            if (!ids.add(id)) {
                throw problem(path, "duplicate component id '" + id + "'");
            }

            if (node.has("side")) {
                throw problem(path, "component '" + id + "' must not declare 'side' in schemaVersion 2");
            }

            JsonNode typeNode = node.get("type");
            ComponentType type;
            if (typeNode == null || !typeNode.isTextual()) {
                throw problem(path, "component '" + id + "' has a missing or non-string type");
            }
            try {
                type = parseComponentType(typeNode.textValue());
            } catch (IllegalArgumentException e) {
                throw problem(path, "component '" + id + "' has unknown type '" + typeNode.textValue() + "'");
            }

            JsonNode angleNode = node.get("angle");
            if (angleNode == null || !angleNode.isNumber()) {
                throw problem(path, "component '" + id + "' has a missing or non-numeric angle");
            }
            double angle = angleNode.doubleValue();
            if (!Double.isFinite(angle)) {
                throw problem(path, "component '" + id + "' has a non-finite angle");
            }

            boolean hasOffset = node.has("offset");
            double offset = DEFAULT_WHEEL_OFFSET;
            if (type == ComponentType.PROXIMITY_SENSOR) {
                if (hasOffset) {
                    throw problem(path, "sensor '" + id + "' must not declare an offset");
                }
            } else { // WHEEL
                if (hasOffset) {
                    JsonNode offsetNode = node.get("offset");
                    if (!offsetNode.isNumber()) {
                        throw problem(path, "wheel '" + id + "' has a non-numeric offset");
                    }
                    offset = offsetNode.doubleValue();
                    if (!Double.isFinite(offset) || offset <= 0.0) {
                        throw problem(path, "wheel '" + id + "' offset must be finite and > 0");
                    }
                }

                double lateral = Math.sin(angle);
                if (lateral > LATERAL_ROLE_THRESHOLD) {
                    leftWheels++;
                } else if (lateral < -LATERAL_ROLE_THRESHOLD) {
                    rightWheels++;
                } else {
                    throw problem(path, "wheel '" + id + "' angle must place it on the left or right "
                            + "(sin(angle) > 0.9 or sin(angle) < -0.9)");
                }
            }

            components.add(new ComponentDescription(id, type, angle, offset));
        }

        if (leftWheels != 1 || rightWheels != 1) {
            throw problem(path, "expected exactly one left and one right wheel, found "
                    + leftWheels + " left and " + rightWheels + " right");
        }

        return new RobotDescription(name, List.copyOf(components));
    }

    /** Accept the manifest's lowercase spellings as well as enum constant names. */
    private static ComponentType parseComponentType(String raw) {
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "WHEEL" -> ComponentType.WHEEL;
            case "PROXIMITY_SENSOR", "PROXIMITYSENSOR" -> ComponentType.PROXIMITY_SENSOR;
            default -> throw new IllegalArgumentException("unknown type: " + raw);
        };
    }

    private static IllegalArgumentException problem(Path path, String detail) {
        return new IllegalArgumentException("Invalid component manifest " + path + ": " + detail);
    }
}
