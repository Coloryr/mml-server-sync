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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

/** HTTP 下载、平台地址解析与哈希校验。 */
final class Downloader {
    /** 字节进度两条日志的最小间隔，单位纳秒 */
    private static final long REPORT_INTERVAL = 1_000_000_000L;
    /** 单个文件最多尝试次数：Connection reset 这类瞬时错误重试往往就好 */
    private static final int DOWNLOAD_ATTEMPTS = 3;

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
            throw new IOException(Text.of(Msg.CUSTOM_ENTRY_HAS_NO_DOWNLOAD_URL) + item.path);
        }
        if (item.fid.isEmpty()) {
            throw new IOException(Text.of(Msg.ENTRY_HAS_NEITHER_URL_NOR_FILEID) + item.path);
        }
        String cached = resolved.get(item.fid);
        if (cached != null) {
            return cached;
        }
        String url = isCurseForge(item.pid, item.fid)
                ? resolveCurseForge(item) : resolveModrinth(item.fid);
        if (url.isEmpty()) {
            throw new IOException(Text.of(Msg.CANNOT_RESOLVE_THE_DOWNLOAD_URL) + item.path);
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
            throw new IOException(Text.of(Msg.CURSEFORGE_FILEID_IS_NOT_A_NUMBER) + item.fid);
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

    /**
     * 下载并校验；本地文件已匹配时直接跳过。返回是否真的下载了。
     *
     * 连接被重置、读超时这类瞬时错误会重试（最多 {@link #DOWNLOAD_ATTEMPTS} 次）；
     * 哈希不匹配说明内容不对，重试没有意义，直接失败。
     */
    boolean download(String url, Path target, String sha1, String sha256) throws IOException {
        if (url == null || url.trim().isEmpty()) {
            throw new IOException(Text.of(Msg.DOWNLOAD_URL_IS_EMPTY) + target);
        }
        if (Files.isRegularFile(target) && matches(target, sha1, sha256)) {
            if (!quiet) {
                Log.info("[skip] " + target);
            }
            return false;
        }
        Files.createDirectories(target.toAbsolutePath().normalize().getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".part");
        IOException last = null;
        for (int attempt = 1; attempt <= DOWNLOAD_ATTEMPTS; attempt++) {
            try {
                return downloadOnce(url, target, temp, sha1, sha256);
            } catch (VerifyException e) {
                // 文件下完了但哈希不对：换一次连接也可能还是坏内容，不重试
                throw e;
            } catch (IOException e) {
                last = e;
                Files.deleteIfExists(temp);
                if (attempt < DOWNLOAD_ATTEMPTS) {
                    if (!quiet) {
                        Log.info(Text.of(Msg.DOWNLOAD_RETRY, attempt, target.getFileName(),
                                describe(e)));
                    }
                    sleep(400L * attempt);
                }
            }
        }
        throw last == null ? new IOException(Text.of(Msg.DOWNLOAD_FAILED)) : last;
    }

    /** 下载一次；失败时由调用方决定是否重试 */
    private boolean downloadOnce(String url, Path target, Path temp, String sha1, String sha256)
            throws IOException {
        Files.deleteIfExists(temp);
        MessageDigest sha1Digest = digest("SHA-1");
        MessageDigest sha256Digest = digest("SHA-256");
        try {
            URLConnection connection = open(url, Collections.<String, String>emptyMap(), false);
            // 服务端强行压缩时长度是压缩后的，进度按"未知总长"显示，免得百分比超 100
            long total = isGzip(connection.getContentEncoding()) ? -1 : connection.getContentLengthLong();
            long done = 0;
            long lastDone = 0;
            long lastTime = System.nanoTime();
            try (InputStream input = body(connection);
                 OutputStream output = Files.newOutputStream(temp)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    // 每读一块就查一次：取消要立刻停，暂停要立刻停住（不再等 1MB 边界）
                    Cancels.check();
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
                throw new VerifyException(Text.of(Msg.SHA1_MISMATCH) + target);
            }
            if (!sha256.isEmpty() && !hex(sha256Digest.digest()).equalsIgnoreCase(sha256)) {
                throw new VerifyException(Text.of(Msg.SHA256_MISMATCH) + target);
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
        double elapsedSeconds = elapsed / 1_000_000_000.0;
        double speed = elapsedSeconds <= 0 ? 0 : delta / elapsedSeconds;
        String size = total > 0 ? sizeText(done) + "/" + sizeText(total) : sizeText(done);
        String percent = total > 0
                ? String.format(Locale.ROOT, " %d%%", done * 100 / total)
                : "";
        Log.info("[download] " + target.getFileName() + percent + " (" + size + ", "
                + speedText(speed) + ")");
    }

    byte[] fetch(String url) throws IOException {
        return fetch(url, Collections.<String, String>emptyMap(), null);
    }

    /** 清单下载进度回调：约每秒通知一次，与进度日志同节奏 */
    interface FetchListener {
        void progress(long done, long total);
    }

    /**
     * 取一份小文件，不打印"获取 JSON"日志
     *
     * 用于清单指纹这类每次启动都会探一次的地址。
     *
     * - `url`: 地址
     *
     * # 返回值
     *
     * 响应体内容；取不到时抛 IOException
     */
    byte[] fetchQuiet(String url) throws IOException {
        return fetchBytes(url, Collections.<String, String>emptyMap(), null, false);
    }

    byte[] fetch(String url, Map<String, String> headers) throws IOException {
        return fetch(url, headers, null);
    }

    /**
     * 取一份远程文件并读入内存，清单类请求会协商 gzip
     *
     * - `url`: 地址
     * - `headers`: 附加请求头，可为 null
     * - `listener`: 下载进度回调，可为 null；约每秒一次
     */
    byte[] fetch(String url, Map<String, String> headers, FetchListener listener) throws IOException {
        if (!quiet) {
            Log.info(Text.of(Msg.FETCHING_JSON_2) + url);
        }
        return fetchBytes(url, headers, listener, !quiet);
    }

    /**
     * 取一份 JSON
     *
     * 默认带 `Accept-Encoding: gzip`：8 MB 的清单压到 1 MB 以内，慢速线路上的等待时间
     * 跟着传输字节数一起降下来。读的过程中每秒打一条字节进度，并每 256 KB 检查一次取消，
     * 免得看起来像卡死。
     *
     * 个别反代会声明 gzip 却给未压缩的内容，此时退回不压缩再取一次。
     *
     * - `url`: 地址
     * - `headers`: 额外请求头，可为 null
     * - `listener`: 进度回调，可为 null
     * - `verbose`: 是否打进度与结果日志
     *
     * # 返回值
     *
     * 解压后的响应体
     */
    private byte[] fetchBytes(String url, Map<String, String> headers, FetchListener listener,
                              boolean verbose) throws IOException {
        Map<String, String> safe = headers == null
                ? Collections.<String, String>emptyMap() : headers;
        long started = System.nanoTime();
        byte[] data;
        try {
            data = readBody(url, safe, listener, verbose, true);
        } catch (ZipException e) {
            // 头里写了 gzip、内容却不是：按未压缩再取一次
            if (verbose) {
                Log.info(Text.of(Msg.GZIP_BODY_INVALID_RETRYING_WITHOUT_COMPRESSION, url));
            }
            data = readBody(url, safe, listener, verbose, false);
        }
        if (verbose) {
            Log.info(Text.of(Msg.DOWNLOADER_JSON_FETCHED, data.length, seconds(started), url));
        }
        return data;
    }

    /** 打开连接并把响应体整段读进来；`gzip` 决定要不要请求压缩传输 */
    private static byte[] readBody(String url, Map<String, String> headers,
                                   FetchListener listener, boolean verbose, boolean gzip)
            throws IOException {
        URLConnection connection = open(url, headers, gzip);
        long transferTotal = connection.getContentLengthLong();
        boolean compressed = isGzip(connection.getContentEncoding());
        if (verbose && transferTotal > 0) {
            Log.info(Text.of(Msg.FETCHING_JSON_SIZE, transferTotal,
                    compressed ? Text.of(Msg.GZIP_SUFFIX) : "", url));
        } else if (verbose && compressed) {
            // chunked 响应没有 Content-Length，但压缩是否生效仍然值得写一行
            Log.info(Text.of(Msg.GZIP_TRANSFER, url));
        }
        // gzip 时长度是压缩后的，进度按"未知总长"报，免得百分比失真
        long total = compressed ? -1 : transferTotal;
        try (InputStream input = body(connection)) {
            return readAll(input, url, total, listener, verbose);
        }
    }

    /**
     * 读完一个流，顺带打进度、查取消
     *
     * - `input`: 响应体，已按 Content-Encoding 解压
     * - `url`: 地址，进进度日志
     * - `total`: 服务端给的传输字节数；未知时为 0 或负数
     * - `listener`: 进度回调，可为 null
     * - `verbose`: 是否每秒打一条进度
     */
    private static byte[] readAll(InputStream input, String url, long total,
                                  FetchListener listener, boolean verbose) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(
                total > 0 && total <= Integer.MAX_VALUE - 8 ? (int) total : 8192);
        byte[] buffer = new byte[16 * 1024];
        long done = 0;
        long lastDone = 0;
        long lastTime = System.nanoTime();
        int read;
        while ((read = input.read(buffer)) >= 0) {
            // 清单也可能几 MB：每读一块就查一次，取消与暂停都能立刻生效
            Cancels.check();
            out.write(buffer, 0, read);
            done += read;
            long now = System.nanoTime();
            if (now - lastTime >= REPORT_INTERVAL) {
                if (verbose) {
                    reportFetch(url, done, total, done - lastDone, now - lastTime);
                }
                if (listener != null) {
                    listener.progress(done, total);
                }
                lastDone = done;
                lastTime = now;
            }
        }
        return out.toByteArray();
    }

    /** 打印一条清单接收进度，约每秒一条 */
    private static void reportFetch(String url, long done, long total, long delta, long elapsed) {
        double elapsedSeconds = elapsed / 1_000_000_000.0;
        double speed = elapsedSeconds <= 0 ? 0 : delta / elapsedSeconds;
        String size = total > 0 ? sizeText(done) + "/" + sizeText(total) : sizeText(done);
        Log.info(Text.of(Msg.FETCHING_JSON_PROGRESS, size, speedText(speed), url));
    }

    private static URLConnection open(String text, Map<String, String> headers, boolean gzip)
            throws IOException {
        URL url;
        try {
            url = new URL(text);
        } catch (IOException e) {
            throw new IOException(Text.of(Msg.INVALID_DOWNLOAD_URL) + text, e);
        }
        URLConnection connection = url.openConnection();
        connection.setConnectTimeout(20000);
        // 单次 read 的上限；只要一直有字节进来就不会触发，慢速线路靠上面的进度日志看动静
        connection.setReadTimeout(120000);
        if (gzip) {
            connection.setRequestProperty("Accept-Encoding", "gzip");
        }
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

    /** 按 Content-Encoding 套上解压流；服务端没压缩时原样返回 */
    private static InputStream body(URLConnection connection) throws IOException {
        InputStream input = connection.getInputStream();
        if (isGzip(connection.getContentEncoding())) {
            return new GZIPInputStream(input, 16 * 1024);
        }
        return input;
    }

    private static boolean isGzip(String encoding) {
        return encoding != null && encoding.toLowerCase(Locale.ROOT).contains("gzip");
    }

    /** 字节数按 KB / MB 显示 */
    private static String sizeText(long bytes) {
        return bytes < 1048576L
                ? String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)
                : String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }

    /** 速度按 KB/s / MB/s 显示 */
    private static String speedText(double bytesPerSecond) {
        return bytesPerSecond < 1048576.0
                ? String.format(Locale.ROOT, "%.0f KB/s", bytesPerSecond / 1024.0)
                : String.format(Locale.ROOT, "%.1f MB/s", bytesPerSecond / 1048576.0);
    }

    /** 从计时起点到现在的秒数，保留一位小数 */
    private static String seconds(long startedNanos) {
        return String.format(Locale.ROOT, "%.1f",
                (System.nanoTime() - startedNanos) / 1_000_000_000.0);
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
    /** 内容哈希不匹配：重试没有意义 */
    private static final class VerifyException extends IOException {
        private static final long serialVersionUID = 1L;

        VerifyException(String message) {
            super(message);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }
}