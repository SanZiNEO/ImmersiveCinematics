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

        // ===== 布局常量（提取魔法数） =====
        int BTN_PADDING_X = 4;    // 按钮到左右边缘的水平间距
        int BTN_PADDING_Y = 2;    // 按钮到上下边缘的垂直间距
        int LIST_BTN_W = 70;      // 脚本列表按钮宽度
        int BTN_BOTTOM_MARGIN = 4; // 按钮底部到菜单栏底部的间距
        int NEW_BTN_W = 50;       // 新建按钮宽度
        int SAVE_BTN_W = 50;      // 保存按钮宽度
        int NEW_BTN_RIGHT = 128;  // 新建按钮到右边缘的距离
        int SAVE_BTN_RIGHT = 72;  // 保存按钮到右边缘的距离

        int px = (int)(BTN_PADDING_X * com.immersivecinematics.immersive_cinematics.editor.Scale.sx);
        int py = (int)(BTN_PADDING_Y * com.immersivecinematics.immersive_cinematics.editor.Scale.sy);
        int listBtnW = (int)(LIST_BTN_W * com.immersivecinematics.immersive_cinematics.editor.Scale.sx);
        int listBtnH = h - (int)(BTN_BOTTOM_MARGIN * com.immersivecinematics.immersive_cinematics.editor.Scale.sy);
        listBtn = new UIButton(x + px, y + py, listBtnW, listBtnH, I18n.get("editor.menu.script_list"), b -> {
            if (onToggleList != null) onToggleList.run();
        });
        listBtn.color(0x00, 0x443A3A3A).textColor(0xFFAAAAAA);

        titleLabel = new UILabel(0, y + (h - 10) / 2, scriptName, 0xFFBBBBBB);
        titleLabel.centered(true).setBounds(x, y, w, h);

        int newBtnW = (int)(NEW_BTN_W * com.immersivecinematics.immersive_cinematics.editor.Scale.sx);
        int saveBtnW = (int)(SAVE_BTN_W * com.immersivecinematics.immersive_cinematics.editor.Scale.sx);
        newBtn = new UIButton(x + w - (int)(NEW_BTN_RIGHT * com.immersivecinematics.immersive_cinematics.editor.Scale.sx), y + py, newBtnW, listBtnH, I18n.get("editor.menu.new"), b -> {
            if (onNewScript != null) onNewScript.run();
        });
        newBtn.color(0xFF333333, 0xFF444444).textColor(0xFFBBBBBB);

        saveBtn = new UIButton(x + w - (int)(SAVE_BTN_RIGHT * com.immersivecinematics.immersive_cinematics.editor.Scale.sx), y + py, saveBtnW, listBtnH, I18n.get("editor.menu.save"), b -> {
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

        // 渲染常量（提取魔法数）
        int STATUS_RIGHT_MARGIN = 205;   // 状态文本到右边缘的距离
        int ACTION_RIGHT_MARGIN = 140;   // 有 action 时状态文本的压缩右距
        int STATUS_LEFT_MIN = 80;        // 状态文本最小左边界（不与标题重叠）

        if (showAction) {
            int tw = ctx.font.width(actionText);
            int right = x + w - (int)(STATUS_RIGHT_MARGIN * com.immersivecinematics.immersive_cinematics.editor.Scale.sx);
            ctx.graphics.drawString(ctx.font, actionText, right - tw, y + (h - 10) / 2, 0xFF888888);
        }

        if (statusText != null) {
            int tw = ctx.font.width(statusText);
            int right = showAction ? x + w - (int)(ACTION_RIGHT_MARGIN * com.immersivecinematics.immersive_cinematics.editor.Scale.sx) : x + w - (int)(STATUS_RIGHT_MARGIN * com.immersivecinematics.immersive_cinematics.editor.Scale.sx);
            ctx.graphics.drawString(ctx.font, statusText, Math.max(x + (int)(STATUS_LEFT_MIN * com.immersivecinematics.immersive_cinematics.editor.Scale.sx), right - tw), y + (h - 10) / 2, statusColor);
        }
    }

    @Override
    public boolean mouseClicked(UIContext ctx) {
        EditorLogger.areaHit(EditorLogger.MENU, "full_area", ctx.mouseX, ctx.mouseY, true);
        for (int i = children.size() - 1; i >= 0; i--) {
            if (children.get(i).mouseClicked(ctx)) return true;
        }
        return false;
    }

    @Override
    public List<UIComponent> getChildren() {
        return children;
    }
}
