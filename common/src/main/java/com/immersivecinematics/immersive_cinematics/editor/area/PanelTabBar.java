package com.immersivecinematics.immersive_cinematics.editor.area;

import com.immersivecinematics.immersive_cinematics.editor.EditorTheme;
import com.immersivecinematics.immersive_cinematics.editor.widget.UIButton;
import com.immersivecinematics.immersive_cinematics.editor.widget.UIComponent;
import com.immersivecinematics.immersive_cinematics.editor.widget.UIContext;
import net.minecraft.client.resources.language.I18n;

import java.util.ArrayList;
import java.util.List;

/**
 * 面板 Tab 栏（0.3.5 第5轮 5A）：独立组件，位于面板内容区上方，不随内容滚动。
 * 负责：Tab 按钮渲染/命中/切换；背景不透明（内容滚动不穿透）。
 */
public class PanelTabBar extends UIComponent {

    /** Tab 切换回调 */
    public interface Listener {
        void onSelect(LeftPanelArea.PanelMode mode);
    }

    private static final int TAB_GAP = 2;

    private final Listener listener;
    private LeftPanelArea.PanelMode mode;
    private final List<UIComponent> buttons = new ArrayList<>();

    public PanelTabBar(int x, int y, int w, int h, LeftPanelArea.PanelMode initial, Listener listener) {
        super(x, y, w, h);
        this.mode = initial;
        this.listener = listener;
        rebuild();
    }

    public void setMode(LeftPanelArea.PanelMode m) {
        if (this.mode == m) return;
        this.mode = m;
        rebuild();
    }

    public LeftPanelArea.PanelMode getMode() {
        return mode;
    }

    private void rebuild() {
        clearChildren();
        buttons.clear();
        int tabX = x + 2;
        int tabH = h;
        int n = LeftPanelArea.PanelMode.values().length;
        int tabW = (w - 4 - (n - 1) * TAB_GAP) / n;

        for (LeftPanelArea.PanelMode m : LeftPanelArea.PanelMode.values()) {
            String label = switch (m) {
                case SCRIPT_LIST -> I18n.get("editor.tab.list");
                case SCRIPT_PROPERTIES -> I18n.get("editor.tab.properties");
                case CLIP_PROPERTIES -> I18n.get("editor.tab.clip");
                case KEYFRAME_PROPERTIES -> I18n.get("editor.tab.keyframe");
                case TRACK_LIST -> I18n.get("editor.tab.tracks");
                case TRIGGER -> I18n.get("editor.tab.triggers");
            };
            UIButton tab = new UIButton(tabX, y, tabW, tabH, label, btn -> {
                if (listener != null) listener.onSelect(m);
            });
            if (m == mode) {
                tab.color(0xFF333344, 0xFF444455).textColor(0xFFFFFFFF);
            } else {
                tab.color(EditorTheme.BG_WIDGET, EditorTheme.BG_HOVER).textColor(0xFF888888);
            }
            tab.fixedToParent = true;
            addChild(tab);
            buttons.add(tab);
            tabX += tabW + TAB_GAP;
        }
    }

    @Override
    public void render(UIContext ctx) {
        if (!visible) return;
        // 不透明背景：滚动内容不会穿透到 Tab 栏
        ctx.graphics.fill(x, y, x + w, y + h, EditorTheme.BG_TRACK);
        super.render(ctx);
    }

    @Override
    public void renderOverlay(UIContext ctx) {
        if (!visible) return;
        super.renderOverlay(ctx);
    }
}
