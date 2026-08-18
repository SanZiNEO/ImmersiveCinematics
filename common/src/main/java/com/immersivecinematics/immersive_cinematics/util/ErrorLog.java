package com.immersivecinematics.immersive_cinematics.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * 脚本错误日志 — 写游戏目录 {@code logs/immersive_cinematics/script-errors.log}。
 * <p>
 * 用途：脚本运行中的各种报错（解析失败/校验问题/资源缺失/运行时异常）只写文件，
 * 控制台仅 debug 级（默认安静，不打扰玩家、不刷屏）。
 * 作者排查时直接翻日志文件即可，无需开任何开关。
 */
public final class ErrorLog {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/ErrorLog");
    private static final String DIR = "logs/immersive_cinematics";
    private static final String FILE = "script-errors.log";
    private static final Object LOCK = new Object();
    private static PrintWriter writer;

    private ErrorLog() {}

    /** 记录一条脚本错误：控制台 ERROR + 日志文件追加（线程安全） */
    public static void log(String category, String message) {
        LOGGER.error("[{}] {}", category, message);
        synchronized (LOCK) {
            try {
                ensureWriter();
                writer.printf("%1$tF %1$tT [%2$s] %3$s%n", System.currentTimeMillis(), category, message);
                writer.flush();
            } catch (IOException ignored) {
                // 日志文件都写不了就不强求（不影响脚本播放）
            }
        }
    }

    /** 记录一条带异常的脚本错误（堆栈写入文件，控制台 ERROR 可见） */
    public static void log(String category, String message, Throwable t) {
        LOGGER.error("[{}] {}: {}", category, message, t != null ? t.getClass().getSimpleName() + " " + t.getMessage() : "");
        synchronized (LOCK) {
            try {
                ensureWriter();
                writer.printf("%1$tF %1$tT [%2$s] %3$s%n", System.currentTimeMillis(), category, message);
                if (t != null) {
                    t.printStackTrace(writer);
                }
                writer.flush();
            } catch (IOException ignored) {
            }
        }
    }

    private static void ensureWriter() throws IOException {
        if (writer == null) {
            File dir = new File(DIR);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("cannot create " + dir);
            }
            writer = new PrintWriter(new FileWriter(new File(dir, FILE), true), false);
        }
    }
}
