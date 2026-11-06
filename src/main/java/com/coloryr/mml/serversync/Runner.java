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
                throw new UsageException("缺少 --manifest <URL 或文件>");
            }
            if (options.gameDir == null) {
                throw new UsageException("缺少 --game-dir <游戏目录>");
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
            Log.info("开始同步，游戏目录 " + options.gameDir);
            try {
                String summary = new Sync(options, ui).run();
                if (!options.quiet) {
                    Log.info(summary);
                }
                ui.finish(true, summary);
            } catch (Exception e) {
                ui.finish(false, e.getMessage());
                Log.error("同步失败: " + e.getMessage());
                throw e;
            } finally {
                Log.close();
            }
            return 0;
        } catch (UsageException e) {
            System.err.println(Log.PREFIX + "参数错误: " + e.getMessage());
            System.err.println(Log.PREFIX + "使用 --help 查看用法");
            return 2;
        } catch (Exception e) {
            System.err.println(Log.PREFIX + "同步失败: " + e.getMessage());
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
                throw new UsageException("不认识的参数: " + arg);
            } else {
                throw new UsageException("不认识的参数: " + arg);
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
            throw new UsageException("--skip-file 读取失败: " + e.getMessage());
        }
    }

    /** 解析线程数，限制在 1-64 */
    private static int threadsOf(String value) throws UsageException {
        try {
            return Math.max(1, Math.min(64, Integer.parseInt(value.trim())));
        } catch (NumberFormatException e) {
            throw new UsageException("--threads 需要整数: " + value);
        }
    }

    private static String value(String[] args, int index, String option) throws UsageException {
        if (index >= args.length || args[index].startsWith("-")) {
            throw new UsageException(option + " 缺少值");
        }
        return args[index];
    }

    private static void printHelp() {
        System.out.println("mml-server-sync " + Main.VERSION);
        System.out.println("独立服务器实例更新器，适用于非 MML 启动器的 pre-launch 更新。");
        System.out.println();
        System.out.println("Java Agent（推荐）:");
        System.out.println("  -javaagent:mml-server-sync.jar=<server.json 的 URL>");
        System.out.println("  MML 启动器启动时自动跳过，MML_SKIP=true 可强制不跳过；游戏工作目录必须是 .minecraft。");
        System.out.println();
        System.out.println("命令行用法:");
        System.out.println("  java -jar mml-server-sync.jar --manifest <URL|文件> --game-dir <目录> [选项]");
        System.out.println();
        System.out.println("参数:");
        System.out.println("  --manifest, -m      MML server.json 的 URL / 本地路径");
        System.out.println("  --game-dir, -g      游戏目录，例如 .minecraft；模组/配置文件写到这里");
        System.out.println("  --base-dir          MML 基础包解压根目录；默认 .minecraft 的父目录，否则同 game-dir");
        System.out.println("  --state-dir         清单状态目录；默认 <game-dir>/.mml-server-sync");
        System.out.println("  --force             包版本相同也强制同步");
        System.out.println("  --dry-run           只打印计划，不下载/删除/解压");
        System.out.println("  --no-delete         不删除旧文件/旧配置目录");
        System.out.println("  --curseforge-api-key CurseForge API Key；缺省读 CURSEFORGE_API_KEY");
        System.out.println("  --log               同步日志文件；默认 <状态目录>/mml-server-sync.log");
        System.out.println("  --threads           同时下载的文件数，1-64；默认 16");
        System.out.println("  --skip              这次不更新的可选模组，清单相对路径，可重复或逗号分隔");
        System.out.println("  --skip-file         从文件读要跳过的路径，UTF-8，一行一个");
        System.out.println("  --no-gui            不显示同步进度窗口");
        System.out.println("  --no-ask            不弹可选模组勾选窗，全部更新");
        System.out.println("  --no-update         不检查自更新");
        System.out.println("  --update-index      自更新索引地址；默认 GitHub 仓库 master 分支的 index.json");
        System.out.println("  --quiet             少输出");
        System.out.println("  --version           显示版本");
        System.out.println("  --help, -h          显示帮助");
        System.out.println();
        System.out.println("示例:");
        System.out.println("  java -jar mml-server-sync.jar -m https://example.com/server.json -g .minecraft");
        System.out.println("  java -jar mml-server-sync.jar -m ./server.json -g ./instance/.minecraft --base-dir ./instance");
        System.out.println("  java -jar mml-server-sync.jar -m https://example.com/server.json -g .minecraft --skip=mods/b.jar --skip=mods/c.jar");
        System.out.println();
        System.out.println("说明:");
        System.out.println("  - 有 PackVersion 且未变时跳过；--force 可强制。");
        System.out.println("  - 只读取 MML server.json 的 Files / Archives / Base。");
        System.out.println("  - CurseForge 没有 URL 时用 fileId 推 CDN；Modrinth 用公开 API 查地址。");
        System.out.println("  - 只支持 zip 配置包；生成器的 Archives / Base 都是 zip。");
        System.out.println("  - 环境变量 MML_SKIP=true 跳过 MML 启动器识别。");
        System.out.println("  - 会删除旧清单里有、当前清单没有的受管文件；不碰清单外的用户文件。");
    }

    private static final class UsageException extends Exception {
        private static final long serialVersionUID = 1L;

        UsageException(String message) {
            super(message);
        }
    }
}
