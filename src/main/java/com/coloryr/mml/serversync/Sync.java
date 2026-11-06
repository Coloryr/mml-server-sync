package com.coloryr.mml.serversync;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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

    /** 远程清单，解析完成后供「自选模组」弹窗即时组装行数据 */
    private volatile Manifest manifest;
    /** 自选模组的上次选择状态 */
    private OptionalState.Loaded optionalState = new OptionalState.Loaded(true);
    /** 本次的自选模组勾选：路径 → 是否启用；事件线程（弹窗）与同步线程都会读写 */
    private final Map<String, Boolean> selections = new ConcurrentHashMap<>();
    /** 发布端写的自选模组简介：路径 → 文字；取不到时为空 */
    private Map<String, String> optionalNotes = Collections.emptyMap();
    /** 打开中的自选模组弹窗；玩家确认后清空 */
    private final AtomicReference<PendingChoice> pendingDialog = new AtomicReference<>();
    /** 本轮最近一次确认过的勾选结果（覆盖弹窗展示过的行）；null 表示还没有玩家确认过 */
    private volatile Map<String, Boolean> lastChoice;

    Sync(Options options, Progress ui) {
        this.options = options;
        this.downloader = new Downloader(options.apiKey, options.quiet);
        this.tempDir = options.stateDir.resolve("tmp");
        this.ui = ui == null ? Progress.NONE : ui;
    }

    /** 打开中的自选模组弹窗：玩家确认后 latch 归零、结果就位 */
    private static final class PendingChoice {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Map<String, Boolean>> result = new AtomicReference<>();

        /** 等玩家确认；返回全部展示行的勾选状态 */
        Map<String, Boolean> await() throws InterruptedException {
            done.await();
            return result.get();
        }
    }

    /**
     * 跑一次同步
     *
     * # 返回值
     *
     * 一句话结果，给进度窗口与日志用
     */
    String run() throws IOException {
        String manifestUrl;
        byte[] data;
        Manifest current;
        try {
            manifestUrl = toUrl(options.manifest);
            ui.stage(Text.of(Msg.FETCHING_MANIFEST_FINGERPRINT));
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
        manifest = current;
        ui.title((current.name.isEmpty() ? Text.of(Msg.SERVER_INSTANCE) : current.name)
                + " · " + packVersionOf(current));

        // 自选模组状态先于按钮接线加载：任何时刻点开的弹窗都有完整数据
        OptionalState.Loaded state = OptionalState.load(options.stateDir);
        optionalState = state;
        List<Manifest.FileEntry> changedOptionals = new ArrayList<>();
        for (Manifest.FileEntry item : current.files) {
            if (!item.optional) {
                continue;
            }
            Boolean recorded = state.selected(item.path);
            boolean selected;
            if (options.skip.contains(item.path)) {
                selected = false;
            } else if (recorded != null) {
                selected = recorded;
            } else {
                selected = canAsk();
            }
            selections.put(item.path, selected);
            if (!state.hashOf(item.path).equalsIgnoreCase(contentHash(item))) {
                changedOptionals.add(item);
            }
        }

        // 「自选模组」入口常驻：清单就绪即可点，弹窗按当前状态即时组装。
        // 按钮接线依赖具体窗口类：从 update/ 包运行时类加载器分裂，
        // instanceof 不成立，按钮在过渡期保持置灰，弹窗本身不受影响。
        if (canAsk()) {
            optionalNotes = fetchOptionalNotes(manifestUrl);
        }
        if (hasButtonUi()) {
            ((ProgressWindow) ui).setOptionalHandler(this::openManageDialog);
            ((ProgressWindow) ui).enableOptionalModsButton();
        }

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

        // 有新增或内容变化的条目就绪后即弹窗：同步继续在后台跑，收尾时再来等确认结果
        if (!options.dryRun && options.skip.isEmpty() && !changedOptionals.isEmpty() && canAsk()) {
            String header = state.firstRun
                    ? Text.of(Msg.HEADER_FIRST_RUN)
                    : Text.of(Msg.HEADER_CHANGES, changedOptionals.size());
            openDialog(buildRows(current), header);
        }

        List<String> removedDirs = new ArrayList<>();
        Set<String> removedFiles = new java.util.HashSet<>();
        String selfJar = selfJarRelative();
        planRemovals(old, current, removedFiles, removedDirs, selfJar);
        if (options.dryRun) {
            printOptionalPlan(current);
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

        boolean needBase = current.base != null && (old == null || old.base == null);
        List<PlannedFile> files = changedFiles(current, selfJar);
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
        // 自选模组收尾：等后台弹窗的确认结果，然后按勾选把文件归位，之后才写状态、放行启动
        applyOptionalChoices(current);

        ui.stage(Text.of(Msg.SAVING_STATE));
        saveState(data);
        OptionalState.save(options.stateDir, OptionalState.entries(selections, hashesOf(current)));
        return Text.of(Msg.SYNC_COMPLETE_VERSION, current.packVersion);
    }

    /**
     * 自选模组收尾：仍开着的弹窗在这里等玩家确认；结果并进勾选后按名字归位。
     * 必须发生在 saveState 之前，否则中断后指纹命中会跳过收尾，改名就再也不会执行。
     */
    private void applyOptionalChoices(Manifest current) throws IOException {
        PendingChoice pending = pendingDialog.get();
        if (pending != null) {
            ui.stage(Text.of(Msg.WAITING_FOR_OPTIONAL_MOD_SELECTION));
            try {
                Map<String, Boolean> chosen = pending.await();
                if (chosen != null) {
                    lastChoice = chosen;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(Text.of(Msg.DOWNLOAD_INTERRUPTED), e);
            }
        }
        checkCancelled();
        ui.stage(Text.of(Msg.OPTIONAL_APPLY_STAGE));
        // 只并入弹窗展示过的行：部分行弹窗（如仅新增条目）确认时，未展示行的选择保持原状
        if (lastChoice != null) {
            for (Map.Entry<String, Boolean> entry : lastChoice.entrySet()) {
                selections.put(entry.getKey(), entry.getValue());
            }
        }
        reconcileOptionalFiles(current);
        int enabled = 0;
        int disabled = 0;
        for (Manifest.FileEntry item : current.files) {
            if (!item.optional) {
                continue;
            }
            if (Boolean.TRUE.equals(selections.get(item.path))) {
                enabled++;
            } else {
                disabled++;
            }
        }
        info(Text.of(Msg.OPTIONAL_APPLY_COUNT, enabled, disabled));
    }

    /**
     * 按勾选把自选模组文件归位：勾选 = 清单路径（启用），未勾选 = 加 .disabled（禁用）。
     * 内容在下载阶段已就位，这里通常只是改名；另一侧变体清掉，避免同名双份。
     */
    private void reconcileOptionalFiles(Manifest current) throws IOException {
        for (Manifest.FileEntry item : current.files) {
            if (!item.optional) {
                continue;
            }
            Path target = Fs.resolveSafe(options.gameDir, item.path);
            boolean selected = Boolean.TRUE.equals(selections.get(item.path));
            Path desired = selected ? target : withSuffix(target, DISABLED_SUFFIX);
            Path existing = existingVariant(target);
            if (existing == null) {
                continue;
            }
            if (!existing.equals(desired)) {
                moveReplace(existing, desired);
                info(Text.of(Msg.OPTIONAL_RENAMED, existing.getFileName().toString(),
                        desired.getFileName().toString()));
            }
            if (!options.noDelete) {
                Path stale = selected ? withSuffix(target, DISABLED_SUFFIX) : target;
                if (!stale.equals(desired) && !stale.equals(existing)) {
                    Files.deleteIfExists(stale);
                }
            }
        }
    }

    private static void moveReplace(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 按钮回调：弹管理窗；已有弹窗开着、还没解析到清单或 dry-run 时忽略 */
    private void openManageDialog() {
        Manifest current = manifest;
        if (options.dryRun || current == null || pendingDialog.get() != null) {
            return;
        }
        openDialog(buildRows(current), Text.of(Msg.HEADER_MANAGE, countOptionals(current)));
    }

    private void openDialog(List<OptionalModsDialog.Row> rows, String header) {
        PendingChoice pending = new PendingChoice();
        if (!pendingDialog.compareAndSet(null, pending)) {
            return;
        }
        javax.swing.JFrame parent = ownerFrame();
        javax.swing.SwingUtilities.invokeLater(() -> {
            try {
                Map<String, Boolean> chosen = OptionalModsDialog.askForSelection(parent, rows, header);
                pending.result.set(chosen);
                lastChoice = chosen;
            } finally {
                pending.done.countDown();
                pendingDialog.compareAndSet(pending, null);
            }
        });
    }

    /** 弹窗行数据：状态区分新增 / 需更新 / 已同步，并带当前启用状态后缀 */
    private List<OptionalModsDialog.Row> buildRows(Manifest current) {
        List<OptionalModsDialog.Row> rows = new ArrayList<>();
        for (Manifest.FileEntry item : current.files) {
            if (!item.optional) {
                continue;
            }
            Path existing = existingVariantQuiet(item.path);
            boolean isNew = optionalState.hashOf(item.path).isEmpty();
            boolean contentStale = existing == null
                    || !Downloader.matches(existing, item.sha1, item.sha256)
                    || !optionalState.hashOf(item.path).equalsIgnoreCase(contentHash(item));
            String status = isNew ? Text.of(Msg.ADDED)
                    : contentStale ? Text.of(Msg.STATUS_NEEDS_UPDATE) : Text.of(Msg.STATUS_UP_TO_DATE);
            if (existing != null) {
                status += isDisabled(existing) ? Text.of(Msg.DISABLED) : Text.of(Msg.ENABLED);
            }
            rows.add(new OptionalModsDialog.Row(item.path, item.fileName(),
                    optionalNotes.get(item.path), status,
                    Boolean.TRUE.equals(selections.get(item.path))));
        }
        return rows;
    }

    /**
     * 取发布端写的自选模组简介：清单地址加 .notes.json，取不到就是没有
     *
     * - `manifestUrl`: 清单地址
     *
     * # 返回值
     *
     * 路径 → 简介文字；文件不存在或格式不对时为空表
     */
    private Map<String, String> fetchOptionalNotes(String manifestUrl) {
        try {
            Map<String, Object> root = Json.map(Json.parse(
                    downloader.fetchQuiet(manifestUrl + ".notes.json")));
            Map<String, String> out = new HashMap<>();
            if (root != null) {
                for (Map.Entry<String, Object> entry : root.entrySet()) {
                    if (entry.getValue() instanceof String) {
                        out.put(entry.getKey(), (String) entry.getValue());
                    }
                }
            }
            return out;
        } catch (IOException e) {
            return new HashMap<>();
        }
    }

    /** 自选模组的勾选条目 → 状态文件记录 */
    private static Map<String, String> hashesOf(Manifest current) {
        Map<String, String> out = new HashMap<>();
        for (Manifest.FileEntry item : current.files) {
            if (item.optional) {
                out.put(item.path, contentHash(item));
            }
        }
        return out;
    }

    private static int countOptionals(Manifest current) {
        int count = 0;
        for (Manifest.FileEntry item : current.files) {
            if (item.optional) {
                count++;
            }
        }
        return count;
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

    private void planRemovals(Manifest old, Manifest current, Set<String> files, List<String> dirs,
                              String selfJar) {
        if (old == null) {
            return;
        }
        Map<String, Manifest.FileEntry> byIdentity = new HashMap<>();
        for (Manifest.FileEntry item : current.files) {
            byIdentity.put(item.identity(), item);
        }
        for (Manifest.FileEntry item : old.files) {
            Manifest.FileEntry replacement = byIdentity.get(item.identity());
            if ((replacement == null || !replacement.path.equals(item.path))
                    && !item.path.equals(selfJar)) {
                files.add(item.path);
            }
        }
        Set<String> currentArchives = new java.util.HashSet<>();
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
     * 自选模组的内容始终同步，勾选与否只决定装成启用名还是 .disabled。
     *
     * - `current`: 远程清单
     * - `selfJar`: 运行中的原始更新器 jar 对游戏目录的相对路径；空串表示不跳过
     */
    private List<PlannedFile> changedFiles(Manifest current, String selfJar) throws IOException {
        List<PlannedFile> out = new ArrayList<>();
        for (Manifest.FileEntry item : current.files) {
            // 更新器自身在运行中无法被覆盖（Windows 文件锁）：升级走自更新通道
            if (item.path.equals(selfJar)) {
                info(Text.of(Msg.SELF_JAR_SKIPPED, item.path));
                continue;
            }
            Path target = Fs.resolveSafe(options.gameDir, item.path);
            Path existing = existingVariant(target);
            if (existing != null && Downloader.matches(existing, item.sha1, item.sha256)) {
                continue;
            }
            if (item.optional) {
                boolean selected = Boolean.TRUE.equals(selections.get(item.path));
                out.add(new PlannedFile(item, selected ? target : withSuffix(target, DISABLED_SUFFIX)));
                continue;
            }
            out.add(new PlannedFile(item, target));
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

    /** 条目的内容哈希：SHA1 优先，没有时用 SHA256 */
    private static String contentHash(Manifest.FileEntry item) {
        return item.sha1.isEmpty() ? item.sha256 : item.sha1;
    }

    /**
     * 运行中的原始更新器 jar 对游戏目录的相对路径
     *
     * jar 不在游戏目录内、跨盘或找不到时返回空串——相对化会带 .. 或抛异常，
     * 而清单路径不允许 ..，永远不会与这种值相等。
     */
    private String selfJarRelative() {
        Path jar = Updater.originalJarPath();
        if (jar == null) {
            return "";
        }
        try {
            return options.gameDir.toAbsolutePath().normalize().relativize(
                    jar.toAbsolutePath().normalize()).toString().replace('\\', '/');
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** 有界面、允许交互时才弹窗；不要求具体窗口类型（从 update/ 包运行时类加载器分裂，instanceof 不可用） */
    private boolean canAsk() {
        return options.ask && options.gui && !java.awt.GraphicsEnvironment.isHeadless();
    }

    /** 按钮接线是否可用：弹窗之外还需要能拿到具体窗口类（不经 update/ 运行时才成立） */
    private boolean hasButtonUi() {
        return canAsk() && ui instanceof ProgressWindow;
    }

    /** 找正在显示的进度窗当弹窗父窗口；没有时返回 null，对话框居屏幕中 */
    private static javax.swing.JFrame ownerFrame() {
        for (java.awt.Window window : java.awt.Window.getWindows()) {
            if (window instanceof javax.swing.JFrame && window.isShowing()) {
                return (javax.swing.JFrame) window;
            }
        }
        return null;
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

    /** --dry-run 时把自选模组的启用 / 禁用计划打出来 */
    private void printOptionalPlan(Manifest current) {
        int enabled = 0;
        int disabled = 0;
        for (Manifest.FileEntry item : current.files) {
            if (!item.optional) {
                continue;
            }
            if (Boolean.TRUE.equals(selections.get(item.path))) {
                enabled++;
            } else {
                disabled++;
            }
        }
        info(Text.of(Msg.OPTIONAL_APPLY_COUNT, enabled, disabled));
        for (Manifest.FileEntry item : current.files) {
            if (!item.optional) {
                continue;
            }
            info("  - " + (Boolean.TRUE.equals(selections.get(item.path))
                    ? Text.of(Msg.MARK_ENABLE) : Text.of(Msg.MARK_DISABLE)) + item.path);
        }
    }

    private List<ArchivePlan> archivePlans(Manifest old, Manifest current, List<String> removedDirs) {
        Map<String, Manifest.ArchiveEntry> oldArchives = new HashMap<>();
        if (old != null) {
            for (Manifest.ArchiveEntry item : old.archives) {
                oldArchives.put(item.file, item);
            }
        }
        Set<String> skipped = new java.util.HashSet<>();
        for (Manifest.ArchiveEntry item : current.archives) {
            if (archiveUnchanged(oldArchives.get(item.file), item)) {
                skipped.add(item.dir);
            }
        }
        Set<String> cleared = new java.util.HashSet<>(removedDirs);
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
