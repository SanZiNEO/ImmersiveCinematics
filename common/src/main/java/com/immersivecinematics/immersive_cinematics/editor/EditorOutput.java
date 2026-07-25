package com.immersivecinematics.immersive_cinematics.editor;

import com.immersivecinematics.immersive_cinematics.editor.debug.EditorLogger;

/**
 * Standalone output dispatcher for the editor.
 *
 * The editor records user intent (time change, script push, play/pause/stop)
 * and feeds it here. This component owns when and how to actually send data
 * to the bridge — throttled, on the client tick, never inside a UI event.
 *
 * Usage:
 *   EditorOutput output = new EditorOutput(bridge);
 *   // ... editor calls output.setTime(t), output.pushScript(json), etc.
 *   // Every client tick: output.tick();
 */
public class EditorOutput {

    private final EditorBridge bridge;
    private static final long TIME_THROTTLE_MS = 50;
    private static final long SCRIPT_THROTTLE_MS = 200;

    // ── Pending intents ──────────────────────────────────────────
    private float pendingTime = -1;
    private long lastTimeSend;
    private String pendingScriptJson;
    private long lastScriptSend;
    private boolean pendingPlay, pendingPause, pendingStop;

    public EditorOutput(EditorBridge bridge) {
        this.bridge = bridge;
    }

    /** Called from editor when user drags or clicks the playhead. */
    public void setTime(float seconds) {
        pendingTime = seconds;
    }

    /** Called from editor Save button. */
    public void pushScript(String jsonContent) {
        pendingScriptJson = jsonContent;
    }

    /**
     * Called on every edit operation to push the latest script + time to the bridge.
     * Script pushing is throttled to avoid excessive re-parsing during drag operations.
     */
    public void markDirty(String jsonContent, float time) {
        pendingScriptJson = jsonContent;
        pendingTime = time;
    }

    /** Called from editor Play button. */
    public void play() {
        pendingPlay = true;
    }

    /** Called from editor Pause button. */
    public void pause() {
        pendingPause = true;
    }

    /** Called from editor Stop button. */
    public void stop() {
        pendingStop = true;
    }

    /**
     * Called every client tick (END phase).
     * Dispatches pending intents with throttling.
     */
    public void tick() {
        long now = System.currentTimeMillis();

        // Throttled time update
        if (pendingTime >= 0 && now - lastTimeSend >= TIME_THROTTLE_MS) {
            bridge.setTime(pendingTime);
            EditorLogger.action(EditorLogger.SCREEN, "OUTPUT_SET_TIME",
                    String.format("%.3f", pendingTime));
            pendingTime = -1;
            lastTimeSend = now;
        }

        // Throttled script push
        if (pendingScriptJson != null && now - lastScriptSend >= SCRIPT_THROTTLE_MS) {
            bridge.pushScript(pendingScriptJson);
            EditorLogger.action(EditorLogger.SCREEN, "OUTPUT_PUSH_SCRIPT",
                    "len=" + pendingScriptJson.length());
            pendingScriptJson = null;
            lastScriptSend = now;
        }

        // One-shot intents
        if (pendingPlay) {
            bridge.play();
            EditorLogger.action(EditorLogger.SCREEN, "OUTPUT_PLAY", "");
            pendingPlay = false;
        }
        if (pendingPause) {
            bridge.pause();
            EditorLogger.action(EditorLogger.SCREEN, "OUTPUT_PAUSE", "");
            pendingPause = false;
        }
        if (pendingStop) {
            bridge.stop();
            EditorLogger.action(EditorLogger.SCREEN, "OUTPUT_STOP", "");
            pendingStop = false;
        }
    }
}
