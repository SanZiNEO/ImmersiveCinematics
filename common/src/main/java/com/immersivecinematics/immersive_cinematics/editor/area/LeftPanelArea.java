package com.immersivecinematics.immersive_cinematics.editor.area;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.editor.EditorTheme;
import com.immersivecinematics.immersive_cinematics.editor.debug.EditorLogger;
import com.immersivecinematics.immersive_cinematics.editor.panel.EditorPanel;
import com.immersivecinematics.immersive_cinematics.editor.panel.PanelContext;
import com.immersivecinematics.immersive_cinematics.editor.panel.PanelRegistry;
import com.immersivecinematics.immersive_cinematics.editor.panel.TriggerPanel;
import com.immersivecinematics.immersive_cinematics.editor.widget.*;
import com.immersivecinematics.immersive_cinematics.editor.widget.IFocusable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class LeftPanelArea extends UIComponent {

    public enum PanelMode { SCRIPT_LIST, SCRIPT_PROPERTIES, CLIP_PROPERTIES, KEYFRAME_PROPERTIES, TRACK_LIST, TRIGGER }
    private PanelMode mode = PanelMode.SCRIPT_PROPERTIES;

    private JsonObject script;
    private List<String> scriptFileNames = new ArrayList<>();
    private JsonObject selectedClip;
    private JsonObject selectedKeyframe;
    private float totalDuration;
    private String selectedTrackType = "CAMERA";
    private JsonArray tracks;
    private static final int TAB_HEIGHT = 20;
    private boolean dataDirty = true;
    private long lastBuildTime;

    private Consumer<String> onOpenScript;
    private Consumer<String> onDeleteScript;
    private Runnable onNewScript;
    private Consumer<String> onNameChanged;
    private Consumer<String> onAuthorChanged;
    private Consumer<Integer> onTrackSelected;
    private Consumer<String> onTrackAdd;
    private Consumer<Integer> onTrackDelete;
    private Consumer<String> onDescChanged;
    private Consumer<String> onBehaviorFlag;
    private Runnable onDirty;
    private Consumer<JsonObject> onToggleTrackVisible;

    /** tab 栏独立组件（固定在面板顶部，不随内容滚动；渲染与命中均豁免滚动偏移） */
    private PanelTabBar tabBar;
    /** 可滚动内容容器（位于 Tab 栏下方，承载当前模式面板） */
    private ScrollablePanel content;

    public LeftPanelArea(int x, int y, int w, int h) {
        super(x, y, w, h);
        EditorLogger.areaRegister(EditorLogger.LEFT, "full_area", x, y, w, h);
    }

    public void setMode(PanelMode m) {
        if (this.mode != m || dataDirty) {
            dataDirty = false;
            if (this.mode != m) {
                EditorLogger.areaMode(EditorLogger.LEFT, "mode", this.mode.name(), m.name());
                // 切模式回到面板顶部：scrollY 残留会把新面板内容/固定 tab 滚出可视区（面板"全白"根因）
                if (content != null) content.resetScroll();
            }
            this.mode = m;
            build();
        }
    }
    public PanelMode getMode() { return mode; }

    public void setDirtyCallback(Runnable r) { onDirty = r; }

    public void setData(JsonObject meta, JsonObject clip, JsonObject kf) {
        this.script = meta;
        this.selectedClip = clip;
        this.selectedKeyframe = kf;
        this.dataDirty = true;
    }

    public void setTotalDuration(float d) { totalDuration = d; }
    public void setSelectedTrackType(String t) { selectedTrackType = t; }
    public void setScriptFileNames(List<String> names) { scriptFileNames = names; }
    public void setOnOpenScript(Consumer<String> r) { onOpenScript = r; }
    public void setOnTrackAdd(Consumer<String> r) { onTrackAdd = r; }
    public void setOnTrackDelete(Consumer<Integer> r) { onTrackDelete = r; }

    public void setOnDeleteScript(Consumer<String> r) { onDeleteScript = r; }
    public void setOnNewScript(Runnable r) { onNewScript = r; }
    public void setOnNameChanged(Consumer<String> r) { onNameChanged = r; }
    public void setOnAuthorChanged(Consumer<String> r) { onAuthorChanged = r; }
    public void setOnDescChanged(Consumer<String> r) { onDescChanged = r; }
    public void setOnBehaviorFlag(Consumer<String> r) { onBehaviorFlag = r; }

    public void build() {
        EditorLogger.action(EditorLogger.LEFT, "BUILD", "mode=" + mode);
        clearChildren();
        tabBar = new PanelTabBar(x, y, w, TAB_HEIGHT, mode, this::setMode);
        tabBar.fixedToParent = true;
        addChild(tabBar);
        content = new ScrollablePanel(x, y + TAB_HEIGHT, w, h - TAB_HEIGHT);
        addChild(content);

        EditorPanel panel = PanelRegistry.create(mode);
        panel.setContext(buildContext());
        panel.setBounds(x, content.y + 4, w, Math.max(1, content.h - 4));
        if (panel instanceof TriggerPanel) {
            ((TriggerPanel) panel).setOnTriggerChanged(this::build);
        }
        content.addChild(panel);
        panel.build();
        content.recompute();
    }

    private PanelContext buildContext() {
        PanelContext c = new PanelContext();
        c.script = script;
        c.scriptFileNames = scriptFileNames;
        c.selectedClip = selectedClip;
        c.selectedKeyframe = selectedKeyframe;
        c.totalDuration = totalDuration;
        c.selectedTrackType = selectedTrackType;
        c.tracks = tracks;
        c.onDirty = onDirty;
        c.onRebuild = this::scheduleBuild;
        c.onOpenScript = onOpenScript;
        c.onNewScript = onNewScript;
        c.onTrackSelected = onTrackSelected;
        c.onToggleTrackVisible = onToggleTrackVisible;
        return c;
    }

    // Debounce: skip builds within 150ms to avoid flash on rapid property edits
    private void scheduleBuild() {
        long now = System.currentTimeMillis();
        if (now - lastBuildTime < 150) return;
        lastBuildTime = now;
        build();
    }

    public UIComponent getFocusedInput() {
        return findFocusedInput(getChildren());
    }

    private static UIComponent findFocusedInput(List<UIComponent> list) {
        for (UIComponent c : list) {
            if (c instanceof IFocusable) {
                IFocusable f = (IFocusable) c;
                if (f.isFocused()) return c;
            }
            List<UIComponent> sub = c.getChildren();
            if (sub != null) {
                UIComponent found = findFocusedInput(sub);
                if (found != null) return found;
            }
        }
        return null;
    }

    public void clearTextFocus() {
        clearTextFocus(getChildren());
    }

    private static void clearTextFocus(List<UIComponent> list) {
        for (UIComponent c : list) {
            if (c instanceof IFocusable) ((IFocusable) c).clearFocus();
            List<UIComponent> sub = c.getChildren();
            if (sub != null) clearTextFocus(sub);
        }
    }

    /**
     * 渲染管线：背景 + Tab 栏 + ScrollablePanel 内容 + 外框。
     * 滚动、视口裁剪、滚动条全部由 ScrollablePanel 自管。
     */
    @Override
    public void render(UIContext ctx) {
        if (!visible) return;

        ctx.graphics.fill(x, y, x + w, y + h, EditorTheme.BG_TRACK);
        ctx.graphics.fill(x + w - 1, y, x + w, y + h, EditorTheme.BORDER_DARK);

        // Tab 栏：不透明背景（内容滚动不会穿透）+ 固定按钮（不随内容滚动）
        if (tabBar != null) tabBar.render(ctx);
        if (content != null) content.render(ctx);
        // Tab 栏最后再盖一层（防止滚动内容在视口边缘残留）并重绘按钮
        if (tabBar != null) tabBar.render(ctx);

        ctx.graphics.renderOutline(x, y, w, h, EditorTheme.BORDER);
    }

    @Override
    public void renderOverlay(UIContext ctx) {
        if (tabBar != null) tabBar.renderOverlay(ctx);
        if (content != null) content.renderOverlay(ctx);
    }

    @Override
    protected boolean onClicked(UIContext ctx) {
        if (!ctx.isMouseIn(hitX(), hitY(), w, h)) return false;

        EditorLogger.areaHit(EditorLogger.LEFT, "full_area", ctx.mouseX, ctx.mouseY, true);
        EditorLogger.areaHit(EditorLogger.LEFT, "mode_" + mode.name(), ctx.mouseX, ctx.mouseY, true);

        // 子组件命中已由 UIComponent.mouseClicked 模板统一分发（绝对坐标 + 滚动修正 + 容器裁剪），
        // 这里只保留 toggle 点击日志特判，不再重复分发。
        logToggleHits(getChildren(), ctx);
        return false;
    }

    private void logToggleHits(List<UIComponent> list, UIContext ctx) {
        for (UIComponent c : list) {
            if (c.isHovered(ctx) && c instanceof com.immersivecinematics.immersive_cinematics.editor.widget.UIToggle) {
                com.immersivecinematics.immersive_cinematics.editor.widget.UIToggle tgl =
                        (com.immersivecinematics.immersive_cinematics.editor.widget.UIToggle) c;
                EditorLogger.action(EditorLogger.LEFT, "TOGGLE_CLICK", "label=" + mode + " value=" + !tgl.isOn());
            }
            List<UIComponent> sub = c.getChildren();
            if (sub != null) logToggleHits(sub, ctx);
        }
    }

    public void setTracks(JsonArray t) { this.tracks = t; dataDirty = true; }
    public void setOnTrackSelected(Consumer<Integer> r) { onTrackSelected = r; }
    public void setOnToggleTrackVisible(Consumer<JsonObject> r) { onToggleTrackVisible = r; }
}
