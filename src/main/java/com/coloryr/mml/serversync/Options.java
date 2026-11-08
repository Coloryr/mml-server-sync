package com.coloryr.mml.serversync;

import java.nio.file.Path;

/** 命令行选项。 */
final class Options {
    String manifest;
    Path gameDir;
    Path baseDir;
    Path stateDir;
    /** 日志文件；为空时用状态目录下的默认文件名 */
    Path logFile;
    String apiKey = "";
    boolean force;
    boolean dryRun;
    boolean noDelete;
    boolean quiet;
    /** 清单拉不到时只记日志、不中止启动；仅 Agent 模式打开 */
    boolean lenient;
    /** 失败时弹窗询问重试 / 跳过 / 关闭；优先于 {@link #lenient}，无图形界面时自动退化 */
    boolean askOnFailure;
    /** 同时下载的文件数，1-64 */
    int threads = 16;
    /** 是否显示 Swing 进度窗口；无图形界面时自动退化为不显示 */
    boolean gui = true;
    /** 是否弹可选模组勾选窗；Agent 模式固定关掉，全部更新 */
    boolean ask = true;
    /** 是否检查自更新 */
    boolean update = true;
    /** 自更新索引地址；空串表示用 {@link Updater#DEFAULT_INDEX} */
    String updateIndex = "";
    /** 这次强制跳过的可选模组，清单里的相对路径，`/` 分隔 */
    final java.util.List<String> skip = new java.util.ArrayList<>();
    /** --skip-file 指定的文件；null 表示没给 */
    Path skipFile;
    boolean help;
    boolean version;
}
