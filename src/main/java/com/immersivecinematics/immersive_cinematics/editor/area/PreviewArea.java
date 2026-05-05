package com.immersivecinematics.immersive_cinematics.editor.area;

import com.immersivecinematics.immersive_cinematics.editor.widget.*;
import java.util.ArrayList;
import java.util.List;

public class PreviewArea extends UIComponent {
    private final List<UIComponent> children = new ArrayList<>();
    private final UIButton playBtn;
    private final UIButton pauseBtn;
    private final UIButton stopBtn;
    private final UILabel timeLabel;

    private int fboTextureId = -1;
    private boolean playing;
    private float currentTime;

    public PreviewArea(int x, int y, int w, int h) {
        super(x, y, w, h);

        int barW = 160;
        int barH = 24;
        int barX = x + (w - barW) / 2;
        int barY = y + h - barH - 8;

        playBtn = new UIButton(barX, barY, 36, barH, "\u25B6", b -> {});
        playBtn.color(0xFF333333, 0xFF444444);
        pauseBtn = new UIButton(barX + 40, barY, 36, barH, "\u23F8", b -> {});
        pauseBtn.color(0xFF333333, 0xFF444444);
        stopBtn = new UIButton(barX + 80, barY, 36, barH, "\u25A0", b -> {});
        stopBtn.color(0xFF333333, 0xFF444444);
        timeLabel = new UILabel(barX + 124, barY + (barH - 10) / 2, "0.0s", 0xFF999999);

        children.add(playBtn);
        children.add(pauseBtn);
        children.add(stopBtn);
        children.add(timeLabel);
    }

    public void setFboTexture(int id) { fboTextureId = id; }
    public void setPlaying(boolean p) { playing = p; }
    public void setCurrentTime(float t) {
        currentTime = t;
        timeLabel.setText(String.format("%.1fs", t));
    }

    public void setOnPlay(Runnable r) { playBtn.setOnClick(b -> r.run()); }
    public void setOnPause(Runnable r) { pauseBtn.setOnClick(b -> r.run()); }
    public void setOnStop(Runnable r) { stopBtn.setOnClick(b -> r.run()); }

    @Override
    public void render(UIContext ctx) {
        ctx.graphics.fill(x, y, x + w, y + h, 0xFF151515);
        ctx.graphics.renderOutline(x, y, w, h, 0xFF333333);

        int previewH = h - 40;
        int previewW = (int) (previewH * 16f / 9f);
        if (previewW > w - 16) {
            previewW = w - 16;
            previewH = (int) (previewW * 9f / 16f);
        }
        int px = x + (w - previewW) / 2;
        int py = y + 8;

        ctx.graphics.fill(px, py, px + previewW, py + previewH, 0xFF0F0F0F);
        ctx.graphics.renderOutline(px, py, previewW, previewH, 0xFF3A3A3A);
        if (fboTextureId < 0) {
            String msg = "No Preview";
            int tw = ctx.font.width(msg);
            ctx.graphics.drawString(ctx.font, msg, px + (previewW - tw) / 2, py + previewH / 2 - 4, 0xFF555555);
        }

        for (UIComponent c : children) {
            c.render(ctx);
        }
    }

    @Override
    protected List<UIComponent> getChildren() {
        return children;
    }
}
