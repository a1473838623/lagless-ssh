package dev.lagless;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Just enough JSON to read a GitHub release document. Objects become maps,
 * arrays become lists, numbers become doubles.
 */
public final class Json {

    private final String src;
    private int pos;

    private Json(String src) {
        this.src = src;
    }

    public static Object parse(String text) {
        Json p = new Json(text);
        p.skipWhitespace();
        Object v = p.value();
        p.skipWhitespace();
        if (p.pos < p.src.length()) {
            throw new IllegalArgumentException("trailing content at offset " + p.pos);
        }
        return v;
    }

    /** Serialize the same value types accepted by the parser. */
    public static String stringify(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) {
            StringBuilder out = new StringBuilder("\"");
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> { if (c < 32) out.append(String.format("\\u%04x", (int) c)); else out.append(c); }
                }
            }
            return out.append('"').toString();
        }
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) return "{" + map.entrySet().stream()
                .map(e -> stringify(e.getKey().toString()) + ":" + stringify(e.getValue()))
                .collect(java.util.stream.Collectors.joining(",")) + "}";
        if (value instanceof Iterable<?> list) {
            List<String> items = new ArrayList<>();
            for (Object item : list) items.add(stringify(item));
            return "[" + String.join(",", items) + "]";
        }
        throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass());
    }

    /**
     * Walks maps by key and lists by index, returning null as soon as a step
     * does not exist, so callers can dig without a pile of null checks.
     */
    public static Object get(Object root, Object... path) {
        Object cur = root;
        for (Object step : path) {
            if (cur instanceof Map<?, ?> m && step instanceof String key) {
                cur = m.get(key);
            } else if (cur instanceof List<?> l && step instanceof Integer i) {
                cur = i >= 0 && i < l.size() ? l.get(i) : null;
            } else {
                return null;
            }
        }
        return cur;
    }

    public static String string(Object root, Object... path) {
        Object v = get(root, path);
        return v instanceof String s ? s : null;
    }

    public static long number(Object root, long fallback, Object... path) {
        Object v = get(root, path);
        return v instanceof Double d ? d.longValue() : fallback;
    }

    public static List<Object> list(Object root, Object... path) {
        Object v = get(root, path);
        return v instanceof List<?> l ? new ArrayList<>(l) : List.of();
    }

    private Object value() {
        if (pos >= src.length()) {
            throw new IllegalArgumentException("unexpected end of input");
        }
        char c = src.charAt(pos);
        switch (c) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
                return string();
            case 't':
                expect("true");
                return Boolean.TRUE;
            case 'f':
                expect("false");
                return Boolean.FALSE;
            case 'n':
                expect("null");
                return null;
            default:
                return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++;
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWhitespace();
            String key = string();
            skipWhitespace();
            if (peek() != ':') {
                throw new IllegalArgumentException("expected ':' at offset " + pos);
            }
            pos++;
            skipWhitespace();
            map.put(key, value());
            skipWhitespace();
            char c = peek();
            pos++;
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                throw new IllegalArgumentException("expected ',' or '}' at offset " + (pos - 1));
            }
        }
    }

    private List<Object> array() {
        List<Object> out = new ArrayList<>();
        pos++;
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return out;
        }
        while (true) {
            skipWhitespace();
            out.add(value());
            skipWhitespace();
            char c = peek();
            pos++;
            if (c == ']') {
                return out;
            }
            if (c != ',') {
                throw new IllegalArgumentException("expected ',' or ']' at offset " + (pos - 1));
            }
        }
    }

    private String string() {
        if (peek() != '"') {
            throw new IllegalArgumentException("expected a string at offset " + pos);
        }
        pos++;
        StringBuilder b = new StringBuilder();
        while (true) {
            if (pos >= src.length()) {
                throw new IllegalArgumentException("unterminated string");
            }
            char c = src.charAt(pos++);
            if (c == '"') {
                return b.toString();
            }
            if (c != '\\') {
                b.append(c);
                continue;
            }
            char esc = src.charAt(pos++);
            switch (esc) {
                case '"', '\\', '/' -> b.append(esc);
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'n' -> b.append('\n');
                case 'r' -> b.append('\r');
                case 't' -> b.append('\t');
                case 'u' -> {
                    b.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                    pos += 4;
                }
                default -> throw new IllegalArgumentException("bad escape at offset " + (pos - 1));
            }
        }
    }

    private Double number() {
        int start = pos;
        while (pos < src.length() && "+-.eE0123456789".indexOf(src.charAt(pos)) >= 0) {
            pos++;
        }
        if (start == pos) {
            throw new IllegalArgumentException("expected a value at offset " + pos);
        }
        return Double.valueOf(src.substring(start, pos));
    }

    private char peek() {
        if (pos >= src.length()) {
            throw new IllegalArgumentException("unexpected end of input");
        }
        return src.charAt(pos);
    }

    private void expect(String word) {
        if (!src.startsWith(word, pos)) {
            throw new IllegalArgumentException("expected " + word + " at offset " + pos);
        }
        pos += word.length();
    }

    private void skipWhitespace() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }
}
