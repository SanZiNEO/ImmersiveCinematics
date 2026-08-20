package com.immersivecinematics.immersive_cinematics.editor.area;

import com.immersivecinematics.immersive_cinematics.editor.EditorTheme;
import com.immersivecinematics.immersive_cinematics.editor.widget.UIComponent;
import com.immersivecinematics.immersive_cinematics.editor.widget.UIContext;
import java.util.List;

/**
 * 通用滚动容器（0.3.5 第5轮 5A）。
 * <p>
 * 从 LeftPanelArea 抽出的滚动语义：scrollY/targetScrollY/maxScroll/contentHeight、
 * 滚动条、滚轮、渲染平移与命中偏移全部由本类负责。
 * 子组件仍使用屏幕绝对坐标创建；本容器通过 getScrollOffset() 让子孙命中坐标自动减去 scrollY，
 * 渲染时通过 pushScroll 做视觉平移。
 */
public class ScrollablePanel extends UIComponent {

    private int scrollY;
    /** 滚轮滚动目标值（render 逐帧向它插值，实现平滑滚动） */
    private int targetScrollY;
    private int maxScroll;
    private int contentHeight;
    private boolean scrollbarGrabbed;
    private int scrollbarGrabOffset;

    public ScrollablePanel(int x, int y, int w, int h) {
        super(x, y, w, h);
    }

    /** 事件树滚动语义：子孙组件的命中坐标 = 绝对坐标 − scrollY（渲染平移与命中统一） */
    @Override
    public int getScrollOffset() {
        return scrollY;
    }

    public int getScrollY() {
        return scrollY;
    }

    public int getMaxScroll() {
        return maxScroll;
    }

    public void resetScroll() {
        scrollY = 0;
        targetScrollY = 0;
    }

    /** 内容变化后重算滚动范围并夹紧当前/目标滚动值 */
    public void recompute() {
        int bottom = y;
        for (UIComponent c : getChildren()) {
            bottom = Math.max(bottom, getComponentBottom(c));
        }
        contentHeight = Math.max(0, bottom - y);

        boolean shouldScroll = contentHeight > h * 0.8f;
        if (!shouldScroll) {
            scrollY = 0;
            targetScrollY = 0;
            maxScroll = 0;
            return;
        }
        maxScroll = Math.max(0, contentHeight - h);
        scrollY = Math.max(0, Math.min(scrollY, maxScroll));
        targetScrollY = Math.max(0, Math.min(targetScrollY, maxScroll));
    }

    private static int getComponentBottom(UIComponent comp) {
        if (!comp.visible) return comp.y;
        int b = comp.y + comp.h;
        List<UIComponent> sub = comp.getChildren();
        if (sub != null) {
            for (UIComponent s : sub) {
                if (!s.visible) continue;
                b = Math.max(b, getComponentBottom(s));
            }
        }
        return b;
    }

    private void clampScrollY() {
        targetScrollY = Math.max(0, Math.min(targetScrollY, maxScroll));
    }

    @Override
    public void render(UIContext ctx) {
        if (!visible) return;

        ctx.graphics.fill(x, y, x + w, y + h, EditorTheme.BG_TRACK);

        // 向目标滚动值平滑逼近（滚动条拖动走即时路径不受影响）
        if (scrollY != targetScrollY) {
            scrollY += (int)((targetScrollY - scrollY) * 0.25f);
            if (Math.abs(targetScrollY - scrollY) < 1) scrollY = targetScrollY;
        }

        // 内容视口裁剪到本容器边界（滚动内容不会画到容器外）
        ctx.pushViewport(x, y, w, h);
        ctx.pushScroll(scrollY);

        super.render(ctx);

        ctx.popScroll(scrollY);
        ctx.popViewport();

        if (maxScroll > 0) {
            int sbX = x + w - 4;
            int sbH = h;
            ctx.graphics.fill(sbX, y, sbX + 4, y + sbH, EditorTheme.SCROLLBAR_BG);
            float thumbRatio = (float)h / contentHeight;
            int thumbH = Math.max(8, (int)(sbH * thumbRatio));
            int thumbY = y + (int)((float)scrollY / maxScroll * (sbH - thumbH));
            ctx.graphics.fill(sbX, thumbY, sbX + 4, thumbY + thumbH, EditorTheme.SCROLLBAR_THUMB);
            ctx.graphics.renderOutline(sbX, thumbY, 4, thumbH, EditorTheme.BORDER_LIGHT);
        }
    }

    @Override
    public void renderOverlay(UIContext ctx) {
        if (!visible) return;
        ctx.pushScroll(scrollY);
        super.renderOverlay(ctx);
        ctx.popScroll(scrollY);
    }

    @Override
    protected boolean onClicked(UIContext ctx) {
        if (!ctx.isMouseIn(hitX(), hitY(), w, h)) return false;

        if (maxScroll > 0) {
            int sbX = x + w - 4;
            if (ctx.mouseX >= sbX) {
                float thumbRatio = (float)h / contentHeight;
                int thumbH = Math.max(8, (int)(h * thumbRatio));
                int thumbY = y + (int)((float)scrollY / maxScroll * (h - thumbH));
                if (ctx.mouseY >= thumbY && ctx.mouseY < thumbY + thumbH) {
                    scrollbarGrabbed = true;
                    scrollbarGrabOffset = ctx.mouseY - thumbY;
                } else {
                    scrollY = targetScrollY = (int)((float)(ctx.mouseY - y) / h * maxScroll);
                    clampScrollY();
                }
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean onDragged(UIContext ctx) {
        if (scrollbarGrabbed && maxScroll > 0) {
            float thumbRatio = (float)h / contentHeight;
            int thumbH = Math.max(8, (int)(h * thumbRatio));
            int trackSpace = h - thumbH;
            if (trackSpace > 0) {
                scrollY = targetScrollY = (int)((float)(ctx.mouseY - y - scrollbarGrabOffset) / trackSpace * maxScroll);
                clampScrollY();
            }
            return true;
        }
        return false;
    }

    @Override
    protected boolean onReleased(UIContext ctx) {
        scrollbarGrabbed = false;
        return false;
    }

    @Override
    protected boolean onScrolled(UIContext ctx, double scroll) {
        if (!visible || !ctx.isMouseIn(hitX(), hitY(), w, h)) return false;
        // 子组件滚轮（如聚焦的数值输入）由 UIComponent.mouseScrolled 模板先分发，未消费才到这里
        if (maxScroll > 0) {
            // 滚轮改 targetScrollY，视觉由 render 渐变驱动
            targetScrollY -= (int)(scroll * 20);
            clampScrollY();
            return true;
        }
        return false;
    }
}
