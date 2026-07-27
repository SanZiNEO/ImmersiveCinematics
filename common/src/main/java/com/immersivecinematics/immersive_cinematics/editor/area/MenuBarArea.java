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

        int gap = (int)(4 * com.immersivecinematics.immersive_cinematics.editor.Scale.sx);
        int textH = (int)(12 * com.immersivecinematics.immersive_cinematics.editor.Scale.sy);
        int btnH = (int)(16 * com.immersivecinematics.immersive_cinematics.editor.Scale.sy);
        int by = y + (h - btnH) / 2;

        titleLabel = new UILabel(x + gap + 4, y + (h - textH) / 2, I18n.get("editor.title.cinematic_editor"), 0xFFCCCCCC);
        newBtn = new UIButton(x + (int)(120 * com.immersivecinematics.immersive_cinematics.editor.Scale.sx), by, (int)(52 * com.immersivecinematics.immersive_cinematics.editor.Scale.sx), btnH,
                I18n.get("editor.action.new_script_short"), b -> { if (onNewScript != null) onNewScript.run(); });
        newBtn.color(0xFF333333, 0xFF444444);
        saveBtn = new UIButton(newBtn.x + newBtn.w + gap, by, (int)(52 * com.immersivecinematics.immersive_cinematics.editor.Scale.sx), btnH,
                I18n.get("editor.action.save_short"), b -> { if (onSaveScript != null) onSaveScript.run(); });
        saveBtn.color(0xFF333333, 0xFF444444);
        listBtn = new UIButton(saveBtn.x + saveBtn.w + gap, by, (int)(52 * com.immersivecinematics.immersive_cinematics.editor.Scale.sx), btnH,
                I18n.get("editor.action.list_short"), b -> { if (onToggleList != null) onToggleList.run(); });
        listBtn.color(0xFF333333, 0xFF444444);

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
        ctx.graphics.fill(x, y, x + w, y + h, 0xFF1F1F1F);
        ctx.graphics.fill(x, y + h - 1, x + w, y + h, 0xFF333333);

        titleLabel.setText(getDisplayTitle());
        titleLabel.render(ctx);

        newBtn.render(ctx);
        saveBtn.render(ctx);
        listBtn.render(ctx);

        int sx = listBtn.x + listBtn.w + (int)(12 * com.immersivecinematics.immersive_cinematics.editor.Scale.sx);
        if (statusText != null) {
            ctx.graphics.drawString(ctx.font, statusText, sx, y + (h - 8) / 2, statusColor);
            sx += ctx.font.width(statusText) + (int)(8 * com.immersivecinematics.immersive_cinematics.editor.Scale.sx);
        }
        if (actionText != null && System.currentTimeMillis() - actionTime < 3000) {
            ctx.graphics.drawString(ctx.font, actionText, sx, y + (h - 8) / 2, 0xFF88AA88);
        }
    }
}
