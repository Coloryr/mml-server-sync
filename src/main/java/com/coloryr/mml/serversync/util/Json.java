package com.coloryr.mml.serversync.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 只服务清单解析的轻量 JSON 读取器，避免给独立 jar 增加第三方依赖。 */
public final class Json {
    private Json() {}

    /**
     * 解析 UTF-8 JSON。
     *
     * @param data JSON 字节
     * @return 根值
     * @throws IOException JSON 非法时
     */
    public static Object parse(byte[] data) throws IOException {
        String text = new String(data, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        Parser parser = new Parser(text);
        Object value = parser.value();
        parser.ws();
        if (parser.pos != text.length()) {
            throw parser.error(Text.of(Msg.TRAILING_CONTENT_AFTER_JSON));
        }
        return value;
    }

    /**
     * 把值当 JSON 对象。
     *
     * @param value 任意值
     * @return Map；不是对象时 null
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object value) {
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return null;
    }

    /**
     * 把值当 JSON 数组。
     *
     * @param value 任意值
     * @return List；不是数组时 null
     */
    @SuppressWarnings("unchecked")
    public static List<Object> list(Object value) {
        if (value instanceof List) {
            return (List<Object>) value;
        }
        return null;
    }

    /**
     * 按候选键取字符串。
     *
     * @param map JSON 对象
     * @param keys 候选键
     * @return 第一个字符串值；没有时空串
     */
    public static String string(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value instanceof String) {
                return (String) value;
            }
        }
        return "";
    }

    /**
     * 取布尔值。
     *
     * @param map JSON 对象
     * @param key 键
     * @return 键为 true 时 true
     */
    public static boolean bool(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof Boolean && (Boolean) value;
    }

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
            this.pos = 0;
        }

        Object value() throws IOException {
            ws();
            if (pos >= text.length()) {
                throw error(Text.of(Msg.EXPECTED_A_JSON_VALUE));
            }
            char c = text.charAt(pos);
            switch (c) {
                case '{':
                    return object();
                case '[':
                    return array();
                case '"':
                    return string();
                case 't':
                    literal("true");
                    return Boolean.TRUE;
                case 'f':
                    literal("false");
                    return Boolean.FALSE;
                case 'n':
                    literal("null");
                    return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return number();
                    }
                    throw error(Text.of(Msg.UNEXPECTED_JSON_CHARACTER) + c);
            }
        }

        private Map<String, Object> object() throws IOException {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++;
            ws();
            if (take('}')) {
                return map;
            }
            while (true) {
                ws();
                if (pos >= text.length() || text.charAt(pos) != '"') {
                    throw error(Text.of(Msg.OBJECT_KEYS_MUST_BE_STRINGS));
                }
                String key = string();
                ws();
                expect(':');
                map.put(key, value());
                ws();
                if (take('}')) {
                    return map;
                }
                expect(',');
            }
        }

        private List<Object> array() throws IOException {
            List<Object> list = new ArrayList<>();
            pos++;
            ws();
            if (take(']')) {
                return list;
            }
            while (true) {
                list.add(value());
                ws();
                if (take(']')) {
                    return list;
                }
                expect(',');
            }
        }

        private String string() throws IOException {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (pos >= text.length()) {
                    throw error(Text.of(Msg.INCOMPLETE_STRING_ESCAPE));
                }
                char escape = text.charAt(pos++);
                switch (escape) {
                    case '"': out.append('"'); break;
                    case '\\': out.append('\\'); break;
                    case '/': out.append('/'); break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    case 'u':
                        if (pos + 4 > text.length()) {
                            throw error(Text.of(Msg.INCOMPLETE_U_ESCAPE));
                        }
                        String hex = text.substring(pos, pos + 4);
                        try {
                            out.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException e) {
                            throw error(Text.of(Msg.INVALID_U_ESCAPE) + hex);
                        }
                        pos += 4;
                        break;
                    default:
                        throw error(Text.of(Msg.UNKNOWN_ESCAPE) + escape);
                }
            }
            throw error(Text.of(Msg.UNTERMINATED_STRING));
        }

        private Object number() throws IOException {
            int start = pos;
            if (text.charAt(pos) == '-') {
                pos++;
            }
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                pos++;
            }
            if (pos < text.length() && text.charAt(pos) == '.') {
                pos++;
                while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                    pos++;
                }
            }
            if (pos < text.length() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                pos++;
                if (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
                    pos++;
                }
                while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                    pos++;
                }
            }
            try {
                return Double.valueOf(text.substring(start, pos));
            } catch (NumberFormatException e) {
                throw error(Text.of(Msg.INVALID_NUMBER));
            }
        }

        /**
         * 匹配字面量。
         *
         * @param word 期望字面量
         */
        private void literal(String word) throws IOException {
            if (!text.startsWith(word, pos)) {
                throw error(Text.of(Msg.EXPECTED) + word);
            }
            pos += word.length();
        }

        /**
         * 匹配单个字符。
         *
         * @param expected 期望字符
         */
        private void expect(char expected) throws IOException {
            ws();
            if (pos >= text.length() || text.charAt(pos) != expected) {
                throw error(Text.of(Msg.EXPECTED_2) + expected + "'");
            }
            pos++;
        }

        /**
         * 消费一个字符（可选）。
         *
         * @param expected 期望字符
         * @return 匹配并消费时 true
         */
        private boolean take(char expected) {
            if (pos < text.length() && text.charAt(pos) == expected) {
                pos++;
                return true;
            }
            return false;
        }

        void ws() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        /**
         * 构造带行列号的解析异常。
         *
         * @param message 原因
         * @return 异常
         */
        IOException error(String message) {
            int line = 1;
            int col = 1;
            for (int i = 0; i < pos && i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    line++;
                    col = 1;
                } else {
                    col++;
                }
            }
            return new IOException(message + Text.of(Msg.LINE) + line
                    + Text.of(Msg.COLUMN) + col + Text.of(Msg.TEXT_45));
        }
    }
}
