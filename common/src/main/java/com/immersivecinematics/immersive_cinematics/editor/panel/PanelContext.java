package com.immersivecinematics.immersive_cinematics.editor.panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 编辑器面板共享上下文（0.3.5 第5轮 5A）。
 * <p>
 * LeftPanelArea 每次 build 时把当前数据与回调打包进 PanelContext，
 * 各 EditorPanel 只依赖本上下文，不反向依赖 LeftPanelArea。
 */
public class PanelContext {

    public JsonObject script;
    public List<String> scriptFileNames = new ArrayList<>();
    public JsonObject selectedClip;
    public JsonObject selectedKeyframe;
    public float totalDuration;
    public String selectedTrackType = "CAMERA";
    public JsonArray tracks;

    public Runnable onDirty;
    public Runnable onRebuild;

    public Consumer<String> onOpenScript;
    public Runnable onNewScript;
    public Consumer<Integer> onTrackSelected;
    public Consumer<JsonObject> onToggleTrackVisible;
}
