package com.immersivecinematics.immersive_cinematics.editor.widget;

import com.immersivecinematics.immersive_cinematics.editor.EditorTheme;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.joml.Matrix4f;

public class UIDropdown extends UIComponent {
    private final List<String> options;
    private final Supplier<Integer> source;
    private final Consumer<Integer> sink;
    private boolean expanded;
    private int highlightIndex = -1;
    private int maxListHeight = Integer.MAX_VALUE;
    private int listScrollOffset;
    private Consumer<Integer> onRightClick;
    /** 可选字段名前缀（左侧显示，如"注视："），选项从其后开始 */
    private String label;

    public UIDropdown(int x, int y, int w, int h, List<String> options,
                      Supplier<Integer> source, Consumer<Integer> sink) {
        super(x, y, w, h);
        this.options = options;
        this.source = source;
        this.sink = sink;
    }

    public UIDropdown setLabel(String label) {
        this.label = label;
        return this;
    }

    public UIDropdown setHighlightIndex(int idx) {
        this.highlightIndex = idx;
        return this;
    }

    public UIDropdown setOnRightClick(Consumer<Integer> r) {
        this.onRightClick = r;
        return this;
    }

    public UIDropdown setMaxListHeight(int h) {
        this.maxListHeight = h;
        return this;
    }

    /** 展开列表高度（限高 + 不超出屏幕底部） */
    private int listHeight(UIContext ctx) {
        int totalH = options.size() * h;
        int listH = Math.min(totalH, maxListHeight);
        int maxToBottom = ctx.screenHeight - (y + h);
        if (listH > maxToBottom) listH = Math.max(0, maxToBottom);
        return listH;
    }

    /** 浮层命中：展开列表区域（含溢出到父容器外的部分）优先于所有普通组件 */
    @Override
    protected boolean overlayHit(UIContext ctx) {
        return expanded && ctx.isMouseIn(hitX(), hitY() + h, w, listHeight(ctx));
    }

    /** Render the collapsed button only. Expanded list is drawn in renderOverlay. */
    @Override
    public void render(UIContext ctx) {
        String rawText = "";
        if (source != null) {
            int idx = source.get();
            if (idx >= 0 && idx < options.size()) rawText = options.get(idx);
        }
        String text = rawText;
        int labelW = 0;
        if (label != null && !label.isEmpty()) {
            labelW = ctx.font.width(label);
            ctx.graphics.drawString(ctx.font, label, x + 4, y + (h - 8) / 2, EditorTheme.TEXT_SECONDARY);
        }
        int maxTextW = w - 18 - labelW;
        while (ctx.font.width(text) > maxTextW && !text.isEmpty())
            text = text.substring(0, text.length() - 1);
        if (!text.equals(rawText) && !rawText.isEmpty()) text += "..";

        ctx.graphics.fill(x, y, x + w, y + h, EditorTheme.BG_HOVER);
        ctx.graphics.renderOutline(x, y, w, h, EditorTheme.TEXT_DISABLED);
        ctx.graphics.drawString(ctx.font, text, x + 4 + labelW, y + (h - 8) / 2, EditorTheme.TEXT_PRIMARY);
        ctx.graphics.drawString(ctx.font, "\u25BC", x + w - 12, y + (h - 8) / 2, EditorTheme.TEXT_SECONDARY);
        renderTooltipIfHovered(ctx);
    }

    /** Render the expanded dropdown list on top of everything. */
    @Override
    public void renderOverlay(UIContext ctx) {
        if (!expanded) return;
        int totalH = options.size() * h;
        int listH = listHeight(ctx);
        int maxScroll = Math.max(0, totalH - listH);
        if (listScrollOffset > maxScroll) listScrollOffset = maxScroll;

        Matrix4f m = ctx.graphics.pose().last().pose();

        // Background fill
        BufferBuilder bb = new BufferBuilder(256);
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        vertex(bb, m, x, y + h, 0xFF2F2F2F);
        vertex(bb, m, x, y + h + listH, 0xFF2F2F2F);
        vertex(bb, m, x + w, y + h + listH, 0xFF2F2F2F);
        vertex(bb, m, x + w, y + h, 0xFF2F2F2F);
        RenderSystem.enableBlend();
        BufferUploader.drawWithShader(bb.end());

        // Outline
        drawOutline(m, x, y + h, w, listH, 0xFF555555);

        // Items
        int itemStart = Math.max(0, listScrollOffset / h);
        int visible = listH / h + 1;
        for (int i = itemStart; i < Math.min(options.size(), itemStart + visible); i++) {
            int itemY = y + h + i * h - listScrollOffset;
            if (itemY + h < y + h || itemY > y + h + listH) continue;
            if (i == highlightIndex) continue;
            if (ctx.isMouseIn(hitX(), hitY() + h + i * h - listScrollOffset, w, h)) {
                BufferBuilder hb = new BufferBuilder(64);
                hb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
                vertex(hb, m, x, itemY, EditorTheme.SEPARATOR);
                vertex(hb, m, x, itemY + h, EditorTheme.SEPARATOR);
                vertex(hb, m, x + w, itemY + h, EditorTheme.SEPARATOR);
                vertex(hb, m, x + w, itemY, EditorTheme.SEPARATOR);
                BufferUploader.drawWithShader(hb.end());
            }
            ctx.graphics.drawString(ctx.font, options.get(i), x + 4, itemY + (h - 8) / 2, 0xFFBBBBBB);
        }

        // Scroll bar
        if (totalH > listH) {
            float barH = listH * listH / (float) totalH;
            float barY = listH * listScrollOffset / (float) totalH;
            BufferBuilder sb = new BufferBuilder(64);
            sb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            vertex(sb, m, x + w - 3, (int) (y + h + barY), 0xFF666666);
            vertex(sb, m, x + w - 3, (int) (y + h + barY + barH), 0xFF666666);
            vertex(sb, m, x + w - 1, (int) (y + h + barY + barH), 0xFF666666);
            vertex(sb, m, x + w - 1, (int) (y + h + barY), 0xFF666666);
            BufferUploader.drawWithShader(sb.end());
        }
    }

    private static void vertex(BufferBuilder b, Matrix4f m, float x, float y, int argb) {
        b.vertex(m, x, y, 0).color((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >> 24) & 0xFF).endVertex();
    }

    private void drawOutline(Matrix4f m, int rx, int ry, int rw, int rh, int argb) {
        BufferBuilder bb = new BufferBuilder(256);
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        vertex(bb, m, rx, ry, argb); vertex(bb, m, rx + rw, ry, argb); vertex(bb, m, rx + rw, ry + 1, argb); vertex(bb, m, rx, ry + 1, argb);
        vertex(bb, m, rx, ry + rh - 1, argb); vertex(bb, m, rx + rw, ry + rh - 1, argb); vertex(bb, m, rx + rw, ry + rh, argb); vertex(bb, m, rx, ry + rh, argb);
        vertex(bb, m, rx, ry, argb); vertex(bb, m, rx + 1, ry, argb); vertex(bb, m, rx + 1, ry + rh, argb); vertex(bb, m, rx, ry + rh, argb);
        vertex(bb, m, rx + rw - 1, ry, argb); vertex(bb, m, rx + rw, ry, argb); vertex(bb, m, rx + rw, ry + rh, argb); vertex(bb, m, rx + rw - 1, ry + rh, argb);
        BufferUploader.drawWithShader(bb.end());
    }

    @Override
    protected boolean onClicked(UIContext ctx) {
        if (expanded) {
            int totalH = options.size() * h;
            int listH = listHeight(ctx);
            int baseY = hitY() + h;
            for (int i = 0; i < options.size(); i++) {
                int itemY = baseY + i * h - listScrollOffset;
                if (itemY + h < baseY || itemY > baseY + listH) continue;
                if (!ctx.isMouseIn(hitX(), itemY, w, h)) continue;
                expanded = false;
                if (i == highlightIndex) return true;
                if (ctx.mouseButton == 1 && onRightClick != null)
                    onRightClick.accept(i);
                else if (ctx.mouseButton == 0 && sink != null)
                    sink.accept(i);
                return true;
            }
            expanded = false;
            return true;
        }
        if (isHovered(ctx)) { expanded = true; return true; }
        return false;
    }

    @Override
    protected boolean onScrolled(UIContext ctx, double scroll) {
        if (!expanded) return false;
        int totalH = options.size() * h;
        int listH = listHeight(ctx);
        int scrollAreaY = hitY() + h;
        if (!ctx.isMouseIn(hitX(), scrollAreaY, w, listH)) return false;
        listScrollOffset -= (int) scroll * h;
        int maxScroll = Math.max(0, totalH - listH);
        if (listScrollOffset < 0) listScrollOffset = 0;
        if (listScrollOffset > maxScroll) listScrollOffset = maxScroll;
        return true;
    }
}
