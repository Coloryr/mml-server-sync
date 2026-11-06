package com.coloryr.mml.serversync;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/** 一次服务器实例更新。 */
final class Sync {
    /** 禁用后缀：与 MMC / HMCL 一致 */
    private static final String DISABLED_SUFFIX = ".disabled";
    /** 旧版禁用后缀，读取时仍认 */
    private static final String LEGACY_DISABLE_SUFFIX = ".disable";

    private final Options options;
    private final Downloader downloader;
    private final Path tempDir;

    Sync(Options options) {
        this.options = options;
        this.downloader = new Downloader(options.apiKey, options.quiet);
        this.tempDir = options.stateDir.resolve("tmp");
    }

    void run() throws IOException {
        String manifestUrl;
        byte[] data;
        Manifest current;
        try {
            manifestUrl = toUrl(options.manifest);
            data = downloader.fetch(manifestUrl);
            current = Manifest.parse(data);
            current.resolveUrls(manifestUrl);
        } catch (IOException e) {
            // 清单没拉到等于一个文件都没动：游戏模式只记日志，别让服务器掉线锁死启动
            if (!options.lenient) {
                throw e;
            }
            Log.error("清单获取失败，跳过本次同步: " + e.getMessage());
            return;
        }

        Manifest old = loadOld(current);
        if (old == null) {
            info("本地无同步记录，远程包版本 " + packVersionOf(current));
        } else {
            info("本地包版本 " + packVersionOf(old) + "，远程包版本 " + packVersionOf(current));
        }
        if (!options.force && sameVersion(old, current)) {
            info("包版本未变（" + current.packVersion + "），跳过同步");
            return;
        }

        validate(current);
        if (old != null) {
            validate(old);
        }

        // 删旧文件之前先记下玩家启用着的条目：可选模组换文件名时按它延续启用状态
        Set<String> keptEnabled = enabledIdentities(old);

        List<String> removedDirs = new ArrayList<>();
        Set<String> removedFiles = new HashSet<>();
        planRemovals(old, current, removedFiles, removedDirs);
        if (options.dryRun) {
            printPlan(current, old, removedFiles, removedDirs);
            return;
        }
        if (!options.noDelete) {
            applyRemovals(removedFiles, removedDirs);
        } else {
            info("--no-delete：跳过 " + removedFiles.size() + " 个文件、" + removedDirs.size() + " 个目录的删除");
        }

        boolean needBase = current.base != null && (old == null || old.base == null);
        List<PlannedFile> files = changedFiles(current, keptEnabled);
        List<ArchivePlan> archives = archivePlans(old, current, removedDirs);
        int archiveCount = 0;
        for (ArchivePlan plan : archives) {
            if (!plan.skip) {
                archiveCount++;
            }
        }
        int total = files.size() + archiveCount + (needBase ? 1 : 0);
        info("需要处理 " + total + " 项（模组/文件 " + files.size() + "，配置压缩包 "
                + archiveCount + "，基础包 " + (needBase ? 1 : 0) + "）");

        Files.createDirectories(tempDir);
        Path baseTemp = null;
        List<DownloadTask> tasks = new ArrayList<>();
        if (needBase) {
            Manifest.BaseEntry base = current.base;
            baseTemp = tempDir.resolve(safeName(base.file));
            tasks.add(new DownloadTask(base.url, baseTemp, base.sha1, base.sha256, null));
        }

        for (PlannedFile planned : files) {
            tasks.add(new DownloadTask(null, planned.target, planned.item.sha1, planned.item.sha256,
                    planned.item));
        }

        List<ArchivePlan> downloaded = new ArrayList<>();
        int archiveIndex = 0;
        for (ArchivePlan plan : archives) {
            if (plan.skip) {
                continue;
            }
            String url = plan.item.url.trim();
            if (url.isEmpty()) {
                throw new IOException("配置压缩包没有下载地址: " + plan.item.file);
            }
            Path temp = tempDir.resolve("archive-" + (archiveIndex++) + "-" + safeName(plan.item.file));
            tasks.add(new DownloadTask(url, temp, plan.item.sha1, plan.item.sha256, null));
            downloaded.add(new ArchivePlan(plan.item, temp, false));
        }

        downloadAll(tasks, total);

        if (baseTemp != null) {
            Fs.extractZip(baseTemp, options.baseDir, true);
            Files.deleteIfExists(baseTemp);
            info("基础包已解压到 " + options.baseDir);
        }

        downloaded.sort(Comparator.comparingInt((ArchivePlan plan) -> depth(plan.item.dir)));
        for (ArchivePlan plan : downloaded) {
            Path target = Fs.resolveSafe(options.gameDir, plan.item.dir);
            if (plan.item.deleteOld) {
                Fs.deleteDirectory(target);
            }
            Fs.extractZip(plan.temp, target, false);
            Files.deleteIfExists(plan.temp);
            info("解压配置: " + plan.item.dir);
        }

        saveState(data);
        info("同步完成，当前包版本 " + current.packVersion);
    }

    /** 清单的包版本，空串按「未标注」显示。 */
    private static String packVersionOf(Manifest manifest) {
        String version = manifest.packVersion == null ? "" : manifest.packVersion.trim();
        return version.isEmpty() ? "未标注" : version;
    }

    private Manifest loadOld(Manifest current) {
        Path state = options.stateDir.resolve("last-manifest.json");
        Path fallback = options.baseDir.resolve("server.json");
        for (Path path : new Path[]{state, fallback}) {
            if (!Files.isRegularFile(path)) {
                continue;
            }
            try {
                byte[] data = Files.readAllBytes(path);
                Manifest old = Manifest.parse(data);
                old.resolveUrls(toUrl(options.manifest));
                return old;
            } catch (IOException e) {
                info("忽略无法读取的旧清单 " + path + "：" + e.getMessage());
            }
        }
        return null;
    }

    private boolean sameVersion(Manifest old, Manifest current) {
        if (old == null) {
            return false;
        }
        String a = old.packVersion.trim();
        String b = current.packVersion.trim();
        return !b.isEmpty() && a.equals(b);
    }

    private void validate(Manifest manifest) throws IOException {
        for (Manifest.FileEntry item : manifest.files) {
            Fs.resolveSafe(options.gameDir, item.path);
        }
        for (Manifest.ArchiveEntry item : manifest.archives) {
            Fs.resolveSafe(tempDir, item.file);
            Fs.resolveSafe(options.gameDir, item.dir);
        }
        if (manifest.base != null) {
            Fs.resolveSafe(tempDir, manifest.base.file);
        }
    }

    private void planRemovals(Manifest old, Manifest current, Set<String> files,
                              List<String> dirs) {
        if (old == null) {
            return;
        }
        Map<String, Manifest.FileEntry> byIdentity = new HashMap<>();
        for (Manifest.FileEntry item : current.files) {
            byIdentity.put(item.identity(), item);
        }
        for (Manifest.FileEntry item : old.files) {
            Manifest.FileEntry replacement = byIdentity.get(item.identity());
            if (replacement == null || !replacement.path.equals(item.path)) {
                files.add(item.path);
            }
        }
        Set<String> currentArchives = new HashSet<>();
        for (Manifest.ArchiveEntry item : current.archives) {
            currentArchives.add(item.file);
        }
        for (Manifest.ArchiveEntry item : old.archives) {
            if (!currentArchives.contains(item.file) && item.deleteOld && !item.dir.isEmpty()) {
                dirs.add(item.dir);
            }
        }
        dirs.sort(Comparator.comparingInt((String dir) -> -depth(dir)));
    }

    private void applyRemovals(Set<String> files, List<String> dirs) throws IOException {
        for (String path : files) {
            Path target = Fs.resolveSafe(options.gameDir, path);
            Fs.deleteFile(target);
            info("删除旧文件: " + path);
        }
        for (String dir : dirs) {
            Path target = Fs.resolveSafe(options.gameDir, dir);
            if (Files.exists(target)) {
                Fs.deleteDirectory(target);
                info("删除旧配置目录: " + dir);
            }
        }
    }

    /**
     * 算出要下载的文件与各自落盘位置。
     *
     * 禁用中的文件也算装好：按内容比对，别把玩家停用的模组又装回启用；
     * 本地没有、清单又标了可选时写在禁用名下，装完默认不加载。
     */
    private List<PlannedFile> changedFiles(Manifest current, Set<String> keptEnabled)
            throws IOException {
        List<PlannedFile> out = new ArrayList<>();
        for (Manifest.FileEntry item : current.files) {
            Path target = Fs.resolveSafe(options.gameDir, item.path);
            Path existing = existingVariant(target);
            if (existing != null && Downloader.matches(existing, item.sha1, item.sha256)) {
                continue;
            }
            // 玩家之前启用着这条（可能换了文件名）时，新版本照样装成启用
            boolean disable = item.optional && !keptEnabled.contains(item.identity());
            Path file = existing != null ? existing
                    : disable ? withSuffix(target, DISABLED_SUFFIX) : target;
            out.add(new PlannedFile(item, file));
        }
        return out;
    }

    /**
     * 旧清单里玩家处于启用状态的条目身份。
     *
     * 可选模组换文件名时按项目号延续启用状态：玩家启用过的，更新版本后不要重新禁用。
     * 必须在删除旧文件之前调用，否则读不到本地的启用 / 禁用状态。
     */
    private Set<String> enabledIdentities(Manifest old) {
        Set<String> out = new HashSet<>();
        if (old == null) {
            return out;
        }
        for (Manifest.FileEntry item : old.files) {
            Path target;
            try {
                target = Fs.resolveSafe(options.gameDir, item.path);
            } catch (IOException e) {
                continue;
            }
            Path existing = existingVariant(target);
            if (existing != null && !isDisabled(existing)) {
                out.add(item.identity());
            }
        }
        return out;
    }

    /** 文件名是不是禁用状态（.disabled 或旧版 .disable） */
    private static boolean isDisabled(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(DISABLED_SUFFIX) || name.endsWith(LEGACY_DISABLE_SUFFIX);
    }

    /**
     * 本地已有的文件：原始名，或追加了 .disabled / .disable 后缀的禁用版。
     *
     * 模组、资源包被禁用后文件名会变，按原始路径判断存在性会把「已经装好但停用」当成没装，
     * 每次同步覆盖一次，玩家停用的东西就又被装回启用了。
     */
    private static Path existingVariant(Path target) {
        if (Files.isRegularFile(target)) {
            return target;
        }
        for (String suffix : new String[]{DISABLED_SUFFIX, LEGACY_DISABLE_SUFFIX}) {
            Path candidate = withSuffix(target, suffix);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** 给文件名追加后缀，如 `mods/a.jar` → `mods/a.jar.disabled` */
    private static Path withSuffix(Path target, String suffix) {
        return target.resolveSibling(target.getFileName().toString() + suffix);
    }

    private List<ArchivePlan> archivePlans(Manifest old, Manifest current, List<String> removedDirs) {
        Map<String, Manifest.ArchiveEntry> oldArchives = new HashMap<>();
        if (old != null) {
            for (Manifest.ArchiveEntry item : old.archives) {
                oldArchives.put(item.file, item);
            }
        }
        Set<String> skipped = new HashSet<>();
        for (Manifest.ArchiveEntry item : current.archives) {
            if (archiveUnchanged(oldArchives.get(item.file), item)) {
                skipped.add(item.dir);
            }
        }
        Set<String> cleared = new HashSet<>(removedDirs);
        for (Manifest.ArchiveEntry item : current.archives) {
            if (!skipped.contains(item.dir) && item.deleteOld) {
                cleared.add(item.dir);
            }
        }
        List<ArchivePlan> out = new ArrayList<>();
        for (Manifest.ArchiveEntry item : current.archives) {
            boolean skip = skipped.contains(item.dir);
            if (skip) {
                for (String ancestor : cleared) {
                    if (Fs.under(item.dir, ancestor)) {
                        skip = false;
                        break;
                    }
                }
            }
            out.add(new ArchivePlan(item, null, skip));
        }
        return out;
    }

    private boolean archiveUnchanged(Manifest.ArchiveEntry old, Manifest.ArchiveEntry current) {
        if (old == null || !old.dir.equals(current.dir) || old.deleteOld != current.deleteOld) {
            return false;
        }
        String a = old.sha1.isEmpty() ? old.sha256 : old.sha1;
        String b = current.sha1.isEmpty() ? current.sha256 : current.sha1;
        if (a.isEmpty() || !a.equalsIgnoreCase(b)) {
            return false;
        }
        try {
            Path target = Fs.resolveSafe(options.gameDir, current.dir);
            return Files.isDirectory(target);
        } catch (IOException e) {
            return false;
        }
    }

    private void printPlan(Manifest current, Manifest old, Set<String> removedFiles,
                          List<String> removedDirs) {
        info("清单: " + (current.name.isEmpty() ? "(未命名)" : current.name)
                + "，版本 " + current.packVersion);
        info("将删除旧文件 " + removedFiles.size() + " 个");
        for (String path : removedFiles) {
            info("  - " + path);
        }
        info("将删除旧配置目录 " + removedDirs.size() + " 个");
        for (String dir : removedDirs) {
            info("  - " + dir);
        }
        info("当前需要检查 " + current.files.size() + " 个文件，"
                + current.archives.size() + " 个配置压缩包");
        if (current.base != null && (old == null || old.base == null)) {
            info("基础包: " + current.base.file);
        }
    }

    private void saveState(byte[] data) throws IOException {
        Files.createDirectories(options.stateDir);
        Path target = options.stateDir.resolve("last-manifest.json");
        Path temp = options.stateDir.resolve("last-manifest.json.tmp");
        Files.write(temp, data);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.deleteIfExists(temp);
    }

    private void progress(int done, int total) {
        if (!options.quiet && total > 0) {
            Log.info("[progress] " + done + "/" + total);
        }
    }

    private void info(String message) {
        if (!options.quiet) {
            Log.info(message);
        }
    }

    /**
     * 按选项里的线程数并发下载
     *
     * 任一条失败就取消其余任务并抛出第一个错误，避免半套文件继续被解压。
     *
     * - `tasks`: 待下载条目
     * - `total`: 条目总数，进度按它报
     */
    private void downloadAll(List<DownloadTask> tasks, int total) throws IOException {
        if (tasks.isEmpty()) {
            return;
        }
        int threads = Math.max(1, Math.min(options.threads, tasks.size()));
        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "mml-server-sync-download");
            thread.setDaemon(true);
            return thread;
        });
        AtomicInteger finished = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>(tasks.size());
        try {
            for (DownloadTask task : tasks) {
                futures.add(pool.submit(() -> {
                    String url = task.entry == null
                            ? task.url
                            : downloader.resolveFile(task.entry);
                    downloader.download(url, task.target, task.sha1, task.sha256);
                    progress(finished.incrementAndGet(), total);
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof IOException) {
                        throw (IOException) cause;
                    }
                    throw new IOException(cause == null ? "下载失败" : cause.toString(), cause);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("下载被中断", e);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** 需要下载的一个文件：清单条目与落盘位置 */
    private static final class PlannedFile {
        final Manifest.FileEntry item;
        final Path target;

        PlannedFile(Manifest.FileEntry item, Path target) {
            this.item = item;
            this.target = target;
        }
    }

    /** 一个待下载条目 */
    private static final class DownloadTask {
        /// 直接可用的地址；为空时用 `entry` 解析平台地址
        final String url;
        /// 落盘位置
        final Path target;
        /// SHA1，空串表示不校验
        final String sha1;
        /// SHA256，空串表示不校验
        final String sha256;
        /// 清单里的文件条目；基础包与配置包为 null
        final Manifest.FileEntry entry;

        DownloadTask(String url, Path target, String sha1, String sha256, Manifest.FileEntry entry) {
            this.url = url;
            this.target = target;
            this.sha1 = sha1;
            this.sha256 = sha256;
            this.entry = entry;
        }
    }

    private static int depth(String path) {
        int count = 0;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '/') {
                count++;
            }
        }
        return count;
    }

    private static String safeName(String name) {
        String out = name.replace('\\', '/');
        int slash = out.lastIndexOf('/');
        if (slash >= 0) {
            out = out.substring(slash + 1);
        }
        return out.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String toUrl(String value) throws IOException {
        String text = value.trim();
        // Windows 盘符路径（D:\x、D:/x）不是 URL：交给下面的 scheme 正则会被当成「D:」协议
        if (isWindowsPath(text)) {
            return fileUri(text);
        }
        if (text.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")) {
            return text;
        }
        return fileUri(text);
    }

    /** 是不是带盘符的 Windows 绝对路径，如 D:\dir\server.json 或 D:/dir/server.json */
    private static boolean isWindowsPath(String text) {
        if (text.length() < 3 || text.charAt(1) != ':' || !isAsciiLetter(text.charAt(0))) {
            return false;
        }
        char separator = text.charAt(2);
        return separator == '\\' || separator == '/';
    }

    private static boolean isAsciiLetter(char value) {
        return (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z');
    }

    /** 本地路径转 file URI，相对路径按当前目录解析 */
    private static String fileUri(String path) throws IOException {
        return java.nio.file.Paths.get(path).toAbsolutePath().normalize().toUri().toString();
    }

    private static final class ArchivePlan {
        final Manifest.ArchiveEntry item;
        final Path temp;
        final boolean skip;

        ArchivePlan(Manifest.ArchiveEntry item, Path temp, boolean skip) {
            this.item = item;
            this.temp = temp;
            this.skip = skip;
        }
    }
}
