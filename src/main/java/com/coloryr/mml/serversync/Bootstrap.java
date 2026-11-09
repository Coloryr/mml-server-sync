package com.coloryr.mml.serversync;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.coloryr.mml.serversync.host.Progress;
import com.coloryr.mml.serversync.sync.Verify;
import com.coloryr.mml.serversync.ui.ProgressWindow;
import com.coloryr.mml.serversync.ui.UiScale;
import com.coloryr.mml.serversync.update.UpdateClassLoader;
import com.coloryr.mml.serversync.update.Updater;
import com.coloryr.mml.serversync.util.Log;
import com.coloryr.mml.serversync.util.Msg;
import com.coloryr.mml.serversync.util.Text;

/** 启动引导：先查一次更新，再从 update 目录里挑最新的 jar 交给它跑。 */
final class Bootstrap {
    /** 更新包里的入口类，每个版本都必须有 */
    private static final String ENTRY = "com.coloryr.mml.serversync.Runner";
    /** 更新包文件名前缀与后缀，中间的版本号用于排序 */
    private static final String FILE_PREFIX = "mml-server-sync-";
    private static final String FILE_SUFFIX = ".jar";
    /** 进度窗结束后停留多久，通过系统属性传给更新包。 */
    static final String LINGER_KEY = "mml.serverSync.linger";

    private Bootstrap() {}

    /**
     * 引导一次运行
     *
     * @param args 原始命令行参数
     * @param lingerMillis 进度窗口结束后停留多久
     * @param waitUpdate 是否等后台自更新收尾；命令行模式等，游戏模式不等
     * @return 进程退出码
     */
    static int run(String[] args, int lingerMillis, boolean waitUpdate) {
        boolean gui = true;
        boolean update = true;
        String index = "";
        boolean quick = false;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--no-gui".equals(arg)) {
                gui = false;
            } else if ("--gui".equals(arg)) {
                gui = true;
            } else if ("--no-update".equals(arg)) {
                update = false;
            } else if ("--update".equals(arg)) {
                update = true;
            } else if ("--update-url".equals(arg) || "-u".equals(arg)
                    || "--update-index".equals(arg)) {
                if (i + 1 < args.length) {
                    index = args[++i];
                }
            } else if (arg.startsWith("--update-url=")) {
                index = arg.substring("--update-url=".length());
            } else if (arg.startsWith("--update-index=")) {
                index = arg.substring("--update-index=".length());
            } else if (arg.startsWith("--ui-scale=")) {
                // 基包的进度窗口在本方法里就建了，缩放要在建窗之前定下来；
                // 更新包由独立类加载器加载，靠 UiScale 的 JVM 属性共享同一个倍数。
                Double value = UiScale.parse(arg.substring("--ui-scale=".length()));
                if (value != null) {
                    UiScale.override(value);
                }
            } else if ("--help".equals(arg) || "-h".equals(arg) || "--version".equals(arg)) {
                // 只看用法 / 版本：不连网
                quick = true;
            }
        }
        if (index.isEmpty()) {
            // 启动器也可以只用 -Dmml.serverSync.updateUrl / 环境变量指定，不必改启动参数
            index = Updater.configuredIndex();
        }
        // 有更新包时窗口交给它建：基包不随更新包变化，窗口留在基包里会让界面
        // 永远停在基包那一版的布局。更新包拿到的是空实现，自己按参数决定要不要开窗。
        Path packageJar = newestOf();
        Progress[] holder = new Progress[1];
        holder[0] = quick || packageJar != null
                ? Progress.NONE
                : ProgressWindow.open(gui, lingerMillis);
        if (packageJar != null) {
            System.setProperty(LINGER_KEY, String.valueOf(lingerMillis));
        }
        Thread updater = quick ? null : Updater.start(update, index);
        int code;
        try {
            code = launch(args, packageJar, holder, gui, lingerMillis);
        } finally {
            if (waitUpdate) {
                join(updater);
            }
            closeUnfinished(holder[0], lingerMillis);
        }
        return code;
    }

/** 有更新的 jar 就用它，否则退回当前 jar */
    private static int launch(String[] args, Path packageJar, Progress[] holder,
                              boolean gui, int lingerMillis) {
        if (packageJar == null) {
            return Runner.run(args, holder[0]);
        }
        Integer code = runFromJar(packageJar, args, holder[0]);
        if (code != null) {
            return code;
        }
        // 更新包装不上：回到本包重跑，窗口也得由本包补上（此前交给更新包了）
        if (holder[0] == Progress.NONE) {
            holder[0] = ProgressWindow.open(gui, lingerMillis);
        }
        return Runner.run(args, holder[0]);
    }

/** 本次运行可用的更新包；没有时返回 null */
    private static Path newestOf() {
        Path jar = Updater.jarPath();
        return jar == null ? null : newest(Updater.updateDir(jar));
    }

    /**
     * 用独立类加载器跑更新包；返回 null 表示装不上、需要回退。
     *
     * @param jar 更新包路径
     * @param args 原样转交的命令行参数
     * @param progress 进度窗口，跨类加载器共享
     * @return 退出码；返回 null 表示这个包装不上，调用方回退到当前版本
     */
    private static Integer runFromJar(Path jar, String[] args, Progress progress) {
        UpdateClassLoader loader;
        try {
            loader = new UpdateClassLoader(jar.toUri().toURL());
        } catch (MalformedURLException e) {
            return null;
        }
        // 故意不关闭：同步失败时 EDT 上可能还开着自选模组弹窗，它收尾时还要用这个加载器。
        // 关闭后晚加载的类会抛 NoClassDefFoundError（见 OptionalModsDialog.Result）。
        try {
            Class<?> entry = Class.forName(ENTRY, true, loader);
            Method method = entry.getMethod("run", String[].class, Progress.class);
            Log.info(Text.of(Msg.USING_UPDATE_PACKAGE_VERSION, jar.getFileName(), Main.VERSION));
            Object result = method.invoke(null, args, progress);
            return result instanceof Integer ? (Integer) result : 1;
        } catch (InvocationTargetException e) {
            // 更新包自己跑挂了：不再拿当前版本重跑一遍，同一套操作不能做两次
            Throwable cause = e.getCause() == null ? e : e.getCause();
            System.out.println(Log.PREFIX + Text.of(Msg.UPDATE_PACKAGE_FAILED) + Log.describe(cause));
            return 1;
        } catch (Throwable e) {
            System.out.println(Log.PREFIX
                    + Text.of(Msg.UPDATE_PACKAGE_UNUSABLE_FALLING_BACK_TO_THE_CURRENT_VERSION)
                    + Log.describe(e));
            deleteUpdate(jar);
            return null;
        }
    }

    /**
     * 更新目录里版本最高、且比自己新的 jar
     *
     * @param dir update 目录
     * @return 没有可用的更新包时返回 null
     */
    private static Path newest(Path dir) {
        if (!Files.isDirectory(dir)) {
            return null;
        }
        List<Path> candidates = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, FILE_PREFIX + "*" + FILE_SUFFIX)) {
            for (Path file : stream) {
                String version = versionOf(file.getFileName().toString());
                if (version == null || Updater.compare(version, Main.VERSION) <= 0) {
                    continue;
                }
                candidates.add(file);
            }
        } catch (Exception e) {
            System.out.println(Log.PREFIX + Text.of(Msg.FAILED_TO_READ_THE_UPDATE_DIRECTORY) + Log.describe(e));
            return null;
        }
        // 版本从高到低试：最新的那份校验不过，还能退到上一份
        candidates.sort((left, right) -> Updater.compare(
                versionOf(right.getFileName().toString()),
                versionOf(left.getFileName().toString())));
        for (Path file : candidates) {
            String reason = Verify.check(file);
            if (reason == null) {
                return file;
            }
            System.out.println(Log.PREFIX + Text.of(Msg.UPDATE_PACKAGE_INVALID, file.getFileName(), reason));
            deleteUpdate(file);
        }
        return null;
    }

    /**
     * 从 mml-server-sync-1.1.1.jar 里取出版本号；格式不对返回 null
     *
     * @param name 文件名
     * @return 版本号；格式不对时 null
     */
    private static String versionOf(String name) {
        if (!name.startsWith(FILE_PREFIX) || !name.endsWith(FILE_SUFFIX)) {
            return null;
        }
        String version = name.substring(FILE_PREFIX.length(), name.length() - FILE_SUFFIX.length());
        return version.isEmpty() ? null : version;
    }

    /**
     * 兜底关窗：没走到 finish 或游戏模式下窗口未释放时强制关掉，避免 AWT 线程拖住 JVM。
     *
     * @param progress 本次运行的进度窗口
     * @param lingerMillis 进度窗口结束后停留多久；0 表示游戏模式，不留停留时间
     */
    private static void closeUnfinished(Progress progress, int lingerMillis) {
        if (!(progress instanceof ProgressWindow)) {
            return;
        }
        ProgressWindow window = (ProgressWindow) progress;
        if (!window.finished()) {
            window.close();
            return;
        }
        if (lingerMillis <= 0) {
            window.ensureDisposed();
        }
    }

    /**
     * 等后台自更新收尾，最多 3 秒。
     *
     * @param thread 后台线程；null 时忽略
     */
    private static void join(Thread thread) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 删掉一份不可用的更新包及其哈希记录
     *
     * @param file 更新包
     */
    private static void deleteUpdate(Path file) {
        deleteQuietly(file);
        deleteQuietly(file.resolveSibling(file.getFileName().toString() + Verify.SHA1_SIDECAR));
        deleteQuietly(file.resolveSibling(file.getFileName().toString() + ".sha256"));
    }

    /**
     * 删除文件，忽略失败。
     *
     * @param file 目标文件
     */
    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (Exception ignored) {
            // 删不掉不影响回退
        }
    }
}
