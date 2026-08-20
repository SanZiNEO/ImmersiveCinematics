package com.immersivecinematics.immersive_cinematics.editor.panel;

import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.editor.EditorTheme;
import com.immersivecinematics.immersive_cinematics.editor.widget.UIButton;
import net.minecraft.client.resources.language.I18n;

/**
 * 轨道列表面板（0.3.5 第5轮 5A）。
 */
public class TrackListPanel extends EditorPanel {

    @Override
    protected void buildContent() {
        if (ctx == null || ctx.tracks == null) return;

        int cy = y + 4;
        int lx = x + 6;
        int rowH = 20;

        addSectionLabel(I18n.get("editor.section.track_list"), lx, cy, 0);
        cy += 16;

        for (int ti = 0; ti < ctx.tracks.size(); ti++) {
            JsonObject track = ctx.tracks.get(ti).getAsJsonObject();
            String type = track.has("type") ? track.get("type").getAsString() : "TRACK";
            int clipCount = track.has("clips") ? track.getAsJsonArray("clips").size() : 0;

            int finalTi = ti;
            String trackId = track.has("id") ? track.get("id").getAsString() : type;
            String label = trackId + "  (" + I18n.get("editor.label.clip_count", String.valueOf(clipCount)) + ")";
            UIButton row = new UIButton(lx + 4, cy, w - 12 - 26, rowH, label, btn -> {
                if (ctx.onTrackSelected != null) ctx.onTrackSelected.accept(finalTi);
            });
            row.color(0x00, 0x443A3A3A).textColor(EditorTheme.TEXT_SECONDARY);
            addChild(row);

            UIButton visBtn = new UIButton(lx + 4 + (w - 12 - 26) + 2, cy, 24, rowH,
                    "", btn -> {
                        if (ctx.onToggleTrackVisible != null) ctx.onToggleTrackVisible.accept(track);
                    });
            visBtn.icon("eye").color(EditorTheme.BG_WIDGET, EditorTheme.BG_HOVER).textColor(EditorTheme.TEXT_SECONDARY);
            addChild(visBtn);

            cy += rowH + 2;
        }
    }
}
