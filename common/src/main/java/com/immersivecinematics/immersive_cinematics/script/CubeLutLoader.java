package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.util.ErrorLog;
import com.immersivecinematics.immersive_cinematics.util.ResourcePath;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link CubeLut} 的资源定位与缓存 —— 与音频（{@code CinematicAudioInstance}）/ 图片（{@code TextureLoader}）
 * 同口径：文件名经 {@link ResourcePath#resolve(String)} 落到
 * {@code <游戏目录>/immersive_cinematics/resource/} 下，扩展名不敏感（{@code .cube} / {@code .CUBE} 都行，
 * 由调用方给文件名，本类不猜扩展名）。
 *
 * <h2>缓存</h2>
 * <ul>
 *   <li><b>按文件路径</b>缓存解析结果（同一文件只解析一次，多个 clip 共享同一不可变实例）；
 *       解析失败也会被记住（负缓存），保证「失败只记一次日志」、不每帧重试；</li>
 *   <li><b>按 clip</b>（{@link #forClip(String, String)} / {@link #releaseClip(String)}）是给下一子任务接
 *       {@code Clip} 的接线面：clip 只持有引用，底层实例仍按路径共享；</li>
 *   <li>{@link #clearCache()} 清空全部（资源重载时调用）。</li>
 * </ul>
 *
 * <h2>失败口径（与着色器加载失败一致）</h2>
 * 解析失败不抛给调用方、不静默：{@link ErrorLog} 记一条 ERROR（含文件名 + 行号 + 原因），
 * {@link #load(String)} / {@link #loadFile(Path, String)} 返回 {@code null} = 本次「无 LUT」，
 * 调用方按无 LUT 继续。
 */
public final class CubeLutLoader {

    /** 解析成功缓存：绝对路径 → 不可变 LUT。 */
    private static final Map<String, CubeLut> CACHE = new ConcurrentHashMap<>();

    /** 解析失败负缓存：绝对路径（避免同一坏文件每帧重解析 + 重复刷日志）。 */
    private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();

    /** 按 clip 的引用表（接线面，下一子任务接 Clip）：clipId → LUT（与路径缓存共享实例）。 */
    private static final Map<String, CubeLut> CLIP_CACHE = new ConcurrentHashMap<>();

    private CubeLutLoader() {}

    /**
     * 从 {@code resource/} 目录加载并解析一个 .cube 文件（按路径缓存）。
     *
     * @param fileName 资源目录下的文件名（如 {@code "film.cube"}）
     * @return 解析结果；文件不存在或格式非法返回 {@code null}（已记日志）
     */
    public static CubeLut load(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return null;
        }
        return loadFile(ResourcePath.resolve(fileName), fileName);
    }

    /**
     * 从<b>显式路径</b>加载并解析一个 .cube 文件（按绝对路径缓存）。
     * <p>不经 {@code resource/} 目录 —— 供编辑器/校验器按用户选定的路径加载，也便于脱离客户端单测。</p>
     *
     * @param file        文件路径
     * @param displayName 错误信息中使用的文件名
     * @return 解析结果；文件不存在或格式非法返回 {@code null}（已记日志）
     */
    public static CubeLut loadFile(Path file, String displayName) {
        if (file == null) {
            return null;
        }
        String key = file.toAbsolutePath().toString();

        CubeLut cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        if (FAILED.contains(key)) {
            return null;
        }

        if (!Files.exists(file)) {
            FAILED.add(key);
            ErrorLog.log("LUT", "LUT 文件不存在（" + displayName + "）：本次不做 LUT，检查 " + file);
            return null;
        }

        try {
            CubeLut lut = CubeLut.parse(file, displayName);
            CACHE.put(key, lut);
            return lut;
        } catch (CubeLut.CubeLutException e) {
            FAILED.add(key);
            ErrorLog.log("LUT", "LUT 解析失败（" + displayName + "）：本次不做 LUT，检查 " + file, e);
            return null;
        }
    }

    /**
     * 接线面：按 clip 取 LUT（同一文件仍共享同一实例）。
     * <p>下一子任务把 {@code Clip} 的 {@code lut} 字段接到这里；{@code clipId} 由调用方给（如 clip 的稳定标识）。
     * 解析失败时该 clip 记录被清掉、返回 {@code null}。</p>
     *
     * @param clipId   该 clip 的标识（同一 clip 反复调用应传同一值）
     * @param fileName 资源目录下的 .cube 文件名
     * @return LUT；失败返回 {@code null}
     */
    public static CubeLut forClip(String clipId, String fileName) {
        CubeLut lut = load(fileName);
        if (clipId != null) {
            if (lut != null) {
                CLIP_CACHE.put(clipId, lut);
            } else {
                CLIP_CACHE.remove(clipId);
            }
        }
        return lut;
    }

    /** 接线面：释放某个 clip 的 LUT 引用（clip 结束时调用；不释放共享的路径缓存）。 */
    public static void releaseClip(String clipId) {
        if (clipId != null) {
            CLIP_CACHE.remove(clipId);
        }
    }

    /** 清空全部缓存（解析缓存 + 负缓存 + clip 引用表）；资源重载时调用。 */
    public static void clearCache() {
        CACHE.clear();
        FAILED.clear();
        CLIP_CACHE.clear();
    }
}
