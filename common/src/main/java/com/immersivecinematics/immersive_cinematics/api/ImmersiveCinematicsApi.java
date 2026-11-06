package com.immersivecinematics.immersive_cinematics.api;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.camera.cover.CameraModifier;
import com.immersivecinematics.immersive_cinematics.script.schema.MathModelRegistry;
import com.immersivecinematics.immersive_cinematics.script.schema.PathCurveRegistry;
import com.immersivecinematics.immersive_cinematics.script.track.TrackRegistry;
import com.immersivecinematics.immersive_cinematics.spi.FieldDef;
import com.immersivecinematics.immersive_cinematics.spi.MathModel;
import com.immersivecinematics.immersive_cinematics.spi.ModelParams;
import com.immersivecinematics.immersive_cinematics.spi.PathCapability;
import com.immersivecinematics.immersive_cinematics.spi.PathParamDef;
import com.immersivecinematics.immersive_cinematics.spi.PathParams;
import com.immersivecinematics.immersive_cinematics.spi.PathSpec;
import com.immersivecinematics.immersive_cinematics.spi.TrackSchema;
import com.immersivecinematics.immersive_cinematics.spi.TrackTypeId;
import com.immersivecinematics.immersive_cinematics.spi.TriggerPrerequisite;
import com.immersivecinematics.immersive_cinematics.spi.TriggerType;
import com.immersivecinematics.immersive_cinematics.trigger.server.TriggerRegistry;
import com.immersivecinematics.immersive_cinematics.trigger.server.prereq.PrerequisiteRegistry;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 统一注册门面（对外稳定面）：第三方模组只通过本类一个入口登记全部八类扩展点
 * （轨道 / 触发器 / 数学模型 / 路径曲线 / 前置条件 / Base 相机 / 相机 Modifier / HUD 层）。
 *
 * <p><b>不建表</b>：本类<b>不持有任何注册表</b>，每个方法都转发到既有注册表
 * （{@link TrackRegistry}、{@link TriggerRegistry}、{@link MathModelRegistry}、{@link PathCurveRegistry}、
 * {@link PrerequisiteRegistry}、{@link HudLayerRegistry}、{@code CameraManager} 持有的覆盖链）。
 * 冲突检查全部落在本层，内部注册表的既有语义不变。</p>
 *
 * <p><b>注册时机</b>：注册只能在模组初始化期完成，即<b>首个脚本解析之前</b>。{@code ScriptParser.parse}
 * 的第一行冻结轨道注册表；此后本类除 {@link #registerTrack}（由 {@link TrackRegistry} 自行判定）之外的
 * 一切注册 / 注销调用抛 {@link IllegalStateException}，消息含「注册必须在模组初始化期完成」。
 * 冻结是进程内一次性的：跨存档 / 资源重载不重置。{@link #isRegistrationFrozen()} 供第三方自查。</p>
 *
 * <p><b>id 冲突</b>：不静默覆盖。内置轨道 id 受注册表保护；第三方之间的重复登记一律抛
 * {@link IllegalArgumentException}（{@link #registerPathCurve} 的重复判定由 {@link PathCurveRegistry}
 * 自己完成，异常原样上抛）。唯一例外是 HUD 层：后注册覆盖先注册是既定语义，不报错。</p>
 *
 * <p><b>线程契约</b>：注册调用与各自目标注册表的主线程一致——客户端主线程：相机（Base / Modifier）、
 * HUD 层；服务端主线程：触发器、前置条件（事实只读面见 {@link ServerFactsApi}）。轨道注册在
 * {@link TrackRegistry} 的初始化期完成，不承诺运行期跨线程安全。</p>
 *
 * <p><b>执行契约类型</b>：{@link #registerMathModel} / {@link #registerPathCurve} 的工厂入参仍是既有类型，
 * 位于 {@code math} / {@code math.path} / {@code script.schema} 包（本轮未重编包名，不提供镜像类型）。</p>
 *
 * <p><b>稳定性</b>：本包（{@code api}）内的类型与方法<b>只增不删</b>，语义不变；破坏性变更只允许发生在
 * {@code spi} 包与内部实现。</p>
 */
public final class ImmersiveCinematicsApi {

    private ImmersiveCinematicsApi() {
    }

    // ========== 轨道 ==========

    /**
     * 登记一条第三方轨道类型。
     *
     * <p>内置保护 / id 重复 / schema 自检 / 冻结判定全部由 {@link TrackRegistry#register} 完成：
     * 失败抛 {@link IllegalArgumentException}；已冻结抛 {@link IllegalStateException}。</p>
     *
     * @param id      轨道类型 id（区分大小写；不得与已登记 id 重复）
     * @param schema  对象声明表（{@link TrackSchema#builder()} 构造）
     * @param factory 播放回调工厂（每段活跃期调用一次）
     * @return 原样返回 {@code id}
     * @throws IllegalArgumentException id / schema / factory 非法、id 已登记或 schema 自检不通过
     * @throws IllegalStateException    注册表已冻结（注册必须在模组初始化期完成）
     */
    public static TrackTypeId registerTrack(TrackTypeId id, TrackSchema schema,
                                            TrackRegistry.TrackPlayerFactory factory) {
        TrackRegistry.register(id, schema, factory);
        return id;
    }

    // ========== 触发器 ==========

    /**
     * 登记一个触发器类型。同 id 重复登记抛 {@link IllegalArgumentException}，不静默覆盖。
     *
     * @param type 触发器类型定义（非 null）
     * @throws IllegalArgumentException {@code type == null} 或 id 已登记
     * @throws IllegalStateException    注册表已冻结（注册必须在模组初始化期完成）
     */
    public static void registerTrigger(TriggerType type) {
        ensureNotFrozen();
        if (type == null) {
            throw new IllegalArgumentException("触发器类型不能为 null");
        }
        String id = type.getId();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("触发器类型 id 不能为空");
        }
        if (TriggerRegistry.get(id) != null) {
            throw new IllegalArgumentException("触发器 id 已登记: " + id);
        }
        TriggerRegistry.register(type);
    }

    // ========== 数学模型 ==========

    /**
     * 登记一个数学模型类型。同 id 重复登记抛 {@link IllegalArgumentException}，不静默覆盖。
     *
     * @param id      注册名（snake_case，与 JSON 的 {@code type} 一致；不得为空白）
     * @param params  参数 schema（键顺序 = 编辑器表单顺序）
     * @param factory 归一参数 → 模型定义
     * @throws IllegalArgumentException id 为空白或已登记
     * @throws IllegalStateException    注册表已冻结（注册必须在模组初始化期完成）
     */
    public static void registerMathModel(String id, Map<String, FieldDef> params,
                                         Function<ModelParams, MathModel> factory) {
        ensureNotFrozen();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("数学模型类型名不能为空");
        }
        if (MathModelRegistry.types().contains(id)) {
            throw new IllegalArgumentException("数学模型类型已登记: " + id);
        }
        MathModelRegistry.register(id, params, factory);
    }

    // ========== 路径曲线 ==========

    /**
     * 登记一个路径曲线类型。重复登记由 {@link PathCurveRegistry} 判定，
     * 其 {@link IllegalArgumentException} 原样上抛（不静默覆盖）。
     *
     * @param id           注册名（与 JSON 的 {@code type} 一致；不得为空白）
     * @param capabilities 该曲线声明具备的能力
     * @param params       参数 schema（键顺序 = 编辑器表单顺序）
     * @param factory      归一参数 → 路径定义
     * @throws IllegalArgumentException id 为空白；或注册表判定重复 / 参数非法
     * @throws IllegalStateException    注册表已冻结（注册必须在模组初始化期完成）
     */
    public static void registerPathCurve(String id, Set<PathCapability> capabilities,
                                         List<PathParamDef> params,
                                         Function<PathParams, PathSpec> factory) {
        ensureNotFrozen();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("曲线类型名不能为空");
        }
        PathCurveRegistry.register(id, capabilities, params, factory);
    }

    // ========== 前置条件 ==========

    /**
     * 登记一个触发器前置条件类型。同 id 重复登记抛 {@link IllegalArgumentException}，不静默覆盖。
     *
     * @param id           类型标识（建议 {@code modid:name}）
     * @param prerequisite 求值器
     * @throws IllegalArgumentException id 为空白或已登记
     * @throws IllegalStateException    注册表已冻结（注册必须在模组初始化期完成）
     */
    public static void registerPrerequisite(String id, TriggerPrerequisite prerequisite) {
        ensureNotFrozen();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("前置条件类型名不能为空");
        }
        if (prerequisite == null) {
            throw new IllegalArgumentException("前置条件求值器不能为 null: " + id);
        }
        if (PrerequisiteRegistry.has(id)) {
            throw new IllegalArgumentException("前置条件类型已登记: " + id);
        }
        PrerequisiteRegistry.register(id, prerequisite);
    }

    // ========== 相机（客户端） ==========

    /**
     * 登记一层 Base 相机。线程 = 客户端主线程；注册 / 注销与每帧求值同线程。
     *
     * @param provider Base 层实现（非 null）
     * @throws IllegalArgumentException {@code provider == null}
     * @throws IllegalStateException    注册表已冻结（注册必须在模组初始化期完成）
     */
    public static void registerBaseCamera(BaseCameraProvider provider) {
        ensureNotFrozen();
        if (provider == null) {
            throw new IllegalArgumentException("Base 相机层不能为 null");
        }
        CameraManager.INSTANCE.registerBaseCamera(provider);
    }

    /**
     * 注销一层 Base 相机；未注册过时为无操作。线程 = 客户端主线程。
     *
     * @param provider 先前登记的 Base 层（非 null）
     * @throws IllegalArgumentException {@code provider == null}
     * @throws IllegalStateException    注册表已冻结（注册必须在模组初始化期完成）
     */
    public static void unregisterBaseCamera(BaseCameraProvider provider) {
        ensureNotFrozen();
        if (provider == null) {
            throw new IllegalArgumentException("Base 相机层不能为 null");
        }
        CameraManager.INSTANCE.unregisterBaseCamera(provider);
    }

    /**
     * 登记一条相机 Modifier。线程 = 客户端主线程；注册 / 注销与每帧求值同线程。
     *
     * @param modifier Modifier 实现（非 null）
     * @throws IllegalArgumentException {@code modifier == null}
     * @throws IllegalStateException    注册表已冻结（注册必须在模组初始化期完成）
     */
    public static void registerCameraModifier(CameraModifier modifier) {
        ensureNotFrozen();
        if (modifier == null) {
            throw new IllegalArgumentException("相机 Modifier 不能为 null");
        }
        CameraManager.INSTANCE.registerCameraModifier(modifier);
    }

    /**
     * 注销一条相机 Modifier；未注册过时为无操作。线程 = 客户端主线程。
     *
     * @param modifier 先前登记的 Modifier（非 null）
     * @throws IllegalArgumentException {@code modifier == null}
     * @throws IllegalStateException    注册表已冻结（注册必须在模组初始化期完成）
     */
    public static void unregisterCameraModifier(CameraModifier modifier) {
        ensureNotFrozen();
        if (modifier == null) {
            throw new IllegalArgumentException("相机 Modifier 不能为 null");
        }
        CameraManager.INSTANCE.unregisterCameraModifier(modifier);
    }

    // ========== HUD 层（客户端） ==========

    /**
     * 注册一个 overlay 到指定分类。转发到唯一注册表 {@link HudLayerRegistry}；后注册覆盖先注册
     * 是既定语义（不报错）。线程 = 客户端主线程。
     *
     * @param overlayId overlay 资源 ID（null 由注册表静默忽略）
     * @param category  分类名（空串按「未指定分类」处理）
     * @throws IllegalStateException 注册表已冻结（注册必须在模组初始化期完成）
     */
    public static void registerHudLayer(ResourceLocation overlayId, String category) {
        ensureNotFrozen();
        HudLayerRegistry.register(overlayId, category);
    }

    /**
     * 注册一个 overlay，分类 = 资源 ID 字符串。转发到 {@link HudLayerRegistry}。线程 = 客户端主线程。
     *
     * @param overlayId overlay 资源 ID（null 由注册表静默忽略）
     * @throws IllegalStateException 注册表已冻结（注册必须在模组初始化期完成）
     */
    public static void registerHudLayer(ResourceLocation overlayId) {
        ensureNotFrozen();
        HudLayerRegistry.register(overlayId);
    }

    // ========== 状态 ==========

    /**
     * 注册表是否已冻结（= 首个脚本已开始解析，注册窗口已关闭）。
     *
     * @return {@code true} = 此后一切注册 / 注销调用都会失败
     */
    public static boolean isRegistrationFrozen() {
        return TrackRegistry.isFrozen();
    }

    private static void ensureNotFrozen() {
        if (TrackRegistry.isFrozen()) {
            throw new IllegalStateException("注册已冻结：注册必须在模组初始化期完成（首个脚本解析前）");
        }
    }
}
