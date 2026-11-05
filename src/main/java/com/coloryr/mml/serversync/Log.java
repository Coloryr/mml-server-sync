package com.coloryr.mml.serversync;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 同步日志：控制台与状态目录下的日志文件各写一份。
 *
 * - 游戏进程的 stdout 会被启动器 / 游戏自己的日志系统截走，只打控制台等于没有日志，
 *   所以每次同步都另存一份文件，路径默认是 {@code <状态目录>/mml-server-sync.log}。
 * - 每行带本地时间与级别，写文件后立即 flush，进程被强制结束也不丢已写的内容。
 */
final class Log {
    /** 每条日志的行首前缀，方便启动器日志里一眼认出是本工具的 */
    static final String PREFIX = "[MML-SERVER-SYNC] ";

    /** 日志文件名，放在状态目录下 */
    static final String FILE_NAME = "mml-server-sync.log";

    private Log() {}

    private static PrintWriter file;

    /**
     * 打开日志文件
     *
     * 打不开时退化成只写控制台，不影响同步本身。
     *
     * - `target`: 日志文件路径，父目录不存在时自动创建
     */
    static void open(Path target) {
        close();
        try {
            Path parent = target.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            file = new PrintWriter(Files.newBufferedWriter(target, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE), true);
        } catch (IOException e) {
            file = null;
            System.err.println(PREFIX + "无法写入日志文件 " + target + "：" + e.getMessage());
        }
    }

    /** 写一条普通日志 */
    static void info(String message) {
        write("info", message);
    }

    /** 写一条错误日志 */
    static void error(String message) {
        write("error", message);
    }

    /** 关闭日志文件，之后只写控制台 */
    static void close() {
        if (file != null) {
            file.flush();
            file.close();
            file = null;
        }
    }

    private static void write(String level, String message) {
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        String line = PREFIX + "[" + time + "] [" + level + "] " + message;
        System.out.println(line);
        if (file != null) {
            file.println(line);
        }
    }
}
