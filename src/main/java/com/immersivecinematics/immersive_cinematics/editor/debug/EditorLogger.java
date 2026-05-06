package com.immersivecinematics.immersive_cinematics.editor.debug;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Centralised debug logger for the editor module.
 *
 * Each session writes to its own file:
 *   logs/editor/editor-YYYY-MM-DD_HH-MM-SS.log
 *
 * Also echoes to System.out for in-game console visibility.
 *
 * Four log categories:
 *   1. AREA   — hit-testing and focus
 *   2. MOUSE  — raw mouse events
 *   3. ACTION — button clicks, keyframe ops, timeline scrub
 *   4. STATE  — dirty flags, sync points, render frames
 */
public class EditorLogger {

    // ── Component names ──────────────────────────────────────────
    public static final String SCREEN     = "EditorScreen";
    public static final String TIMELINE   = "TimelineArea";
    public static final String LEFT       = "LeftPanelArea";
    public static final String PREVIEW    = "PreviewArea";
    public static final String MENU       = "MenuBarArea";

    // ── Constants ────────────────────────────────────────────────
    private static final String PREFIX = "[EDITOR-DEBUG]";
    private static final Map<String, Long> throttleMap = new HashMap<>();
    private static PrintWriter fileOut;
    private static int frameCounter;

    // ── File initialisation (lazy, per-session file) ─────────────
    private static PrintWriter getWriter() {
        if (fileOut == null) {
            try {
                String ts = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
                File dir = new File("logs/editor");
                dir.mkdirs();
                File f = new File(dir, "editor-" + ts + ".log");
                fileOut = new PrintWriter(new FileWriter(f, true), true);
                System.out.println(PREFIX + " Logging to " + f.getAbsolutePath());
            } catch (IOException e) {
                System.err.println(PREFIX + " Failed to open log file: " + e.getMessage());
            }
        }
        return fileOut;
    }

    /** Call this from EditorScreen.onClose() to finalise and close. */
    public static void close() {
        if (fileOut != null) {
            fileOut.println("=== end ===");
            fileOut.close();
            fileOut = null;
        }
        throttleMap.clear();
    }

    // ── Internal helpers ─────────────────────────────────────────
    private static String ts() {
        return String.format("%1$tH:%1$tM:%1$tS.%1$tL", System.currentTimeMillis());
    }

    private static void out(String component, String cat, String msg) {
        String line = ts() + " [" + component + "] [" + cat + "] " + msg;
        System.out.println(PREFIX + " " + line);
        PrintWriter w = getWriter();
        if (w != null) {
            w.println(line);
        }
    }

    /**
     * Returns true if this key was logged less than {@code minIntervalMs} ago.
     * Callers skip their log line when this returns true.
     */
    public static boolean throttle(String key, long minIntervalMs) {
        long now = System.currentTimeMillis();
        Long prev = throttleMap.get(key);
        if (prev == null || now - prev >= minIntervalMs) {
            throttleMap.put(key, now);
            return false;
        }
        return true;
    }

    // ══════════════════════════════════════════════════════════════
    //  CATEGORY 1 – Area hit-testing & focus
    // ══════════════════════════════════════════════════════════════

    public static void areaRegister(String component, String areaName,
                                    int x, int y, int w, int h) {
        out(component, "AREA", "REGISTER name=" + areaName
                + " bounds=(" + x + "," + y + "," + w + "," + h + ")");
    }

    public static void areaHit(String component, String areaName,
                               int mx, int my, boolean hit) {
        out(component, "AREA", "HIT name=" + areaName
                + " mouse=(" + mx + "," + my + ") hit=" + hit);
    }

    public static void areaNoHit(String component, int mx, int my) {
        out(component, "AREA", "NO_HIT mouse=(" + mx + "," + my + ")");
    }

    public static void areaMode(String component, String areaName,
                                String oldMode, String newMode) {
        out(component, "AREA", "MODE name=" + areaName
                + " from=" + oldMode + " to=" + newMode);
    }

    // ══════════════════════════════════════════════════════════════
    //  CATEGORY 2 – Raw mouse events
    // ══════════════════════════════════════════════════════════════

    public static void mousePressed(String component, int button,
                                    int mx, int my, String activeArea) {
        out(component, "MOUSE", "PRESS button=" + button
                + " pos=(" + mx + "," + my + ") area=" + activeArea);
    }

    public static void mouseReleased(String component, int button,
                                     int mx, int my,
                                     int startX, int startY, String activeArea) {
        out(component, "MOUSE", "RELEASE button=" + button
                + " pos=(" + mx + "," + my + ")"
                + " start=(" + startX + "," + startY + ")"
                + " area=" + activeArea);
    }

    public static void mouseMove(String component, int mx, int my,
                                 String activeArea, boolean dragging) {
        if (throttle("mouseMove", 1000)) return;
        out(component, "MOUSE", "MOVE pos=(" + mx + "," + my + ")"
                + " area=" + activeArea + " dragging=" + dragging);
    }

    public static void mouseMove(String component, int mx, int my,
                                 String activeArea, boolean dragging, String detail) {
        if (throttle("mouseMove_detail", 250)) return;
        out(component, "MOUSE", "MOVE pos=(" + mx + "," + my + ")"
                + " area=" + activeArea + " dragging=" + dragging + " " + detail);
    }

    public static void mouseDrag(String component, int mx, int my,
                                 String target, float currentValue) {
        out(component, "MOUSE", "DRAG pos=(" + mx + "," + my + ")"
                + " target=" + target + " value=" + currentValue);
    }

    public static void mouseConsumedBy(String component, String areaName, boolean consumed) {
        out(component, "MOUSE", "CONSUMED_BY area=" + areaName + " consumed=" + consumed);
    }

    // ══════════════════════════════════════════════════════════════
    //  CATEGORY 3 – Core interaction actions
    // ══════════════════════════════════════════════════════════════

    public static void action(String component, String action, String detail) {
        out(component, "ACTION", "name=" + action + " " + detail);
    }

    public static void playhead(String component, float time, float pixel,
                                String reason) {
        out(component, "ACTION", "PLAYHEAD time=" + time
                + " pixel=" + pixel + " reason=" + reason);
    }

    public static void scrubSession(String component, float time, float pixel,
                                    String state) {
        out(component, "ACTION", "SCRUB state=" + state
                + " time=" + time + " pixel=" + pixel);
    }

    // ══════════════════════════════════════════════════════════════
    //  CATEGORY 4 – Internal state & sync
    // ══════════════════════════════════════════════════════════════

    public static void state(String component, String field,
                             Object before, Object after) {
        out(component, "STATE", "field=" + field
                + " before=" + before + " after=" + after);
    }

    public static void dirty(String component, String flag, boolean value) {
        out(component, "STATE", "DIRTY flag=" + flag + "=" + value);
    }

    public static void sync(String component, String target, String detail) {
        out(component, "STATE", "SYNC target=" + target + " " + detail);
    }

    public static void renderTick(String component) {
        frameCounter++;
        if (frameCounter % 60 == 0) {
            out(component, "STATE", "RENDER frame=" + frameCounter);
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  NEW: Keyboard / scroll / focus-transition / data-sync helpers
    // ══════════════════════════════════════════════════════════════

    public static void mouseScroll(String component, double scroll,
                                   int mx, int my, String area) {
        out(component, "MOUSE", "SCROLL delta=" + String.format("%.0f", scroll)
                + " pos=(" + mx + "," + my + ") area=" + area);
    }

    public static void keyPress(String component, String keyName,
                                int keyCode, String detail) {
        out(component, "ACTION", "KEY name=" + keyName
                + " code=" + keyCode + " " + detail);
    }

    public static void dataSync(String component, String field,
                                float dataValue, float uiValue) {
        out(component, "STATE", "DATASYNC " + field
                + " data=" + String.format("%.3f", dataValue)
                + " ui=" + String.format("%.3f", uiValue));
    }

    public static void focusChange(String component,
                                   String fromArea, String toArea, int mx, int my) {
        out(component, "AREA", "FOCUS from=" + fromArea
                + " to=" + toArea + " mouse=(" + mx + "," + my + ")");
    }

    /** Log a marker line, e.g. "=== PLAY START ===" */
    public static void marker(String component, String msg) {
        out(component, "STATE", "MARKER " + msg);
    }

    /** Log the boundaries of every active area for diagnosing no-hit cases. */
    public static void areaBoundaries(String component, String areasSummary) {
        out(component, "AREA", "BOUNDARIES " + areasSummary);
    }

    // ══════════════════════════════════════════════════════════════
    //  ERROR / ALIVE helpers
    // ══════════════════════════════════════════════════════════════

    /** Log an exception with full stack trace. Call from catch blocks. */
    public static void error(String component, String context, Throwable t) {
        out(component, "ERROR", context + " exception=" + t.getClass().getSimpleName()
                + " msg=" + (t.getMessage() != null ? t.getMessage() : "null"));
        // Write stack trace to log file
        PrintWriter pw = getWriter();
        if (pw != null) {
            t.printStackTrace(pw);
            pw.flush();
        }
        // Also print to console for immediate visibility
        System.err.println(PREFIX + " [" + component + "] [ERROR] " + context);
        t.printStackTrace();
    }

    /** Direct output to log file + console (bypasses category formatting). */
    public static void outRaw(String component, String cat, String msg) {
        out(component, cat, msg);
    }

    /** Periodic alive ping — call from render loop every ~60 frames. */
    public static void alive(String component, String detail) {
        if (throttle("alive", 5000)) return;
        out(component, "STATE", "ALIVE " + detail);
    }
}
