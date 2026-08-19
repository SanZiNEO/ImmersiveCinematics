package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 玩家移动控制 — EVENT 关键帧可选 position(x/z) 驱动玩家平滑直线移动（假输入走原版移动链路）。
 * <ul>
 *   <li>有 position 的关键帧 = 玩家目标点（多个 = 折线）；相邻两点间玩家直线移动</li>
 *   <li>position 写 x/z = 世界绝对坐标；写 dx/dz = 相对玩家激活位置（触发点）</li>
 *   <li>假输入：{@code LocalPlayerMixin} 在 serverAiStep @HEAD 设 input 冲量 → 原版 travel()
 *       完整链路（碰撞/走路动画/脚步/自动跳跃），服务端零改动</li>
 *   <li>被挡停在被挡处；播完/停止停在最后目标；游戏暂停 / 编辑器预览时不驱动</li>
 * </ul>
 */
public class PlayerMoveController {

    /** 到达判定阈值（格） */
    private static final double ARRIVE_EPS = 0.05;

    private static final class Target {
        final float time;
        final double x;
        final double z;

        Target(float time, double x, double z) {
            this.time = time;
            this.x = x;
            this.z = z;
        }
    }

    private final List<Target> targets = new ArrayList<>();
    private int destIndex = 0;
    private boolean moving = false;
    private Target current;

    /** 脚本开始时初始化：从 EVENT 轨道提取"有 position 的关键帧"，按全局时间排序 */
    public void onScriptStart(CinematicScript script, Vec3 originPos) {
        targets.clear();
        destIndex = 0;
        current = null;
        moving = false;
        if (script == null || script.getTimeline() == null) return;
        double originX = originPos != null ? originPos.x : 0;
        double originZ = originPos != null ? originPos.z : 0;
        for (TimelineTrack track : script.getTimeline().getTracks()) {
            if (track.getType() != TrackType.EVENT) continue;
            for (Clip clip : track.getClips()) {
                float clipStart = clip.getStartTime();
                for (Keyframe kf : clip.getKeyframes()) {
                    Object pos = kf.getObject("position");
                    if (!(pos instanceof Map<?, ?> m)) continue;
                    Double x = num(m.get("x"));
                    Double z = num(m.get("z"));
                    if (x != null && z != null) {
                        targets.add(new Target(clipStart + kf.getTime(), x, z));
                        continue;
                    }
                    Double dx = num(m.get("dx"));
                    Double dz = num(m.get("dz"));
                    if (dx != null && dz != null) {
                        targets.add(new Target(clipStart + kf.getTime(), originX + dx, originZ + dz));
                    }
                }
            }
        }
        targets.sort((a, b) -> Float.compare(a.time, b.time));
        if (targets.size() >= 2) {
            destIndex = 1; // 从 targets[0] 走向 targets[1]
            moving = true;
        }
    }

    /** 每渲染帧（脚本时间）更新当前移动目标；由 {@link ScriptPlayer#onRenderFrame} 调用 */
    public void onRenderFrame(float elapsed) {
        current = null;
        if (!moving || targets.size() < 2) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.isPaused() || CameraManager.INSTANCE.isPreviewMode()) return;

        // 未到段起点不动（段 = 前一个目标时间 → 当前目标时间）
        if (elapsed < targets.get(destIndex - 1).time) return;
        LocalPlayer p = mc.player;

        // 已到达当前目标 → 推进（终点到达则停）
        boolean advanced = true;
        while (advanced && destIndex < targets.size()) {
            Target t = targets.get(destIndex);
            double dx = t.x - p.getX();
            double dz = t.z - p.getZ();
            if (dx * dx + dz * dz < ARRIVE_EPS * ARRIVE_EPS) {
                destIndex++;
            } else {
                advanced = false;
            }
        }
        if (destIndex >= targets.size()) {
            moving = false;
            return;
        }
        current = targets.get(destIndex);
    }

    /** 由 {@code LocalPlayerMixin} 调用：激活时把"朝当前目标"写成假输入 */
    public void applyFakeInput(LocalPlayer player) {
        if (current == null || player == null) return;
        double dx = current.x - player.getX();
        double dz = current.z - player.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        player.input.forwardImpulse = 1.0F;
        player.input.leftImpulse = 0.0F;
        // 朝向目标行走（yRotO 交原版插值，转身稍顺滑）
        player.setYRot(yaw);
    }

    public boolean isMoving() {
        return current != null;
    }

    /** 脚本停止/打断/结束 → 清状态（下一帧不再驱动输入） */
    public void onStop() {
        targets.clear();
        destIndex = 0;
        current = null;
        moving = false;
    }

    private static Double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }
}
