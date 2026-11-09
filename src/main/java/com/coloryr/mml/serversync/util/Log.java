package com.coloryr.mml.serversync.util;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;

/** 同步日志：控制台与状态目录下的日志文件各写一份。 */
public final class Log {
    /** 每条日志的行首前缀，方便启动器日志里一眼认出是本工具的 */
    public static final String PREFIX = "[MML-SERVER-SYNC] ";

    /** 日志文件名，放在状态目录下 */
    public static final String FILE_NAME = "mml-server-sync.log";

    private Log() {}

    private static PrintWriter file;

    /**
     * 打开日志文件
     *
     * @param target 日志文件路径，父目录不存在时自动创建
     */
    public static void open(Path target) {
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
            System.err.println(PREFIX + Text.of(Msg.CANNOT_WRITE_LOG, target, e.getMessage()));
        }
    }

    /**
     * 写一条普通日志
     *
     * @param message 日志内容
     */
    public static void info(String message) {
        write("info", message);
    }

    /**
     * 写一条错误日志
     *
     * @param message 日志内容
     */
    public static void error(String message) {
        write("error", message);
    }

/** 关闭日志文件，之后只写控制台 */
    public static void close() {
        if (file != null) {
            file.flush();
            file.close();
            file = null;
        }
    }

    /**
     * 异常的简短描述：有 message 用 message，否则用类名
     *
     * @param error 异常
     * @return 异常 message；为空时用类名
     */
    public static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    /**
     * 写一行日志到控制台与文件。
     *
     * @param level 级别
     * @param message 内容
     */
    private static void write(String level, String message) {
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        String line = PREFIX + "[" + time + "] [" + level + "] " + message;
        System.out.println(line);
        if (file != null) {
            file.println(line);
        }
    }
}
