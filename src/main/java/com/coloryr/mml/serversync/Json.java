package com.coloryr.mml.serversync;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 只服务清单解析的轻量 JSON 读取器，避免给独立 jar 增加第三方依赖。 */
final class Json {
    private Json() {}

    static Object parse(byte[] data) throws IOException {
        String text = new String(data, java.nio.charset.StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        Parser parser = new Parser(text);
        Object value = parser.value();
        parser.ws();
        if (parser.pos != text.length()) {
            throw parser.error("JSON 末尾还有内容");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object value) {
        if (value instanceof List) {
            return (List<Object>) value;
        }
        return null;
    }

    static String string(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value instanceof String) {
                return (String) value;
            }
        }
        return "";
    }

    static boolean bool(Map<String, Object> map, String key) {
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
                throw error("需要 JSON 值");
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
                    throw error("不认识的 JSON 字符: " + c);
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
                    throw error("对象键必须是字符串");
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
                    throw error("字符串转义不完整");
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
                            throw error("\\u 转义不完整");
                        }
                        String hex = text.substring(pos, pos + 4);
                        try {
                            out.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException e) {
                            throw error("非法的 \\u 转义: " + hex);
                        }
                        pos += 4;
                        break;
                    default:
                        throw error("不认识的转义: \\" + escape);
                }
            }
            throw error("字符串没有闭合");
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
                throw error("非法数字");
            }
        }

        private void literal(String word) throws IOException {
            if (!text.startsWith(word, pos)) {
                throw error("需要 " + word);
            }
            pos += word.length();
        }

        private void expect(char expected) throws IOException {
            ws();
            if (pos >= text.length() || text.charAt(pos) != expected) {
                throw error("需要 '" + expected + "'");
            }
            pos++;
        }

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
            return new IOException(message + "（第 " + line + " 行，第 " + col + " 列）");
        }
    }
}
