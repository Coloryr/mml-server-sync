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
        // 「自选模组」在清单下载期间就可点：请求由安全点接管
        if (canAsk()) {
            ((ProgressWindow) ui).showOptionalModsButton();
        }
        ui.stage(Text.of(Msg.FETCHING_MANIFEST_FINGERPRINT));
        String manifestUrl;
        byte[] data;
        Manifest current;
        try {
            manifestUrl = toUrl(options.manifest);
            // 先比清单指纹：发布目录里有 server.json.sha1 且没变时，整份清单都不用下
            if (!options.force && manifestUnchanged(manifestUrl)) {
                return Text.of(Msg.MANIFEST_UNCHANGED_SKIPPING_SYNC);
            }
            ui.stage(Text.of(Msg.DOWNLOADING_MANIFEST));
            // 清单可能要下几十秒：进度条转不确定状态，接收进度回传到窗口
            ui.total(0);
            data = downloader.fetch(manifestUrl, null, (done, total) -> ui.step(0, total > 0
                    ? Text.of(Msg.MANIFEST_PROGRESS_TOTAL, mb(done), mb(total))
                    : Text.of(Msg.MANIFEST_PROGRESS, mb(done))));
            current = Manifest.parse(data);
            current.resolveUrls(manifestUrl);
        } catch (IOException e) {
            // 清单没拉到等于一个文件都没动：游戏模式只记日志，别让服务器掉线锁死启动
            if (!options.lenient) {
                throw e;
            }
            Log.error(Text.of(Msg.MANIFEST_FETCH_FAILED_SKIPPING_THIS_SYNC) + e.getMessage());
            return Text.of(Msg.MANIFEST_FETCH_FAILED_SYNC_SKIPPED);
        }
        ui.title((current.name.isEmpty() ? Text.of(Msg.SERVER_INSTANCE) : current.name)
                + " · " + packVersionOf(current));

        Manifest old = loadOld(current);
        if (old == null) {
            info(Text.of(Msg.NO_LOCAL_RECORD_VERSION, packVersionOf(current)));
        } else {
            info(Text.of(Msg.LOCAL_REMOTE_VERSION, packVersionOf(old), packVersionOf(current)));
        }
        if (!options.force && sameVersion(old, current)) {
            info(Text.of(Msg.PACK_VERSION_UNCHANGED_DETAIL, current.packVersion));
            return Text.of(Msg.PACK_VERSION_UNCHANGED_SKIPPING_SYNC);
        }

        ui.stage(Text.of(Msg.CHECKING_LOCAL_FILES));
        validate(current);
        if (old != null) {
            validate(old);
        }
        ui.stage(Text.of(Msg.COMPARING_CHANGES));

        // 上次跳过的可选模组：这次发布方没换内容就不再问
        List<Skipped.Entry> previousSkipped = Skipped.load(options.stateDir);
        Set<String> skipPaths = new HashSet<>(options.skip);
        // 安全点一：清单就绪、还没动任何文件，点过「自选模组」就先按全量列表处理一轮
        ManageResult firstStop = manageCheckpoint(current, old, previousSkipped, skipPaths);
        if (firstStop == ManageResult.CANCELLED) {
            info(Text.of(Msg.SYNC_CANCELLED_NO_FILES_CHANGED));
            return Text.of(Msg.SYNC_CANCELLED);
        }
        boolean managed = firstStop == ManageResult.APPLIED;
        List<Manifest.FileEntry> optionalChanges = changedOptional(current, old, previousSkipped);
        // 管理窗已列过全量条目并让用户确认过，不再重复弹会变的那几条
        if (!options.dryRun && !managed && skipPaths.isEmpty() && !optionalChanges.isEmpty() && canAsk()) {
            ui.stage(Text.of(Msg.WAITING_FOR_OPTIONAL_MOD_SELECTION));
            OptionalModsDialog.Result choice = askOptionalMods(optionalChanges, old);
            if (choice.endGame) {
                throw new SyncAbortException(Text.of(Msg.CANCEL_AFTER_ENDED));
            }
            if (choice.cancelled) {
                info(Text.of(Msg.SYNC_CANCELLED_NO_FILES_CHANGED));
                return Text.of(Msg.SYNC_CANCELLED);
            }
            skipPaths.addAll(choice.unchecked);
        }
        if (!skipPaths.isEmpty()) {
            info(Text.of(Msg.SKIPPING_OPTIONAL_COUNT, skipPaths.size()));
        }

        // 删旧文件之前先记下玩家启用着的条目：可选模组换文件名时按它延续启用状态
        Set<String> keptEnabled = enabledIdentities(old, previousSkipped);

        // 安全点二：下载计划在这里定稿，按钮随之收起
        int skipsBeforePlan = skipPaths.size();
        if (manageCheckpoint(current, old, previousSkipped, skipPaths) == ManageResult.CANCELLED) {
            info(Text.of(Msg.SYNC_CANCELLED_NO_FILES_CHANGED));
            return Text.of(Msg.SYNC_CANCELLED);
        }
        if (ui instanceof ProgressWindow) {
            ((ProgressWindow) ui).hideOptionalModsButton();
        }
        if (skipPaths.size() != skipsBeforePlan && !skipPaths.isEmpty()) {
            info(Text.of(Msg.SKIPPING_OPTIONAL_COUNT, skipPaths.size()));
        }

        Set<String> skippedIdentities = skippedIdentities(current, skipPaths);
        List<String> removedDirs = new ArrayList<>();
        Set<String> removedFiles = new HashSet<>();
        planRemovals(old, current, removedFiles, removedDirs, skippedIdentities);
        if (options.dryRun) {
            printOptionalPlan(optionalChanges, skipPaths);
            printPlan(current, old, removedFiles, removedDirs);
            return Text.of(Msg.PLAN_ONLY_NO_FILES_CHANGED);
        }
        checkCancelled();
        ui.stage(Text.of(Msg.CLEANING_OLD_FILES));
        if (!options.noDelete) {
            applyRemovals(removedFiles, removedDirs);
        } else {
            info(Text.of(Msg.NO_DELETE_SKIPPED, removedFiles.size(), removedDirs.size()));
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
        info(Text.of(Msg.NEED_TO_HANDLE_ITEMS, total, files.size(), archiveCount, needBase ? 1 : 0));
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
                throw new IOException(Text.of(Msg.CONFIG_ARCHIVE_HAS_NO_DOWNLOAD_URL) + plan.item.file);
            }
            Path temp = tempDir.resolve("archive-" + (archiveIndex++) + "-" + safeName(plan.item.file));
            tasks.add(new DownloadTask(url, temp, plan.item.sha1, plan.item.sha256, null));
            downloaded.add(new ArchivePlan(plan.item, temp, false));
        }

        ui.stage(Text.of(Msg.DOWNLOADING_FILES));
        int done = downloadAll(tasks, total);

        if (baseTemp != null) {
            checkCancelled();
            ui.stage(Text.of(Msg.EXTRACTING_BASE_PACK));
            Fs.extractZip(baseTemp, options.baseDir, true);
            Files.deleteIfExists(baseTemp);
            info(Text.of(Msg.BASE_EXTRACTED_TO, options.baseDir));
            ui.step(++done, current.base.file);
        }

        downloaded.sort(Comparator.comparingInt((ArchivePlan plan) -> depth(plan.item.dir)));
        if (!downloaded.isEmpty()) {
            ui.stage(Text.of(Msg.EXTRACTING_ARCHIVES));
        }
        for (ArchivePlan plan : downloaded) {
            checkCancelled();
            Path target = Fs.resolveSafe(options.gameDir, plan.item.dir);
            if (plan.item.deleteOld) {
                Fs.deleteDirectory(target);
            }
            Fs.extractZip(plan.temp, target, false);
            Files.deleteIfExists(plan.temp);
            info(Text.of(Msg.EXTRACTING_CONFIG_TARGET, plan.item.dir));
            ui.step(++done, plan.item.dir);
        }

        checkCancelled();
        ui.stage(Text.of(Msg.SAVING_STATE));
        saveState(data);
        Skipped.save(options.stateDir, skippedMarkers(current, old, previousSkipped, skipPaths));
        return Text.of(Msg.SYNC_COMPLETE_VERSION, current.packVersion);
    }

    /**
     * 清单是否没变：先取 `<清单>.sha1`，与本地清单的 SHA1 比
     *
     * 发布目录没放 .sha1、取不到、或本地还没有清单时都按"变了"处理，照旧整份下载。
     * 口径与启动器的 server_pack_update 一致。
     *
     * - `manifestUrl`: 清单地址
     *
     * # 返回值
     *
     * 远端指纹与本地清单一致时返回 true
     */
    private boolean manifestUnchanged(String manifestUrl) {
        if (manifestUrl == null || manifestUrl.isEmpty()) {
            return false;
        }
        String remote = remoteSha1(manifestUrl);
        if (remote.isEmpty()) {
            return false;
        }
        String local = localManifestSha1();
        if (local.isEmpty() || !local.equalsIgnoreCase(remote)) {
            return false;
        }
        info(Text.of(Msg.MANIFEST_SHA1_UNCHANGED, remote.substring(0, 8)));
        return true;
    }

    /** 取 `<清单>.sha1`，只认纯哈希；取不到或内容不像哈希时返回空串 */
    private String remoteSha1(String manifestUrl) {
        try {
            String text = new String(downloader.fetchQuiet(manifestUrl + ".sha1"),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            int space = text.indexOf(' ');
            if (space > 0) {
                // 有些工具会写成 "哈希  文件名"
                text = text.substring(0, space).trim();
            }
            return text.matches("[0-9a-fA-F]{40}") ? text : "";
        } catch (IOException e) {
            return "";
        }
    }

    /** 本地清单的 SHA1：状态文件优先，其次基础包目录里的 server.json */
    private String localManifestSha1() {
        Path state = options.stateDir.resolve("last-manifest.json");
        Path fallback = options.baseDir.resolve("server.json");
        for (Path path : new Path[]{state, fallback}) {
            if (!Files.isRegularFile(path)) {
                continue;
            }
            String hash = Downloader.hash(path, "SHA-1");
            if (!hash.isEmpty()) {
                return hash;
            }
        }
        return "";
    }

    /**
     * 用户在进度窗上按了取消：到安全点就停
     *
     * 系统属性由进度窗写：skip 跳过本次更新（退出码 0），end 结束游戏（退出码 1）。
     */
    private static void checkCancelled() {
        Cancels.check();
    }

    /** 清单的包版本，空串按「未标注」显示。 */
    private static String packVersionOf(Manifest manifest) {
        String version = manifest.packVersion == null ? "" : manifest.packVersion.trim();
        return version.isEmpty() ? Text.of(Msg.NOT_SPECIFIED) : version;
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
                info(Text.of(Msg.IGNORING_OLD_MANIFEST, path, e.getMessage()));
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
            info(Text.of(Msg.REMOVING_OLD_FILE_PATH, path));
        }
        for (String dir : dirs) {
            Path target = Fs.resolveSafe(options.gameDir, dir);
            if (Files.exists(target)) {
                Fs.deleteDirectory(target);
                info(Text.of(Msg.REMOVING_OLD_DIR, dir));
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
    private OptionalModsDialog.Result askOptionalMods(List<Manifest.FileEntry> items, Manifest old)
            throws IOException {
        return askOptionalMods(changedRows(items, old), Text.of(Msg.DIALOG_HEADER, items.size()));
    }

    /** 弹出勾选窗，表头文字由调用方给；返回未勾选的路径 */
    private OptionalModsDialog.Result askOptionalMods(List<OptionalModsDialog.Row> rows,
                                                      String header) {
        javax.swing.JFrame parent = ((ProgressWindow) ui).frame();
        return OptionalModsDialog.ask(parent, rows, header);
    }

    /** 会变的可选模组的行：状态列沿用启用 / 禁用 / 新增 */
    private List<OptionalModsDialog.Row> changedRows(List<Manifest.FileEntry> items, Manifest old)
            throws IOException {
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
        return rows;
    }

    /** 管理安全点的结果 */
    private enum ManageResult {
        /** 没有请求，直接过 */
        NONE,
        /** 已按勾选结果更新跳过列表 */
        APPLIED,
        /** 用户在管理窗里取消同步 */
        CANCELLED
    }

    /**
     * 「自选模组」安全点：用户点过按钮就暂停同步，弹出全部可选模组的列表，
     * 勾选结果并进跳过列表后继续；没有请求时直接过。
     *
     * 调用位置必须在改动任何文件之前：取消同步的语义是「一个文件都没动」。
     *
     * - `current`: 远程清单
     * - `old`: 本地旧清单，没有时传 null
     * - `previousSkipped`: 上次的跳过记录
     * - `skipPaths`: 这次跳过的路径，勾选结果并入这里
     *
     * # 返回值
     *
     * 见 {@link ManageResult}
     */
    private ManageResult manageCheckpoint(Manifest current, Manifest old,
                                          List<Skipped.Entry> previousSkipped,
                                          Set<String> skipPaths) throws IOException {
        if (options.dryRun || !canAsk()) {
            return ManageResult.NONE;
        }
        ProgressWindow window = (ProgressWindow) ui;
        if (!window.consumeOptionalRequest()) {
            return ManageResult.NONE;
        }
        ui.stage(Text.of(Msg.WAITING_FOR_OPTIONAL_MOD_SELECTION));
        List<OptionalModsDialog.Row> rows = allOptionalRows(current, old, previousSkipped, skipPaths);
        OptionalModsDialog.Result choice = askOptionalMods(rows,
                Text.of(Msg.DIALOG_HEADER_ALL, rows.size()));
        window.rearmOptionalModsButton();
        if (choice.endGame) {
            throw new SyncAbortException(Text.of(Msg.CANCEL_AFTER_ENDED));
        }
        if (choice.cancelled) {
            return ManageResult.CANCELLED;
        }
        skipPaths.addAll(choice.unchecked);
        return ManageResult.APPLIED;
    }

    /**
     * 全部可选模组的行：状态区分需更新 / 已同步 / 已跳过
     *
     * 判据与 changedOptional 一致：内容与本地一致的是已同步，上次跳过且内容没变的
     * 是已跳过（默认不勾选），其余是需更新。
     */
    private List<OptionalModsDialog.Row> allOptionalRows(Manifest current, Manifest old,
                                                         List<Skipped.Entry> previous,
                                                         Set<String> skipPaths) throws IOException {
        List<OptionalModsDialog.Row> rows = new ArrayList<>();
        for (Manifest.FileEntry item : current.files) {
            if (!item.optional) {
                continue;
            }
            String status;
            boolean checked;
            if (skipPaths.contains(item.path) || skippedBefore(previous, item)) {
                status = Text.of(Msg.STATUS_SKIPPED);
                checked = false;
            } else if (needsUpdate(item, old)) {
                status = Text.of(Msg.STATUS_NEEDS_UPDATE);
                checked = true;
            } else {
                status = Text.of(Msg.STATUS_UP_TO_DATE);
                checked = true;
            }
            Manifest.FileEntry before = findByIdentity(old, item.identity());
            Path existing = existingVariant(Fs.resolveSafe(options.gameDir, item.path));
            String name = item.fileName();
            String oldName = existing == null || existing.getFileName().toString().equals(name)
                    ? "" : existing.getFileName().toString();
            rows.add(new OptionalModsDialog.Row(item.path, name, before == null,
                    existing != null && !isDisabled(existing), oldName, status, checked));
        }
        return rows;
    }

    /** 内容与本地是否对得上：清单里同一身份变了，或本地文件哈希不一致 */
    private boolean needsUpdate(Manifest.FileEntry item, Manifest old) throws IOException {
        Manifest.FileEntry before = findByIdentity(old, item.identity());
        if (before == null || !before.hasSameContent(item)) {
            return true;
        }
        Path existing = existingVariant(Fs.resolveSafe(options.gameDir, item.path));
        return existing == null || !Downloader.matches(existing, item.sha1, item.sha256);
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
                info(Text.of(Msg.REMOVING_KEPT_FILE, kept));
            } catch (IOException e) {
                info(Text.of(Msg.FAILED_TO_REMOVE_OLD_FILE, kept, e.getMessage()));
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
        info(Text.of(Msg.CHANGED_OPTIONAL_COUNT, optionalChanges.size()));
        for (Manifest.FileEntry item : optionalChanges) {
            info("  " + (skipPaths.contains(item.path) ? Text.of(Msg.SKIP) : Text.of(Msg.UPDATE_2)) + item.path);
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
        info(Text.of(Msg.PLAN_MANIFEST,
                current.name.isEmpty() ? Text.of(Msg.UNNAMED) : current.name, current.packVersion));
        info(Text.of(Msg.PLAN_REMOVE_FILES, removedFiles.size()));
        for (String path : removedFiles) {
            info("  - " + path);
        }
        info(Text.of(Msg.PLAN_REMOVE_DIRS, removedDirs.size()));
        for (String dir : removedDirs) {
            info("  - " + dir);
        }
        info(Text.of(Msg.PLAN_CHECK_FILES, current.files.size(), current.archives.size()));
        if (current.base != null && (old == null || old.base == null)) {
            info(Text.of(Msg.PLAN_BASE, current.base.file));
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
        // 阶段带整体计数，细节行留给出当前文件名
        ui.stage(Text.of(Msg.DOWNLOADING_FILES_PROGRESS, done, total));
        ui.step(done, detail);
    }

    /** 字节数的 MB 显示，与日志里的进度格式一致 */
    private static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
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
                    checkCancelled();
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
                    if (cause instanceof CancelledException || cause instanceof SyncAbortException) {
                        throw (RuntimeException) cause;
                    }
                    if (cause instanceof IOException) {
                        throw (IOException) cause;
                    }
                    throw new IOException(cause == null ? Text.of(Msg.DOWNLOAD_FAILED) : cause.toString(), cause);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(Text.of(Msg.DOWNLOAD_INTERRUPTED), e);
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
