package com.coloryr.mml.serversync;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import com.coloryr.mml.serversync.host.Progress;

/**
 * 一次命令行同步的完整流程。
 *
 * 自更新会把 update 目录里的新 jar 用独立类加载器装进来，再反射调用 {@link #run}：
 * 这个类的全名与方法签名要跨版本保持不变，每个版本的 jar 里都带一份。
 * 它只做同步，不查更新、不建窗口、也绝不调用 System.exit。
 */
public final class Runner {
    private Runner() {}

    /**
     * 按命令行参数跑一次同步
     *
     * - `args`: 命令行参数
     * - `progress`: 进度窗口；没有窗口时传 null
     *
     * # 返回值
     *
     * 进程退出码：0 成功或跳过，1 同步失败，2 参数错误
     */
    public static int run(String[] args, Progress progress) {
        Progress ui = progress == null ? Progress.NONE : progress;
        try {
            Options options = parse(args);
            if (options.help) {
                printHelp();
                return 0;
            }
            if (options.version) {
                System.out.println("mml-server-sync " + Main.VERSION);
                return 0;
            }
            if (options.manifest == null || options.manifest.trim().isEmpty()) {
                throw new UsageException(Text.of(Msg.MISSING_MANIFEST_URL_OR_FILE));
            }
            if (options.gameDir == null) {
                throw new UsageException(Text.of(Msg.MISSING_GAME_DIR_GAME_DIRECTORY));
            }
            options.gameDir = options.gameDir.toAbsolutePath().normalize();
            options.baseDir = options.baseDir == null
                    ? Main.defaultBaseDir(options.gameDir)
                    : options.baseDir.toAbsolutePath().normalize();
            options.stateDir = options.stateDir == null
                    ? options.gameDir.resolve(".mml-server-sync")
                    : options.stateDir.toAbsolutePath().normalize();
            Files.createDirectories(options.gameDir);
            Files.createDirectories(options.baseDir);
            Log.open(options.logFile == null
                    ? options.stateDir.resolve(Log.FILE_NAME)
                    : options.logFile.toAbsolutePath().normalize());
            Log.info("mml-server-sync " + Main.VERSION);
            Log.info(Text.of(Msg.STARTING_SYNC_GAME_DIRECTORY) + options.gameDir);
            try {
                String summary = new Sync(options, ui).run();
                if (!options.quiet) {
                    Log.info(summary);
                }
                ui.finish(true, summary);
            } catch (CancelledException e) {
                // 用户选了「跳过更新」：退出码 0，游戏照常启动，下次启动补齐
                ui.finish(false, e.getMessage());
                Log.info(e.getMessage());
                return 0;
            } catch (SyncAbortException e) {
                // 用户选了「结束游戏」：不按同步失败处理，直接以退出码 1 收尾
                ui.finish(false, e.getMessage());
                Log.info(e.getMessage());
                return 1;
            } catch (Exception e) {
                ui.finish(false, e.getMessage());
                Log.error(Text.of(Msg.SYNC_FAILED_2) + e.getMessage());
                throw e;
            } finally {
                System.clearProperty("mml.serverSync.cancel");
                Log.close();
            }
            return 0;
        } catch (UsageException e) {
            System.err.println(Log.PREFIX + Text.of(Msg.INVALID_ARGUMENT) + e.getMessage());
            System.err.println(Log.PREFIX + Text.of(Msg.USE_HELP_FOR_USAGE));
            return 2;
        } catch (Exception e) {
            System.err.println(Log.PREFIX + Text.of(Msg.SYNC_FAILED_2) + e.getMessage());
            return 1;
        }
    }

    private static Options parse(String[] args) throws UsageException {
        Options options = new Options();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--help".equals(arg) || "-h".equals(arg)) {
                options.help = true;
            } else if ("--version".equals(arg)) {
                options.version = true;
            } else if ("--force".equals(arg)) {
                options.force = true;
            } else if ("--dry-run".equals(arg)) {
                options.dryRun = true;
            } else if ("--no-delete".equals(arg)) {
                options.noDelete = true;
            } else if ("--quiet".equals(arg)) {
                options.quiet = true;
            } else if ("--lenient".equals(arg)) {
                options.lenient = true;
            } else if ("--no-gui".equals(arg)) {
                options.gui = false;
            } else if ("--gui".equals(arg)) {
                options.gui = true;
            } else if ("--no-ask".equals(arg)) {
                options.ask = false;
            } else if ("--no-update".equals(arg)) {
                options.update = false;
            } else if ("--update".equals(arg)) {
                options.update = true;
            } else if (arg.startsWith("--update-index=")) {
                options.updateIndex = arg.substring("--update-index=".length());
            } else if (arg.startsWith("--skip=")) {
                addSkips(options.skip, arg.substring("--skip=".length()));
            } else if (arg.startsWith("--skip-file=")) {
                options.skipFile = Paths.get(arg.substring("--skip-file=".length()));
            } else if (arg.startsWith("--manifest=")) {
                options.manifest = arg.substring("--manifest=".length());
            } else if (arg.startsWith("--game-dir=")) {
                options.gameDir = Paths.get(arg.substring("--game-dir=".length()));
            } else if (arg.startsWith("--base-dir=")) {
                options.baseDir = Paths.get(arg.substring("--base-dir=".length()));
            } else if (arg.startsWith("--state-dir=")) {
                options.stateDir = Paths.get(arg.substring("--state-dir=".length()));
            } else if (arg.startsWith("--curseforge-api-key=")) {
                options.apiKey = arg.substring("--curseforge-api-key=".length());
            } else if (arg.startsWith("--log=")) {
                options.logFile = Paths.get(arg.substring("--log=".length()));
            } else if (arg.startsWith("--threads=")) {
                options.threads = threadsOf(arg.substring("--threads=".length()));
            } else if ("--manifest".equals(arg) || "-m".equals(arg)) {
                options.manifest = value(args, ++i, arg);
            } else if ("--game-dir".equals(arg) || "-g".equals(arg)) {
                options.gameDir = Paths.get(value(args, ++i, arg));
            } else if ("--base-dir".equals(arg)) {
                options.baseDir = Paths.get(value(args, ++i, arg));
            } else if ("--state-dir".equals(arg)) {
                options.stateDir = Paths.get(value(args, ++i, arg));
            } else if ("--curseforge-api-key".equals(arg)) {
                options.apiKey = value(args, ++i, arg);
            } else if ("--log".equals(arg)) {
                options.logFile = Paths.get(value(args, ++i, arg));
            } else if ("--threads".equals(arg)) {
                options.threads = threadsOf(value(args, ++i, arg));
            } else if ("--update-index".equals(arg)) {
                options.updateIndex = value(args, ++i, arg);
            } else if ("--skip".equals(arg)) {
                addSkips(options.skip, value(args, ++i, arg));
            } else if ("--skip-file".equals(arg)) {
                options.skipFile = Paths.get(value(args, ++i, arg));
            } else if (!arg.startsWith("-")) {
                throw new UsageException(Text.of(Msg.UNKNOWN_ARGUMENT) + arg);
            } else {
                throw new UsageException(Text.of(Msg.UNKNOWN_ARGUMENT) + arg);
            }
        }
        if (options.apiKey.isEmpty()) {
            String env = System.getenv("CURSEFORGE_API_KEY");
            if (env != null) {
                options.apiKey = env;
            }
        }
        readSkipFile(options);
        return options;
    }

    /** 解析逗号分隔的 `--skip` 值，路径统一成 `/` 分隔 */
    private static void addSkips(List<String> target, String value) {
        for (String part : (value == null ? "" : value).split(",")) {
            String path = normalizePath(part);
            if (!path.isEmpty()) {
                target.add(path);
            }
        }
    }

    private static String normalizePath(String value) {
        return value == null ? "" : value.trim().replace('\\', '/');
    }

    /**
     * 读 `--skip-file`：UTF-8，一行一个路径，空行与 `#` 开头的行忽略
     *
     * - `options`: 已经把 skipFile 填好的选项
     */
    private static void readSkipFile(Options options) throws UsageException {
        if (options.skipFile == null) {
            return;
        }
        Path file = options.skipFile.toAbsolutePath().normalize();
        try {
            for (String line : Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8)) {
                String path = normalizePath(line);
                if (path.isEmpty() || path.startsWith("#")) {
                    continue;
                }
                options.skip.add(path);
            }
        } catch (java.io.IOException e) {
            throw new UsageException(Text.of(Msg.FAILED_TO_READ_SKIP_FILE) + e.getMessage());
        }
    }

    /** 解析线程数，限制在 1-64 */
    private static int threadsOf(String value) throws UsageException {
        try {
            return Math.max(1, Math.min(64, Integer.parseInt(value.trim())));
        } catch (NumberFormatException e) {
            throw new UsageException(Text.of(Msg.THREADS_NEEDS_AN_INTEGER) + value);
        }
    }

    private static String value(String[] args, int index, String option) throws UsageException {
        if (index >= args.length || args[index].startsWith("-")) {
            throw new UsageException(option + Text.of(Msg.IS_MISSING_A_VALUE));
        }
        return args[index];
    }

    private static void printHelp() {
        System.out.println("mml-server-sync " + Main.VERSION);
        System.out.println(Text.of(Msg.STANDALONE_SERVER_INSTANCE_UPDATER_FOR_PRE_LAUNCH_UPDATES_FROM_NON_MML_LAUNCHERS));
        System.out.println();
        System.out.println(Text.of(Msg.JAVA_AGENT_RECOMMENDED));
        System.out.println(Text.of(Msg.JAVAAGENT_MML_SERVER_SYNC_JAR_URL_OF_SERVER_JSON));
        System.out.println(Text.of(Msg.SKIPPED_AUTOMATICALLY_UNDER_THE_MML_LAUNCHER_SET_MML_SKIP_TRUE_TO_FORCE_IT_THE_WORKING_DIRECTORY_MUST_BE_MINECRAFT));
        System.out.println();
        System.out.println(Text.of(Msg.COMMAND_LINE));
        System.out.println(Text.of(Msg.JAVA_JAR_MML_SERVER_SYNC_JAR_MANIFEST_URL_FILE_GAME_DIR_DIR_OPTIONS));
        System.out.println();
        System.out.println(Text.of(Msg.OPTIONS));
        System.out.println(Text.of(Msg.MANIFEST_M_URL_OR_LOCAL_PATH_OF_THE_MML_SERVER_JSON));
        System.out.println(Text.of(Msg.GAME_DIR_G_GAME_DIRECTORY_E_G_MINECRAFT_MODS_AND_CONFIGS_GO_HERE));
        System.out.println(Text.of(Msg.BASE_DIR_ROOT_FOR_THE_MML_BASE_PACK_PARENT_OF_MINECRAFT_OTHERWISE_GAME_DIR));
        System.out.println(Text.of(Msg.STATE_DIR_MANIFEST_STATE_DIRECTORY_DEFAULT_GAME_DIR_MML_SERVER_SYNC));
        System.out.println(Text.of(Msg.FORCE_SYNC_EVEN_WHEN_THE_PACK_VERSION_IS_UNCHANGED));
        System.out.println(Text.of(Msg.DRY_RUN_PRINT_THE_PLAN_ONLY_NO_DOWNLOAD_DELETE_EXTRACT));
        System.out.println(Text.of(Msg.NO_DELETE_DO_NOT_DELETE_OLD_FILES_OR_CONFIG_DIRECTORIES));
        System.out.println(Text.of(Msg.CURSEFORGE_API_KEY_CURSEFORGE_API_KEY_DEFAULTS_TO_CURSEFORGE_API_KEY));
        System.out.println(Text.of(Msg.LOG_SYNC_LOG_FILE_DEFAULT_STATE_DIR_MML_SERVER_SYNC_LOG));
        System.out.println(Text.of(Msg.THREADS_CONCURRENT_DOWNLOADS_1_64_DEFAULT_16));
        System.out.println(Text.of(Msg.SKIP_OPTIONAL_MODS_NOT_TO_UPDATE_MANIFEST_RELATIVE_PATHS_REPEATABLE_OR_COMMA_SEPARATED));
        System.out.println(Text.of(Msg.SKIP_FILE_READ_SKIP_PATHS_FROM_A_UTF_8_FILE_ONE_PER_LINE));
        System.out.println(Text.of(Msg.NO_GUI_DO_NOT_SHOW_THE_PROGRESS_WINDOW));
        System.out.println(Text.of(Msg.NO_ASK_DO_NOT_ASK_ABOUT_OPTIONAL_MODS_UPDATE_ALL));
        System.out.println(Text.of(Msg.NO_UPDATE_DO_NOT_CHECK_FOR_SELF_UPDATES));
        System.out.println(Text.of(Msg.UPDATE_INDEX_SELF_UPDATE_INDEX_URL_DEFAULT_HTTPS_WWW_COLORYR_COM_MML_SERVER_SYNC_INDEX_JSON));
        System.out.println(Text.of(Msg.QUIET_LESS_OUTPUT));
        System.out.println(Text.of(Msg.VERSION_SHOW_VERSION));
        System.out.println(Text.of(Msg.HELP_H_SHOW_HELP));
        System.out.println();
        System.out.println(Text.of(Msg.EXAMPLES));
        System.out.println("  java -jar mml-server-sync.jar -m https://example.com/server.json -g .minecraft");
        System.out.println("  java -jar mml-server-sync.jar -m ./server.json -g ./instance/.minecraft --base-dir ./instance");
        System.out.println("  java -jar mml-server-sync.jar -m https://example.com/server.json -g .minecraft --skip=mods/b.jar --skip=mods/c.jar");
        System.out.println();
        System.out.println(Text.of(Msg.NOTES));
        System.out.println(Text.of(Msg.SKIPPED_WHEN_PACKVERSION_IS_UNCHANGED_FORCE_OVERRIDES));
        System.out.println(Text.of(Msg.ONLY_FILES_ARCHIVES_BASE_OF_THE_MML_SERVER_JSON_ARE_READ));
        System.out.println(Text.of(Msg.CURSEFORGE_ENTRIES_WITHOUT_A_URL_FALL_BACK_TO_THE_CDN_MODRINTH_USES_ITS_PUBLIC_API));
        System.out.println(Text.of(Msg.ONLY_ZIP_CONFIG_PACKS_ARE_SUPPORTED_THE_GENERATOR_EMITS_ZIP_FOR_ARCHIVES_BASE));
        System.out.println(Text.of(Msg.MML_SKIP_TRUE_SKIPS_THE_MML_LAUNCHER_DETECTION));
        System.out.println(Text.of(Msg.MANAGED_FILES_DROPPED_FROM_THE_NEW_MANIFEST_ARE_REMOVED_FILES_OUTSIDE_IT_ARE_UNTOUCHED));
        System.out.println(Text.of(Msg.LANGUAGE_NOTE));
    }

    private static final class UsageException extends Exception {
        private static final long serialVersionUID = 1L;

        UsageException(String message) {
            super(message);
        }
    }
}
