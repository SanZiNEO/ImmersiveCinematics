package com.immersivecinematics.immersive_cinematics.editor.area;

import com.immersivecinematics.immersive_cinematics.editor.debug.EditorLogger;
import com.immersivecinematics.immersive_cinematics.editor.widget.*;
import net.minecraft.client.resources.language.I18n;
import java.util.ArrayList;
import java.util.List;

public class MenuBarArea extends UIComponent {
    private final UILabel titleLabel;
    private final UIButton newBtn;
    private final UIButton saveBtn;
    private final UIButton listBtn;
    private final List<UIComponent> children = new ArrayList<>();

    private String scriptName;
    private String statusText = "Ready";
    private int statusColor = 0xFF888888;
    private String actionText;
    private long actionTime;
    private Runnable onNewScript;
    private Runnable onSaveScript;
    private Runnable onToggleList;

    public MenuBarArea(int x, int y, int w, int h) {
        super(x, y, w, h);
        EditorLogger.areaRegister(EditorLogger.MENU, "full_area", x, y, w, h);
        scriptName = "Untitled";

        int px = (int)(4 * com.immersivecinematics.immersive_cinematics.editor.EditorScreen.sx);
        int py = (int)(2 * com.immersivecinematics.immersive_cinematics.editor.EditorScreen.sy);
        int bw = (int)(70 * com.immersivecinematics.immersive_cinematics.editor.EditorScreen.sx);
        int bh = h - (int)(4 * com.immersivecinematics.immersive_cinematics.editor.EditorScreen.sy);
        listBtn = new UIButton(x + px, y + py, bw, bh, I18n.get("editor.menu.script_list"), b -> {
            if (onToggleList != null) onToggleList.run();
        });
        listBtn.color(0x00, 0x443A3A3A).textColor(0xFFAAAAAA);

        titleLabel = new UILabel(0, y + (h - 10) / 2, scriptName, 0xFFBBBBBB);
        titleLabel.centered(true).setBounds(x, y, w, h);

        int newBtnW = (int)(50 * com.immersivecinematics.immersive_cinematics.editor.EditorScreen.sx);
        int saveBtnW = (int)(50 * com.immersivecinematics.immersive_cinematics.editor.EditorScreen.sx);
        int rightGap = (int)(4 * com.immersivecinematics.immersive_cinematics.editor.EditorScreen.sx);
        newBtn = new UIButton(x + w - (int)(128 * com.immersivecinematics.immersive_cinematics.editor.EditorScreen.sx), y + py, newBtnW, bh, I18n.get("editor.menu.new"), b -> {
            if (onNewScript != null) onNewScript.run();
        });
        newBtn.color(0xFF333333, 0xFF444444).textColor(0xFFBBBBBB);

        saveBtn = new UIButton(x + w - (int)(72 * com.immersivecinematics.immersive_cinematics.editor.EditorScreen.sx), y + py, saveBtnW, bh, I18n.get("editor.menu.save"), b -> {
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

    public void setStatus(String text, int color) {
        statusText = text;
        statusColor = color;
    }

    public void setAction(String text) {
        actionText = text;
        actionTime = System.currentTimeMillis();
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

        long now = System.currentTimeMillis();
        boolean showAction = actionText != null && now - actionTime < 5000;

        if (showAction) {
            int tw = ctx.font.width(actionText);
            int right = x + w - (int)(205 * com.immersivecinematics.immersive_cinematics.editor.EditorScreen.sx);
            ctx.graphics.drawString(ctx.font, actionText, right - tw, y + (h - 10) / 2, 0xFF888888);
        }

        if (statusText != null) {
            int tw = ctx.font.width(statusText);
            int right = showAction ? x + w - (int)(140 * com.immersivecinematics.immersive_cinematics.editor.EditorScreen.sx) : x + w - (int)(205 * com.immersivecinematics.immersive_cinematics.editor.EditorScreen.sx);
            ctx.graphics.drawString(ctx.font, statusText, Math.max(x + (int)(80 * com.immersivecinematics.immersive_cinematics.editor.EditorScreen.sx), right - tw), y + (h - 10) / 2, statusColor);
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
    public List<UIComponent> getChildren() {
        return children;
    }
}
