package com.coloryr.mml.serversync;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 自选模组的选择状态，落在状态目录下的 optional-state.txt。
 *
 * 一行一条 TSV：内容哈希、清单路径、上次是否勾选（1/0）。服务三件事——
 * 首次同步（文件不存在）与内容变化时重新弹窗、记住玩家上一次的勾选、
 * 以及按勾选决定自选模组装成启用（.jar）还是禁用（.jar.disabled）。
 */
final class OptionalState {
    /** 状态文件名，放在状态目录下 */
    static final String FILE_NAME = "optional-state.txt";
    /** 旧版跳过记录文件名；存在时迁移成「未勾选」后删除 */
    static final String LEGACY_FILE_NAME = "skipped.txt";

    private OptionalState() {}

    /** 一条选择记录 */
    static final class Entry {
        /** 清单条目当时的哈希：SHA1，没有时是 SHA256 */
        final String sha1;
        /** 清单里的相对路径，`/` 分隔 */
        final String file;
        /** 上次是否勾选 */
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
    }

    /**
     * 读状态目录里的选择状态
     *
     * 旧版 skipped.txt（只记「跳过」）存在时迁移：其中的路径按未勾选接入，
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
}
