package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.client.post.ColorAdjustParams;
import com.immersivecinematics.immersive_cinematics.control.CompletionReason;
import com.immersivecinematics.immersive_cinematics.control.ExitReason;
import com.immersivecinematics.immersive_cinematics.overlay.OverlayManager;
import com.immersivecinematics.immersive_cinematics.util.Clock;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 脚本播放器 — 驱动 CinematicScript 的运行时播放
 * <p>
 * 核心职责（重构后）：
 * <ul>
 *   <li>管理全局播放时间（基于 CameraManager 虚拟时钟）</li>
 *   <li>创建并调度 TrackPlayer 实例（Camera/Letterbox/Audio/ModEvent）</li>
 *   <li>管理当前脚本的运行时行为（从 ScriptMeta.RuntimeBehavior 直接持有）</li>
 *   <li>处理脚本结束 / holdAtEnd</li>
 * </ul>
 * <p>
 * 不再直接访问 CameraManager/OverlayManager 的写入方法 —
 * 所有轨道处理逻辑已抽取到各自的 TrackPlayer 实现中。
 */
public class ScriptPlayer {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/ScriptPlayer");

    /**
     * holdAtEnd 模式下的时间偏移量 — 略小于 totalDuration，
     * 避免 isFinished() 判定脚本已结束。
     * 0.1ms 的偏移在视觉上不可察觉，但确保插值仍取最后一帧的值。
     */
    private static final float HOLD_END_EPSILON = 0.0001f;

    // ========== 状态 ==========

    private CinematicScript script;
    private boolean playing = false;

    // 虚拟时间驱动（单位：秒，double 精度）
    private double startGameTimeSeconds = 0;

    /**
     * 本实例的时钟源（{@link Clock}，秒）——默认 = 游戏共享虚拟时钟（{@code CameraManager.getGameTimeSeconds()}，游戏实例）。
     * <p>
     * 预览实例由 {@code CameraManager} 注入<b>预览播放头</b>（{@code plans/0.3.6/parallel-playback.md}
     * §7 步骤 5）：预览与游戏各自独立计时——预览的暂停/播放不再冻结游戏实例的时间轴，反之亦然。
     */
    private Clock clockSource = CameraManager.INSTANCE::getGameTimeSeconds;

    // 相对模式基准位置（玩家激活时的位置）
    private Vec3 originPos = Vec3.ZERO;

    // 玩家移动控制（EVENT 关键帧 position 驱动，假输入走原版链路；不建 TrackPlayer，直接读 EVENT 轨道数据）
    private final PlayerMoveController playerMovement = new PlayerMoveController();

    public PlayerMoveController getPlayerMovement() { return playerMovement; }
    public Vec3 getOriginPos() { return originPos; }

    // 当前活跃的脚本运行时行为（从 ScriptMeta.RuntimeBehavior 直接持有）
    private ScriptMeta.RuntimeBehavior currentBehavior = null;

    /**
     * 宏观循环区间起点 a（秒）：timeline {@code loop_start}，缺省 0（负值按 0 处理）。
     * 语义 = "从 a 到 b 这一段重复执行"——循环是播放控制（执行层的重复），不是时间折叠。
     */
    private float macroLoopStart = 0f;

    /**
     * 宏观循环区间终点 b（秒）；NaN = 未启用宏观循环（时间照直走）。
     * b 优先取 timeline 显式声明的 {@code loop_end}（作者声明的子区间），缺省取宏观末端。
     * 脚本开始时推导一次并缓存。
     */
    private float macroLoopEnd = Float.NaN;

    /** 圈长度（b − a，秒）；macroLoopEnd 有效时恒 &gt; 0 */
    private float macroLoopSpan = 0f;

    /** 宏观循环次数：-1 = 无限重复（脚本不再自然结束）；正整数 N = 重复 N 圈后自然结束 */
    private int macroLoopCount = -1;

    /** 是否允许宏观循环（编辑器预览实例关闭：预览按真实时间线播放，循环不展开也不折叠） */
    private boolean macroLoopAllowed = true;

    /** 上一帧分发给轨道的圈内局部时间（检测圈边界折返）；NaN = 无上一帧 */
    private float lastLocalElapsed = Float.NaN;

    private boolean stopping = false;

    // TrackPlayer 调度列表
    private List<TrackPlayer> trackPlayers = Collections.emptyList();

    /** 组 A：按轨道索引取 clips（动态数据源；支持同类型多条轨道——OVERLAY 多轨道） */
    public List<Clip> clipsForTrack(int trackIndex) {
        if (script == null) return Collections.emptyList();
        List<TimelineTrack> tracks = script.getTimeline().getTracks();
        if (trackIndex < 0 || trackIndex >= tracks.size()) return Collections.emptyList();
        return tracks.get(trackIndex).getClips();
    }

    /**
     * 组 A：增量替换脚本数据（编辑器编辑路径，常驻播放器零重启）。
     * <p>
     * 只替换 CinematicScript 引用并通知各 TrackPlayer 复位/重映射——
     * 不重建 TrackPlayer、不重新解码音频。相机/音频在下一帧用新数据驱动。
     */
    public void replaceScript(CinematicScript newScript) {
        if (newScript == null) return;
        // LUT：旧脚本的 clip 引用释放（新脚本的片段在下一帧采样时按新 clipId 重新登记）
        releaseLutClipRefs(this.script);
        List<TimelineTrack> oldTracks = script != null ? script.getTimeline().getTracks() : null;
        List<TimelineTrack> newTracks = newScript.getTimeline().getTracks();
        boolean layoutChanged = !sameTrackLayout(oldTracks, newTracks);

        this.script = newScript;
        this.currentBehavior = newScript.getMeta() != null ? newScript.getMeta().getBehavior() : null;
        this.computeMacroLoop();

        if (layoutChanged) {
            // 轨道布局变化(轨道数/类型顺序不同):旧 TrackPlayer 绑定的轨道索引对新脚本失效,
            // 必须重建——否则编辑器加载不同轨道结构的脚本后,OVERLAY/AUDIO 等会读到错位/越界的轨道
            // (症状:OVERLAY 层不出现、AUDIO 把字幕轨当音频报错)
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) this.originPos = mc.player.position();
            cleanupTrackPlayers();
            buildTrackPlayers(newScript);
        } else {
            for (TrackPlayer tp : trackPlayers) {
                try {
                    tp.onScriptReplaced();
                } catch (Exception e) {
                    com.immersivecinematics.immersive_cinematics.util.ErrorLog.log("Playback", "TrackPlayer onScriptReplaced 异常", e);
                }
            }
        }
        // 玩家移动控制：增量替换后重新提取 EVENT position 关键帧
        playerMovement.onScriptStart(newScript, originPos);
    }

    /**
     * 维度校验（0.3.5 B）：CAMERA clip 的 dimension 字段 ≠ 玩家当前维度 → 日志提示。
     * 不做自动切换（跨维度运镜为 0.4.0 F 类，消费本字段）。有错即报错，不兜底。
     */
    private void validateClipDimensions(CinematicScript script) {
        if (script == null || script.getTimeline() == null) return;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        String playerDim = mc.level != null ? mc.level.dimension().location().toString() : "";
        for (TimelineTrack track : script.getTimeline().getTracks()) {
            if (track.getType() != TrackType.CAMERA) continue;
            for (Clip clip : track.getClips()) {
                String dim = clip.getString("dimension", "");
                if (!dim.isEmpty() && !dim.equals(playerDim)) {
                    LOGGER.warn("脚本 '{}' CAMERA clip ({}s) 声明维度 '{}' ≠ 玩家当前维度 '{}'：本版本不自动切换维度（0.4.0 预留）",
                            script.getId(), clip.getStartTime(), dim, playerDim);
                }
            }
        }
    }

    /** 轨道布局相同判断:轨道数 + 类型顺序一致(忽略 clip 内容) */
    private static boolean sameTrackLayout(List<TimelineTrack> a, List<TimelineTrack> b) {        if (a == null || b == null) return false;
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i).getType() != b.get(i).getType()) return false;
        }
        return true;
    }

    /** 按脚本轨道创建 TrackPlayer(跳过 EVENT 轨道;trackIndex 用原始轨道索引,轨道布局变化时必须重建) */
    private void buildTrackPlayers(CinematicScript script) {
        trackPlayers = new ArrayList<>();
        List<TimelineTrack> tracks = script.getTimeline().getTracks();
        for (int ti = 0; ti < tracks.size(); ti++) {
            TimelineTrack track = tracks.get(ti);
            if (track.getType() != TrackType.EVENT) {  // event 轨道不在客户端处理
                trackPlayers.add(TrackPlayer.create(track.getType(), this, originPos,
                        CameraManager.INSTANCE, OverlayManager.INSTANCE, ti));
            }
        }
    }

    // ========== 生命周期 ==========

    /** 停止并清空当前所有 TrackPlayer（start 前与 stop 时共用，防音频实例泄漏） */
    private void cleanupTrackPlayers() {
        for (TrackPlayer tp : trackPlayers) {
            try {
                tp.onStop();
            } catch (Exception e) {
                com.immersivecinematics.immersive_cinematics.util.ErrorLog.log("Playback", "TrackPlayer 停止异常", e);
            }
        }
        trackPlayers = Collections.emptyList();
    }

    /**
     * 释放脚本里带 {@code lut} 的片段在 {@link CubeLutLoader} 的 clip 引用（播放结束 / 换脚本时）。
     * <p>只摘「哪些 clip 引用了 LUT」的引用表，按路径共享的解析缓存不动——同一 LUT 的其它脚本 / 后续播放
     * 仍复用同一不可变实例；资源重载走 {@link CubeLutLoader#clearCache()}。</p>
     */
    private static void releaseLutClipRefs(CinematicScript script) {
        if (script == null || script.getTimeline() == null) return;
        for (TimelineTrack track : script.getTimeline().getTracks()) {
            for (Clip clip : track.getClips()) {
                if (clip.getLut() != null) {
                    CubeLutLoader.releaseClip(clip.getClipId());
                }
            }
        }
    }

    /**
     * 启动脚本播放（预执行首帧从脚本开头开始——游戏内播放路径）
     *
     * @param script 已解析的脚本对象
     */
    public void start(CinematicScript script) {
        start(script, 0f);
    }

    /**
     * 启动脚本播放
     *
     * @param script        已解析的脚本对象
     * @param preExecuteAt  预执行首帧的期望 elapsed 时间（预览模式 = previewTime，
     *                      避免重启后首帧写 t=0 造成画面跳变；游戏内 = 0）
     */
    public void start(CinematicScript script, float preExecuteAt) {
        // D1：替换 trackPlayers 前先清理旧实例（否则 AudioTrackPlayer 的 OpenAL source 泄漏 → 重复播放/无法停止）
        cleanupTrackPlayers();
        // LUT：被本次 start 顶掉的旧脚本的 clip 引用一并释放（this.script 尚未指向新脚本）
        releaseLutClipRefs(this.script);

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            com.immersivecinematics.immersive_cinematics.util.ErrorLog.log("Playback", "无法启动脚本：玩家不存在");
            return;
        }

        this.script = script;
        this.originPos = mc.player.position();
        this.playing = true;
        this.stopping = false;
        this.startGameTimeSeconds = clockSeconds();

        // 持有当前脚本的运行时行为
        ScriptMeta meta = script.getMeta();
        this.currentBehavior = meta.getBehavior();

        // 宏观循环：推导区间 [a, b] 与圈数并缓存（NaN = 不循环）
        computeMacroLoop();

        // block_mob_ai：清空已锁定玩家的生物目标
        if (currentBehavior.blockMobAi() && mc.level != null) {
            Player player = mc.player;
            AABB range = new AABB(player.blockPosition()).inflate(128);
            List<Mob> mobs = mc.level.getEntitiesOfClass(Mob.class, range);
            for (Mob mob : mobs) {
                if (mob.getTarget() == player) mob.setTarget(null);
                LivingEntity le = mob;
                if (le.getBrain().hasMemoryValue(MemoryModuleType.ATTACK_TARGET)
                        && le.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null) == player) {
                    le.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, Optional.empty());
                }
            }
        }

        // 创建 TrackPlayer 实例（组 A：数据源动态化，后续 replaceScript 不重建；传轨道索引支持同类型多轨道）
        buildTrackPlayers(script);

        // 玩家移动控制：提取 EVENT position 关键帧
        playerMovement.onScriptStart(script, originPos);

        // 维度校验（0.3.5 B）：CAMERA clip 声明 dimension ≠ 玩家当前维度 → 提示（不做自动切换，0.4.0 F 类）
        validateClipDimensions(script);

        // 预执行第一帧（避免首帧闪烁）— 用调用方期望的 elapsed（预览模式 = previewTime，避免 t=0 跳变）
        float elapsedSeconds = preExecuteAt;
        for (TrackPlayer tp : trackPlayers) {
            if (tp.isActiveAt(elapsedSeconds)) {
                try {
                    tp.onRenderFrame(elapsedSeconds);
                } catch (Exception e) {
                    com.immersivecinematics.immersive_cinematics.util.ErrorLog.log("Playback", "TrackPlayer 首帧执行异常", e);
                }
            }
        }

        LOGGER.info("脚本播放开始: {} (总时长: {}s, TrackPlayer数: {})",
                script.getName(), script.getTotalDuration(), trackPlayers.size());
    }

    /**
     * 停止脚本播放
     *
     * @param reason 完成原因
     */
    public void stop(CompletionReason reason) {
        if (script != null) {
            LOGGER.info("脚本播放停止: {} (原因: {})", script.getName(), reason);
        }

        // 通知所有 TrackPlayer 停止并清空
        cleanupTrackPlayers();
        // LUT：本脚本的 clip 引用释放（按路径共享的解析缓存不动；资源重载走 CubeLutLoader#clearCache）
        releaseLutClipRefs(this.script);
        // 玩家移动控制：停止驱动
        playerMovement.onStop();

        this.playing = false;
        this.stopping = false;
        this.script = null;
        this.currentBehavior = null;
        resetMacroLoop();
    }

    public boolean isPlaying() {
        return playing;
    }

    /**
     * 脚本是否已播放完成（时间耗尽）
     * <p>
     * 注意：此方法仅检查时间是否耗尽，不检查 holdAtEnd。
     * holdAtEnd 的保持逻辑由 CameraManager.onRenderFrame() 负责：
     * <ul>
     *   <li>holdAtEnd=false → 播完后自动 deactivateNow()</li>
     *   <li>holdAtEnd=true → 播完后保持相机状态，等待用户退出或新脚本打断</li>
     * </ul>
     */
    public boolean isFinished() {
        if (!playing || script == null) return false;
        if (isMacroLoopActive()) {
            // 无限重复 → 脚本不再自然结束（退出走 skippable / interruptible 路径）
            if (macroLoopCount < 0) return false;
            // 有限次数 → 播完第 N 圈后自然结束
            return getElapsedSeconds() >= macroLoopTotalEnd();
        }
        float totalDuration = script.getTotalDuration();
        if (totalDuration < 0) return false; // 无限循环脚本
        float elapsed = getElapsedSeconds();
        if (elapsed < totalDuration) return false;
        // 时间耗尽后：若存在已开始的无限循环片段（loop=true + loop_count=-1），脚本永不结束
        return !hasActiveInfiniteLoopClip(elapsed);
    }

    /**
     * 获取当前播放脚本的ID
     * <p>
     * 用于日志和调试，如 CameraManager 打印脚本抢占拒绝信息。
     *
     * @return 脚本ID，无脚本播放时返回 "<none>"
     */
    public String getScriptId() {
        return script != null ? script.getId() : "<none>";
    }

    /**
     * 获取剩余播放时间（秒）
     */
    public float getRemainingTime() {
        if (!playing || script == null) return 0f;
        if (isMacroLoopActive()) {
            if (macroLoopCount < 0) return Float.MAX_VALUE; // 无限重复：不自然结束
            return Math.max(0f, macroLoopTotalEnd() - getElapsedSeconds());
        }
        float totalDuration = script.getTotalDuration();
        if (totalDuration < 0) return Float.MAX_VALUE; // 无限循环
        if (hasActiveInfiniteLoopClip(getElapsedSeconds())) return Float.MAX_VALUE; // 无限循环片段已开始
        return Math.max(0f, totalDuration - getElapsedSeconds());
    }

    /**
     * 画面 lane 快照收集：按绘制顺序扁平化列出各 CAMERA 轨本帧的<b>全部活跃 clip</b>快照
     * ——先按轨道层级（时间轴中先出现的轨在前），再按轨道内 clip 顺序（<b>后面的在上</b>）。
     * <p>
     * 时间重叠窗口内一条轨可能贡献多份（如叠化：上一个 clip 的 hold 尾帧 + 下一个 clip 的 hold 首帧）；
     * 无重叠时每条轨一份。列表顺序即 lane 合成的绘制顺序：先画的在下，后画的盖在上面
     * （见 {@code LaneCompositor}）。
     * <p>
     * <b>顶层相机 = 列表最后一个元素</b>（轨道层级最后的活跃轨的顶层活跃 clip，即写入全局
     * {@link CameraManager} 的那一份状态）。本方法只读快照，不改变相机行为。
     * <p>
     * 每份 {@link LaneFrame} 携带产出它的片段与该片段内的本地时间（渲染侧据此取该时刻的合成参数
     * opacity / dest / source）、产出它的 CAMERA 轨序号、以及该 lane 的调色参数（来自该 lane 的
     * 相机片段：在片段本地时间处由 {@link ColorAdjustSampler} 采样——重叠窗口下同轨的多条 lane
     * 各自独立采样；恒等参数归一化为 {@code null} = 不调色）。该参数只承载 <b>RGB</b> 调整
     * （不含 alpha / opacity）：调色 pass 只动 RGB、alpha 逐位直通，透明度在<b>合成之后</b>由合成层调控。
     * <p>
     * <b>相机轨序号口径</b> = 时间轴里第几条 CAMERA 轨（0 起，按出现顺序）
     * （不是时间轴中的绝对轨道索引；OVERLAY / AUDIO 等其它轨不占号）。
     *
     * @return 扁平化的 lane 列表；无 CAMERA 轨 / 未播放 / 各轨本帧均无画面时为空列表
     */
    public List<LaneFrame> collectCameraLanes() {
        List<LaneFrame> frames = new ArrayList<>();
        int cameraLaneIndex = -1;
        for (TrackPlayer tp : trackPlayers) {
            if (tp instanceof CameraTrackPlayer ctp) {
                cameraLaneIndex++;
                for (CameraLane lane : ctp.getLaneSnapshots()) {
                    // 每条 lane 各自用「自己的相机片段 + 片段本地时间」采样调色（重叠窗口下各自独立）。
                    // 恒等参数归一化为 null —— 渲染侧因此保持 lane FBO 直接进合成的原路径。
                    ColorAdjustParams adjust = ColorAdjustSampler.sample(lane.clip(), lane.clipLocalTime());
                    frames.add(new LaneFrame(lane, cameraLaneIndex, adjust.isIdentity() ? null : adjust));
                }
            }
        }
        return frames;
    }

    public boolean hasActiveCameraTrack(float elapsed) {
        // 宏观循环：渲染门控（相机/FOV/roll/遮挡/玩家模型）必须与实际分发给轨道的时间一致，
        // 否则第二圈起 hasActiveCameraClip() 变 false → 相机不渲染
        float t = localTimeFor(elapsed);
        if (Float.isNaN(t)) return false; // 有限次数已播完：不渲染，等自然结束
        for (TrackPlayer tp : trackPlayers) {
            if (tp instanceof CameraTrackPlayer && tp.isActiveAt(t)) {
                return true;
            }
        }
        return false;
    }

    public CinematicScript getScript() {
        return script;
    }

    /**
     * 获取当前活跃的脚本运行时行为
     *
     * @return 当前脚本运行时行为，无脚本播放时返回 null
     */
    public ScriptMeta.RuntimeBehavior getCurrentProperties() {
        return currentBehavior;
    }

    // ========== 帧回调驱动 ==========

    /**
     * 每渲染帧驱动：由 CameraManager.onRenderFrame() 调用
     *
     * @param gameTimeSeconds 当前虚拟游戏时间（秒，double 精度）
     */
    public void onRenderFrame(double gameTimeSeconds) {
        if (!playing || script == null) return;

        float elapsedSeconds = getElapsedSeconds();
        float totalDuration = script.getTotalDuration();

        if (isMacroLoopActive()) {
            // 宏观循环（repeat）：从 a 到 b 重复执行；对外分发的一律是圈内局部时间。
            // 作用点唯一——TrackPlayer / PlayerMoveController 拿到的是圈内局部时间，不感知循环。
            float local = localTimeFor(elapsedSeconds);
            if (Float.isNaN(local)) {
                // 有限次数已播完：isFinished() 为 true，由 CameraManager 走自然结束
                return;
            }
            // 圈边界（本帧局部时间 < 上帧）→ 折返重置（其余状态每帧按时间重查，天然自愈）
            if (!Float.isNaN(lastLocalElapsed) && local < lastLocalElapsed) {
                playerMovement.onScriptLoop();
            }
            lastLocalElapsed = local;
            elapsedSeconds = local;
        } else if (totalDuration > 0 && elapsedSeconds >= totalDuration) {
            // 检查脚本是否结束（宏观循环与 hold 钳制互斥：开启循环时不走本分支）
            if (hasActiveInfiniteLoopClip(elapsedSeconds)) {
                // 无限循环片段（loop=true + loop_count=-1）已开始：脚本持续播放，不退出
            } else if (script.getMeta().isHoldAtEnd()) {
                // holdAtEnd: 保持在最后一帧
                elapsedSeconds = totalDuration - HOLD_END_EPSILON;
            } else {
                // 脚本结束，isFinished() 将返回 true
                return;
            }
        }

        // 调度所有 TrackPlayer（onRenderFrame 内部自行判断是否有活跃 clip，无需 isActiveAt() 预检查）
        for (TrackPlayer tp : trackPlayers) {
            try {
                tp.onRenderFrame(elapsedSeconds);
            } catch (Exception e) {
                com.immersivecinematics.immersive_cinematics.util.ErrorLog.log("Playback", "TrackPlayer 执行异常", e);
            }
        }

        // 玩家移动控制（EVENT position 关键帧驱动，与相机同一虚拟时钟，暂停感知）
        playerMovement.onRenderFrame(elapsedSeconds);
    }

    /**
     * Align the internal clock so that the script elapsed time matches a desired value.
     * Called by CameraManager when the script is reloaded during preview.
     */
    public void alignTime(float desiredElapsedSeconds, double currentGameTimeSeconds) {
        this.startGameTimeSeconds = currentGameTimeSeconds - desiredElapsedSeconds;
    }

    /**
     * 暂停所有音频轨道（游戏暂停时调用）
     */
    public void pauseAudio() {
        for (TrackPlayer tp : trackPlayers) {
            if (tp instanceof AudioTrackPlayer atp) {
                atp.pauseAll();
            }
        }
    }

    /**
     * 恢复所有音频轨道（游戏恢复时调用）
     */
    public void resumeAudio() {
        for (TrackPlayer tp : trackPlayers) {
            if (tp instanceof AudioTrackPlayer atp) {
                atp.resumeAll();
            }
        }
    }

    /**
     * Reposition audio tracks to match a new global time.
     * Called from CameraManager when editor playhead is dragged.
     */
    public void repositionAudio(float globalTime) {
        for (TrackPlayer tp : trackPlayers) {
            if (tp instanceof AudioTrackPlayer atp) {
                atp.repositionAudio(globalTime);
            }
        }
    }

    // ========== 内部方法 ==========

    /**
     * 开关宏观循环。编辑器预览实例必须关闭——预览按真实时间线播放：
     * 循环是运行时播放控制，编辑器时间轴既不展开也不折叠。
     */
    public void setMacroLoopAllowed(boolean allowed) {
        if (this.macroLoopAllowed == allowed) return;
        this.macroLoopAllowed = allowed;
        computeMacroLoop();
    }

    /** 清空宏观循环参数（不循环） */
    private void resetMacroLoop() {
        macroLoopStart = 0f;
        macroLoopEnd = Float.NaN;
        macroLoopSpan = 0f;
        macroLoopCount = -1;
        lastLocalElapsed = Float.NaN;
    }

    /** 宏观循环是否生效（区间 [a, b] 已推导出来） */
    private boolean isMacroLoopActive() {
        return !Float.isNaN(macroLoopEnd);
    }

    /** 有限次数宏观循环的总播放时长（秒）：a + N × (b − a)；仅在 macroLoopCount &gt; 0 时有意义 */
    private float macroLoopTotalEnd() {
        return macroLoopStart + macroLoopSpan * macroLoopCount;
    }

    /**
     * 推导宏观循环参数（区间 [a, b] 与圈数）；不满足条件 → 不循环（macroLoopEnd = NaN）。
     * <p>
     * 条件：{@code meta.macro_loop=true} + 宏观循环被允许（预览实例关闭）+ 区间非空。
     * a = {@code timeline.loop_start}（缺省 0，负值按 0）；b 优先取 {@code timeline.loop_end}
     * （作者声明的子区间），缺省取宏观末端——此时存在永不结束片段 → 末端不存在 → 不循环。
     * 脚本开始时调用一次并缓存。
     */
    private void computeMacroLoop() {
        resetMacroLoop();
        if (script == null || script.getMeta() == null || !macroLoopAllowed) return;
        if (!script.getMeta().isMacroLoop()) return;
        Timeline timeline = script.getTimeline();
        if (timeline == null) return;

        float a = Math.max(0f, timeline.getLoopStart());
        float b;
        if (timeline.getLoopEnd() > a) {
            b = timeline.getLoopEnd(); // 作者声明的子区间末端
        } else {
            Float macroEnd = computeMacroEnd();
            if (macroEnd == null) return; // 末端不存在（存在永不结束片段）→ 不循环
            b = macroEnd;
        }
        if (!(b > a)) return; // 空区间 → 不循环（避免对 0 取模）

        macroLoopStart = a;
        macroLoopEnd = b;
        macroLoopSpan = b - a;
        int count = script.getMeta().getMacroLoopCount();
        macroLoopCount = count > 0 ? count : -1;
    }

    /**
     * 圈内局部时间（repeat 语义）：t &lt; a 直通；t ≥ a → a + (t − a) mod (b − a)。
     * 圈边界（本帧局部时间 &lt; 上帧）即折返重置点。
     */
    private float toLocalTime(float elapsed) {
        if (elapsed < macroLoopStart) return elapsed;
        double rel = (double) elapsed - macroLoopStart;
        return macroLoopStart + (float) (rel % macroLoopSpan);
    }

    /**
     * 分发给轨道的圈内局部时间；有限次数已播完 → NaN（不渲染，脚本已结束）。
     * {@code hold_at_end} 时停在区间末端最后一帧。未开启循环 → 原值返回。
     */
    private float localTimeFor(float elapsed) {
        if (!isMacroLoopActive()) return elapsed;
        if (macroLoopCount > 0 && elapsed >= macroLoopTotalEnd()) {
            return script != null && script.getMeta().isHoldAtEnd()
                    ? Math.max(macroLoopStart, macroLoopEnd - HOLD_END_EPSILON)
                    : Float.NaN;
        }
        return toLocalTime(elapsed);
    }

    /**
     * 宏观末端（秒）= 脚本内所有片段展开结束时刻（{@link Clip#getWindowEnd()}，含片段自身循环展开）的最大值。
     * <p>
     * 存在永不结束的片段（{@link Clip#isEffectivelyInfinite()}）→ 末端不存在 → 返回 null（不循环）。
     * 不用作者声明的 total_duration 当末端。
     */
    private Float computeMacroEnd() {
        if (script == null || script.getTimeline() == null) return null;
        float end = 0f;
        for (TimelineTrack track : script.getTimeline().getTracks()) {
            for (Clip clip : track.getClips()) {
                if (clip.isEffectivelyInfinite()) return null; // 永不结束片段 → 末端不存在
                end = Math.max(end, clip.getWindowEnd());
            }
        }
        return end > 0f ? end : null; // 空时间轴 / 零长度：不循环，避免对 0 取模
    }

    /**
     * 是否存在已开始（elapsed >= clip.startTime）且永不结束（无限时长或无限循环）的片段。
     * 用于时间耗尽后判定脚本是否因无限循环片段而持续播放。
     */
    private boolean hasActiveInfiniteLoopClip(float elapsed) {
        if (script == null) return false;
        for (TimelineTrack track : script.getTimeline().getTracks()) {
            for (Clip clip : track.getClips()) {
                if (clip.isEffectivelyInfinite() && elapsed >= clip.getStartTime()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 本实例已播放时间（秒）= 本实例时钟读数 − 起始读数。
     * <p>
     * 时钟源随实例走（{@link #setClockSource}）：游戏实例 = 全局虚拟时钟；预览实例 = 预览播放头。
     */
    public float getElapsedSeconds() {
        return (float)(clockSeconds() - startGameTimeSeconds);
    }

    /**
     * 设置本实例的时钟源（秒）；{@code null} = 保持当前（默认游戏共享虚拟时钟）。
     * <p>
     * 由 {@code CameraManager} 在创建预览实例时注入预览播放头（§7 步骤 5）；必须在
     * {@link #start(CinematicScript, float)} 之前设置，起始读数在 start 时取。
     */
    public void setClockSource(Clock source) {
        if (source != null) this.clockSource = source;
    }

    /** 本实例当前时钟读数（秒）。 */
    private double clockSeconds() {
        return clockSource.seconds();
    }
}
