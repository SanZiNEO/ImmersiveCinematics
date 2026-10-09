package com.immersivecinematics.immersive_cinematics.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 脚本错误日志 — 写游戏目录 {@code logs/immersive_cinematics/script-errors.log}。
 * <p>
 * 用途：脚本运行中的各种报错（解析失败/校验问题/资源缺失/运行时异常）。
 * 控制台 **ERROR 级可见**（模组规范：报错不吞，作者直接看到），同时落盘完整堆栈供深挖。
 */
public final class ErrorLog {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/ErrorLog");
    private static final String DIR = "logs/immersive_cinematics";
    private static final String FILE = "script-errors.log";
    private static final Object LOCK = new Object();
    private static PrintWriter writer;

    private ErrorLog() {}

    /** 限频表：位置标识 → {上次输出时刻 ms, 窗口内被抑制的次数}。 */
    private static final Map<String, long[]> RATE_LIMIT = new ConcurrentHashMap<>();

    /**
     * 限频记录一条错误：同一 {@code key} 两次输出至少间隔 {@code intervalMs}。
     * <p>用于逐帧复现的渲染失败（不限频就会每帧刷屏）；窗口内被抑制的次数合并到下一次输出里，
     * 不丢「一直在失败」这个事实。</p>
     *
     * @param category   分类（控制台 / 文件里的 {@code [category]}）
     * @param key        限频位置标识（不同的失败点用不同 key，互不影响）
     * @param intervalMs 同一 key 的最小输出间隔（毫秒）
     * @param message    失败描述
     */
    public static void logRateLimited(String category, String key, long intervalMs, String message) {
        String merged = rateLimit(key, intervalMs, message);
        if (merged != null) {
            log(category, merged);
        }
    }

    /**
     * 同 {@link #logRateLimited(String, String, long, String)}，另带异常（堆栈写入文件，控制台只打摘要）。
     *
     * @param t 异常（可传 {@code null}）
     */
    public static void logRateLimited(String category, String key, long intervalMs, String message, Throwable t) {
        String merged = rateLimit(key, intervalMs, message);
        if (merged != null) {
            log(category, merged, t);
        }
    }

    /**
     * 限频记账：未到间隔 → 计数抑制并返回 {@code null}；否则返回待输出的文案
     * （带窗口内被抑制的次数）。
     */
    private static String rateLimit(String key, long intervalMs, String message) {
        long now = System.currentTimeMillis();
        long[] state = RATE_LIMIT.computeIfAbsent(key, k -> new long[2]);
        synchronized (state) {
            if (now - state[0] < intervalMs) {
                state[1]++;
                return null;
            }
            long suppressed = state[1];
            state[0] = now;
            state[1] = 0L;
            return suppressed == 0L ? message
                    : message + "（另有 " + suppressed + " 次同类失败未逐条记录）";
        }
    }

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
                // 日志文件都写不了就不强求（控制台 ERROR 已打过；不影响脚本播放）
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
