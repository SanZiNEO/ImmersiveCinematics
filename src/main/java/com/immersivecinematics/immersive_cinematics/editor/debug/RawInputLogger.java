package com.immersivecinematics.immersive_cinematics.editor.debug;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Logs raw GLFW input events at the Forge event bus level,
 * BEFORE any Screen receives them.
 *
 * Three monitoring layers:
 *   1. Input events (mouse button, key, scroll) via @SubscribeEvent
 *   2. Client tick heartbeat (every 100 ticks) to confirm event bus is alive
 *   3. Mouse position polling from GLFW on each tick (unthrottled)
 *
 * Each session writes to logs/input/input-YYYY-MM-DD_HH-MM-SS.log
 * so we can compare "what GLFW sent" vs. "what the editor received".
 */
@Mod.EventBusSubscriber(
        modid = com.immersivecinematics.immersive_cinematics.ImmersiveCinematics.MODID,
        bus = Mod.EventBusSubscriber.Bus.FORGE,
        value = Dist.CLIENT
)
public class RawInputLogger {

    private static PrintWriter writer;
    private static boolean opened;

    // ── Editor-mode gating ───────────────────────────────────────
    private static boolean enabled;
    public static void enable() { enabled = true; resetCounters(); log("[STATE] enabled=true"); }
    public static void disable() { enabled = false; log("[STATE] enabled=false"); }

    // ── Event counters (for stall detection) ─────────────────────
    private static long totalMouseButtons, totalKeys, totalScrolls;
    private static long lastHeartbeatTick;
    private static final int HEARTBEAT_INTERVAL = 100; // ticks (~5s)

    private static void resetCounters() {
        totalMouseButtons = totalKeys = totalScrolls = 0;
        lastHeartbeatTick = 0;
    }

    private static PrintWriter w() {
        if (writer == null && !opened) {
            opened = true;
            try {
                String ts = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
                File dir = new File("logs/input");
                dir.mkdirs();
                writer = new PrintWriter(new FileWriter(new File(dir, "input-" + ts + ".log"), true), true);
                log("[INIT] RawInputLogger started");
                log("[HEARTBEAT] interval=" + HEARTBEAT_INTERVAL + " ticks");
            } catch (IOException e) {
                System.err.println("[RAW-INPUT] Failed to open log: " + e.getMessage());
            }
        }
        return writer;
    }

    private static void log(String line) {
        String ts = String.format("%1$tH:%1$tM:%1$tS.%1$tL", System.currentTimeMillis());
        String out = ts + " " + line;
        PrintWriter pw = w();
        if (pw != null) pw.println(out);
        System.out.println("[RAW-INPUT] " + out);
    }

    // ══════════════════════════════════════════════════════════════
    //  1. INPUT EVENTS
    // ══════════════════════════════════════════════════════════════

    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton event) {
        if (!enabled) return;
        totalMouseButtons++;
        String action = switch (event.getAction()) {
            case 0 -> "UP";
            case 1 -> "DOWN";
            default -> "REPEAT";
        };
        log("[MOUSE] button=" + event.getButton()
                + " action=" + action
                + " mods=" + event.getModifiers()
                + " total=" + totalMouseButtons);
    }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        if (!enabled) return;
        totalKeys++;
        String action = switch (event.getAction()) {
            case 0 -> "UP";
            case 1 -> "DOWN";
            default -> "REPEAT";
        };
        log("[KEY]  key=" + event.getKey()
                + " scancode=" + event.getScanCode()
                + " action=" + action
                + " mods=" + event.getModifiers()
                + " total=" + totalKeys);
    }

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        if (!enabled) return;
        totalScrolls++;
        double delta = event.getScrollDelta();
        log("[SCRL] delta=" + String.format("%.1f", delta)
                + " total=" + totalScrolls);
    }

    // ══════════════════════════════════════════════════════════════
    //  2. CLIENT TICK — heartbeat + mouse position
    // ══════════════════════════════════════════════════════════════

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (!enabled || event.phase != TickEvent.Phase.END) return;

        // ── Heartbeat ────────────────────────────────────────────
        long now = System.currentTimeMillis();
        if (now - lastHeartbeatTick >= HEARTBEAT_INTERVAL * 50L) {
            lastHeartbeatTick = now;

            // Poll current mouse position from GLFW
            long window = GLFW.glfwGetCurrentContext();
            double mx = 0, my = 0;
            if (window != 0) {
                double[] mxBuf = new double[1], myBuf = new double[1];
                GLFW.glfwGetCursorPos(window, mxBuf, myBuf);
                mx = mxBuf[0];
                my = myBuf[0];
            }

            log("[HEARTBEAT] alive"
                    + " mouse=(" + String.format("%.0f", mx) + "," + String.format("%.0f", my) + ")"
                    + " counters=(btn=" + totalMouseButtons + " key=" + totalKeys + " scroll=" + totalScrolls + ")"
                    + " tick=" + (now / 50));
        }
    }
}
