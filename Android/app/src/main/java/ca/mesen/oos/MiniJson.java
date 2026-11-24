package ca.mesen.oos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader for the bundled Oracle data files (checkpoints.json,
 * warps.json, ROM .build.json sidecars). Objects become LinkedHashMap, arrays
 * ArrayList, numbers Long or Double, plus String, Boolean and null. Pure Java
 * so host tests run without org.json.
 */
final class MiniJson {
    private final String text;
    private int pos;

    private MiniJson(String text) {
        this.text = text;
    }

    static Object parse(String text) {
        MiniJson reader = new MiniJson(text);
        reader.skipSpace();
        Object value = reader.value();
        reader.skipSpace();
        if (reader.pos != text.length()) throw reader.error("trailing data");
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    static List<Object> array(Object value) {
        return value instanceof List ? (List<Object>) value : new ArrayList<>();
    }

    static String string(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private Object value() {
        if (pos >= text.length()) throw error("unexpected end");
        char c = text.charAt(pos);
        switch (c) {
            case '{': return readObject();
            case '[': return readArray();
            case '"': return readString();
            case 't': return literal("true", Boolean.TRUE);
            case 'f': return literal("false", Boolean.FALSE);
            case 'n': return literal("null", null);
            default: return readNumber();
        }
    }

    private Map<String, Object> readObject() {
        Map<String, Object> out = new LinkedHashMap<>();
        pos++;
        skipSpace();
        if (peek() == '}') {
            pos++;
            return out;
        }
        while (true) {
            skipSpace();
            if (peek() != '"') throw error("expected key");
            String key = readString();
            skipSpace();
            expect(':');
            skipSpace();
            out.put(key, value());
            skipSpace();
            char c = next();
            if (c == '}') return out;
            if (c != ',') throw error("expected , or }");
        }
    }

    private List<Object> readArray() {
        List<Object> out = new ArrayList<>();
        pos++;
        skipSpace();
        if (peek() == ']') {
            pos++;
            return out;
        }
        while (true) {
            skipSpace();
            out.add(value());
            skipSpace();
            char c = next();
            if (c == ']') return out;
            if (c != ',') throw error("expected , or ]");
        }
    }

    private String readString() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') return out.toString();
            if (c != '\\') {
                out.append(c);
                continue;
            }
            char e = next();
            switch (e) {
                case '"': out.append('"'); break;
                case '\\': out.append('\\'); break;
                case '/': out.append('/'); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case 'u':
                    if (pos + 4 > text.length()) throw error("short \\u escape");
                    out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                    pos += 4;
                    break;
                default: throw error("bad escape \\" + e);
            }
        }
    }

    private Object readNumber() {
        int start = pos;
        boolean fraction = false;
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == '.' || c == 'e' || c == 'E') {
                fraction = true;
            } else if (!(c == '-' || c == '+' || (c >= '0' && c <= '9'))) {
                break;
            }
            pos++;
        }
        if (start == pos) throw error("unexpected character");
        String number = text.substring(start, pos);
        try {
            return fraction ? (Object) Double.valueOf(number) : (Object) Long.valueOf(number);
        } catch (NumberFormatException e) {
            throw error("bad number " + number);
        }
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, pos)) throw error("expected " + word);
        pos += word.length();
        return value;
    }

    private void skipSpace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
    }

    private char peek() {
        return pos < text.length() ? text.charAt(pos) : '\0';
    }

    private char next() {
        if (pos >= text.length()) throw error("unexpected end");
        return text.charAt(pos++);
    }

    private void expect(char c) {
        if (next() != c) throw error("expected " + c);
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException("JSON " + message + " at " + pos);
    }
}
