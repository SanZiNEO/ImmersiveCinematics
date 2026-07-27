package com.immersivecinematics.immersive_cinematics.editor.area;

import com.immersivecinematics.immersive_cinematics.editor.PreviewCapture;
import com.immersivecinematics.immersive_cinematics.editor.debug.EditorLogger;
import com.immersivecinematics.immersive_cinematics.editor.widget.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.renderer.GameRenderer;
import java.util.ArrayList;
import java.util.List;

public class PreviewArea extends UIComponent {
    private final UIButton playBtn;
    private final UIButton pauseBtn;
    private final UIButton stopBtn;
    private final UILabel timeLabel;

    private float currentTime;

    public PreviewArea(int x, int y, int w, int h) {
        super(x, y, w, h);
        EditorLogger.areaRegister(EditorLogger.PREVIEW, "full_area", x, y, w, h);

        float sx = com.immersivecinematics.immersive_cinematics.editor.Scale.sx;
        float sy = com.immersivecinematics.immersive_cinematics.editor.Scale.sy;
        int barW = (int)(160 * sx);
        int barH = (int)(24 * sy);
        int barX = x + (w - barW) / 2;
        int barY = y + h - barH - (int)(8 * sy);

        int btnW = (int)(36 * sx);
        int btnGap = (int)(40 * sx);
        playBtn = new UIButton(barX, barY, btnW, barH, "\u25B6", b -> {});
        playBtn.color(0xFF333333, 0xFF444444);
        pauseBtn = new UIButton(barX + btnGap, barY, btnW, barH, "\u23F8", b -> {});
        pauseBtn.color(0xFF333333, 0xFF444444);
        stopBtn = new UIButton(barX + btnGap * 2, barY, btnW, barH, "\u25A0", b -> {});
        stopBtn.color(0xFF333333, 0xFF444444);
        timeLabel = new UILabel(barX + btnGap * 3 + (int)(8 * sx), barY + (int)(7 * sy), "0.0s", 0xFF999999);

        addChild(playBtn);
        addChild(pauseBtn);
        addChild(stopBtn);
        addChild(timeLabel);
    }

    public void setCurrentTime(float t) {
        this.currentTime = t;
        if (timeLabel != null) timeLabel.setText(String.format("%.1fs", t));
    }

    public void setOnPlay(Runnable r) { playBtn.setOnClick(b -> r.run()); }
    public void setOnPause(Runnable r) { pauseBtn.setOnClick(b -> r.run()); }
    public void setOnStop(Runnable r) { stopBtn.setOnClick(b -> r.run()); }

    @Override
    public void renderContent(UIContext ctx) {
        ctx.graphics.fill(x, y, x + w, y + h, 0xFF111111);
        ctx.graphics.renderOutline(x, y, w, h, 0xFF333333);
        ctx.graphics.enableScissor(x, y, x + w, y + h);

        if (currentTime >= 0) {
            int cx = x + w / 2;
            int cy2 = y + h / 2;
            ctx.graphics.drawString(ctx.font, I18n.get("editor.preview"), cx - ctx.font.width(I18n.get("editor.preview")) / 2, cy2 - 20, 0xFF444444);
        }

        ctx.graphics.disableScissor();
    }
}
