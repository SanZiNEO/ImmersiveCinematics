package com.immersivecinematics.immersive_cinematics.editor.panel;

import com.immersivecinematics.immersive_cinematics.editor.EditorTheme;
import com.immersivecinematics.immersive_cinematics.editor.Scale;
import com.immersivecinematics.immersive_cinematics.editor.widget.UIButton;
import com.immersivecinematics.immersive_cinematics.editor.widget.UILabel;
import net.minecraft.client.resources.language.I18n;

/**
 * 脚本列表面板（0.3.5 第5轮 5A）。
 */
public class ScriptListPanel extends EditorPanel {

    @Override
    protected void buildContent() {
        if (ctx == null) return;

        int cy = y + 6;
        addChild(new UILabel(x + 6, cy, "Scripts", EditorTheme.TEXT_SECONDARY));
        cy += (int)(16 * Scale.sy);

        for (String name : ctx.scriptFileNames) {
            int btnH = (int)(20 * Scale.sy);
            UIButton itemBtn = new UIButton(x + 4, cy, w - 12, btnH, name, b -> {
                if (ctx.onOpenScript != null) ctx.onOpenScript.accept(name);
            });
            itemBtn.color(0x00, 0x443A3A3A).textColor(EditorTheme.TEXT_SECONDARY);
            addChild(itemBtn);
            cy += btnH + (int)(2 * Scale.sy);
        }

        UIButton newBtn = new UIButton(x + 4, cy, w - 12, (int)(20 * Scale.sy),
                I18n.get("editor.script.new_button"), b -> {
                    if (ctx.onNewScript != null) ctx.onNewScript.run();
                });
        newBtn.color(EditorTheme.BG_WIDGET, EditorTheme.BG_HOVER).textColor(EditorTheme.TEXT_SECONDARY);
        addChild(newBtn);
    }
}
