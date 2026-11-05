package com.coloryr.mml.serversync;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** HTTP 下载、平台地址解析与哈希校验。 */
final class Downloader {
    /** 字节进度两条日志的最小间隔，单位纳秒 */
    private static final long REPORT_INTERVAL = 1_000_000_000L;

    private final String apiKey;
    private final boolean quiet;
    private final Map<String, String> resolved = new ConcurrentHashMap<>();

    Downloader(String apiKey, boolean quiet) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.quiet = quiet;
    }

    String resolveFile(Manifest.FileEntry item) throws IOException {
        if (!item.url.isEmpty()) {
            return item.url;
        }
        if (item.custom) {
            throw new IOException("自定义条目没有下载地址: " + item.path);
        }
        if (item.fid.isEmpty()) {
            throw new IOException("条目既没有 Url 也没有 FileId: " + item.path);
        }
        String cached = resolved.get(item.fid);
        if (cached != null) {
            return cached;
        }
        String url = isCurseForge(item.pid, item.fid)
                ? resolveCurseForge(item) : resolveModrinth(item.fid);
        if (url.isEmpty()) {
            throw new IOException("解析不到下载地址: " + item.path);
        }
        resolved.put(item.fid, url);
        return url;
    }

    private static boolean isCurseForge(String pid, String fid) {
        String value = pid.isEmpty() ? fid : pid;
        if (value.isEmpty()) {
            return false;
        }
        try {
            Long.parseLong(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String resolveCurseForge(Manifest.FileEntry item) throws IOException {
        if (!apiKey.isEmpty() && !item.pid.isEmpty() && !item.fid.isEmpty()) {
            try {
                String api = "https://api.curseforge.com/v1/mods/" + item.pid + "/files/" + item.fid;
                Map<String, Object> root = Json.map(Json.parse(
                        fetch(api, Collections.singletonMap("x-api-key", apiKey))));
                Map<String, Object> data = root == null ? null : Json.map(root.get("data"));
                String url = data == null ? "" : Json.string(data, "downloadUrl");
                if (!url.trim().isEmpty()) {
                    return url.trim();
                }
            } catch (IOException ignored) {
                // API 不可用时退回 CDN 规则
            }
        }
        long id;
        try {
            id = Long.parseLong(item.fid);
        } catch (NumberFormatException e) {
            throw new IOException("CurseForge FileId 不是数字: " + item.fid);
        }
        String name = item.fileName();
        try {
            name = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException ignored) {
        }
        return "https://edge.forgecdn.net/files/" + (id / 1000) + "/" + (id % 1000) + "/" + name;
    }

    private String resolveModrinth(String fid) throws IOException {
        String api = "https://api.modrinth.com/v2/version/" + encodeSegment(fid);
        Map<String, Object> root = Json.map(Json.parse(fetch(api)));
        List<Object> files = root == null ? null : Json.list(root.get("files"));
        if (files != null) {
            Map<String, Object> first = null;
            for (Object raw : files) {
                Map<String, Object> file = Json.map(raw);
                if (file == null) {
                    continue;
                }
                if (first == null) {
                    first = file;
                }
                if (Json.bool(file, "primary")) {
                    first = file;
                    break;
                }
            }
            if (first != null) {
                return Json.string(first, "url").trim();
            }
        }
        return "";
    }

    /** 下载并校验；本地文件已匹配时直接跳过。返回是否真的下载了。 */
    boolean download(String url, Path target, String sha1, String sha256) throws IOException {
        if (url == null || url.trim().isEmpty()) {
            throw new IOException("下载地址为空: " + target);
        }
        if (Files.isRegularFile(target) && matches(target, sha1, sha256)) {
            if (!quiet) {
                Log.info("[skip] " + target);
            }
            return false;
        }
        Files.createDirectories(target.toAbsolutePath().normalize().getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".part");
        Files.deleteIfExists(temp);
        MessageDigest sha1Digest = digest("SHA-1");
        MessageDigest sha256Digest = digest("SHA-256");
        try {
            URLConnection connection = open(url, Collections.<String, String>emptyMap());
            long total = connection.getContentLengthLong();
            long done = 0;
            long lastDone = 0;
            long lastTime = System.nanoTime();
            try (InputStream input = connection.getInputStream();
                 OutputStream output = Files.newOutputStream(temp)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    output.write(buffer, 0, read);
                    if (!sha1.isEmpty()) {
                        sha1Digest.update(buffer, 0, read);
                    }
                    if (!sha256.isEmpty()) {
                        sha256Digest.update(buffer, 0, read);
                    }
                    done += read;
                    long now = System.nanoTime();
                    if (!quiet && now - lastTime >= REPORT_INTERVAL) {
                        report(target, done, total, done - lastDone, now - lastTime);
                        lastDone = done;
                        lastTime = now;
                    }
                }
            }
            if (!sha1.isEmpty() && !hex(sha1Digest.digest()).equalsIgnoreCase(sha1)) {
                throw new IOException("SHA1 不匹配: " + target);
            }
            if (!sha256.isEmpty() && !hex(sha256Digest.digest()).equalsIgnoreCase(sha256)) {
                throw new IOException("SHA256 不匹配: " + target);
            }
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            if (!quiet) {
                Log.info("[down] " + target);
            }
            return true;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * 打印一条下载字节进度，约每秒一条
     *
     * - `target`: 目标文件，只取文件名
     * - `done`: 已下载字节数
     * - `total`: 总字节数，服务端没给长度时为 0 或负数
     * - `delta`: 距上一条进度新增的字节数
     * - `elapsed`: 距上一条进度的纳秒数
     */
    private static void report(Path target, long done, long total, long delta, long elapsed) {
        double seconds = elapsed / 1_000_000_000.0;
        double speed = seconds <= 0 ? 0 : delta / 1048576.0 / seconds;
        String size = total > 0
                ? String.format(Locale.ROOT, "%.1f/%.1f MB", done / 1048576.0, total / 1048576.0)
                : String.format(Locale.ROOT, "%.1f MB", done / 1048576.0);
        String percent = total > 0
                ? String.format(Locale.ROOT, " %d%%", done * 100 / total)
                : "";
        Log.info("[download] " + target.getFileName() + percent + " (" + size + ", "
                + String.format(Locale.ROOT, "%.1f MB/s", speed) + ")");
    }

    byte[] fetch(String url) throws IOException {
        return fetch(url, Collections.<String, String>emptyMap());
    }

    byte[] fetch(String url, Map<String, String> headers) throws IOException {
        if (!quiet) {
            Log.info("获取 JSON: " + url);
        }
        URLConnection connection = open(url, headers);
        try (InputStream input = connection.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
            if (!quiet) {
                Log.info("JSON 已获取，共 " + out.size() + " 字节: " + url);
            }
            return out.toByteArray();
        }
    }

    private static URLConnection open(String text, Map<String, String> headers) throws IOException {
        URL url;
        try {
            url = new URL(text);
        } catch (IOException e) {
            throw new IOException("非法下载地址: " + text, e);
        }
        URLConnection connection = url.openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(120000);
        for (Map.Entry<String, String> header : headers.entrySet()) {
            connection.setRequestProperty(header.getKey(), header.getValue());
        }
        if (connection instanceof HttpURLConnection) {
            HttpURLConnection http = (HttpURLConnection) connection;
            http.setInstanceFollowRedirects(true);
            http.setRequestProperty("User-Agent", "MML-ServerSync/1.0");
            int code = http.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + ": " + text);
            }
        }
        return connection;
    }

    static boolean matches(Path file, String sha1, String sha256) {
        if (sha1 == null || sha1.trim().isEmpty()) {
            if (sha256 == null || sha256.trim().isEmpty()) {
                return false;
            }
            return hash(file, "SHA-256").equalsIgnoreCase(sha256.trim());
        }
        return hash(file, "SHA-1").equalsIgnoreCase(sha1.trim())
                && (sha256 == null || sha256.trim().isEmpty()
                || hash(file, "SHA-256").equalsIgnoreCase(sha256.trim()));
    }

    static String hash(Path file, String algorithm) {
        MessageDigest digest = digest(algorithm);
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        } catch (IOException e) {
            return "";
        }
        return hex(digest.digest());
    }

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] data) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] out = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            out[i * 2] = digits[(data[i] >> 4) & 0xF];
            out[i * 2 + 1] = digits[data[i] & 0xF];
        }
        return new String(out);
    }

    private static String encodeSegment(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            return value;
        }
    }
}
