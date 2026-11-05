package com.coloryr.mml.serversync;

import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Optional;

/**
 * -javaagent 入口。
 *
 * 用法：-javaagent:mml-server-sync.jar=http://example.com/server.json
 * agent 在游戏 main 之前执行同步；如果认出是 MML 启动器，直接跳过。
 * 设环境变量 MML_SKIP=true 可以强制跳过这段识别，用于在 MML 下验证同步。
 */
public final class Agent {
    private Agent() {}

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        Path gameDir = gameDir();
        if (skipLauncherCheck()) {
            System.out.println(Log.PREFIX + "MML_SKIP 已设置，跳过 MML 启动器检查");
        } else if (isMmlLauncher(gameDir)) {
            System.out.println(Log.PREFIX + "检测到 MML 启动器，跳过服务器包同步");
            return;
        }
        String manifest = agentArgs == null ? "" : agentArgs.trim();
        if (manifest.isEmpty()) {
            throw new RuntimeException("mml-server-sync: -javaagent 参数缺少清单 URL");
        }
        try {
            Options options = new Options();
            options.manifest = manifest;
            options.gameDir = gameDir;
            options.baseDir = Main.defaultBaseDir(gameDir);
            options.stateDir = stateDir(gameDir);
            options.quiet = false;
            options.lenient = true;
            options.threads = threadsOf(System.getProperty("mml.serverSync.threads"));
            options.force = truthy(System.getProperty("mml.serverSync.force"))
                    || truthy(System.getenv("MML_SERVER_SYNC_FORCE"));
            String apiKey = System.getenv("CURSEFORGE_API_KEY");
            options.apiKey = apiKey == null ? "" : apiKey.trim();
            options.logFile = logFile(options.stateDir);
            Log.open(options.logFile);
            Log.info("mml-server-sync " + Main.VERSION);
            Log.info("开始同步，游戏目录 " + gameDir);
            try {
                new Sync(options).run();
            } catch (Exception e) {
                Log.error("更新失败: " + e.getMessage());
                throw e;
            } finally {
                Log.close();
            }
        } catch (Exception e) {
            throw new RuntimeException("mml-server-sync 更新失败，已中止游戏启动", e);
        }
    }

    private static Path gameDir() {
        String property = System.getProperty("mml.serverSync.gameDir", "").trim();
        if (!property.isEmpty()) {
            return Paths.get(property).toAbsolutePath().normalize();
        }
        String env = System.getenv("MML_SERVER_SYNC_GAME_DIR");
        if (env != null && !env.trim().isEmpty()) {
            return Paths.get(env.trim()).toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }

    /** 下载线程数，缺省或非法时用 16。 */
    private static int threadsOf(String value) {
        if (value == null || value.trim().isEmpty()) {
            return 16;
        }
        try {
            return Math.max(1, Math.min(64, Integer.parseInt(value.trim())));
        } catch (NumberFormatException e) {
            return 16;
        }
    }

    /** 日志文件路径：JVM 属性可覆盖，默认放在状态目录下。 */
    private static Path logFile(Path stateDir) {
        String property = System.getProperty("mml.serverSync.log", "").trim();
        if (!property.isEmpty()) {
            return Paths.get(property).toAbsolutePath().normalize();
        }
        return stateDir.resolve(Log.FILE_NAME);
    }

    private static Path stateDir(Path gameDir) {
        String property = System.getProperty("mml.serverSync.stateDir", "").trim();
        if (!property.isEmpty()) {
            return Paths.get(property).toAbsolutePath().normalize();
        }
        String env = System.getenv("MML_SERVER_SYNC_STATE_DIR");
        if (env != null && !env.trim().isEmpty()) {
            return Paths.get(env.trim()).toAbsolutePath().normalize();
        }
        return gameDir.resolve(".mml-server-sync");
    }

    /** 是否强制跳过 MML 启动器识别，由环境变量 MML_SKIP 控制。 */
    private static boolean skipLauncherCheck() {
        return truthy(System.getenv("MML_SKIP"));
    }

    /** 是否是 MML 启动器起的游戏进程。 */
    static boolean isMmlLauncher(Path gameDir) {
        if (truthy(System.getProperty("mml.launcher"))) {
            return true;
        }
        if (hasMmlGameArgs(gameDir)) {
            return true;
        }
        if (isolatedToInstance(gameDir)) {
            return true;
        }
        return parentLooksLikeMml();
    }

    /** MML 游戏进程会带 ARG_GAME_DIR / ARG_GAME_BASE_DIR 等启动参数环境变量。 */
    private static boolean hasMmlGameArgs(Path gameDir) {
        String game = System.getenv("ARG_GAME_DIR");
        String base = System.getenv("ARG_GAME_BASE_DIR");
        String uuid = System.getenv("ARG_GAME_UUID");
        Path instance = gameDir.getParent();
        if (samePath(game, gameDir) && instance != null && samePath(base, instance)) {
            return true;
        }
        return uuid != null && !uuid.trim().isEmpty() && samePath(game, gameDir);
    }

    /**
     * MML 会默认隔离用户目录，把 HOME / USERPROFILE / APPDATA / LOCALAPPDATA 指到实例根目录。
     * 这是现有 MML 版本一定会传的环境变量特征。
     */
    private static boolean isolatedToInstance(Path gameDir) {
        Path instance = gameDir.getParent();
        if (instance == null) {
            return false;
        }
        String[] exact = {"HOME", "USERPROFILE", "APPDATA", "LOCALAPPDATA"};
        for (String key : exact) {
            String value = System.getenv(key);
            if (samePath(value, instance) || samePath(value, gameDir)) {
                return true;
            }
        }
        if (samePath(System.getProperty("user.home"), instance)
                || samePath(System.getProperty("user.home"), gameDir)) {
            return true;
        }
        String[] prefixes = {"XDG_CONFIG_HOME", "XDG_DATA_HOME", "XDG_CACHE_HOME"};
        for (String key : prefixes) {
            String value = System.getenv(key);
            if (value != null && !value.trim().isEmpty()) {
                try {
                    Path path = Paths.get(value).toAbsolutePath().normalize();
                    if (path.startsWith(instance)) {
                        return true;
                    }
                } catch (RuntimeException ignored) {
                    // 非法环境变量按不匹配处理
                }
            }
        }
        return false;
    }

    private static boolean samePath(String value, Path expected) {
        if (value == null || value.trim().isEmpty()) {
            return false;
        }
        try {
            return Paths.get(value).toAbsolutePath().normalize().equals(expected.toAbsolutePath().normalize());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean truthy(String value) {
        if (value == null) {
            return false;
        }
        String text = value.trim().toLowerCase(Locale.ROOT);
        return "1".equals(text) || "true".equals(text) || "yes".equals(text)
                || "mml".equals(text);
    }

    /**
     * 旧版 MML 没有写环境变量时的兜底：Java 9+ 反射读父进程命令行。
     * Java 8 没有 ProcessHandle，只能靠环境变量，捕获所有反射异常。
     */
    private static boolean parentLooksLikeMml() {
        try {
            Class<?> handle = Class.forName("java.lang.ProcessHandle");
            Object current = handle.getMethod("current").invoke(null);
            Object parentValue = handle.getMethod("parent").invoke(current);
            if (!(parentValue instanceof Optional) || !((Optional<?>) parentValue).isPresent()) {
                return false;
            }
            Object parent = ((Optional<?>) parentValue).get();
            Object info = handle.getMethod("info").invoke(parent);
            Class<?> infoClass = Class.forName("java.lang.ProcessHandle$Info");
            Object commandValue = infoClass.getMethod("command").invoke(info);
            if (!(commandValue instanceof Optional) || !((Optional<?>) commandValue).isPresent()) {
                return false;
            }
            String command = String.valueOf(((Optional<?>) commandValue).get())
                    .toLowerCase(Locale.ROOT).replace('\\', '/');
            return command.contains("mml-gui")
                    || command.contains("modernminecraftlauncher")
                    || command.contains("mml_launcher")
                    || command.contains("mml-launcher");
        } catch (Throwable ignored) {
            return false;
        }
    }
}
