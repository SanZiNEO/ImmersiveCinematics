package com.immersivecinematics.immersive_cinematics.webui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.media.video.Ffmpeg;
import com.immersivecinematics.immersive_cinematics.util.ErrorLog;
import net.minecraft.client.Minecraft;

import java.util.Locale;

/**
 * 编辑器预览流配置：输出尺寸档位、帧率、关键帧间隔、是否允许 H.264 编码。
 *
 * <p>尺寸三档：{@link Mode#ORIGINAL} 跟随窗口 framebuffer，{@link Mode#P720} / {@link Mode#P1080}
 * 固定 1280×720 / 1920×1080。帧率决定捕获提交节流（每会话按 {@code 1000/fps} ms 取帧一次）。</p>
 *
 * <p>不可变值对象：改配置走 {@link PreviewStreamer#setConfig(PreviewConfig)}，
 * 由流层重建每会话编码器与新尺寸的捕获器，不改已有实例。</p>
 *
 * @param mode                   输出尺寸档位
 * @param fps                    目标帧率（1..120）
 * @param keyframeIntervalSeconds 关键帧间隔秒（1..10）
 * @param encoderEnabled         是否允许 H.264 编码（ffmpeg 不可用时仍回退 RGBA）
 */
public record PreviewConfig(Mode mode, int fps, int keyframeIntervalSeconds, boolean encoderEnabled) {

    /** 输出尺寸档位。 */
    public enum Mode {
        /** 跟随窗口 framebuffer 尺寸（分辨率随窗口变化）。 */
        ORIGINAL,
        /** 固定 1280×720。 */
        P720,
        /** 固定 1920×1080。 */
        P1080
    }

    public static final int MIN_FPS = 1;
    public static final int MAX_FPS = 120;
    public static final int MIN_KEYFRAME_SECONDS = 1;
    public static final int MAX_KEYFRAME_SECONDS = 10;

    private static final String LOG_CATEGORY = "WebUI";
    private static final String LOG_KEY = "preview-config";

    /** 默认配置：原始尺寸、60fps、2s 关键帧间隔、允许编码。 */
    public static PreviewConfig defaults() {
        return new PreviewConfig(Mode.ORIGINAL, 60, 2, true);
    }

    /**
     * 当前档位的输出宽度（像素）。
     *
     * @param mc 客户端实例；{@code null}（无客户端）时 ORIGINAL 回落 1280
     */
    public int width(Minecraft mc) {
        return switch (mode) {
            case ORIGINAL -> mc != null && mc.getWindow() != null ? mc.getWindow().getWidth() : 1280;
            case P720 -> 1280;
            case P1080 -> 1920;
        };
    }

    /**
     * 当前档位的输出高度（像素）。
     *
     * @param mc 客户端实例；{@code null}（无客户端）时 ORIGINAL 回落 720
     */
    public int height(Minecraft mc) {
        return switch (mode) {
            case ORIGINAL -> mc != null && mc.getWindow() != null ? mc.getWindow().getHeight() : 720;
            case P720 -> 720;
            case P1080 -> 1080;
        };
    }

    /** 线上名称：{@code original} / {@code 720p} / {@code 1080p}。 */
    public String wireName() {
        return switch (mode) {
            case ORIGINAL -> "original";
            case P720 -> "720p";
            case P1080 -> "1080p";
        };
    }

    /**
     * 实际捕获宽度：{@link Mode#ORIGINAL} 且 ffmpeg 不可用时封顶 1280。
     *
     * <p>无编码能力时只能发原始 RGBA，按窗口原尺寸会把带宽打爆；回退侧按 720p 档封顶，
     * 窗口本身更窄时保持原宽（不放大）。其它档位与 {@link #width(Minecraft)} 一致。</p>
     */
    public int captureWidth(Minecraft mc) {
        int width = width(mc);
        if (mode == Mode.ORIGINAL && !Ffmpeg.encoderAvailable()) {
            return Math.min(width, 1280);
        }
        return width;
    }

    /**
     * 实际捕获高度：{@link Mode#ORIGINAL} 且 ffmpeg 不可用时封顶 720。
     *
     * <p>与 {@link #captureWidth(Minecraft)} 成对使用；窗口本身更矮时保持原高（不放大）。
     * 其它档位与 {@link #height(Minecraft)} 一致。</p>
     */
    public int captureHeight(Minecraft mc) {
        int height = height(mc);
        if (mode == Mode.ORIGINAL && !Ffmpeg.encoderAvailable()) {
            return Math.min(height, 720);
        }
        return height;
    }

    /**
     * 从 {@code editor.previewConfig} 的 {@code data} 解析配置。
     *
     * <p>缺字段用默认值；非法值（未知档位、越界帧率/关键帧间隔、非布尔编码开关）逐项回落默认，
     * 并限频记一条 WARN，绝不抛异常。</p>
     *
     * @param data 消息 data 对象；{@code null} 视为全默认
     */
    public static PreviewConfig parse(JsonObject data) {
        PreviewConfig def = defaults();
        if (data == null) {
            return def;
        }
        StringBuilder issues = new StringBuilder();

        Mode mode = def.mode();
        JsonElement modeEl = data.get("mode");
        if (modeEl != null && modeEl.isJsonPrimitive()) {
            Mode parsed = parseMode(modeEl.getAsString());
            if (parsed != null) {
                mode = parsed;
            } else {
                issues.append("mode=").append(modeEl.getAsString()).append(' ');
            }
        }

        int fps = readInt(data.get("fps"), def.fps(), MIN_FPS, MAX_FPS, "fps", issues);
        int keyframeSeconds = readInt(data.get("keyframeIntervalSeconds"), def.keyframeIntervalSeconds(),
                MIN_KEYFRAME_SECONDS, MAX_KEYFRAME_SECONDS, "keyframeIntervalSeconds", issues);

        boolean encoderEnabled = def.encoderEnabled();
        JsonElement encoderEl = data.get("encoder");
        if (encoderEl == null) {
            encoderEl = data.get("encoderEnabled");
        }
        if (encoderEl != null && encoderEl.isJsonPrimitive() && encoderEl.getAsJsonPrimitive().isBoolean()) {
            encoderEnabled = encoderEl.getAsBoolean();
        } else if (encoderEl != null) {
            issues.append("encoder=").append(encoderEl).append(' ');
        }

        if (issues.length() > 0) {
            ErrorLog.warnRateLimited(LOG_CATEGORY, LOG_KEY + "-invalid", 5000L,
                    "预览配置含非法值，已回落默认：" + issues.toString().trim()
                            + "（生效 " + mode + " " + fps + "fps " + keyframeSeconds + "s encoder=" + encoderEnabled + "）");
        }
        return new PreviewConfig(mode, fps, keyframeSeconds, encoderEnabled);
    }

    /**
     * 安全读取有界整数：缺字段静默默认；类型不对 / 越界 / 解析失败记一条问题并回落默认。
     */
    private static int readInt(JsonElement element, int fallback, int min, int max,
                               String name, StringBuilder issues) {
        if (element == null) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            issues.append(name).append('=').append(element).append(' ');
            return fallback;
        }
        try {
            int value = element.getAsInt();
            if (value >= min && value <= max) {
                return value;
            }
            issues.append(name).append('=').append(value).append(' ');
        } catch (RuntimeException e) {
            issues.append(name).append('=').append(element).append(' ');
        }
        return fallback;
    }

    /** 档位字符串 → 枚举；无法识别返回 {@code null}（由调用方回落默认）。 */
    private static Mode parseMode(String raw) {
        if (raw == null) return null;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "original", "native", "window" -> Mode.ORIGINAL;
            case "720p", "p720", "720" -> Mode.P720;
            case "1080p", "p1080", "1080" -> Mode.P1080;
            default -> null;
        };
    }
}
