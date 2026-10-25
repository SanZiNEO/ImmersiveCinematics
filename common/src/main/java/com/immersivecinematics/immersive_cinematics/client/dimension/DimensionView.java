package com.immersivecinematics.immersive_cinematics.client.dimension;

import com.immersivecinematics.immersive_cinematics.util.FatalErrors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 一条跨维度 lane 的目标视图：独立的 {@link ClientLevel} + {@link LevelRenderer} + {@link LightTexture}。
 *
 * 玩家本身不跨维度（没有传送语义）：lane 渲染期间由 {@code LaneRenderer} 临时把全局
 * {@code Minecraft.level} / {@code levelRenderer} / 两个渲染分发器指向本视图，pass 结束即还原；
 * 主画面继续用玩家当前维度。
 *
 * {@link DimensionViews#MAIN} 是「玩家当前维度」哨兵：所有访问器对它都是 no-op / {@code null}，
 * 渲染侧走原来的 {@code mc.level} / {@code mc.levelRenderer} / {@code gameRenderer.lightTexture()}，
 * 不产生任何额外分配或状态切换。
 *
 * 生命周期：由 {@link DimensionViews#acquire} 惰性创建（渲染线程），跨帧持有；世界退出时
 * {@link DimensionViews#closeAll()} 统一 {@link #close()}。没有 lane 用它的那一帧不关闭——
 * 创建成本（区块网格 / 编译线程 / 描边链）远高于保留成本。
 */
public final class DimensionView {

    private final Minecraft minecraft;
    private final ResourceKey<Level> dimension;
    private final ClientLevel level;
    private final LevelRenderer renderer;
    private final LightTexture lightTexture;

    /** 玩家当前维度哨兵（不持有任何目标资源）。 */
    private DimensionView() {
        this.minecraft = null;
        this.dimension = null;
        this.level = null;
        this.renderer = null;
        this.lightTexture = null;
    }

    DimensionView(Minecraft minecraft, ResourceKey<Level> dimension, ClientLevel level,
                  LevelRenderer renderer, LightTexture lightTexture) {
        this.minecraft = minecraft;
        this.dimension = dimension;
        this.level = level;
        this.renderer = renderer;
        this.lightTexture = lightTexture;
    }

    /** 是否为「玩家当前维度」哨兵。 */
    public boolean isMain() {
        return this.dimension == null;
    }

    /** 目标维度 key；哨兵为 {@code null}。 */
    @Nullable
    public ResourceKey<Level> dimension() {
        return this.dimension;
    }

    /** 目标维度的客户端世界；哨兵为 {@code null}（主画面用 {@code mc.level}）。 */
    @Nullable
    public ClientLevel level() {
        return this.level;
    }

    /** 目标维度的渲染器；哨兵为 {@code null}（主画面用 {@code mc.levelRenderer}）。 */
    @Nullable
    public LevelRenderer renderer() {
        return this.renderer;
    }

    /** 目标维度的光照贴图；哨兵为 {@code null}（主画面用 {@code gameRenderer.lightTexture()}）。 */
    @Nullable
    public LightTexture lightTexture() {
        return this.lightTexture;
    }

    /**
     * 把目标视图的区块缓存中心 / 半径对齐到该 lane 的相机：视图中心是单份共享状态，
     * 目标维度有自己的 {@link ClientChunkCache}，按 lane 相机逐帧推进（主画面那份由原版自己推进）。
     *
     * @param cameraPos    该 lane 本帧的相机位置
     * @param viewDistance 玩家当前有效视距（lane 无独立视距覆盖）
     */
    public void updateViewCenter(Vec3 cameraPos, int viewDistance) {
        if (this.level == null) {
            return;
        }
        ClientChunkCache chunks = this.level.getChunkSource();
        chunks.updateViewCenter(SectionPos.posToSectionCoord(cameraPos.x),
                SectionPos.posToSectionCoord(cameraPos.z));
        chunks.updateViewRadius(viewDistance);
    }

    /** 窗口尺寸变化：目标渲染器的描边 / 半透明链跟随重建（主渲染器由原版自己 resize）。 */
    public void resize(int width, int height) {
        if (this.renderer != null) {
            this.renderer.resize(width, height);
        }
    }

    /** F3+T 资源重载：目标渲染器重建描边链（调用方保证在渲染线程）。 */
    public void onResourceReload(Minecraft mc) {
        if (this.renderer != null) {
            this.renderer.onResourceManagerReload(mc.getResourceManager());
        }
    }

    /**
     * 释放目标视图的全部 GL / 区块资源（渲染线程）。
     * {@code LevelRenderer.close()} 只关描边 / 半透明链，不释放区块网格与编译线程；
     * 这里先 {@code setLevel(null)} 释放这些，再关渲染器与光照贴图。
     * {@code setLevel} 会改写共享的实体渲染分发器，必须立刻还原到玩家当前世界。
     */
    public void close() {
        if (this.renderer == null) {
            return;
        }
        try {
            this.renderer.setLevel(null);
        } catch (Throwable t) {
            // 释放失败不阻断后续关闭：区块资源最多留到进程结束，不能连描边链 / 光照贴图一起漏
            FatalErrors.rethrowIfFatal(t);
        } finally {
            Minecraft mc = this.minecraft;
            if (mc != null) {
                mc.getEntityRenderDispatcher().setLevel(mc.level);
                mc.getBlockEntityRenderDispatcher().setLevel(mc.level);
            }
        }
        try {
            this.renderer.close();
        } finally {
            if (this.lightTexture != null) {
                this.lightTexture.close();
            }
        }
    }

    /** 创建玩家当前维度哨兵（只由 {@link DimensionViews} 调用一次）。 */
    static DimensionView main() {
        return new DimensionView();
    }
}
