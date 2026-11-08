package com.coloryr.mml.serversync;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 发布端旁文件 `server.json.sync.json`：累积废弃路径与屏蔽规则。
 *
 * 这不是 MML 的清单设计：MML 只生成 `server.json`（含可选模组与四种配置方式），
 * 不会生成或读取本旁文件。它是本更新器的扩展能力，长期方案优先使用
 * `Archives` 压缩包同步，逐文件模式再按需使用。
 * 规则语义与发布端 `excludePatterns` 一致：目录前缀、通配、精确匹配，一律忽略大小写。
 * 旁文件缺失时按空规则处理，保留旧清单删除行为。
 */
final class SyncRules {
    /** 禁用后缀：与 MMC / HMCL 一致 */
    static final String DISABLED_SUFFIX = ".disabled";
    /** 旧版禁用后缀，读取时仍认 */
    static final String LEGACY_DISABLE_SUFFIX = ".disable";

    private static final SyncRules EMPTY = new SyncRules(
            Collections.<String>emptyList(), Collections.<String>emptyList());

    /** 历史废弃路径，只接受精确相对路径 */
    final List<String> removed;

    private final Set<String> exact = new HashSet<>();
    private final List<String> prefixes = new ArrayList<>();
    private final List<Pattern> globs = new ArrayList<>();

    private SyncRules(List<String> removed, List<String> ignoreRules) {
        this.removed = Collections.unmodifiableList(new ArrayList<>(removed));
        for (String rule : ignoreRules) {
            String raw = rule == null ? "" : rule.trim().replace('\\', '/');
            // 结尾的 `/` 表示目录前缀，与发布端 path_matcher 一致
            boolean directory = raw.endsWith("/");
            String value = Fs.pathKey(raw);
            if (value.isEmpty()) {
                continue;
            }
            if (directory) {
                prefixes.add(value);
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

    /** 生效的规则条数；无效写法（越界、空规则）不计入 */
    int ignoreCount() {
        return exact.size() + prefixes.size() + globs.size();
    }

    /**
     * 路径是否受屏蔽规则保护。禁用后缀的变体按原路径判断，避免保护名单漏掉
     * 玩家手动停用的同名文件。
     */
    boolean isIgnored(String rawPath) {
        String path = Fs.pathKey(rawPath);
        if (path.isEmpty()) {
            return false;
        }
        if (matches(path)) {
            return true;
        }
        String variant = stripDisabledSuffix(path);
        return !variant.equals(path) && matches(variant);
    }

    private boolean matches(String path) {
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
        return false;
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

    /** 去掉禁用后缀；没有后缀时原样返回。入参必须是已规范化的路径键。 */
    static String stripDisabledSuffix(String path) {
        for (String suffix : new String[]{DISABLED_SUFFIX, LEGACY_DISABLE_SUFFIX}) {
            if (path.endsWith(suffix)) {
                return path.substring(0, path.length() - suffix.length());
            }
        }
        return path;
    }

    /**
     * `*` 允许跨越目录，与发布端 `path_matcher` 一致：只有 `*` 与 `?` 是通配符，
     * 方括号按字面处理（发布端也只在含 `*` / `?` 时才走通配分支）。
     */
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
