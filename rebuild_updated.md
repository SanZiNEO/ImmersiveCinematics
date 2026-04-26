# ImmersiveCinematics 完全重构技术规范（修订版）
## 基于Minecraft模组架构设计提示词

## 1. 项目概述

### 1.1 核心目标
- **完全替换游戏现有相机系统和脚本执行框架**
- **实现高性能、解耦式相机系统**
- **支持多加载器（Forge/Fabric/NeoForge）架构**
- **提供编辑器-播放器分离的双产品版本**

### 1.2 架构原则
1. **解耦式相机系统** - CameraProperties与CameraPath完全分离
2. **客户端-服务器分离** - 服务端仅负责脚本分发，客户端专注播放
3. **游戏内编辑器集成** - 编辑器作为模组内置功能，在游戏内运行
4. **双产品版本支持** - 完整版（包含编辑器）和播放器版（仅播放逻辑）
5. **多加载器支持** - Forge/Fabric/NeoForge统一架构
6. **纯数据架构** - 相机系统不依赖Minecraft Entity，使用纯POJO数据类管理所有相机属性和位置
7. **CameraManager唯一桥梁** - CameraProperties与CameraPath互不知晓，通过CameraManager间接交互
8. **Mixin只读Manager** - Mixin注入层不直接依赖CameraProperties/CameraPath，统一从CameraManager读取

## 2. 核心模块边界定义

### 2.1 整体项目结构
```
ImmersiveCinematics/
├── common/                          # 核心共享库（不依赖特定加载器）
│   ├── camera/                    # 相机系统
│   ├── timeline/                  # 时间轴系统
│   ├── script/                    # 脚本格式与解析
│   ├── events/                    # 事件系统
│   ├── network/                   # 网络通信协议
│   └── editor/                    # 游戏内编辑器（作为核心功能）
├── forge/                         # Forge加载器实现
│   └── src/
│       ├── main/java/
│       └── main/resources/
├── fabric/                        # Fabric加载器实现
│   └── src/
│       ├── main/java/
│       └── main/resources/
└── neoforge/                      # NeoForge加载器实现
│   └── src/
│       ├── main/java/
│       └── main/resources/
```

### 2.2 模块职责划分

#### **核心库 (core)**
- **职责**：提供加载器无关的通用功能
- **包含**：
  1. CameraProperties/CameraPath/CameraManager组件
  2. 时间轴数据结构
  3. 脚本格式与序列化
  4. 网络通信协议定义
  5. 事件系统接口

#### **加载器实现模块 (forge/fabric/neoforge)**
- **职责**：适配特定加载器的实现
- **包含**：
  1. 加载器特定的注册和初始化
  2. 网络包注册和处理器
  3. 事件总线集成
  4. 配置系统适配

#### **编辑器功能 (editor)**
- **职责**：游戏内图形化脚本创作工具
- **位置**：作为common模块的一部分
- **包含**：
  1. 可视化时间轴编辑器（游戏内UI）
  2. 3D场景预览（在游戏世界中）
  3. 脚本导入/导出（游戏内操作）
  4. 关键帧编辑工具（游戏内交互）
- **构建控制**：通过`build.editor`开关控制是否包含

#### **播放器模块 (player)**
- **职责**：轻量级脚本运行时
- **包含**：
  1. 脚本解析和执行
  2. 相机系统运行时
  3. 事件触发器（仅用于启动/停止脚本）
  4. 网络通信客户端

## 3. 核心相机组件设计（纯数据架构）

> **架构决策：不使用 Minecraft Entity 作为相机核心**
>
> 相机系统采用纯 POJO 数据类而非 Entity 实现，理由如下：
> 1. **性能**：Entity 每tick执行 baseTick/碰撞检测/网络同步等无用开销，纯数据类仅做属性插值
> 2. **解耦**：Entity 只能持有 MC 系统认知的属性（位置/旋转/生命值），FOV/Roll/DOF/缩放等必须靠外部数据类
> 3. **稳定性**：Entity 生命周期管理（addFreshEntity/remove）是真实 bug 来源，纯数据单例无此问题
> 4. **扩展性**：纯数据类加字段即可扩展，Entity 需要持续"堵漏"（isInvulnerable/isPushable/canBeCollidedWith）
> 5. **强制渲染区块**：Forge 的 forceChunk API 使用 Ticket 机制与 Entity 无关，客户端区块缓存覆盖直接读 CameraManager 更直接
>
> 核心架构：`CameraProperties` ↔ `CameraManager` ↔ `CameraPath`，两者互不知晓，Manager 是唯一桥梁

### 3.1 CameraProperties（相机属性控制器）

#### **类职责**
- 管理相机的**视觉属性**：FOV、Roll、DOF、缩放等
- 纯数学计算，无 Minecraft 类依赖
- 提供带时长的目标插值（current → target，经 duration 完成）
- 完全不知道 CameraPath 的存在

#### **类定义**
```java
public class CameraProperties {
    // --- 当前值 ---
    private float currentFov = 70.0f;
    private float currentRoll = 0.0f;    // 翻滚角（度）
    private float currentDof = 0.0f;     // 景深（0=关闭）
    private float currentZoom = 1.0f;    // 缩放倍率
    
    // --- 目标值 ---
    private float targetFov = 70.0f;
    private float targetRoll = 0.0f;
    private float targetDof = 0.0f;
    private float targetZoom = 1.0f;
    
    // --- 插值控制 ---
    private float startFov, startRoll, startDof, startZoom; // 插值起始值
    private float transitionDuration = 0f;  // 过渡时长（秒），0=瞬时
    private float transitionProgress = 1f;  // 0~1，1=已完成
    
    // 设置目标值（带过渡时长）
    public void setTargetFov(float fov, float duration);
    public void setTargetRoll(float roll, float duration);
    public void setTargetDof(float dof, float duration);
    public void setTargetZoom(float zoom, float duration);
    
    // 每tick驱动插值
    public void tick(float deltaTime);
    
    // 重置到默认值
    public void reset();
    
    // 获取当前值（供 Mixin 读取）
    public float getFov() { return currentFov; }
    public float getRoll() { return currentRoll; }
    public float getDof() { return currentDof; }
    public float getZoom() { return currentZoom; }
}
```

#### **tick() 核心逻辑**
```java
public void tick(float deltaTime) {
    if (transitionProgress < 1f) {
        transitionProgress = Math.min(1f, transitionProgress + deltaTime / transitionDuration);
        float t = transitionProgress; // 线性插值，后续可替换为缓动函数
        currentFov = lerp(startFov, targetFov, t);
        currentRoll = lerp(startRoll, targetRoll, t);
        currentDof = lerp(startDof, targetDof, t);
        currentZoom = lerp(startZoom, targetZoom, t);
    }
}
```

### 3.2 CameraPath（相机位置控制器）

#### **类职责**
- 管理相机的**世界位置**：position(x,y,z) + rotation(yaw,pitch)
- 纯数据，无 Minecraft 类依赖（Vec3d 除外）
- 提供带时长的目标插值
- 完全不知道 CameraProperties 的存在

#### **类定义**
```java
public class CameraPath {
    // --- 当前值 ---
    private Vec3 currentPosition = Vec3.ZERO;
    private float currentYaw = 0f;
    private float currentPitch = 0f;
    
    // --- 目标值 ---
    private Vec3 targetPosition = Vec3.ZERO;
    private float targetYaw = 0f;
    private float targetPitch = 0f;
    
    // --- 插值控制 ---
    private Vec3 startPosition;
    private float startYaw, startPitch;
    private float transitionDuration = 0f;
    private float transitionProgress = 1f;
    
    // 设置目标值（带过渡时长）
    public void setTargetPosition(Vec3 pos, float duration);
    public void setTargetRotation(float yaw, float pitch, float duration);
    
    // 每tick驱动插值
    public void tick(float deltaTime);
    
    // 重置到玩家视角
    public void resetFromPlayer();
    
    // 获取当前值（供 Mixin 读取）
    public Vec3 getPosition() { return currentPosition; }
    public float getYaw() { return currentYaw; }
    public float getPitch() { return currentPitch; }
}
```

#### **tick() 核心逻辑**
```java
public void tick(float deltaTime) {
    if (transitionProgress < 1f) {
        transitionProgress = Math.min(1f, transitionProgress + deltaTime / transitionDuration);
        float t = transitionProgress;
        currentPosition = startPosition.lerp(targetPosition, t);
        currentYaw = lerpAngle(startYaw, targetYaw, t);
        currentPitch = lerpAngle(startPitch, targetPitch, t);
    }
}
```

### 3.3 CameraManager（统一调度器）

#### **类职责**
- 单例模式，是 CameraProperties 和 CameraPath 的唯一桥梁
- 每tick驱动两个子系统的 tick()
- 提供 Mixin 可读取的静态访问点
- 管理相机激活/停用状态

#### **类定义**
```java
public class CameraManager {
    public static final CameraManager INSTANCE = new CameraManager();
    
    private final CameraProperties properties = new CameraProperties();
    private final CameraPath path = new CameraPath();
    private boolean active = false;
    
    // --- 生命周期 ---
    public void activate();   // 从玩家当前位置激活
    public void deactivate();  // 停用，恢复玩家视角
    
    // --- 每tick驱动（由 ClientTickEvent 调用）---
    public void tick() {
        if (!active) return;
        float deltaTime = 1f / 20f; // 每tick 0.05秒
        properties.tick(deltaTime);
        path.tick(deltaTime);
    }
    
    // --- Mixin 读取接口 ---
    public CameraProperties getProperties() { return properties; }
    public CameraPath getPath() { return path; }
    public boolean isActive() { return active; }
    
    // --- 便捷方法 ---
    public void reset() {
        properties.reset();
        path.resetFromPlayer();
    }
}
```

### 3.4 Mixin 注入层

> Mixin 层只读 CameraManager，不直接依赖 CameraProperties/CameraPath

#### **CameraMixin — 覆盖位置和旋转**
```java
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow protected abstract void setPosition(double x, double y, double z);
    @Shadow protected abstract void setRotation(float yaw, float pitch);
    
    @Inject(method = "setup", at = @At("HEAD"), cancellable = true)
    private void onSetup(BlockGetter level, Entity entity, boolean detached,
                         boolean mirror, float partialTick, CallbackInfo ci) {
        CameraManager mgr = CameraManager.INSTANCE;
        if (mgr.isActive()) {
            Vec3 pos = mgr.getPath().getPosition();
            setPosition(pos.x, pos.y, pos.z);
            setRotation(mgr.getPath().getYaw(), mgr.getPath().getPitch());
            ci.cancel();  // setRotation 内部会自动计算 forward/left/up 向量
        }
    }
}
```

#### **GameRendererMixin — 覆盖 FOV 和 Roll**
```java
@Mixin(GameRenderer.class)
public class GameRendererMixin {
    // FOV 覆盖
    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void onGetFov(CallbackInfoReturnable<Double> cir) {
        CameraManager mgr = CameraManager.INSTANCE;
        if (mgr.isActive()) {
            cir.setReturnValue((double) mgr.getProperties().getFov());
        }
    }
    
    // Roll 注入：在渲染世界前对 PoseStack 做 Z 轴旋转
    // 具体注入点需在 renderLevel() 中相机设置之后、世界渲染之前
    // poseStack.mulPose(Axis.ZP.rotationDegrees(roll));
    // 注：Minecraft 1.20.1 无原生 Roll 支持，需修改投影矩阵
}
```

#### **EntityRenderDispatcherMixin — 仅用于隐藏相机实体（如保留）**
```java
@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDispatcherMixin {
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void onShouldRender(E entity, Frustum frustum,
                                                    double x, double y, double z,
                                                    CallbackInfoReturnable<Boolean> cir) {
        // 纯数据方案无 Entity，此 Mixin 仅在保留 CinematicCameraEntity 时需要
        if (entity instanceof CinematicCameraEntity) {
            cir.setReturnValue(false);
        }
    }
}
```

## 4. 时间轴系统设计

### 4.1 多轨道时间轴结构

根据ui_plan_v1.md中的设计需求，时间轴系统需要支持多轨道并行编辑，每个轨道代表不同的运镜路线或事件类型。

#### **主时间轴管理器（MasterTimelineManager）**
```java
public class MasterTimelineManager {
    private final double totalDuration; // 总时长（秒）
    private final Map<String, TimelineTrack> tracks; // 轨道ID -> 轨道对象
    private double currentPlaybackTime; // 当前播放时间（秒）
    
    // 轨道管理
    public void addTrack(String trackId, TimelineTrack track);
    public void removeTrack(String trackId);
    public TimelineTrack getTrack(String trackId);
    
    // 时间管理
    public void setCurrentTime(double timeInSeconds);
    public double getTotalDuration();
    public void updateAllTracks(double deltaTime);
    
    // 序列化支持
    public JsonObject toJson();
    public static MasterTimelineManager fromJson(JsonObject json);
}
```

#### **时间轴轨道基类（TimelineTrack）**
```java
public abstract class TimelineTrack {
    protected final String trackId;
    protected final TrackType trackType;
    protected double startOffset; // 在当前时间轴中的起始偏移（秒）
    protected double trackDuration; // 轨道自身时长
    
    public enum TrackType {
        CAMERA_TRACK,     // 相机运镜轨道
        AUDIO_TRACK,      // 音频轨道
        VIDEO_TRACK,      // 视频轨道
        EVENT_TRACK,      // 事件轨道
        MOD_PLUGIN_TRACK, // 第三方模组插件轨道
        CUSTOM_TRACK      // 自定义轨道
    }
    
    // 抽象方法
    public abstract void update(double timelineTime);
    public abstract JsonObject toJson();
}
```

#### **相机轨道（CameraTrack）**
```java
public class CameraTrack extends TimelineTrack {
    private final List<CameraKeyframe> keyframes = new ArrayList<>();
    
    @Override
    public void update(double timelineTime) {
        // 计算相对于轨道起始的时间
        double relativeTime = timelineTime - startOffset;
        
        // 如果时间在轨道范围内
        if (relativeTime >= 0 && relativeTime <= trackDuration) {
            // 查找相邻关键帧并进行插值
            CameraKeyframe prev = findPreviousKeyframe(relativeTime);
            CameraKeyframe next = findNextKeyframe(relativeTime);
            
            if (prev != null && next != null) {
                float progress = (float) ((relativeTime - prev.time) / (next.time - prev.time));
                CameraStateData interpolated = KeyframeInterpolator.interpolateState(
                    prev.stateData, next.stateData, progress, next.interpolation);
                
                // 应用相机状态
                applyCameraState(interpolated);
            }
        }
    }
    
    // 关键帧数据结构
    public static class CameraKeyframe {
        private final double time; // 轨道相对时间（秒）
        private final CameraStateData stateData; // 相机状态：位置、镜头属性
        private final InterpolationType interpolation;
        
        public enum InterpolationType {
            LINEAR,     // 线性插值
            SMOOTH,     // 平滑插值（缓入缓出）
            EASE_IN,    // 缓入
            EASE_OUT,   // 缓出
            EASE_IN_OUT // 缓入缓出
            // 注：根据需求，贝塞尔曲线可能后续添加
        }
    }
}
```

#### **事件轨道（EventTrack）**
```java
public class EventTrack extends TimelineTrack {
    private final List<TimelineEvent> events = new ArrayList<>();
    
    @Override
    public void update(double timelineTime) {
        double relativeTime = timelineTime - startOffset;
        
        // 触发在相对时间发生的事件
        for (TimelineEvent event : events) {
            if (Math.abs(event.getTime() - relativeTime) < 0.001) {
                event.trigger();
            }
        }
    }
    
    // 时间轴事件接口
    public interface TimelineEvent {
        double getTime(); // 轨道相对时间
        void trigger();
        String getEventType(); // 事件类型标识
        
        enum EventType {
            SOUND_EVENT,      // 音效事件
            PARTICLE_EVENT,   // 粒子效果
            MOD_EVENT,        // 第三方模组事件（骨骼动画等）
            COMMAND_EVENT,    // 命令执行
            CUSTOM_EVENT      // 自定义事件
        }
    }
}
```

### 4.2 第三方事件插件API

#### **事件注册接口**
```java
public interface IEventPlugin {
    // 插件注册
    void registerEventTypes(EventRegistry registry);
    
    // 事件工厂
    @Nullable
    TimelineEvent createEvent(String eventType, JsonObject data);
    
    // 事件验证
    boolean validateEvent(JsonObject data);
}
```

#### **事件注册表**
```java
public class EventRegistry {
    private final Map<String, EventFactory> eventFactories = new HashMap<>();
    
    public void registerEventType(String type, EventFactory factory) {
        eventFactories.put(type, factory);
    }
    
    public interface EventFactory {
        TimelineEvent create(JsonObject data);
    }
}
```

#### **模组事件示例**
```java
// 第三方模组可以这样注册自定义事件
public class MyModEventPlugin implements IEventPlugin {
    @Override
    public void registerEventTypes(EventRegistry registry) {
        registry.registerEventType("mymod:animation", data -> {
            String animationId = data.get("animation").getAsString();
            return new MyModAnimationEvent(animationId);
        });
    }
}
```

## 5. 客户端-服务器通信协议

### 5.1 服务端职责
1. **脚本分发**：向指定客户端发送相机脚本数据包
2. **状态跟踪**：记录哪些客户端已成功接收脚本
3. **注册表维护**：管理脚本与客户端的映射关系

### 5.2 客户端职责
1. **脚本接收与解析**：接收服务端发来的脚本数据
2. **脚本执行**：本地执行相机脚本
3. **状态报告**：向服务端确认接收状态

### 5.3 网络包定义

#### **ScriptDistributionPacket（脚本分发包）**
```java
public class ScriptDistributionPacket {
    private final String scriptId;      // 脚本唯一标识
    private final int version;          // 脚本版本
    private final byte[] scriptData;    // 压缩的脚本数据
    private final CompressionType compression; // 压缩类型
    private final boolean isUpdate;     // 是否为增量更新
    private final Set<UUID> targetPlayers; // 目标玩家列表
}
```

#### **ScriptReceiptPacket（接收确认包）**
```java
public class ScriptReceiptPacket {
    private final String scriptId;      // 脚本标识
    private final UUID playerId;        // 玩家ID
    private final int receivedVersion;  // 接收的版本
    private final boolean success;      // 接收是否成功
    private final String errorMessage;  // 错误信息（如果失败）
}
```

#### **ScriptRegistry（服务器注册表）**
```java
public class ScriptRegistry {
    // 脚本->玩家映射
    private final Map<String, Set<UUID>> scriptToPlayers = new HashMap<>();
    
    // 玩家->脚本映射
    private final Map<UUID, Set<String>> playerToScripts = new HashMap<>();
    
    // 发送脚本给特定玩家
    public void distributeScript(String scriptId, UUID playerId) {
        // 实现分发逻辑
    }
    
    // 检查玩家是否已接收脚本
    public boolean hasPlayerReceivedScript(UUID playerId, String scriptId) {
        Set<String> scripts = playerToScripts.get(playerId);
        return scripts != null && scripts.contains(scriptId);
    }
}
```

## 6. 多加载器实现方案

### 6.1 构建系统配置

#### **根目录build.gradle**
```gradle
// 定义公共配置
subprojects {
    apply plugin: 'java'
    
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    
    dependencies {
        // 核心库依赖
        implementation project(':core')
    }
}

// 加载器特定项目
project(':forge') {
    apply plugin: 'net.minecraftforge.gradle'
    
    dependencies {
        minecraft "net.minecraftforge:forge:${minecraft_version}-${forge_version}"
    }
}

project(':fabric') {
    apply plugin: 'fabric-loom'
    
    dependencies {
        minecraft "com.mojang:minecraft:${minecraft_version}"
        mappings "net.fabricmc:yarn:${yarn_mappings}:v2"
        modImplementation "net.fabricmc:fabric-loader:${loader_version}"
        modImplementation "net.fabricmc.fabric-api:fabric-api:${fabric_version}"
    }
}
```

### 6.2 加载器适配器模式

#### **通用入口点接口**
```java
public interface IModInitializer {
    // 通用初始化方法
    void initialize(IModContext context);
    
    // 获取加载器类型
    LoaderType getLoaderType();
}
```

#### **加载器上下文**
```java
public interface IModContext {
    // 注册事件监听器
    void registerEventListener(Object listener);
    
    // 注册网络包
    void registerPacket(Class<?> packetClass, NetworkDirection direction);
    
    // 获取配置系统
    IConfigManager getConfigManager();
}
```

#### **加载器特定实现示例**

**Forge实现：**
```java
@Mod("immersive_cinematics")
public class ForgeModInitializer implements IModInitializer {
    
    public ForgeModInitializer() {
        ForgeModContext context = new ForgeModContext();
        CoreMod.initialize(context);
    }
    
    @Override
    public void initialize(IModContext context) {
        // Forge特定的初始化
    }
    
    @Override
    public LoaderType getLoaderType() {
        return LoaderType.FORGE;
    }
}
```

**Fabric实现：**
```java
public class FabricModInitializer implements ModInitializer, IModInitializer {
    
    @Override
    public void onInitialize() {
        FabricModContext context = new FabricModContext();
        CoreMod.initialize(context);
    }
    
    @Override
    public void initialize(IModContext context) {
        // Fabric特定的初始化
    }
    
    @Override
    public LoaderType getLoaderType() {
        return LoaderType.FABRIC;
    }
}
```

## 8. 构建和部署策略

### 8.1 产品版本配置

#### **完整版本构建（包含游戏内编辑器）**
```bash
./gradlew :forge:build -Pbuild.editor=true
```

#### **仅播放器版本构建（不包含编辑器）**
```bash
./gradlew :forge:build -Pbuild.editor=false
```

### 8.2 Gradle配置说明
```gradle
// gradle.properties
build.editor=true          # 是否包含编辑器功能（游戏内）
build.ui_library=imgui     # UI库选择（可选：imgui, javaui等）
build.version=1.0.0

// 条件编译（概念说明）
// 编辑器代码位于common/src/main/java/com/immersivecinematics/common/editor/
// 通过构建开关控制是否编译和包含该目录

// UI相关依赖（根据build.ui_library动态添加）
dependencies {
    if (project.hasProperty('build.editor') && project.property('build.editor') == 'true') {
        // 根据选择的UI库添加相应依赖
        switch (project.property('build.ui_library')) {
            case 'imgui':
                implementation 'org.lwjgl:lwjgl-glfw'
                implementation 'org.lwjgl:lwjgl-opengl'
                implementation 'io.github.spair:imgui-java-binding'
                break
            case 'javaui':
                // 使用Java原生UI库
                break
            default:
                // 使用默认UI库
                break
        }
    }
}
```

## 9. 开发路线图

### 阶段1：核心架构实现
- [ ] 实现CameraProperties/CameraPath/CameraManager纯数据组件
- [ ] 修改现有Mixin（CameraMixin/GameRendererMixin）从CameraManager读取
- [ ] 删除CinematicCameraEntity数据角色和CinematicCameraHandler
- [ ] 添加Roll注入（GameRendererMixin PoseStack Z轴旋转）
- [ ] 添加测试命令验证分离工作正常

### 阶段2：播放器模块开发
- [ ] 实现轻量级脚本解析和执行引擎
- [ ] 相机系统与时间轴对接
- [ ] 实现事件触发器系统（仅用于启动/停止脚本）
- [ ] 优化运行时性能，建立性能基准
- [ ] 创建自动化测试框架和测试用例

### 阶段3：网络和同步系统（3-4周）
- [ ] 实现服务端脚本分发系统
- [ ] 建立客户端接收和确认机制
- [ ] 设计增量更新和压缩传输算法
- [ ] 创建脚本注册表和状态跟踪系统
- [ ] 实现网络同步和错误恢复机制

### 阶段4：编辑器模块开发（5-6周）
- [ ] 集成第三方UI库（如ImGui）
- [ ] 开发可视化时间轴编辑器
- [ ] 实现3D场景预览和相机操作工具
- [ ] 创建关键帧编辑和插值控制系统
- [ ] 实现脚本导入/导出和版本管理

### 阶段5：第三方事件插件系统（3-4周）
- [ ] 设计插件API接口和注册机制
- [ ] 实现事件工厂和验证系统
- [ ] 创建示例插件和文档
- [ ] 建立插件加载和生命周期管理
- [ ] 设计安全沙箱和权限控制系统

### 阶段6：集成测试与优化（3-4周）
- [ ] 端到端功能测试和验证
- [ ] 性能基准测试和优化
- [ ] 多加载器兼容性测试
- [ ] 多版本支持验证
- [ ] 用户验收测试和反馈收集

### 阶段7：文档与部署（2-3周）
- [ ] 编写完整的技术文档
- [ ] 创建用户指南和API文档
- [ ] 建立持续集成/持续部署流程
- [ ] 准备发布包和分发渠道
- [ ] 建立社区支持和反馈机制

## 10. 关键技术决策与实现细节

### 10.1 相机系统初始化（纯数据架构）

> 相机核心不再使用 Entity，CinematicCameraEntity 仅在需要服务端追踪相机位置时保留注册。
> 当前阶段不保留 Entity 注册，所有相机逻辑通过 CameraManager 纯数据驱动。

```java
// CameraManager 在客户端初始化时注册 tick 驱动
@Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public static class ClientEvents {
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            CameraManager.INSTANCE.tick();
        }
    }
}
```

### 10.2 时间轴执行引擎
```java
public class TimelineExecutor {
    private final MasterTimeline timeline;
    private boolean isPlaying = false;
    private double currentTime = 0.0;
    
    public void play() {
        isPlaying = true;
        scheduleNextFrame();
    }
    
    public void pause() {
        isPlaying = false;
    }
    
    public void seek(double timeInSeconds) {
        currentTime = Math.max(0, Math.min(timeInSeconds, timeline.getDuration()));
        updateAllTracks();
    }
    
    private void scheduleNextFrame() {
        if (!isPlaying) return;
        
        // 计算下一帧时间（基于Minecraft tick系统）
        double tickDuration = 1.0 / 20.0; // 每tick 0.05秒
        currentTime += tickDuration;
        
        if (currentTime >= timeline.getDuration()) {
            stop();
            return;
        }
        
        updateAllTracks();
        
        // 安排下一帧
        Minecraft.getInstance().tell(() -> scheduleNextFrame());
    }
    
    private void updateAllTracks() {
        for (TimelineTrack track : timeline.getTracks()) {
            track.update(currentTime);
        }
    }
}
```

### 10.3 关键帧插值系统（支持多轨道平滑插值）

根据需求，关键帧系统需要支持多种平滑插值类型，但不需要贝塞尔曲线等复杂插值。

```java
public class KeyframeInterpolator {
    
    // 插值类型枚举
    public enum InterpolationType {
        LINEAR,     // 线性插值
        SMOOTH,     // 平滑插值（缓入缓出）
        EASE_IN,    // 缓入
        EASE_OUT,   // 缓出
        EASE_IN_OUT // 缓入缓出
    }
    
    // 完整的相机状态插值
    public static CameraStateData interpolateState(
            CameraStateData from, 
            CameraStateData to, 
            float progress, 
            InterpolationType type) {
        
        CameraStateData result = new CameraStateData();
        
        // 根据插值类型调整进度曲线
        float adjustedProgress = applyInterpolationCurve(progress, type);
        
        // 位置插值（线性插值即可）
        result.positionX = lerp(from.positionX, to.positionX, adjustedProgress);
        result.positionY = lerp(from.positionY, to.positionY, adjustedProgress);
        result.positionZ = lerp(from.positionZ, to.positionZ, adjustedProgress);
        
        // 角度插值（需要处理角度环绕）
        result.yaw = lerpAngle(from.yaw, to.yaw, adjustedProgress);
        result.pitch = lerpAngle(from.pitch, to.pitch, adjustedProgress);
        result.roll = lerpAngle(from.roll, to.roll, adjustedProgress);
        
        // 视野插值
        result.fov = lerp(from.fov, to.fov, adjustedProgress);
        
        return result;
    }
    
    // 应用插值曲线
    private static float applyInterpolationCurve(float progress, InterpolationType type) {
        switch (type) {
            case LINEAR:
                return progress;
                
            case SMOOTH:
                // 平滑插值：3t² - 2t³
                return progress * progress * (3.0f - 2.0f * progress);
                
            case EASE_IN:
                // 缓入：t²
                return progress * progress;
                
            case EASE_OUT:
                // 缓出：1 - (1-t)²
                return 1.0f - (1.0f - progress) * (1.0f - progress);
                
            case EASE_IN_OUT:
                // 缓入缓出：分段函数
                if (progress < 0.5f) {
                    return 2.0f * progress * progress;
                } else {
                    progress = 2.0f * progress - 1.0f;
                    return 0.5f * (1.0f - (1.0f - progress) * (1.0f - progress)) + 0.5f;
                }
                
            default:
                return progress;
        }
    }
    
    // 线性插值
    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
    
    // 角度插值（处理角度环绕）
    private static float lerpAngle(float a, float b, float t) {
        // 处理角度环绕（-π 到 π）
        float diff = b - a;
        while (diff > Math.PI) diff -= 2 * Math.PI;
        while (diff < -Math.PI) diff += 2 * Math.PI;
        return a + diff * t;
    }
    
    // 位置插值辅助方法（用于Vec3）
    public static Vec3 interpolatePosition(Vec3 from, Vec3 to, float progress, InterpolationType type) {
        float adjustedProgress = applyInterpolationCurve(progress, type);
        double x = lerp((float) from.x(), (float) to.x(), adjustedProgress);
        double y = lerp((float) from.y(), (float) to.y(), adjustedProgress);
        double z = lerp((float) from.z(), (float) to.z(), adjustedProgress);
        return new Vec3(x, y, z);
    }
}
```

### 10.4 构建配置示例
```gradle
// settings.gradle
include 'common'
include 'forge'
include 'fabric'
include 'neoforge'

// 编辑器功能在common模块中，通过build.editor开关控制
// 当build.editor=true时，common模块中的editor目录被编译
// 当build.editor=false时，editor目录被排除

// 版本配置
ext {
    minecraft_version = '1.20.1'
    forge_version = '47.4.16'
    fabric_version = '0.91.2+1.20.1'
    loader_version = '0.15.0'
}
```

## 11. 风险缓解策略

### 11.1 技术风险
- **多加载器兼容性**：使用适配器模式，为每个加载器创建独立的实现模块
- **性能目标**：建立性能基准测试，持续监控关键指标
- **第三方UI集成**：评估多个UI库，准备备用方案

### 11.2 开发风险
- **模块间依赖**：定义清晰的API契约，使用接口隔离
- **构建复杂性**：自动化构建流程，提供详细文档
- **测试覆盖**：建立全面的测试套件，包括单元、集成和性能测试

### 11.3 部署风险
- **产品版本管理**：建立清晰的版本发布流程
- **向后兼容性**：设计可扩展的数据格式，提供迁移工具
- **社区支持**：建立文档和示例，提供技术支持渠道

## 12. 成功标准与验收标准

### 12.1 性能指标
1. **内存占用**：播放器模块运行时内存占用 < 50MB
2. **帧率稳定**：相机动画播放时保持60+ FPS
3. **网络延迟**：脚本分发延迟 < 100ms
4. **加载时间**：编辑器启动时间 < 5秒

### 12.2 功能完整性
1. **相机系统**：支持所有定义的定位模式和插值类型
2. **时间轴系统**：支持多轨道同步和第三方事件
3. **网络系统**：支持脚本分发、接收确认和状态跟踪
4. **编辑器功能**：提供完整的可视化编辑工具

### 12.3 兼容性要求
1. **加载器支持**：同时支持Forge、Fabric和NeoForge
2. **版本兼容**：支持Minecraft 1.20.1，架构可扩展到其他版本
3. **第三方集成**：提供完善的插件API和文档

## 13. 版本发布计划

### 13.1 版本0.3.0（Alpha）
- 目标：核心相机系统和时间轴数据结构
- 时间：阶段1完成后
- 功能：基础相机组件、时间轴数据结构、网络协议定义

### 13.2 版本0.4.0（Beta）
- 目标：播放器模块完整实现
- 时间：阶段2完成后
- 功能：脚本播放引擎、纯数据相机系统、事件触发器

### 13.3 版本0.5.0（RC1）
- 目标：网络和同步系统
- 时间：阶段3完成后
- 功能：服务端脚本分发、客户端接收确认、状态跟踪

### 13.4 版本0.6.0（RC2）
- 目标：编辑器基础功能
- 时间：阶段4完成后
- 功能：时间轴编辑器、3D预览、关键帧编辑

### 13.5 版本1.0.0（正式版）
- 目标：完整产品发布
- 时间：所有阶段完成后
- 功能：完整功能集、第三方插件系统、多加载器支持

---

**文档状态**：完成纯数据架构修订（相机系统从 Entity 迁移至 POJO）
**最后更新**：2026/4/27
**下一步**：开始实施阶段1 - CameraProperties/CameraPath/CameraManager 核心组件
**备注**：相机系统已确定为纯数据架构，不依赖 Minecraft Entity。详见 Section 3 架构决策说明。
