package com.coloryr.mml.serversync;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 发布端旁文件 `server.json.sync.json`：累积废弃路径与屏蔽规则。
 * 规则语义与发布端 `excludePatterns` 一致：目录前缀、通配、精确匹配，一律忽略大小写。
 * 旁文件缺失或格式错误时按空规则处理，不改变原有同步行为。
 */
final class SyncRules {
    private static final SyncRules EMPTY = new SyncRules(
            Collections.<String>emptyList(), Collections.<String>emptyList());

    /** 历史废弃路径，只接受精确相对路径 */
    final List<String> removed;
    private final List<String> ignoreRules;

    private final Set<String> exact = new HashSet<>();
    private final List<String> prefixes = new ArrayList<>();
    private final List<Pattern> globs = new ArrayList<>();

    private SyncRules(List<String> removed, List<String> ignoreRules) {
        this.removed = Collections.unmodifiableList(new ArrayList<>(removed));
        this.ignoreRules = Collections.unmodifiableList(new ArrayList<>(ignoreRules));
        for (String rule : ignoreRules) {
            String value = normalize(rule);
            if (value.isEmpty()) {
                continue;
            }
            if (value.endsWith("/")) {
                prefixes.add(value.substring(0, value.length() - 1));
            } else if (value.indexOf('*') >= 0 || value.indexOf('?') >= 0) {
                globs.add(Pattern.compile(toRegex(value)));
            } else {
                exact.add(value);
            }
        }
    }

    static SyncRules empty() {
        return EMPTY;
    }

    /** 兼容发布端新旧字段名；同名条目只保留一次 */
    static SyncRules parse(byte[] data) throws IOException {
        Map<String, Object> root = Json.map(Json.parse(data));
        if (root == null) {
            throw new IOException(Text.of(Msg.SYNC_RULES_ROOT_INVALID));
        }
        return new SyncRules(strings(root, "Removed", "removedFiles"),
                strings(root, "Ignore", "noOverwriteFiles"));
    }

    int ignoreCount() {
        return ignoreRules.size();
    }

    /**
     * 路径是否受屏蔽规则保护。禁用后缀的变体按原路径判断，避免保护名单漏掉
     * 玩家手动停用的同名文件。
     */
    boolean isIgnored(String rawPath) {
        String path = normalize(rawPath);
        if (path.isEmpty()) {
            return false;
        }
        if (exact.contains(path)) {
            return true;
        }
        for (String prefix : prefixes) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        for (Pattern glob : globs) {
            if (glob.matcher(path).matches()) {
                return true;
            }
        }
        String variant = stripDisabledSuffix(path);
        return !variant.equals(path) && isIgnored(variant);
    }

    private static List<String> strings(Map<String, Object> root, String... keys) {
        List<String> out = new ArrayList<>();
        for (String key : keys) {
            List<Object> raw = Json.list(root.get(key));
            if (raw == null) {
                continue;
            }
            for (Object item : raw) {
                String value = text(item);
                if (!value.isEmpty() && !out.contains(value)) {
                    out.add(value);
                }
            }
        }
        return out;
    }

    /** 数组元素可以是字符串，也可以是带 `path` / `File` 字段的对象 */
    private static String text(Object value) {
        if (value instanceof String) {
            return ((String) value).trim();
        }
        Map<String, Object> item = Json.map(value);
        return item == null ? "" : Json.string(item, "path", "Path", "File", "file").trim();
    }

    /** 统一分隔符、去掉前导路径标记并折叠大小写；Windows 文件名不区分大小写 */
    private static String normalize(String raw) {
        String value = raw == null ? "" : raw.trim().replace('\\', '/');
        while (value.startsWith("./")) {
            value = value.substring(2);
        }
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static String stripDisabledSuffix(String path) {
        for (String suffix : new String[]{".disabled", ".disable"}) {
            if (path.endsWith(suffix)) {
                return path.substring(0, path.length() - suffix.length());
            }
        }
        return path;
    }

    /** `*` 允许跨越目录，与发布端 fnmatch 语义一致 */
    private static String toRegex(String pattern) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*') {
                out.append(".*");
            } else if (c == '?') {
                out.append('.');
            } else {
                out.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return out.toString();
    }
}
