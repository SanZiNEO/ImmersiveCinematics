package com.immersivecinematics.immersive_cinematics.editor.area;

import com.immersivecinematics.immersive_cinematics.editor.EditorTheme;
import com.immersivecinematics.immersive_cinematics.editor.debug.EditorLogger;
import com.immersivecinematics.immersive_cinematics.editor.widget.*;
import net.minecraft.client.resources.language.I18n;

public class MenuBarArea extends UIComponent {
    // B2：布局常量（值不变，表达式保留 Scale）
    private static final int GAP = (int)(4 * com.immersivecinematics.immersive_cinematics.editor.Scale.sx);
    private static final int TEXT_H = (int)(12 * com.immersivecinematics.immersive_cinematics.editor.Scale.sy);
    private static final int BTN_H = (int)(16 * com.immersivecinematics.immersive_cinematics.editor.Scale.sy);
    private static final int BTN_X_OFFSET = (int)(120 * com.immersivecinematics.immersive_cinematics.editor.Scale.sx);
    private static final int BTN_W = (int)(52 * com.immersivecinematics.immersive_cinematics.editor.Scale.sx);
    private static final int STATUS_GAP = (int)(12 * com.immersivecinematics.immersive_cinematics.editor.Scale.sx);
    private static final int STATUS_ACTION_GAP = (int)(8 * com.immersivecinematics.immersive_cinematics.editor.Scale.sx);
    private static final long ACTION_TTL_MS = 3000;

    private final UILabel titleLabel;
    private final UIButton newBtn;
    private final UIButton saveBtn;
    private final UIButton listBtn;
    private String scriptName;

    private String statusText = I18n.get("editor.status.ready");
    private int statusColor = 0xFF888888;
    private String actionText;
    private long actionTime;
    private Runnable onNewScript;
    private Runnable onSaveScript;
    private Runnable onToggleList;

    public MenuBarArea(int x, int y, int w, int h) {
        super(x, y, w, h);
        EditorLogger.areaRegister(EditorLogger.MENU, "full_area", x, y, w, h);

        int by = y + (h - BTN_H) / 2;

        titleLabel = new UILabel(x + GAP + 4, y + (h - TEXT_H) / 2, I18n.get("editor.title.cinematic_editor"), EditorTheme.TEXT_PRIMARY);
        newBtn = new UIButton(x + BTN_X_OFFSET, by, BTN_W, BTN_H,
                I18n.get("editor.action.new_script_short"), b -> { if (onNewScript != null) onNewScript.run(); });
        newBtn.color(EditorTheme.BG_WIDGET, EditorTheme.BG_HOVER);
        saveBtn = new UIButton(newBtn.x + newBtn.w + GAP, by, BTN_W, BTN_H,
                I18n.get("editor.action.save_short"), b -> { if (onSaveScript != null) onSaveScript.run(); });
        saveBtn.color(EditorTheme.BG_WIDGET, EditorTheme.BG_HOVER);
        listBtn = new UIButton(saveBtn.x + saveBtn.w + GAP, by, BTN_W, BTN_H,
                I18n.get("editor.action.list_short"), b -> { if (onToggleList != null) onToggleList.run(); });
        listBtn.color(EditorTheme.BG_WIDGET, EditorTheme.BG_HOVER);

        addChild(titleLabel);
        addChild(newBtn);
        addChild(saveBtn);
        addChild(listBtn);
    }

    public void setScriptName(String name) {
        this.scriptName = name;
    }

    public void setStatus(String text, int color) {
        this.statusText = text;
        this.statusColor = color;
    }

    public void setAction(String text) {
        this.actionText = text;
        this.actionTime = System.currentTimeMillis();
    }

    public void setOnNewScript(Runnable r) { onNewScript = r; }
    public void setOnSaveScript(Runnable r) { onSaveScript = r; }
    public void setOnToggleList(Runnable r) { onToggleList = r; }

    private String getDisplayTitle() {
        if (scriptName != null && !scriptName.isEmpty()) return scriptName;
        return titleLabel.getText();
    }

    @Override
    public void renderContent(UIContext ctx) {
        ctx.graphics.fill(x, y, x + w, y + h, EditorTheme.BG_PANEL);
        ctx.graphics.fill(x, y + h - 1, x + w, y + h, EditorTheme.BORDER);

        titleLabel.setText(getDisplayTitle());

        int sx = listBtn.x + listBtn.w + STATUS_GAP;
        if (statusText != null) {
            ctx.graphics.drawString(ctx.font, statusText, sx, y + (h - 8) / 2, statusColor);
            sx += ctx.font.width(statusText) + STATUS_ACTION_GAP;
        }
        // E6：action 文字最后 500ms 渐隐
        long remain = ACTION_TTL_MS - (System.currentTimeMillis() - actionTime);
        if (actionText != null && remain > 0) {
            int alpha = (int)(Math.min(1f, remain / 500f) * 255);
            int base = 0xFF88AA88;
            ctx.graphics.drawString(ctx.font, actionText, sx, y + (h - 8) / 2, (base & 0x00FFFFFF) | (alpha << 24));
        }
    }
}
