package com.coloryr.mml.serversync.sync;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.coloryr.mml.serversync.update.Updater;

/** 命令行选项。 */
public final class Options {
    public String manifest;
    public Path gameDir;
    public Path baseDir;
    public Path stateDir;
    /** 日志文件；为空时用状态目录下的默认文件名 */
    public Path logFile;
    public String apiKey = "";
    public boolean force;
    public boolean dryRun;
    public boolean noDelete;
    public boolean quiet;
    /** 清单拉不到时只记日志、不中止启动；仅 Agent 模式打开 */
    public boolean lenient;
    /** 失败时弹窗询问重试 / 跳过 / 关闭；优先于 {@link #lenient}，无图形界面时自动退化 */
    public boolean askOnFailure;
    /** 同时下载的文件数，1-64 */
    public int threads = 16;
    /** 是否显示 Swing 进度窗口；无图形界面时自动退化为不显示 */
    public boolean gui = true;
    /** 是否弹可选模组勾选窗；Agent 模式固定关掉，全部更新 */
    public boolean ask = true;
    /** 是否检查自更新 */
    public boolean update = true;
    /** 自更新地址（索引文件或目录）；空串表示用 {@link Updater#DEFAULT_INDEX} */
    public String updateUrl = "";
    /** 这次强制跳过的可选模组，清单里的相对路径，`/` 分隔 */
    public final List<String> skip = new ArrayList<>();
    /** --skip-file 指定的文件；null 表示没给 */
    public Path skipFile;
    public boolean help;
    public boolean version;
}
