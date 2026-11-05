package com.coloryr.mml.serversync;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** 独立服务器实例更新器入口。 */
public final class Main {
    /** 工具版本号，启动日志与 --version 都用它 */
    static final String VERSION = "1.0.0";

    private Main() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            if (options == null) {
                return;
            }
            if (options.help) {
                printHelp();
                return;
            }
            if (options.version) {
                System.out.println("mml-server-sync " + VERSION);
                return;
            }
            if (options.manifest == null || options.manifest.trim().isEmpty()) {
                throw new UsageException("缺少 --manifest <URL 或文件>");
            }
            if (options.gameDir == null) {
                throw new UsageException("缺少 --game-dir <游戏目录>");
            }
            options.gameDir = options.gameDir.toAbsolutePath().normalize();
            options.baseDir = options.baseDir == null
                    ? defaultBaseDir(options.gameDir)
                    : options.baseDir.toAbsolutePath().normalize();
            options.stateDir = options.stateDir == null
                    ? options.gameDir.resolve(".mml-server-sync")
                    : options.stateDir.toAbsolutePath().normalize();
            Files.createDirectories(options.gameDir);
            Files.createDirectories(options.baseDir);
            Log.open(options.logFile == null
                    ? options.stateDir.resolve(Log.FILE_NAME)
                    : options.logFile.toAbsolutePath().normalize());
            Log.info("mml-server-sync " + VERSION);
            Log.info("开始同步，游戏目录 " + options.gameDir);
            try {
                new Sync(options).run();
            } catch (Exception e) {
                Log.error("同步失败: " + e.getMessage());
                throw e;
            } finally {
                Log.close();
            }
        } catch (UsageException e) {
            System.err.println(Log.PREFIX + "参数错误: " + e.getMessage());
            System.err.println(Log.PREFIX + "使用 --help 查看用法");
            System.exit(2);
        } catch (Exception e) {
            System.err.println(Log.PREFIX + "同步失败: " + e.getMessage());
            System.exit(1);
        }
    }

    static Path defaultBaseDir(Path gameDir) {
        Path name = gameDir.getFileName();
        if (name != null && ".minecraft".equalsIgnoreCase(name.toString())
                && gameDir.getParent() != null) {
            return gameDir.getParent();
        }
        return gameDir;
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
        return options;
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
        System.out.println("mml-server-sync " + VERSION);
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
        System.out.println("  --quiet             少输出");
        System.out.println("  --version           显示版本");
        System.out.println("  --help, -h          显示帮助");
        System.out.println();
        System.out.println("示例:");
        System.out.println("  java -jar mml-server-sync.jar -m https://example.com/server.json -g .minecraft");
        System.out.println("  java -jar mml-server-sync.jar -m ./server.json -g ./instance/.minecraft --base-dir ./instance");
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
        UsageException(String message) {
            super(message);
        }
    }
}
