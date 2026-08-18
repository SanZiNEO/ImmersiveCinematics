package com.immersivecinematics.immersive_cinematics.editor.debug;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Logs raw GLFW input events at the event bus level, BEFORE any Screen receives them.
 * <p>
 * Three monitoring layers:
 * <ol>
 *   <li>Input events (mouse button, key, scroll) via {@code ClientRawInputEvent}</li>
 *   <li>Client tick heartbeat (every 100 ticks) to confirm event bus is alive</li>
 *   <li>Mouse position polling from GLFW on each tick (unthrottled)</li>
 * </ol>
 * <p>
 * Each session writes to {@code logs/input/input-YYYY-MM-DD_HH-MM-SS.log}
 * so we can compare "what GLFW sent" vs. "what the editor received".
 * <p>
 * Registration is done in {@code ClientEventHandler} via Architectury events.
 */
public class RawInputLogger {

    private static PrintWriter writer;
    private static boolean opened;

    // ── Editor-mode gating ───────────────────────────────────────
    private static boolean enabled;
    public static void enable() { enabled = true; resetCounters(); log("[STATE] enabled=true"); }
    public static void disable() { enabled = false; log("[STATE] enabled=false"); }
    public static void close() {
        if (writer != null) {
            log("[STATE] closing");
            writer.close();
            writer = null;
        }
        opened = false;
    }

    // ── Event counters (for stall detection) ─────────────────────
    private static long totalMouseButtons, totalKeys, totalScrolls;
    private static long lastHeartbeatTick;
    private static final int HEARTBEAT_INTERVAL = 100; // ticks (~5s)

    private static void resetCounters() {
        totalMouseButtons = totalKeys = totalScrolls = 0;
        lastHeartbeatTick = 0;
    }

    private static PrintWriter w() {
        if (!opened) {
            opened = true;
            try {
                String ts = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
                new File("logs/input").mkdirs();
                writer = new PrintWriter(new FileWriter("logs/input/input-" + ts + ".log", true));
            } catch (IOException e) {
                writer = null;
                // 调试工具：输入日志文件写不了 → 控制台输出仍是主通道，回退"不落盘"合法（注释记录而非静默）
            }
        }
        return writer;
    }

    private static void log(String line) {
        String ts = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date());
        String full = "[" + ts + "] " + line;
        System.out.println(full);
        PrintWriter pw = w();
        if (pw != null) pw.println(full);
    }

    // ══════════════════════════════════════════════════════════════
    //  1. INPUT EVENTS — called from ClientRawInputEvent
    // ══════════════════════════════════════════════════════════════

    public static void onMouseButton(int button, int action, int modifiers) {
        if (!enabled) return;
        totalMouseButtons++;
        log("[MOUSE] button=" + button + " action=" + action + " mods=" + modifiers
                + " count=" + totalMouseButtons);
    }

    public static void onKeyPress(int key, int scanCode, int action, int modifiers) {
        if (!enabled) return;
        totalKeys++;
        String keyName = GLFW.glfwGetKeyName(key, scanCode);
        log("[KEY] key=" + key + " scancode=" + scanCode + " action=" + action
                + " mods=" + modifiers + " name=" + (keyName != null ? keyName : "?")
                + " count=" + totalKeys);
    }

    public static void onMouseScroll(double delta) {
        if (!enabled) return;
        totalScrolls++;
        log("[SCROLL] delta=" + delta + " count=" + totalScrolls);
    }

    // ══════════════════════════════════════════════════════════════
    //  2. CLIENT TICK — heartbeat + mouse position
    // ══════════════════════════════════════════════════════════════

    public static void onClientTick() {
        if (!enabled) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        long tick = mc.level.getGameTime();
        if (tick - lastHeartbeatTick >= HEARTBEAT_INTERVAL) {
            lastHeartbeatTick = tick;
            log("[HEARTBEAT] tick=" + tick
                    + " mouseButtons=" + totalMouseButtons
                    + " keys=" + totalKeys
                    + " scrolls=" + totalScrolls);
        }

        // Raw mouse position poll from GLFW (unthrottled, for comparing with Screen coords)
        long window = mc.getWindow().getWindow();
        double[] mx = new double[1];
        double[] my = new double[1];
        GLFW.glfwGetCursorPos(window, mx, my);
        log("[POS] glfw=(" + (int) mx[0] + "," + (int) my[0] + ")");
    }
}
