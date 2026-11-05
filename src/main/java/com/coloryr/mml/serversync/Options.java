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
    /** 同时下载的文件数，1-64 */
    int threads = 16;
    boolean help;
    boolean version;
}
