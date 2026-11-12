package oobot.robot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON writer/reader for the locked wire protocol.
 * <p>
 * Only what the MVP needs is implemented (no external libraries):
 * <ul>
 *   <li>writing {@code motorCommand} envelopes,</li>
 *   <li>parsing incoming {@code sensorSnapshot} envelopes well enough to
 *       extract {@code type} and {@code simTick}.</li>
 * </ul>
 * Malformed input yields {@code null} (log and ignore; never crash).
 */
final class Json {
    private Json() {}

    static String writeMotorCommand(long seq, long simTick, double left, double right) {
        return "{\"type\":\"motorCommand\",\"seq\":" + seq
            + ",\"simTick\":" + simTick
            + ",\"payload\":{\"left\":" + fmt(left) + ",\"right\":" + fmt(right) + "}}";
    }

    static String fmt(double d) {
        if (!Double.isFinite(d)) {
            return "0";
        }
        return Double.toString(d);
    }

    /**
     * Parse one NDJSON line into an {@link Envelope}. Returns {@code null} if
     * the line is malformed or not an object envelope (log and ignore).
     */
    static Envelope parseEnvelope(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        try {
            int[] pos = {0};
            Object value = parse(line, pos);
            skipWhitespace(line, pos);
            if (!(value instanceof Map) || pos[0] != line.length()) {
                return null; // not an object, or trailing garbage -> malformed
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) value;
            Object typeObj = m.get("type");
            String type = (typeObj instanceof String) ? (String) typeObj : null;
            if (type == null) {
                return null;
            }
            long seq = asLong(m.get("seq"), 0L);
            long simTick = asLong(m.get("simTick"), 0L);
            Object payload = m.get("payload");
            return new Envelope(type, seq, simTick, payload);
        } catch (Exception e) {
            return null; // malformed: log and ignore
        }
    }

    private static long asLong(Object o, long def) {
        if (o == null) {
            return def;
        }
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        if (o instanceof String) {
            try {
                return Long.parseLong(((String) o).trim());
            } catch (Exception e) {
                return def;
            }
        }
        return def;
    }

    /* ---------------- minimal JSON parser ---------------- */

    private static Object parse(String s, int[] pos) {
        skipWhitespace(s, pos);
        if (pos[0] >= s.length()) {
            throw new IllegalArgumentException("unexpected end of input");
        }
        char c = s.charAt(pos[0]);
        switch (c) {
            case '{': return parseObject(s, pos);
            case '[': return parseArray(s, pos);
            case '"': return parseString(s, pos);
            case 't': return parseLiteral(s, pos, "true", true);
            case 'f': return parseLiteral(s, pos, "false", false);
            case 'n': return parseLiteral(s, pos, "null", null);
            default: return parseNumber(s, pos);
        }
    }

    private static void skipWhitespace(String s, int[] pos) {
        while (pos[0] < s.length() && Character.isWhitespace(s.charAt(pos[0]))) {
            pos[0]++;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseObject(String s, int[] pos) {
        Map<String, Object> m = new LinkedHashMap<>();
        pos[0]++; // '{'
        skipWhitespace(s, pos);
        if (pos[0] < s.length() && s.charAt(pos[0]) == '}') {
            pos[0]++;
            return m;
        }
        while (true) {
            skipWhitespace(s, pos);
            if (pos[0] >= s.length() || s.charAt(pos[0]) != '"') {
                throw new IllegalArgumentException("expected string key");
            }
            String key = parseString(s, pos);
            skipWhitespace(s, pos);
            if (pos[0] >= s.length() || s.charAt(pos[0]) != ':') {
                throw new IllegalArgumentException("expected ':'");
            }
            pos[0]++;
            m.put(key, parse(s, pos));
            skipWhitespace(s, pos);
            if (pos[0] >= s.length()) {
                throw new IllegalArgumentException("unexpected end of input");
            }
            char c = s.charAt(pos[0]);
            if (c == ',') {
                pos[0]++;
                continue;
            }
            if (c == '}') {
                pos[0]++;
                return m;
            }
            throw new IllegalArgumentException("expected ',' or '}'");
        }
    }

    private static List<Object> parseArray(String s, int[] pos) {
        List<Object> list = new ArrayList<>();
        pos[0]++; // '['
        skipWhitespace(s, pos);
        if (pos[0] < s.length() && s.charAt(pos[0]) == ']') {
            pos[0]++;
            return list;
        }
        while (true) {
            list.add(parse(s, pos));
            skipWhitespace(s, pos);
            if (pos[0] >= s.length()) {
                throw new IllegalArgumentException("unexpected end of input");
            }
            char c = s.charAt(pos[0]);
            if (c == ',') {
                pos[0]++;
                continue;
            }
            if (c == ']') {
                pos[0]++;
                return list;
            }
            throw new IllegalArgumentException("expected ',' or ']'");
        }
    }

    private static String parseString(String s, int[] pos) {
        pos[0]++; // opening quote
        StringBuilder sb = new StringBuilder();
        while (pos[0] < s.length()) {
            char c = s.charAt(pos[0]++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                if (pos[0] >= s.length()) {
                    throw new IllegalArgumentException("bad escape");
                }
                char e = s.charAt(pos[0]++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (pos[0] + 4 > s.length()) {
                            throw new IllegalArgumentException("bad unicode escape");
                        }
                        int cp = Integer.parseInt(s.substring(pos[0], pos[0] + 4), 16);
                        pos[0] += 4;
                        sb.append((char) cp);
                        break;
                    default:
                        throw new IllegalArgumentException("bad escape: " + e);
                }
            } else {
                sb.append(c);
            }
        }
        throw new IllegalArgumentException("unterminated string");
    }

    private static Object parseLiteral(String s, int[] pos, String lit, Object value) {
        for (int i = 0; i < lit.length(); i++) {
            if (pos[0] + i >= s.length() || s.charAt(pos[0] + i) != lit.charAt(i)) {
                throw new IllegalArgumentException("invalid literal");
            }
        }
        pos[0] += lit.length();
        return value;
    }

    private static Object parseNumber(String s, int[] pos) {
        int start = pos[0];
        if (pos[0] < s.length() && (s.charAt(pos[0]) == '-' || s.charAt(pos[0]) == '+')) {
            pos[0]++;
        }
        while (pos[0] < s.length()) {
            char c = s.charAt(pos[0]);
            if (Character.isDigit(c) || c == '.' || c == 'e' || c == 'E') {
                pos[0]++;
            } else {
                break;
            }
        }
        return Double.parseDouble(s.substring(start, pos[0]));
    }
}
