package com.coloryr.mml.serversync;

import java.nio.file.Path;

import com.coloryr.mml.serversync.update.Updater;

/** 独立服务器实例更新器入口。 */
public final class Main {
    /** 工具版本号，启动日志、--version 与自更新比较都用它 */
    public static final String VERSION = "1.9.0";

    private Main() {}

    /**
     * 程序入口。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        // -jar 模式的原始 jar 就是主类所在 jar；先记下，同步计划据此跳过自身条目
        Updater.captureSelfJar();
        // 独立运行时窗口多留一会儿，让用户看到结果；命令行模式等自更新收尾
        int code = Bootstrap.run(args, 900, true);
        if (code != 0) {
            System.exit(code);
        }
    }

    /**
     * 默认基础包目录
     *
     * @param gameDir 游戏目录
     * @return 游戏目录是 .minecraft 时返回它的父目录，否则就是游戏目录本身
     */
    static Path defaultBaseDir(Path gameDir) {
        Path name = gameDir.getFileName();
        if (name != null && ".minecraft".equalsIgnoreCase(name.toString())
                && gameDir.getParent() != null) {
            return gameDir.getParent();
        }
        return gameDir;
    }
}
