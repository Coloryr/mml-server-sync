package com.coloryr.mml.serversync;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** 清单路径、删除与 zip 解压的安全工具。 */
final class Fs {
    private Fs() {}

    static Path normalizeRelative(String raw) throws IOException {
        String value = raw == null ? "" : raw.trim().replace('\\', '/');
        if (value.isEmpty()) {
            throw new IOException("清单里有空路径");
        }
        if (value.startsWith("/") || value.matches("^[A-Za-z]:.*")) {
            throw new IOException("清单路径不能是绝对路径: " + raw);
        }
        List<String> parts = new ArrayList<>();
        for (String part : value.split("/", -1)) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                throw new IOException("清单路径不能包含 ..: " + raw);
            }
            parts.add(part);
        }
        if (parts.isEmpty()) {
            throw new IOException("清单路径为空: " + raw);
        }
        Path out = null;
        for (String part : parts) {
            out = out == null ? java.nio.file.Paths.get(part) : out.resolve(part);
        }
        return out;
    }

    static Path resolveSafe(Path root, String raw) throws IOException {
        Path base = root.toAbsolutePath().normalize();
        Path rel = normalizeRelative(raw);
        Path target = base.resolve(rel).normalize();
        if (!target.startsWith(base) || target.equals(base)) {
            throw new IOException("路径越界: " + raw);
        }
        return target;
    }

    static void deleteFile(Path file) throws IOException {
        Files.deleteIfExists(file);
        Files.deleteIfExists(java.nio.file.Paths.get(file.toString() + ".disable"));
        Files.deleteIfExists(java.nio.file.Paths.get(file.toString() + ".disabled"));
    }

    static void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void extractZip(Path zip, Path target, boolean base) throws IOException {
        if (!zip.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new IOException("只支持 zip 压缩包: " + zip);
        }
        List<ZipEntry> entries = new ArrayList<>();
        try (ZipFile file = new ZipFile(zip.toFile(), java.nio.charset.StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> enumeration = file.entries();
            while (enumeration.hasMoreElements()) {
                entries.add(enumeration.nextElement());
            }
            for (ZipEntry entry : entries) {
                String name = entry.getName();
                if (name == null || name.trim().isEmpty()) {
                    continue;
                }
                resolveSafe(target, name);
                if (base) {
                    checkBaseEntry(name);
                }
            }
            for (ZipEntry entry : entries) {
                String name = entry.getName();
                if (name == null || name.trim().isEmpty()) {
                    continue;
                }
                Path out = resolveSafe(target, name);
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                Files.createDirectories(out.getParent());
                try (InputStream input = file.getInputStream(entry);
                     OutputStream output = Files.newOutputStream(out, StandardOpenOption.CREATE,
                             StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        output.write(buffer, 0, read);
                    }
                }
            }
        }
    }

    private static void checkBaseEntry(String raw) throws IOException {
        String value = raw.replace('\\', '/').replaceAll("^/+", "");
        String[] parts = value.split("/", -1);
        String first = parts.length > 0 ? parts[0].toLowerCase(Locale.ROOT) : "";
        if (first.equals("game.json") || first.equals("icon.png")
                || first.equals("server.json") || first.equals("server.json.sha1")
                || first.equals("server.json.old") || first.equals("server_instance_ids.json")) {
            throw new IOException("基础包不能覆盖启动器元数据: " + raw);
        }
        if (first.equals(".minecraft") && parts.length > 1) {
            String second = parts[1].toLowerCase(Locale.ROOT);
            if (second.equals("server.json") || second.equals("server.json.sha1")
                    || second.equals("server.json.old")
                    || second.equals("server_instance_ids.json")) {
                throw new IOException("基础包不能覆盖服务器清单: " + raw);
            }
        }
    }

    static boolean under(String dir, String ancestor) {
        return dir.startsWith(ancestor) && dir.length() > ancestor.length()
                && dir.charAt(ancestor.length()) == '/';
    }

    static void copy(Path from, Path to) throws IOException {
        Files.createDirectories(to.getParent());
        Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
    }
}
