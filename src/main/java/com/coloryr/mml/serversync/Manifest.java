package com.coloryr.mml.serversync;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** MML 服务器清单 server.json。 */
final class Manifest {
    final String name;
    final String version;
    final String packVersion;
    final List<FileEntry> files;
    final List<ArchiveEntry> archives;
    final BaseEntry base;

    private Manifest(String name, String version, String packVersion, List<FileEntry> files,
                     List<ArchiveEntry> archives, BaseEntry base) {
        this.name = name;
        this.version = version;
        this.packVersion = packVersion;
        this.files = Collections.unmodifiableList(files);
        this.archives = Collections.unmodifiableList(archives);
        this.base = base;
    }

    static Manifest parse(byte[] data) throws IOException {
        Map<String, Object> root = Json.map(Json.parse(data));
        if (root == null) {
            throw new IOException("清单根节点不是 JSON 对象");
        }
        List<Object> rawFiles = Json.list(root.get("Files"));
        if (rawFiles == null) {
            throw new IOException("不是 MML server.json：缺少 Files 数组");
        }
        List<FileEntry> files = new ArrayList<>();
        for (Object raw : rawFiles) {
            Map<String, Object> item = Json.map(raw);
            if (item == null) {
                continue;
            }
            String path = Json.string(item, "File");
            if (path.trim().isEmpty()) {
                throw new IOException("Files 里有空路径");
            }
            files.add(new FileEntry(path, Json.string(item, "ProjectId"),
                    Json.string(item, "FileId"), Json.string(item, "Url"),
                    Json.string(item, "Sha1"), Json.string(item, "Sha256"),
                    Json.bool(item, "Custom")));
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
                    throw new IOException("Archives 里有空文件或空目标目录");
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
                throw new IOException("Base 缺少文件名");
            }
            base = new BaseEntry(file, Json.string(rawBase, "Url"),
                    Json.string(rawBase, "Sha1"), Json.string(rawBase, "Sha256"));
        }
        return new Manifest(Json.string(root, "Name"), Json.string(root, "Version"),
                Json.string(root, "PackVersion"), files, archives, base);
    }

    /** 把清单里的相对 URL 按清单自身地址解析；MML 生成时通常已是绝对地址。 */
    void resolveUrls(String manifestUrl) {
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

    private static String resolveAgainst(String base, String url) {
        String value = url == null ? "" : url.trim();
        if (value.isEmpty()) {
            return "";
        }
        if (value.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")) {
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

    private static String encodeSegment(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            return value;
        }
    }

    static final class FileEntry {
        final String path;
        final String pid;
        final String fid;
        String url;
        final String sha1;
        final String sha256;
        final boolean custom;

        FileEntry(String path, String pid, String fid, String url, String sha1, String sha256,
                  boolean custom) {
            this.path = path.replace('\\', '/');
            this.pid = trim(pid);
            this.fid = trim(fid);
            this.url = trim(url);
            this.sha1 = trim(sha1);
            this.sha256 = trim(sha256);
            this.custom = custom;
        }

        String identity() {
            return pid.isEmpty() ? "path:" + path : "pid:" + pid;
        }

        String fileName() {
            int i = path.lastIndexOf('/');
            return i >= 0 ? path.substring(i + 1) : path;
        }
    }

    static final class ArchiveEntry {
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

    static final class BaseEntry {
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

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
