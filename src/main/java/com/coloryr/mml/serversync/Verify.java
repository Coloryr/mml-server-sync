package com.coloryr.mml.serversync;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * 更新包校验，类加载之前先过这一关。
 *
 * 查四件事：能当 jar 完整读通（结构 + CRC）、带启动清单与入口类、入口类的 class 版本
 * 不高于当前 JVM、下载时记下的哈希一致。不通过就返回原因，调用方据此拒绝加载。
 */
final class Verify {
    /** 更新包必须带上的入口类 */
    static final String ENTRY_CLASS = "com/coloryr/mml/serversync/Runner.class";
    /** 下载时写下的哈希文件名后缀 */
    static final String SHA1_SIDECAR = ".sha1";
    private static final String SHA256_SIDECAR = ".sha256";
    /** class 文件头：魔数 4 字节 + 次版本 2 字节 + 主版本 2 字节 */
    private static final int CLASS_HEAD = 8;

    private Verify() {}

    /**
     * 校验一个更新包
     *
     * - `jar`: 更新包路径
     *
     * # 返回值
     *
     * 通过时返回 null，否则是不通过的原因
     */
    static String check(Path jar) {
        if (!Files.isRegularFile(jar)) {
            return "不是文件";
        }
        try {
            if (Files.size(jar) == 0) {
                return "空文件";
            }
        } catch (IOException e) {
            return "读不到文件大小: " + describe(e);
        }

        try (JarFile file = new JarFile(jar.toFile(), true)) {
            Manifest manifest = file.getManifest();
            if (manifest == null) {
                return "缺少 MANIFEST.MF";
            }
            Attributes attributes = manifest.getMainAttributes();
            if (blank(attributes.getValue(Attributes.Name.MAIN_CLASS))) {
                return "清单缺少 Main-Class";
            }
            if (blank(attributes.getValue("Premain-Class"))) {
                return "清单缺少 Premain-Class";
            }
            JarEntry entry = file.getJarEntry(ENTRY_CLASS);
            if (entry == null) {
                return "缺少入口类 " + ENTRY_CLASS;
            }
            String version = checkClassVersion(file, entry);
            if (version != null) {
                return version;
            }
            readAll(file);
        } catch (Exception e) {
            return "无法作为 jar 读取: " + describe(e);
        }
        return checkHash(jar);
    }

    /** 入口类必须是 class 文件，且不能要求比当前 JVM 更新的版本 */
    private static String checkClassVersion(JarFile file, JarEntry entry) throws IOException {
        byte[] head = new byte[CLASS_HEAD];
        int read = 0;
        try (InputStream input = file.getInputStream(entry)) {
            while (read < head.length) {
                int count = input.read(head, read, head.length - read);
                if (count < 0) {
                    break;
                }
                read += count;
            }
        }
        if (read < head.length) {
            return "入口类不完整";
        }
        if ((head[0] & 0xFF) != 0xCA || (head[1] & 0xFF) != 0xFE
                || (head[2] & 0xFF) != 0xBA || (head[3] & 0xFF) != 0xBE) {
            return "入口类不是 class 文件";
        }
        int major = ((head[6] & 0xFF) << 8) | (head[7] & 0xFF);
        int running = runningClassVersion();
        if (running > 0 && major > running) {
            return "入口类需要更新的 Java（class " + major + "，当前 " + running + "）";
        }
        return null;
    }

    /** 把所有条目读到底，让 ZipFile 校验 CRC 与解压是否完整 */
    private static void readAll(JarFile file) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        Enumeration<JarEntry> entries = file.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            if (entry.isDirectory()) {
                continue;
            }
            try (InputStream input = file.getInputStream(entry)) {
                while (input.read(buffer) >= 0) {
                    // 读完即触发 CRC 校验，这里不需要内容
                }
            }
        }
    }

    /** 下载时记下了哈希就比对，没记就只信上面的结构校验 */
    private static String checkHash(Path jar) {
        String sha256 = sidecar(jar, SHA256_SIDECAR);
        if (!sha256.isEmpty() && !Downloader.hash(jar, "SHA-256").equalsIgnoreCase(sha256)) {
            return "SHA256 与下载时记录的不一致";
        }
        String sha1 = sidecar(jar, SHA1_SIDECAR);
        if (!sha1.isEmpty() && !Downloader.hash(jar, "SHA-1").equalsIgnoreCase(sha1)) {
            return "SHA1 与下载时记录的不一致";
        }
        return null;
    }

    private static String sidecar(Path jar, String suffix) {
        Path file = jar.resolveSibling(jar.getFileName().toString() + suffix);
        try {
            return Files.isRegularFile(file)
                    ? new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim()
                    : "";
        } catch (IOException e) {
            return "";
        }
    }

    /** 当前 JVM 能接受的 class 主版本，如 52 */
    private static int runningClassVersion() {
        try {
            String value = System.getProperty("java.class.version", "");
            int dot = value.indexOf('.');
            return Integer.parseInt(dot > 0 ? value.substring(0, dot) : value);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }
}
