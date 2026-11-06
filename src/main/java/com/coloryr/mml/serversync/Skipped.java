package com.coloryr.mml.serversync;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 被跳过的可选模组记录，落在状态目录下的 skipped.txt。
 *
 * 一行一条 TSV：内容哈希、清单路径、本地保留的旧文件。只服务两件事——发布方没换内容
 * 就别再问一遍，以及重新勾上时把上次留下的旧文件清掉；清单本身仍以 last-manifest.json 为准。
 */
final class Skipped {
    /** 记录文件名，放在状态目录下 */
    static final String FILE_NAME = "skipped.txt";

    private Skipped() {}

    /** 一条跳过记录 */
    static final class Entry {
        /** 清单条目当时的哈希：SHA1，没有时是 SHA256 */
        final String sha1;
        /** 清单里的相对路径，`/` 分隔 */
        final String file;
        /** 本地保留的旧文件，相对游戏目录；没有时为空串 */
        final String kept;

        Entry(String sha1, String file, String kept) {
            this.sha1 = sha1 == null ? "" : sha1.trim();
            this.file = file == null ? "" : file.trim();
            this.kept = kept == null ? "" : kept.trim();
        }
    }

    /**
     * 读状态目录里的跳过记录
     *
     * - `stateDir`: 状态目录
     *
     * # 返回值
     *
     * 记录列表；文件不存在或读不了时为空列表
     */
    static List<Entry> load(Path stateDir) {
        List<Entry> out = new ArrayList<>();
        Path file = stateDir.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                String[] parts = line.split("\t", -1);
                if (parts.length < 2) {
                    continue;
                }
                out.add(new Entry(parts[0], parts[1], parts.length > 2 ? parts[2] : ""));
            }
        } catch (IOException e) {
            Log.error(Text.of(Msg.FAILED_TO_READ_THE_SKIP_RECORDS) + e.getMessage());
        }
        return out;
    }

    /**
     * 重写跳过记录；一条都没有时把文件删掉
     *
     * - `stateDir`: 状态目录
     * - `entries`: 本次留下的跳过记录
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
                    .append(entry.kept).append('\n');
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
}
