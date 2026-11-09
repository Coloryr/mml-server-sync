package com.coloryr.mml.serversync;

import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.coloryr.mml.serversync.update.Updater;
import com.coloryr.mml.serversync.util.Log;
import com.coloryr.mml.serversync.util.Msg;
import com.coloryr.mml.serversync.util.Text;

/**
 * -javaagent 入口：在游戏 main 之前完成同步。
 *
 * 参数是 server.json 地址；识别到 MML 启动器时跳过，`MML_SKIP=true` 可强制同步。
 */
public final class Agent {
    private Agent() {}

    /**
     * 在游戏 main 之前执行同步。
     *
     * @param agentArgs server.json 地址
     * @param instrumentation JVM 传入的 Instrumentation（未使用）
     */
    public static void premain(String agentArgs, Instrumentation instrumentation) {
        // premain 从 -javaagent 指定的原始 jar 加载，此刻的类加载源就是原始 jar
        Updater.captureSelfJar();
        Path gameDir = gameDir();
        if (skipLauncherCheck()) {
            System.out.println(Log.PREFIX + Text.of(Msg.MML_SKIP_IS_SET_SKIPPING_THE_MML_LAUNCHER_CHECK));
        } else if (isMmlLauncher(gameDir)) {
            System.out.println(Log.PREFIX + Text.of(Msg.MML_LAUNCHER_DETECTED_SKIPPING_SERVER_PACK_SYNC));
            return;
        }
        String manifest = agentArgs == null ? "" : agentArgs.trim();
        if (manifest.isEmpty()) {
            throw new RuntimeException(Text.of(Msg.MML_SERVER_SYNC_JAVAAGENT_ARGUMENT_IS_MISSING_THE_MANIFEST_URL));
        }
        int code;
        try {
            // 游戏在等 premain 返回，窗口不留、自更新不等
            code = Bootstrap.run(agentArgs(manifest, gameDir), 0, false);
        } catch (Throwable e) {
            throw new RuntimeException(Text.of(Msg.MML_SERVER_SYNC_UPDATE_FAILED_GAME_START_ABORTED), e);
        }
        if (code == 3) {
            // 玩家在失败提示框里选了「关闭游戏」：不是更新出错，别报成失败
            throw new RuntimeException(Text.of(Msg.USER_CLOSED_GAME));
        }
        if (code != 0) {
            throw new RuntimeException(Text.of(Msg.AGENT_EXIT_CODE, code));
        }
    }

    /**
     * 把 agent 的配置翻成命令行参数，交给 Runner 解析
     *
     * @param manifest 清单地址
     * @param gameDir 游戏目录
     * @return 等价的参数数组
     */
    private static String[] agentArgs(String manifest, Path gameDir) {
        Path state = stateDir(gameDir);
        List<String> args = new ArrayList<>();
        args.add("--manifest");
        args.add(manifest);
        args.add("--game-dir");
        args.add(gameDir.toString());
        args.add("--base-dir");
        args.add(Main.defaultBaseDir(gameDir).toString());
        args.add("--state-dir");
        args.add(state.toString());
        args.add("--log");
        args.add(logFile(state).toString());
        args.add("--threads");
        args.add(String.valueOf(threadsOf(System.getProperty("mml.serverSync.threads"))));
        if (truthy(System.getProperty("mml.serverSync.force"))
                || truthy(System.getenv("MML_SERVER_SYNC_FORCE"))) {
            args.add("--force");
        }
        // 清单拉不到时只记日志，别让服务器掉线锁死启动
        args.add("--lenient");
        // 启动场景窗口前就有人等着：默认允许自选模组勾选窗，无人值守时显式关掉
        if (!askEnabled()) {
            args.add("--no-ask");
        }
        if (!guiEnabled()) {
            args.add("--no-gui");
        } else {
            // 有进度窗口时，失败要问一句：重试、跳过本次更新，还是关闭游戏
            args.add("--ask-on-failure");
        }
        if (!updateEnabled()) {
            args.add("--no-update");
        }
        String skip = skipArgs();
        if (!skip.isEmpty()) {
            args.add("--skip");
            args.add(skip);
        }
        String index = Updater.configuredIndex();
        if (!index.isEmpty()) {
            args.add("--update-url");
            args.add(index);
        }
        return args.toArray(new String[0]);
    }

    /**
     * 取游戏目录：JVM 属性 → 环境变量 → 当前工作目录。
     *
     * @return 游戏目录
     */
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

    /**
     * 下载线程数，缺省或非法时用 16。
     *
     * @param value 属性值
     * @return 1-64；缺省或非法时 16
     */
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

    /**
     * 是否显示进度窗口；-Dmml.serverSync.gui=false 或 MML_SERVER_SYNC_NO_GUI=1 关掉。
     *
     * @return 要显示进度窗时 true
     */
    private static boolean guiEnabled() {
        String value = System.getProperty("mml.serverSync.gui", "").trim();
        if (value.isEmpty()) {
            return !truthy(System.getenv("MML_SERVER_SYNC_NO_GUI"));
        }
        return truthy(value);
    }

    /**
     * 是否检查自更新；-Dmml.serverSync.update=false 或 MML_SERVER_SYNC_NO_UPDATE=1 关掉。
     *
     * @return 要检查自更新时 true
     */
    private static boolean updateEnabled() {
        String value = System.getProperty("mml.serverSync.update", "").trim();
        if (value.isEmpty()) {
            return !truthy(System.getenv("MML_SERVER_SYNC_NO_UPDATE"));
        }
        return truthy(value);
    }

    /**
     * 是否允许弹自选模组勾选窗；-Dmml.serverSync.noAsk=true 或 MML_SERVER_SYNC_NO_ASK=1 关掉。
     *
     * @return 允许弹自选模组窗时 true
     */
    private static boolean askEnabled() {
        String value = System.getProperty("mml.serverSync.noAsk", "").trim();
        if (value.isEmpty()) {
            return !truthy(System.getenv("MML_SERVER_SYNC_NO_ASK"));
        }
        return !truthy(value);
    }

    /**
     * 脚本 / 启动器传来的跳过列表：JVM 属性优先，其次环境变量；逗号分隔
     *
     * @return 逗号分隔的跳过路径；没配时空串
     */
    private static String skipArgs() {
        String property = System.getProperty("mml.serverSync.skip", "").trim();
        if (!property.isEmpty()) {
            return property;
        }
        String env = System.getenv("MML_SERVER_SYNC_SKIP");
        return env == null ? "" : env.trim();
    }

    /**
     * 日志文件路径：JVM 属性可覆盖，默认放在状态目录下。
     *
     * @param stateDir 状态目录
     * @return 日志文件路径
     */
    private static Path logFile(Path stateDir) {
        String property = System.getProperty("mml.serverSync.log", "").trim();
        if (!property.isEmpty()) {
            return Paths.get(property).toAbsolutePath().normalize();
        }
        return stateDir.resolve(Log.FILE_NAME);
    }

    /**
     * 取状态目录。
     *
     * @param gameDir 游戏目录
     * @return 状态目录
     */
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

    /**
     * 是否是 MML 启动器起的游戏进程。
     *
     * @param gameDir 游戏目录
     * @return 是 MML 启动的进程时 true
     */
    static boolean isMmlLauncher(Path gameDir) {
        if (truthy(System.getProperty("mml.launcher"))) {
            return true;
        }
        // MML 1.4.0+ 会显式写 MML_LAUNCHER=1（见 game_launch.rs 的 mml_env）
        if (truthy(System.getenv("MML_LAUNCHER"))) {
            return true;
        }
        if (hasMmlGameArgs(gameDir)) {
            return true;
        }
        // FCL / PojavLauncher 等安卓启动器会把 XDG_DATA_HOME 放进版本目录，
        // 与 MML 的「用户目录隔离」特征撞车，先排除，避免误跳过同步
        if (isAndroidLauncher()) {
            return false;
        }
        if (isolatedToInstance(gameDir)) {
            return true;
        }
        return parentLooksLikeMml();
    }

    /**
     * FCL / PojavLauncher 等安卓启动器的环境特征；MML 是桌面端，不会带这些变量
     *
     * @return 是安卓启动器时 true
     */
    public static boolean isAndroidLauncher() {
        // 启动器会把 os.version 写成 Android-15 / Android-16
        if (System.getProperty("os.version", "").toLowerCase(Locale.ROOT).contains("android")) {
            return true;
        }
        String[] keys = {"FCL_NATIVEDIR", "FCL_VERSION_CODE", "POJAV_NATIVEDIR",
                "POJAV_RENDERER", "MOD_ANDROID_RUNTIME", "DALVIK_JAVAVM", "ZALITH_VERSION_CODE"};
        for (String key : keys) {
            String value = System.getenv(key);
            if (value != null && !value.trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * MML 游戏进程会带 ARG_GAME_DIR / ARG_GAME_BASE_DIR 等启动参数环境变量。
     *
     * @param gameDir 游戏目录
     * @return 命中 ARG_* 时 true
     */
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
     *
     * @param gameDir 游戏目录
     * @return 用户目录被隔离到实例时 true
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
        // 不再按 XDG_* 前缀判定：FCL 等安卓启动器也会把 XDG_DATA_HOME 放进版本目录。
        // MML 隔离时一定会把上面的 HOME / USERPROFILE / APPDATA / LOCALAPPDATA 指到实例根目录。
        return false;
    }

    /**
     * 两个路径规范化后是否相同。
     *
     * @param value 路径字符串
     * @param expected 期望路径
     * @return 相同或路径非法时 false
     */
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

    /**
     * 配置值是否表示真。
     *
     * @param value 配置值
     * @return 1 / true / yes / mml 时 true
     */
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
     *
     * @return 父进程是 MML 时 true
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
