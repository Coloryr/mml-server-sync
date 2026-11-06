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

import com.coloryr.mml.serversync.host.Progress;

/** 一次服务器实例更新。 */
final class Sync {
    /** 禁用后缀：与 MMC / HMCL 一致 */
    private static final String DISABLED_SUFFIX = ".disabled";
    /** 旧版禁用后缀，读取时仍认 */
    private static final String LEGACY_DISABLE_SUFFIX = ".disable";

    private final Options options;
    private final Downloader downloader;
    private final Path tempDir;
    /** 进度窗口；没有窗口时是空实现 */
    private final Progress ui;

    Sync(Options options, Progress ui) {
        this.options = options;
        this.downloader = new Downloader(options.apiKey, options.quiet);
        this.tempDir = options.stateDir.resolve("tmp");
        this.ui = ui == null ? Progress.NONE : ui;
    }

    /**
     * 跑一次同步
     *
     * # 返回值
     *
     * 一句话结果，给进度窗口与日志用
     */
    String run() throws IOException {
        ui.stage("获取清单");
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
            return "清单获取失败，已跳过同步";
        }
        ui.title((current.name.isEmpty() ? "服务器实例" : current.name)
                + " · " + packVersionOf(current));

        Manifest old = loadOld(current);
        if (old == null) {
            info("本地无同步记录，远程包版本 " + packVersionOf(current));
        } else {
            info("本地包版本 " + packVersionOf(old) + "，远程包版本 " + packVersionOf(current));
        }
        if (!options.force && sameVersion(old, current)) {
            info("包版本未变（" + current.packVersion + "），跳过同步");
            return "包版本未变，跳过同步";
        }

        ui.stage("检查本地文件");
        validate(current);
        if (old != null) {
            validate(old);
        }

        // 上次跳过的可选模组：这次发布方没换内容就不再问
        List<Skipped.Entry> previousSkipped = Skipped.load(options.stateDir);
        Set<String> skipPaths = new HashSet<>(options.skip);
        List<Manifest.FileEntry> optionalChanges = changedOptional(current, old, previousSkipped);
        if (!options.dryRun && skipPaths.isEmpty() && !optionalChanges.isEmpty() && canAsk()) {
            ui.stage("等待选择可选模组");
            Set<String> unchecked = askOptionalMods(optionalChanges, old);
            if (unchecked == null) {
                info("已取消同步，未改动任何文件");
                return "已取消同步";
            }
            skipPaths.addAll(unchecked);
        }
        if (!skipPaths.isEmpty()) {
            info("本次跳过 " + skipPaths.size() + " 个可选模组");
        }

        // 删旧文件之前先记下玩家启用着的条目：可选模组换文件名时按它延续启用状态
        Set<String> keptEnabled = enabledIdentities(old, previousSkipped);

        Set<String> skippedIdentities = skippedIdentities(current, skipPaths);
        List<String> removedDirs = new ArrayList<>();
        Set<String> removedFiles = new HashSet<>();
        planRemovals(old, current, removedFiles, removedDirs, skippedIdentities);
        if (options.dryRun) {
            printOptionalPlan(optionalChanges, skipPaths);
            printPlan(current, old, removedFiles, removedDirs);
            return "只打印了同步计划，未改动文件";
        }
        if (!options.noDelete) {
            applyRemovals(removedFiles, removedDirs);
        } else {
            info("--no-delete：跳过 " + removedFiles.size() + " 个文件、" + removedDirs.size() + " 个目录的删除");
        }
        cleanKept(previousSkipped, old, current, skipPaths, skippedIdentities);

        boolean needBase = current.base != null && (old == null || old.base == null);
        List<PlannedFile> files = changedFiles(current, keptEnabled, skipPaths);
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
        // 下载一步、解压一步，进度条按两步走
        ui.total(total + archiveCount + (needBase ? 1 : 0));

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

        ui.stage("下载文件");
        int done = downloadAll(tasks, total);

        if (baseTemp != null) {
            ui.stage("解压基础包");
            Fs.extractZip(baseTemp, options.baseDir, true);
            Files.deleteIfExists(baseTemp);
            info("基础包已解压到 " + options.baseDir);
            ui.step(++done, current.base.file);
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
            ui.step(++done, plan.item.dir);
        }

        saveState(data);
        Skipped.save(options.stateDir, skippedMarkers(current, old, previousSkipped, skipPaths));
        return "同步完成，当前包版本 " + current.packVersion;
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
                              List<String> dirs, Set<String> skippedIdentities) {
        if (old == null) {
            return;
        }
        Map<String, Manifest.FileEntry> byIdentity = new HashMap<>();
        for (Manifest.FileEntry item : current.files) {
            byIdentity.put(item.identity(), item);
        }
        for (Manifest.FileEntry item : old.files) {
            // 这次不更新的条目：旧文件留着，别删完又不装新的
            if (skippedIdentities.contains(item.identity())) {
                continue;
            }
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
    private List<PlannedFile> changedFiles(Manifest current, Set<String> keptEnabled,
                                           Set<String> skipPaths) throws IOException {
        List<PlannedFile> out = new ArrayList<>();
        for (Manifest.FileEntry item : current.files) {
            // 这次跳过的条目不下载，本地旧版本原样留着
            if (skipPaths.contains(item.path)) {
                continue;
            }
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
    private Set<String> enabledIdentities(Manifest old, List<Skipped.Entry> previousSkipped) {
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
            // 上次跳过留下的旧版本在别的文件名下，按跳过记录找它现在的状态
            if (existing == null) {
                existing = keptFile(previousSkipped, item.path);
            }
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

    /**
     * 这次会变、需要让玩家确认的可选模组
     *
     * 只收 `Optional` 条目，并且要同时满足「清单里是新增或内容变了」与「本地确实要下载」，
     * 避免列出来之后因为本地哈希一致根本不下载。上次跳过且发布方没换内容的也不再列。
     *
     * - `current`: 远程清单
     * - `old`: 本地旧清单，没有时传 null
     * - `previous`: 上次的跳过记录
     *
     * # 返回值
     *
     * 会变的可选条目，顺序与清单一致
     */
    private List<Manifest.FileEntry> changedOptional(Manifest current, Manifest old,
                                                     List<Skipped.Entry> previous) throws IOException {
        List<Manifest.FileEntry> out = new ArrayList<>();
        for (Manifest.FileEntry item : current.files) {
            if (!item.optional) {
                continue;
            }
            if (skippedBefore(previous, item)) {
                continue;
            }
            Manifest.FileEntry before = findByIdentity(old, item.identity());
            if (before != null && before.hasSameContent(item)) {
                continue;
            }
            Path existing = existingVariant(Fs.resolveSafe(options.gameDir, item.path));
            if (existing != null && Downloader.matches(existing, item.sha1, item.sha256)) {
                continue;
            }
            out.add(item);
        }
        return out;
    }

    /** 旧清单里同一身份的条目；没有时返回 null */
    private static Manifest.FileEntry findByIdentity(Manifest manifest, String identity) {
        if (manifest == null) {
            return null;
        }
        for (Manifest.FileEntry item : manifest.files) {
            if (item.identity().equals(identity)) {
                return item;
            }
        }
        return null;
    }

    /** 上次跳过的是不是同一条内容：路径与哈希都对得上才算 */
    private static boolean skippedBefore(List<Skipped.Entry> previous, Manifest.FileEntry item) {
        String hash = contentHash(item);
        for (Skipped.Entry entry : previous) {
            if (entry.file.equals(item.path) && entry.sha1.equalsIgnoreCase(hash)) {
                return true;
            }
        }
        return false;
    }

    /** 条目的内容哈希：SHA1 优先，没有时用 SHA256 */
    private static String contentHash(Manifest.FileEntry item) {
        return item.sha1.isEmpty() ? item.sha256 : item.sha1;
    }

    /** 有界面、允许交互、也真的弹得出来时才弹窗 */
    private boolean canAsk() {
        return options.ask && options.gui && !java.awt.GraphicsEnvironment.isHeadless()
                && ui instanceof ProgressWindow;
    }

    /** 组装并弹出勾选窗，返回未勾选的路径 */
    private Set<String> askOptionalMods(List<Manifest.FileEntry> items, Manifest old)
            throws IOException {
        javax.swing.JFrame parent = ((ProgressWindow) ui).frame();
        List<OptionalModsDialog.Row> rows = new ArrayList<>(items.size());
        for (Manifest.FileEntry item : items) {
            Manifest.FileEntry before = findByIdentity(old, item.identity());
            Path existing = existingVariant(Fs.resolveSafe(options.gameDir, item.path));
            String name = item.fileName();
            String oldName = existing == null || existing.getFileName().toString().equals(name)
                    ? "" : existing.getFileName().toString();
            rows.add(new OptionalModsDialog.Row(item.path, name, before == null,
                    existing != null && !isDisabled(existing), oldName));
        }
        return OptionalModsDialog.ask(parent, rows);
    }

    /** 本次跳过的条目的身份，用于保护旧文件不被删除 */
    private static Set<String> skippedIdentities(Manifest current, Set<String> skipPaths) {
        Set<String> out = new HashSet<>();
        for (Manifest.FileEntry item : current.files) {
            if (skipPaths.contains(item.path)) {
                out.add(item.identity());
            }
        }
        return out;
    }

    /**
     * 上次跳过、这次重新勾上的条目：清掉当时留下的旧文件
     *
     * 新版本还用同一个路径时留给下载覆盖，不先删。
     *
     * - `previous`: 上次的跳过记录
     * - `old`: 本地旧清单，用来把记录里的路径换回条目身份
     * - `current`: 远程清单
     * - `skipPaths`: 这次仍然跳过的路径
     * - `skippedIdentities`: 这次跳过的条目身份
     */
    private void cleanKept(List<Skipped.Entry> previous, Manifest old, Manifest current,
                           Set<String> skipPaths, Set<String> skippedIdentities) {
        if (options.noDelete) {
            return;
        }
        Set<String> currentPaths = new HashSet<>();
        for (Manifest.FileEntry item : current.files) {
            currentPaths.add(item.path);
        }
        for (Skipped.Entry entry : previous) {
            // 同一个模组这次仍然跳过：换了文件名也继续留着旧文件
            if (skipPaths.contains(entry.file)
                    || skippedIdentities.contains(identityOf(old, entry.file))) {
                continue;
            }
            String kept = entry.kept;
            if (kept.isEmpty() || currentPaths.contains(kept)) {
                continue;
            }
            try {
                Path path = Fs.resolveSafe(options.gameDir, kept);
                if (existingVariant(path) == null) {
                    continue;
                }
                Fs.deleteFile(path);
                info("清理上次跳过的旧文件: " + kept);
            } catch (IOException e) {
                info("清理旧文件失败 " + kept + "：" + e.getMessage());
            }
        }
    }

    /** 清单里某条路径的身份；清单里没有时退回路径本身 */
    private static String identityOf(Manifest manifest, String path) {
        if (manifest != null) {
            for (Manifest.FileEntry item : manifest.files) {
                if (item.path.equals(path)) {
                    return item.identity();
                }
            }
        }
        return "path:" + path;
    }

    /** 跳过记录里这条留下的旧文件；没有或读不到时返回 null */
    private Path keptFile(List<Skipped.Entry> previous, String path) {
        for (Skipped.Entry entry : previous) {
            if (!entry.file.equals(path) || entry.kept.isEmpty()) {
                continue;
            }
            try {
                Path kept = Fs.resolveSafe(options.gameDir, entry.kept);
                if (Files.isRegularFile(kept)) {
                    return kept;
                }
            } catch (IOException ignored) {
                // 记录里的路径不合法就当没有
            }
        }
        return null;
    }

    /**
     * 本次跳过的条目，写进 skipped.txt
     *
     * kept 先按旧清单里同身份条目的本地文件取，找不到再按新路径取；记实际文件名，
     * 禁用中的那份带后缀。
     *
     * - `current`: 远程清单
     * - `old`: 本地旧清单
     * - `skipPaths`: 这次跳过的路径
     *
     * # 返回值
     *
     * 要落盘的跳过记录
     */
    private List<Skipped.Entry> skippedMarkers(Manifest current, Manifest old,
                                               List<Skipped.Entry> previous,
                                               Set<String> skipPaths) {
        List<Skipped.Entry> out = new ArrayList<>();
        for (Manifest.FileEntry item : current.files) {
            if (!skipPaths.contains(item.path)) {
                continue;
            }
            Manifest.FileEntry before = findByIdentity(old, item.identity());
            out.add(new Skipped.Entry(contentHash(item), item.path,
                    keptRelative(before, item, previous)));
        }
        return out;
    }

    /** 本地保留的旧文件相对游戏目录的写法；没有时返回空串 */
    private String keptRelative(Manifest.FileEntry before, Manifest.FileEntry item,
                                List<Skipped.Entry> previous) {
        Path found = existingVariantQuiet(before == null ? null : before.path);
        if (found == null) {
            // 上次已经跳过、这次换了文件名：本地那份记在跳过记录里
            found = keptFile(previous, before == null ? item.path : before.path);
        }
        if (found == null) {
            found = existingVariantQuiet(item.path);
        }
        if (found == null) {
            return "";
        }
        try {
            return options.gameDir.toAbsolutePath().normalize()
                    .relativize(found.toAbsolutePath().normalize()).toString().replace('\\', '/');
        } catch (RuntimeException e) {
            return "";
        }
    }

    private Path existingVariantQuiet(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        try {
            return existingVariant(Fs.resolveSafe(options.gameDir, path));
        } catch (IOException e) {
            return null;
        }
    }

    /** --dry-run 时把会变的可选模组与跳过项打进计划 */
    private void printOptionalPlan(List<Manifest.FileEntry> optionalChanges, Set<String> skipPaths) {
        info("会变的可选模组 " + optionalChanges.size() + " 个");
        for (Manifest.FileEntry item : optionalChanges) {
            info("  " + (skipPaths.contains(item.path) ? "- 跳过 " : "- 更新 ") + item.path);
        }
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

    private void progress(int done, int total, String detail) {
        if (!options.quiet && total > 0) {
            Log.info("[progress] " + done + "/" + total);
        }
        ui.step(done, detail);
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
     *
     * # 返回值
     *
     * 实际完成的条目数
     */
    private int downloadAll(List<DownloadTask> tasks, int total) throws IOException {
        if (tasks.isEmpty()) {
            return 0;
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
                    progress(finished.incrementAndGet(), total,
                            task.target.getFileName().toString());
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
        return finished.get();
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
