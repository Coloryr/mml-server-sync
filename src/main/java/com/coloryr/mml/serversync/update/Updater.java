package com.coloryr.mml.serversync.update;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.CodeSource;
import java.util.Locale;
import java.util.Map;

import com.coloryr.mml.serversync.Main;
import com.coloryr.mml.serversync.sync.Downloader;
import com.coloryr.mml.serversync.sync.Fs;
import com.coloryr.mml.serversync.sync.Verify;
import com.coloryr.mml.serversync.util.Json;
import com.coloryr.mml.serversync.util.Log;
import com.coloryr.mml.serversync.util.Msg;
import com.coloryr.mml.serversync.util.Text;

/**
 * 自更新：从 index.json 取新版本，下载到 jar 同目录的 update/。
 *
 * 不覆盖运行中的 jar，新版本下次启动由独立类加载器加载。
 */
public final class Updater {
    /** 默认索引：与更新用的 jar 放在同一个目录下 */
    public static final String DEFAULT_INDEX =
            "https://www.coloryr.com/mml-server-sync/index.json";
    /** 索引文件名：给目录地址时自动补上 */
    private static final String INDEX_FILE = "index.json";
    /** 自定义更新地址的 JVM 属性 / 环境变量；旧写法仍然认 */
    public static final String URL_PROPERTY = "mml.serverSync.updateUrl";
    public static final String LEGACY_URL_PROPERTY = "mml.serverSync.updateIndex";
    public static final String URL_ENV = "MML_SERVER_SYNC_UPDATE_URL";
    public static final String LEGACY_URL_ENV = "MML_SERVER_SYNC_INDEX";

    /** 原始 jar 路径的系统属性键；入口启动时按类加载源写入。 */
    public static final String SELF_JAR_KEY = "mml.serverSync.selfJar";

    /** 更新包目录名，放在 jar 同目录下 */
    private static final String UPDATE_DIR = "update";
    /** 更新包文件名前缀，中间是版本号 */
    private static final String FILE_PREFIX = "mml-server-sync-";
    /** 更新包文件名后缀 */
    private static final String FILE_SUFFIX = ".jar";
    /** 下载中的临时文件后缀 */
    private static final String PART_SUFFIX = ".part";
    /** 索引里给了 SHA256 时落下的副本，加载前用同一份值复核 */
    private static final String SHA256_SIDECAR = ".sha256";

    private Updater() {}

    /**
     * 后台检查一次更新；没开自更新返回 null。
     *
     * @param enabled 是否开启自更新
     * @param index 索引地址；空串用 {@link #DEFAULT_INDEX}
     * @return 后台线程；没有开启自更新时为 null
     */
    public static Thread start(boolean enabled, String index) {
        if (!enabled) {
            return null;
        }
        Thread thread = new Thread(() -> {
            try {
                check(index);
            } catch (Throwable e) {
                Log.info(Text.of(Msg.UPDATE_CHECK_FAILED_IGNORING) + Log.describe(e));
            }
        }, "mml-server-sync-update");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * 拉索引并把新版本下载到 update 目录
     *
     * @param indexText 索引地址；空串用默认地址
     */
    public static void check(String indexText) throws IOException {
        Path jar = jarPath();
        if (jar == null) {
            Log.info(Text.of(Msg.NOT_RUNNING_FROM_A_JAR_SKIPPING_SELF_UPDATE));
            return;
        }
        String index = indexAddress(indexText);
        byte[] body;
        try {
            body = fetch(index);
        } catch (IOException e) {
            Log.info(Text.of(Msg.UPDATE_INDEX_UNAVAILABLE_REASON, index, Log.describe(e)));
            return;
        }
        if (looksLikeHtml(body)) {
            // 索引地址被指到网页（常见于域名首页）时说清楚，别只报一个 JSON 字符错
            Log.info(Text.of(Msg.UPDATE_INDEX_IS_A_WEB_PAGE_SKIPPING, index));
            return;
        }
        Map<String, Object> root;
        try {
            root = Json.map(Json.parse(body));
        } catch (IOException e) {
            Log.info(Text.of(Msg.UPDATE_INDEX_UNAVAILABLE_REASON, index, Log.describe(e)));
            return;
        }
        if (root == null) {
            Log.info(Text.of(Msg.UPDATE_INDEX_IS_NOT_A_JSON_OBJECT_SKIPPING));
            return;
        }
        String version = Json.string(root, "version", "Version").trim();
        // 允许相对地址：index.json 与 jar 放同一个目录时，整包传到哪都能用
        String url = resolveUrl(index, Json.string(root, "url", "Url").trim());
        String sha1 = Json.string(root, "sha1", "Sha1").trim();
        String sha256 = Json.string(root, "sha256", "Sha256").trim();
        if (version.isEmpty() || url.isEmpty()) {
            Log.info(Text.of(Msg.UPDATE_INDEX_IS_MISSING_VERSION_URL_SKIPPING));
            return;
        }
        if (compare(version, Main.VERSION) <= 0) {
            return;
        }
        Path dir = updateDir(jar);
        Path target = dir.resolve(FILE_PREFIX + safeVersion(version) + FILE_SUFFIX);
        if (Files.isRegularFile(target) && sameFile(target, sha1, sha256)) {
            return;
        }
        Log.info(Text.of(Msg.UPDATER_NEW_VERSION, version, Main.VERSION));
        Files.createDirectories(dir);
        download(url, target, sha1, sha256);
        writeSidecar(target, Verify.SHA1_SIDECAR, sha1);
        writeSidecar(target, SHA256_SIDECAR, sha256);
        prune(dir, target);
        Log.info(Text.of(Msg.UPDATER_DOWNLOADED, target));
    }

    /**
     * 从 JVM 属性 / 环境变量取自定义更新地址
     *
     * @return JVM 属性 / 环境变量里的地址；都没配时为空串
     */
    public static String configuredIndex() {
        String property = System.getProperty(URL_PROPERTY, "").trim();
        if (property.isEmpty()) {
            property = System.getProperty(LEGACY_URL_PROPERTY, "").trim();
        }
        if (!property.isEmpty()) {
            return property;
        }
        String env = System.getenv(URL_ENV);
        if (env != null && !env.trim().isEmpty()) {
            return env.trim();
        }
        env = System.getenv(LEGACY_URL_ENV);
        return env == null ? "" : env.trim();
    }

    /**
     * 规整自更新地址：目录自动补 index.json，空串用默认。
     *
     * @param text 启动参数里的地址
     * @return 指向 index.json 的地址
     */
    public static String indexAddress(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) {
            return DEFAULT_INDEX;
        }
        // 查询串 / 锚点不参与文件名判断：.../index.json?token=x
        String query = "";
        int cut = queryStart(value);
        if (cut >= 0) {
            query = value.substring(cut);
            value = value.substring(0, cut);
        }
        String normalized = value;
        if (!Fs.hasScheme(value) || Fs.isWindowsPath(value)) {
            // 本地文件或目录：--update-url D:/updates 也能用
            try {
                normalized = Paths.get(value).toAbsolutePath().normalize().toUri().toString();
            } catch (RuntimeException e) {
                normalized = value;
            }
        }
        if (!normalized.toLowerCase(Locale.ROOT).endsWith(".json")) {
            normalized = normalized.endsWith("/") || normalized.endsWith("\\")
                    ? normalized + INDEX_FILE
                    : normalized + "/" + INDEX_FILE;
        }
        return normalized + query;
    }

    /**
     * 第一个查询串或锚点位置；没有时返回 -1
     *
     * @param value 地址
     * @return 第一个 ?/# 的位置；没有时 -1
     */
    private static int queryStart(String value) {
        int question = value.indexOf('?');
        int hash = value.indexOf('#');
        if (question < 0) {
            return hash;
        }
        if (hash < 0) {
            return question;
        }
        return Math.min(question, hash);
    }

/** 把原始 jar 路径记进系统属性，供同步计划跳过自身条目；须在入口最先调用 */
    public static void captureSelfJar() {
        Path jar = jarPath();
        if (jar != null) {
            System.setProperty(SELF_JAR_KEY, jar.toAbsolutePath().normalize().toString());
        }
    }

    /**
     * 原始 jar（-javaagent / -jar 指定的那份）的路径；找不到返回 null。
     *
     * @return 原始 jar 路径；找不到时返回 null
     */
    public static Path originalJarPath() {
        String property = System.getProperty(SELF_JAR_KEY, "").trim();
        if (!property.isEmpty()) {
            return Paths.get(property);
        }
        Path running = jarPath();
        if (running == null) {
            return null;
        }
        Path parent = running.toAbsolutePath().normalize().getParent();
        if (parent == null || !UPDATE_DIR.equalsIgnoreCase(parent.getFileName().toString())) {
            return running;
        }
        Path originDir = parent.getParent();
        if (originDir == null) {
            return null;
        }
        Path preferred = originDir.resolve("mml-server-sync.jar");
        if (Files.isRegularFile(preferred)) {
            return preferred;
        }
        try (DirectoryStream<Path> stream =
                     Files.newDirectoryStream(originDir, FILE_PREFIX + "*" + FILE_SUFFIX)) {
            for (Path candidate : stream) {
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        } catch (IOException ignored) {
            // 找不到就当没有，跳过逻辑退化为不跳过
        }
        return null;
    }

    /**
     * 当前运行的 jar 路径；以 classes 目录运行时返回 null。
     *
     * @return jar 文件路径；以 classes 目录运行时返回 null
     */
    public static Path jarPath() {
        try {
            CodeSource source = Updater.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            Path path = Paths.get(source.getLocation().toURI());
            return Files.isRegularFile(path) ? path : null;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 更新目录，与当前 jar 同级
     *
     * @param jar 当前 jar
     * @return jar 所在目录下的 update
     */
    public static Path updateDir(Path jar) {
        return jar.toAbsolutePath().normalize().getParent().resolve(UPDATE_DIR);
    }

    /**
     * 下载新 jar
     *
     * @param url 新 jar 地址
     * @param target 落盘位置，即 update/mml-server-sync-<版本>.jar
     * @param sha1 SHA1；空串表示不校验
     * @param sha256 SHA256；空串表示不校验
     */
    private static void download(String url, Path target, String sha1, String sha256)
            throws IOException {
        Path part = target.resolveSibling(target.getFileName().toString() + PART_SUFFIX);
        try {
            Files.deleteIfExists(part);
            URLConnection connection = open(url);
            try (InputStream input = connection.getInputStream();
                 OutputStream output = Files.newOutputStream(part)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    output.write(buffer, 0, read);
                }
            }
            if (!sha1.isEmpty() && !Downloader.hash(part, "SHA-1").equalsIgnoreCase(sha1)) {
                throw new IOException(Text.of(Msg.NEW_JAR_SHA1_MISMATCH));
            }
            if (!sha256.isEmpty() && !Downloader.hash(part, "SHA-256").equalsIgnoreCase(sha256)) {
                throw new IOException(Text.of(Msg.NEW_JAR_SHA256_MISMATCH));
            }
            try {
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(part);
        }
    }

    /**
     * 已经下过并且对得上：索引给了哈希就比哈希，没有就交给结构校验
     *
     * @param target 已下载文件
     * @param sha1 索引里的 SHA1（可空）
     * @param sha256 索引里的 SHA256（可空）
     * @return 哈希或结构校验通过时 true
     */
    private static boolean sameFile(Path target, String sha1, String sha256) {
        if (!sha1.isEmpty() || !sha256.isEmpty()) {
            return Downloader.matches(target, sha1, sha256);
        }
        return Verify.check(target) == null;
    }

    /**
     * 把索引里的哈希落到 jar 旁边，下次启动加载前用同一份值复核
     *
     * @param jar 目标 jar
     * @param suffix 后缀
     * @param value 哈希值
     */
    private static void writeSidecar(Path jar, String suffix, String value) {
        Path file = jar.resolveSibling(jar.getFileName().toString() + suffix);
        try {
            if (value == null || value.trim().isEmpty()) {
                Files.deleteIfExists(file);
            } else {
                Files.write(file, value.trim().getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // 写不下去只影响下次启动的哈希复核，不影响本次更新
        }
    }

    /**
     * 删掉更新目录里比新版本旧的包，只留一份
     *
     * @param dir update 目录
     * @param keep 刚下载好的那份
     */
    private static void prune(Path dir, Path keep) {
        String keepName = keep.getFileName().toString();
        String keepVersion = keepName.substring(FILE_PREFIX.length(),
                keepName.length() - FILE_SUFFIX.length());
        try (DirectoryStream<Path> stream =
                     Files.newDirectoryStream(dir, FILE_PREFIX + "*" + FILE_SUFFIX)) {
            for (Path file : stream) {
                if (file.equals(keep)) {
                    continue;
                }
                String name = file.getFileName().toString();
                String version = name.substring(FILE_PREFIX.length(),
                        name.length() - FILE_SUFFIX.length());
                if (compare(version, keepVersion) < 0) {
                    Files.deleteIfExists(file);
                    Files.deleteIfExists(file.resolveSibling(name + Verify.SHA1_SIDECAR));
                    Files.deleteIfExists(file.resolveSibling(name + SHA256_SIDECAR));
                }
            }
        } catch (Exception ignored) {
            // 清理失败不影响更新
        }
    }

    /**
     * 把索引里的下载地址补成绝对地址
     *
     * @param base 索引自身的地址
     * @param url 索引里写的地址；已是绝对地址时原样返回
     * @return 绝对地址；解析失败时返回原值
     */
    private static String resolveUrl(String base, String url) {
        if (url == null || url.isEmpty()
                || url.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")) {
            return url == null ? "" : url;
        }
        try {
            return new URL(new URL(base), url).toString();
        } catch (IOException e) {
            return url;
        }
    }

    /**
     * 拉取索引
     *
     * @param text HTTP(S) 或 file 地址
     * @return 响应体内容
     */
    private static byte[] fetch(String text) throws IOException {
        URLConnection connection = open(text);
        try (InputStream input = connection.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    /**
     * 打开连接并检查状态码。
     *
     * @param text 地址
     * @return 连接
     * @throws IOException 地址非法或返回非 2xx 时
     */
    private static URLConnection open(String text) throws IOException {
        URL url;
        try {
            url = new URL(text);
        } catch (IOException e) {
            throw new IOException(Text.of(Msg.INVALID_URL) + text, e);
        }
        URLConnection connection = url.openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "MML-ServerSync/" + Main.VERSION);
        connection.setRequestProperty("Cache-Control", "no-cache");
        if (connection instanceof HttpURLConnection) {
            HttpURLConnection http = (HttpURLConnection) connection;
            http.setInstanceFollowRedirects(true);
            int code = http.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + ": " + text);
            }
        }
        return connection;
    }

    /**
     * 版本号里只留做文件名的安全字符
     *
     * @param version 版本号
     * @return 只含安全字符的文件名
     */
    private static String safeVersion(String version) {
        String out = version.replaceAll("[^A-Za-z0-9._-]", "_");
        return out.isEmpty() ? "unknown" : out;
    }

    /**
     * 版本比较，只比数字段，非数字部分忽略
     *
     * @param left 左侧版本，如 1.2.0
     * @param right 右侧版本
     * @return 左侧更新时为正数，相同时为 0
     */
    public static int compare(String left, String right) {
        int[] a = numbers(left);
        int[] b = numbers(right);
        int length = Math.max(a.length, b.length);
        for (int i = 0; i < length; i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) {
                return x > y ? 1 : -1;
            }
        }
        return 0;
    }

    /**
     * 把版本号拆成数字段。
     *
     * @param version 版本号
     * @return 数字段数组
     */
    private static int[] numbers(String version) {
        String[] parts = (version == null ? "" : version.trim()).split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String digits = parts[i].replaceAll("[^0-9].*$", "");
            try {
                out[i] = digits.isEmpty() ? 0 : Integer.parseInt(digits);
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    /**
     * 响应体开头是 '<'（跳过 BOM 与空白）时，基本可以确定是网页而不是 JSON
     *
     * @param data 响应体
     * @return 开头是 '<' 时 true
     */
    private static boolean looksLikeHtml(byte[] data) {
        int index = 0;
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB
                && (data[2] & 0xFF) == 0xBF) {
            index = 3;
        }
        while (index < data.length && ((data[index] & 0xFF) == ' ' || (data[index] & 0xFF) == '\n'
                || (data[index] & 0xFF) == '\r' || (data[index] & 0xFF) == '\t')) {
            index++;
        }
        return index < data.length && data[index] == '<';
    }
}
