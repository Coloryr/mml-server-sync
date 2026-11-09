package com.coloryr.mml.serversync.sync;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.coloryr.mml.serversync.util.Log;
import com.coloryr.mml.serversync.util.Msg;
import com.coloryr.mml.serversync.util.Text;

/** 自选模组的状态，落在状态目录下的两个文件里。 */
public final class OptionalState {
    /** 状态文件名，放在状态目录下 */
    public static final String FILE_NAME = "optional-state.txt";
    /** 「以后不再显示」标记文件名 */
    public static final String PROMPT_FILE_NAME = "optional-prompt.txt";
    /** 标记文件里表示已记住的那一行 */
    private static final String PROMPT_SUPPRESSED_LINE = "suppress=1";
    /** 旧版跳过记录文件名；存在时迁移成「未勾选」后删除 */
    public static final String LEGACY_FILE_NAME = "skipped.txt";

    private OptionalState() {}

/** 一条选择记录 */
    public static final class Entry {
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
    public static final class Loaded {
        /** 路径 → 记录 */
        final Map<String, Entry> byPath = new HashMap<>();
        /** 状态文件不存在时为 true：按首次同步处理，弹窗并默认全选；旧记录迁移后也会置位 */
        boolean firstRun;

        Loaded(boolean firstRun) {
            this.firstRun = firstRun;
        }

        /**
         * 该路径上次的勾选状态；没有记录时返回 null
         *
         * @param path 清单路径
         * @return 上次选择；没有记录时 null
         */
        Boolean selected(String path) {
            Entry entry = byPath.get(path);
            return entry == null ? null : entry.selected;
        }

        /**
         * 该路径上次记录的内容哈希；没有记录时返回空串
         *
         * @param path 清单路径
         * @return 内容哈希；没有记录时空串
         */
        String hashOf(String path) {
            Entry entry = byPath.get(path);
            return entry == null ? "" : entry.sha1;
        }

        /**
         * 同族旧路径的选择：换版本换文件名后沿用上次的更新 / 跳过。
         *
         * @param path 清单里的相对路径
         * @return 同族的上次选择；没有同族记录或有冲突时返回 null（按新增条目处理）
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
         * 同族标识：目录 + 文件名开头的连续 `[标签]`（至少两组），末尾版本标签不算。
         *
         * @param path 清单路径
         * @return 同族标识；不足两组标签时空串
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
     * @param stateDir 状态目录
     */
    public static Loaded load(Path stateDir) {
        Loaded loaded = new Loaded(false);
        Path file = stateDir.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            migrateLegacy(stateDir, loaded);
            return loaded;
        }
        readEntries(file, loaded);
        return loaded;
    }

    /**
     * 读取选择记录文件。
     *
     * @param file 状态文件
     * @param out 载入结果
     */
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

    /**
     * 旧 skipped.txt 的路径按未勾选迁入；迁完把旧文件删掉
     *
     * @param stateDir 状态目录
     * @param out 载入结果
     */
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
     * @param stateDir 状态目录
     * @param entries 本次的选择记录
     */
    public static void save(Path stateDir, List<Entry> entries) throws IOException {
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
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.deleteIfExists(temp);
    }

    /**
     * 便于构造保存列表
     *
     * @param selections 勾选结果
     * @param hashes 内容哈希
     * @return 保存条目
     */
    public static List<Entry> entries(Map<String, Boolean> selections, Map<String, String> hashes) {
        List<Entry> out = new ArrayList<>();
        for (Map.Entry<String, Boolean> item : selections.entrySet()) {
            out.add(new Entry(hashes.getOrDefault(item.getKey(), ""), item.getKey(), item.getValue()));
        }
        return out;
    }

    /**
     * 玩家是否勾过「以后不再显示」；标记缺失或损坏时按未记住处理。
     *
     * @param stateDir 状态目录
     * @return 已记住时返回 true
     */
    public static boolean promptSuppressed(Path stateDir) {
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
     * @param stateDir 状态目录
     * @param suppressed 是否记住
     */
    public static void savePromptSuppressed(Path stateDir, boolean suppressed) throws IOException {
        Path file = stateDir.resolve(PROMPT_FILE_NAME);
        if (!suppressed) {
            Files.deleteIfExists(file);
            return;
        }
        Files.createDirectories(stateDir);
        Files.write(file, Collections.singletonList(PROMPT_SUPPRESSED_LINE), StandardCharsets.UTF_8);
    }
}
