package com.coloryr.mml.serversync.sync;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.coloryr.mml.serversync.util.Json;
import com.coloryr.mml.serversync.util.Msg;
import com.coloryr.mml.serversync.util.Text;

/** MML 服务器清单 server.json。 */
public final class Manifest {
    public final String name;
    public final String version;
    public final String packVersion;
    public final List<FileEntry> files;
    public final List<ArchiveEntry> archives;
    public final BaseEntry base;

    private Manifest(String name, String version, String packVersion, List<FileEntry> files,
                     List<ArchiveEntry> archives, BaseEntry base) {
        this.name = name;
        this.version = version;
        this.packVersion = packVersion;
        this.files = Collections.unmodifiableList(files);
        this.archives = Collections.unmodifiableList(archives);
        this.base = base;
    }

    /**
     * 解析 server.json。
     *
     * @param data 清单字节
     * @return 解析出的清单
     * @throws IOException JSON 非法或缺少必需字段时
     */
    public static Manifest parse(byte[] data) throws IOException {
        Map<String, Object> root = Json.map(Json.parse(data));
        if (root == null) {
            throw new IOException(Text.of(Msg.THE_MANIFEST_ROOT_IS_NOT_A_JSON_OBJECT));
        }
        List<Object> rawFiles = Json.list(root.get("Files"));
        if (rawFiles == null) {
            throw new IOException(Text.of(Msg.NOT_AN_MML_SERVER_JSON_MISSING_THE_FILES_ARRAY));
        }
        List<FileEntry> files = new ArrayList<>();
        for (Object raw : rawFiles) {
            Map<String, Object> item = Json.map(raw);
            if (item == null) {
                continue;
            }
            String path = Json.string(item, "File");
            if (path.trim().isEmpty()) {
                throw new IOException(Text.of(Msg.FILES_CONTAINS_AN_EMPTY_PATH));
            }
            files.add(new FileEntry(path, Json.string(item, "ProjectId"),
                    Json.string(item, "FileId"), Json.string(item, "Url"),
                    Json.string(item, "Sha1"), Json.string(item, "Sha256"),
                    Json.bool(item, "Custom"), Json.bool(item, "Optional")));
        }
        List<ArchiveEntry> archives = new ArrayList<>();
        List<Object> rawArchives = Json.list(root.get("Archives"));
        if (rawArchives != null) {
            for (Object raw : rawArchives) {
                Map<String, Object> item = Json.map(raw);
                if (item == null) {
                    continue;
                }
                String file = Json.string(item, "File");
                String dir = Json.string(item, "Path");
                if (file.trim().isEmpty() || dir.trim().isEmpty()) {
                    throw new IOException(Text.of(Msg.ARCHIVES_CONTAINS_AN_EMPTY_FILE_OR_TARGET_DIRECTORY));
                }
                archives.add(new ArchiveEntry(file, dir, Json.bool(item, "Over"),
                        Json.string(item, "Url"), Json.string(item, "Sha1"),
                        Json.string(item, "Sha256")));
            }
        }
        BaseEntry base = null;
        Map<String, Object> rawBase = Json.map(root.get("Base"));
        if (rawBase != null) {
            String file = Json.string(rawBase, "File");
            if (file.trim().isEmpty()) {
                throw new IOException(Text.of(Msg.BASE_IS_MISSING_THE_FILE_NAME));
            }
            base = new BaseEntry(file, Json.string(rawBase, "Url"),
                    Json.string(rawBase, "Sha1"), Json.string(rawBase, "Sha256"));
        }
        return new Manifest(Json.string(root, "Name"), Json.string(root, "Version"),
                Json.string(root, "PackVersion"), files, archives, base);
    }

    /**
     * 把清单里的相对 URL 按清单自身地址解析；MML 生成时通常已是绝对地址。
     *
     * @param manifestUrl 清单地址
     */
    public void resolveUrls(String manifestUrl) {
        if (manifestUrl == null || manifestUrl.trim().isEmpty()) {
            return;
        }
        for (FileEntry item : files) {
            item.url = resolveAgainst(manifestUrl, item.url);
        }
        for (ArchiveEntry item : archives) {
            item.url = resolveAgainst(manifestUrl, item.url);
        }
        if (base != null) {
            base.url = resolveAgainst(manifestUrl, base.url);
        }
    }

    /**
     * 把相对地址按清单地址解析。
     *
     * @param base 清单地址
     * @param url 条目里的地址
     * @return 绝对地址
     */
    private static String resolveAgainst(String base, String url) {
        String value = url == null ? "" : url.trim();
        if (value.isEmpty()) {
            return "";
        }
        if (Fs.hasScheme(value)) {
            return value;
        }
        try {
            URI root = URI.create(base);
            if (value.startsWith("/")) {
                return root.resolve(value).toString();
            }
            String rel = value.replace('\\', '/');
            String encoded = encodePath(rel);
            return root.resolve(encoded).toString();
        } catch (RuntimeException e) {
            return value;
        }
    }

    /**
     * 按路径段编码相对地址。
     *
     * @param path 相对路径
     * @return 编码后的路径
     */
    private static String encodePath(String path) {
        String[] parts = path.split("/", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                out.append('/');
            }
            out.append(encodeSegment(parts[i]));
        }
        return out.toString();
    }

    /**
     * URL 编码一个路径段。
     *
     * @param value 路径段
     * @return 编码后的文本
     */
    private static String encodeSegment(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }

    public static final class FileEntry {
        final String path;
        final String pid;
        final String fid;
        String url;
        final String sha1;
        final String sha256;
        final boolean custom;
        /** 是否可选模组：首次同步时装成禁用，玩家可以自己启用 */
        final boolean optional;

        FileEntry(String path, String pid, String fid, String url, String sha1, String sha256,
                  boolean custom, boolean optional) {
            this.path = path.replace('\\', '/');
            this.pid = trim(pid);
            this.fid = trim(fid);
            this.url = trim(url);
            this.sha1 = trim(sha1);
            this.sha256 = trim(sha256);
            this.custom = custom;
            this.optional = optional;
        }

        String identity() {
            return pid.isEmpty() ? "path:" + path : "pid:" + pid;
        }

        String fileName() {
            int i = path.lastIndexOf('/');
            return i >= 0 ? path.substring(i + 1) : path;
        }
    }

    public static final class ArchiveEntry {
        final String file;
        final String dir;
        final boolean deleteOld;
        String url;
        final String sha1;
        final String sha256;

        ArchiveEntry(String file, String dir, boolean deleteOld, String url, String sha1,
                     String sha256) {
            this.file = file.replace('\\', '/');
            this.dir = dir.replace('\\', '/');
            this.deleteOld = deleteOld;
            this.url = trim(url);
            this.sha1 = trim(sha1);
            this.sha256 = trim(sha256);
        }
    }

    public static final class BaseEntry {
        final String file;
        String url;
        final String sha1;
        final String sha256;

        BaseEntry(String file, String url, String sha1, String sha256) {
            this.file = file.replace('\\', '/');
            this.url = trim(url);
            this.sha1 = trim(sha1);
            this.sha256 = trim(sha256);
        }
    }

    /**
     * null 安全去空白。
     *
     * @param value 原值
     * @return 去空白后的文本；null 时为空串
     */
    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
