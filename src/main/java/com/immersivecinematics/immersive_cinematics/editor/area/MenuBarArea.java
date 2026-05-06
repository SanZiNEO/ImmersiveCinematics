package com.immersivecinematics.immersive_cinematics.editor.area;

import com.immersivecinematics.immersive_cinematics.editor.debug.EditorLogger;
import com.immersivecinematics.immersive_cinematics.editor.widget.*;
import java.util.ArrayList;
import java.util.List;

public class MenuBarArea extends UIComponent {
    private final UILabel titleLabel;
    private final UIButton newBtn;
    private final UIButton saveBtn;
    private final UIButton listBtn;
    private final List<UIComponent> children = new ArrayList<>();

    private String scriptName;
    private Runnable onNewScript;
    private Runnable onSaveScript;
    private Runnable onToggleList;

    public MenuBarArea(int x, int y, int w, int h) {
        super(x, y, w, h);
        EditorLogger.areaRegister(EditorLogger.MENU, "full_area", x, y, w, h);
        scriptName = "Untitled";

        listBtn = new UIButton(x + 4, y + 2, 70, h - 4, "Scripts \u25BC", b -> {
            if (onToggleList != null) onToggleList.run();
        });
        listBtn.color(0x00, 0x443A3A3A).textColor(0xFFAAAAAA);

        titleLabel = new UILabel(0, y + (h - 10) / 2, scriptName, 0xFFBBBBBB);
        titleLabel.centered(true).setBounds(x, y, w, h);

        newBtn = new UIButton(x + w - 128, y + 2, 50, h - 4, "New", b -> {
            if (onNewScript != null) onNewScript.run();
        });
        newBtn.color(0xFF333333, 0xFF444444).textColor(0xFFBBBBBB);

        saveBtn = new UIButton(x + w - 72, y + 2, 50, h - 4, "Save", b -> {
            if (onSaveScript != null) onSaveScript.run();
        });
        saveBtn.color(0xFF333333, 0xFF444444).textColor(0xFFBBBBBB);

        children.add(listBtn);
        children.add(titleLabel);
        children.add(newBtn);
        children.add(saveBtn);
    }

    public void setScriptName(String name) {
        scriptName = name;
        titleLabel.setText(name);
    }

    public void setOnNewScript(Runnable r) { onNewScript = r; }
    public void setOnSaveScript(Runnable r) { onSaveScript = r; }
    public void setOnToggleList(Runnable r) { onToggleList = r; }

    @Override
    public void render(UIContext ctx) {
        ctx.graphics.fill(x, y, x + w, y + h, 0xFF1A1A1A);
        ctx.graphics.fill(x, y + h - 1, x + w, y + h, 0xFF2A2A2A);
        ctx.graphics.renderOutline(x, y, w, h, 0xFF333333);
        for (UIComponent c : children) {
            c.render(ctx);
        }
    }

    @Override
    public boolean mouseClicked(UIContext ctx) {
        EditorLogger.areaHit(EditorLogger.MENU, "full_area", ctx.mouseX, ctx.mouseY, true);
        for (int i = children.size() - 1; i >= 0; i--) {
            UIComponent child = children.get(i);
            if (child.isHovered(ctx) && child instanceof UIButton btn) {
                EditorLogger.action(EditorLogger.MENU, "BUTTON_CLICK", "label=" + btn.getLabel());
            }
            if (child.mouseClicked(ctx)) return true;
        }
        return false;
    }

    @Override
    protected List<UIComponent> getChildren() {
        return children;
    }
}
