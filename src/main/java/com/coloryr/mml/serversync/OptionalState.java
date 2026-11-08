package com.coloryr.mml.serversync;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 自选模组的状态，落在状态目录下的两个文件里。
 *
 * `optional-state.txt` 一行一条 TSV：内容哈希、清单路径、上次是否选择更新（1/0），
 * 负责记住玩家上一次的勾选、判断新增与内容变化。`optional-prompt.txt` 只有一行 `suppress=1`，
 * 表示玩家勾过「以后不再显示」，此后只在有新增或内容变化时弹窗。
 *
 * 两个文件分开：标记行混进三列 TSV 会让逐行解析多出一条不成形的记录。
 */
final class OptionalState {
    /** 状态文件名，放在状态目录下 */
    static final String FILE_NAME = "optional-state.txt";
    /** 「以后不再显示」标记文件名 */
    static final String PROMPT_FILE_NAME = "optional-prompt.txt";
    /** 标记文件里表示已记住的那一行 */
    private static final String PROMPT_SUPPRESSED_LINE = "suppress=1";
    /** 旧版跳过记录文件名；存在时迁移成「未勾选」后删除 */
    static final String LEGACY_FILE_NAME = "skipped.txt";

    private OptionalState() {}

    /** 一条选择记录 */
    static final class Entry {
        /** 清单条目当时的哈希：SHA1，没有时是 SHA256 */
        final String sha1;
        /** 清单里的相对路径，`/` 分隔 */
        final String file;
        /** 上次是否选择更新 */
        final boolean selected;

        Entry(String sha1, String file, boolean selected) {
            this.sha1 = sha1 == null ? "" : sha1.trim();
            this.file = file == null ? "" : file.trim();
            this.selected = selected;
        }
    }

    /** 一次加载出的状态：记录列表 + 是否首次同步 */
    static final class Loaded {
        /** 路径 → 记录 */
        final Map<String, Entry> byPath = new HashMap<>();
        /** 状态文件不存在时为 true：按首次同步处理，弹窗并默认全选；旧记录迁移后也会置位 */
        boolean firstRun;

        Loaded(boolean firstRun) {
            this.firstRun = firstRun;
        }

        /** 该路径上次的勾选状态；没有记录时返回 null */
        Boolean selected(String path) {
            Entry entry = byPath.get(path);
            return entry == null ? null : entry.selected;
        }

        /** 该路径上次记录的内容哈希；没有记录时返回空串 */
        String hashOf(String path) {
            Entry entry = byPath.get(path);
            return entry == null ? "" : entry.sha1;
        }

        /**
         * 同一族（`[分类]` 标签相同）上一次的选择（更新 / 跳过）。
         *
         * 模组换版本后清单路径会变（`...歌]X-1.0.jar` → `...歌]X-1.1.jar`），
         * 新路径本身没有记录；此时沿用同族旧路径的选择，玩家上次跳过的模组
         * 不会在换名后突然被下载。
         *
         * - `path`: 清单里的相对路径
         *
         * # 返回值
         *
         * 同族的上次选择；没有同族记录或有冲突时返回 null（按新增条目处理）
         */
        Boolean inherited(String path) {
            String family = familyOf(path);
            if (family.isEmpty()) {
                return null;
            }
            Boolean found = null;
            for (Map.Entry<String, Entry> item : byPath.entrySet()) {
                if (!family.equals(familyOf(item.getKey()))) {
                    continue;
                }
                boolean value = item.getValue().selected;
                if (found != null && found != value) {
                    return null;
                }
                found = value;
            }
            return found;
        }

        /**
         * 同族标识：目录 + 文件名开头连续的 `[标签]` 组（至少两组）。
         *
         * 本包的自选模组统一带 `[C][自选][点歌]` 这类标签，标签之后才是文件名与版本号；
         * 只取开头连续的方括号组，文件名末尾的 `[1.3.5]` 这类版本标签不会被算进族，
         * 否则版本一变就认不出是同一个模组。
         */
        static String familyOf(String path) {
            String value = path == null ? "" : path.replace('\\', '/').trim();
            int slash = value.lastIndexOf('/');
            String dir = slash < 0 ? "" : value.substring(0, slash + 1);
            String name = slash < 0 ? value : value.substring(slash + 1);
            int index = 0;
            int groups = 0;
            while (index < name.length() && name.charAt(index) == '[') {
                int close = name.indexOf(']', index);
                if (close < 0) {
                    break;
                }
                index = close + 1;
                groups++;
            }
            return groups < 2 ? "" : dir + name.substring(0, index);
        }
    }

    /**
     * 读状态目录里的选择状态
     *
     * 旧版 skipped.txt（只记「跳过」）存在时迁移：其中的路径按「这次跳过」接入，
     * 其余视为首次同步。迁移后删除旧文件。
     *
     * - `stateDir`: 状态目录
     */
    static Loaded load(Path stateDir) {
        Loaded loaded = new Loaded(false);
        Path file = stateDir.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            migrateLegacy(stateDir, loaded);
            return loaded;
        }
        readEntries(file, loaded);
        return loaded;
    }

    private static void readEntries(Path file, Loaded out) {
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                String[] parts = line.split("\t", -1);
                if (parts.length < 3) {
                    continue;
                }
                Entry entry = new Entry(parts[0], parts[1], "1".equals(parts[2].trim()));
                if (!entry.file.isEmpty()) {
                    out.byPath.put(entry.file, entry);
                }
            }
        } catch (IOException e) {
            Log.error(Text.of(Msg.FAILED_TO_READ_THE_OPTIONAL_STATE) + e.getMessage());
        }
    }

    /** 旧 skipped.txt 的路径按未勾选迁入；迁完把旧文件删掉 */
    private static void migrateLegacy(Path stateDir, Loaded out) {
        Path legacy = stateDir.resolve(LEGACY_FILE_NAME);
        if (!Files.isRegularFile(legacy)) {
            out.firstRun = true;
            return;
        }
        try {
            boolean any = false;
            // 旧 skipped.txt 的路径按「跳过本次更新」迁入
            for (String line : Files.readAllLines(legacy, StandardCharsets.UTF_8)) {
                String[] parts = line.split("\t", -1);
                if (parts.length < 2 || parts[1].trim().isEmpty()) {
                    continue;
                }
                out.byPath.put(parts[1].trim(), new Entry(parts[0], parts[1].trim(), false));
                any = true;
            }
            if (any) {
                Log.info(Text.of(Msg.OPTIONAL_STATE_MIGRATED));
            }
            Files.deleteIfExists(legacy);
        } catch (IOException e) {
            Log.error(Text.of(Msg.FAILED_TO_READ_THE_OPTIONAL_STATE) + e.getMessage());
        }
    }

    /**
     * 重写选择状态；没有任何记录时把文件删掉
     *
     * - `stateDir`: 状态目录
     * - `entries`: 本次的选择记录
     */
    static void save(Path stateDir, List<Entry> entries) throws IOException {
        Path file = stateDir.resolve(FILE_NAME);
        if (entries.isEmpty()) {
            Files.deleteIfExists(file);
            return;
        }
        Files.createDirectories(stateDir);
        StringBuilder out = new StringBuilder();
        for (Entry entry : entries) {
            out.append(entry.sha1).append('\t')
                    .append(entry.file).append('\t')
                    .append(entry.selected ? '1' : '0').append('\n');
        }
        Path temp = stateDir.resolve(FILE_NAME + ".tmp");
        Files.write(temp, out.toString().getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.deleteIfExists(temp);
    }

    /** 便于构造保存列表 */
    static List<Entry> entries(Map<String, Boolean> selections, Map<String, String> hashes) {
        List<Entry> out = new ArrayList<>();
        for (Map.Entry<String, Boolean> item : selections.entrySet()) {
            out.add(new Entry(hashes.getOrDefault(item.getKey(), ""), item.getKey(), item.getValue()));
        }
        return out;
    }

    /**
     * 玩家是否勾过「记住我的选择，以后不再显示」
     *
     * 标记文件缺失、读不出来或内容不是 `suppress=1` 时都按未记住处理：宁可多弹一次，
     * 也不要让一个坏文件把入口彻底藏起来。
     *
     * - `stateDir`: 状态目录
     *
     * # 返回值
     *
     * 已记住时返回 true
     */
    static boolean promptSuppressed(Path stateDir) {
        Path file = stateDir.resolve(PROMPT_FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (PROMPT_SUPPRESSED_LINE.equals(line.trim())) {
                    return true;
                }
            }
        } catch (IOException e) {
            Log.error(Text.of(Msg.FAILED_TO_READ_THE_OPTIONAL_PROMPT_STATE) + e.getMessage());
        }
        return false;
    }

    /**
     * 记住 / 取消记住「以后不再显示」
     *
     * 取消记住就是把标记文件删掉，不留 `suppress=0` 这种同样要解析的中间状态。
     *
     * - `stateDir`: 状态目录
     * - `suppressed`: 是否记住
     */
    static void savePromptSuppressed(Path stateDir, boolean suppressed) throws IOException {
        Path file = stateDir.resolve(PROMPT_FILE_NAME);
        if (!suppressed) {
            Files.deleteIfExists(file);
            return;
        }
        Files.createDirectories(stateDir);
        Files.write(file, Collections.singletonList(PROMPT_SUPPRESSED_LINE), StandardCharsets.UTF_8);
    }
}
