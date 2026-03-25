# ImmersiveCinematics 完全重构技术规范（修订版）
## 基于Minecraft模组架构设计提示词

## 1. 项目概述

### 1.1 核心目标
- **完全替换游戏现有相机系统和脚本执行框架**
- **实现高性能、解耦式相机系统**
- **支持多加载器（Forge/Fabric/NeoForge）架构**
- **提供编辑器-播放器分离的双产品版本**

### 1.2 架构原则
1. **解耦式相机系统** - CameraState与CameraTransform完全分离
2. **客户端-服务器分离** - 服务端仅负责脚本分发，客户端专注播放
3. **编辑器-播放器分离** - 支持两种产品版本
4. **多加载器支持** - Forge/Fabric/NeoForge统一架构
5. **实体继承规则** - 相机实体禁止继承Player类

## 2. 核心模块边界定义

### 2.1 整体项目结构
```
ImmersiveCinematics/
├── common/                          # 核心共享库（不依赖特定加载器）
│   ├── camera/                    # 相机系统
│   ├── timeline/                  # 时间轴系统
│   ├── script/                    # 脚本格式与解析
│   ├── events/                    # 事件系统
│   └── network/                   # 网络通信协议
├── forge/                         # Forge加载器实现
│   └── src/
│       ├── main/java/
│       └── main/resources/
├── fabric/                        # Fabric加载器实现
│   └── src/
│       ├── main/java/
│       └── main/resources/
├── neoforge/                      # NeoForge加载器实现
│   └── src/
│       ├── main/java/
│       └── main/resources/
└── editor/                        # 编辑器模块（可选）
    ├── ui/                        # 编辑器UI
    ├── timeline/                  # 时间轴编辑器
    └── export/                    # 脚本导出
```

### 2.2 模块职责划分

#### **核心库 (core)**
- **职责**：提供加载器无关的通用功能
- **包含**：
  1. CameraState/CameraTransform组件
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

#### **编辑器模块 (editor)**
- **职责**：提供图形化脚本创作工具
- **包含**：
  1. 可视化时间轴编辑器
  2. 3D场景预览
  3. 脚本导入/导出
  4. 关键帧编辑工具

#### **播放器模块 (player)**
- **职责**：轻量级脚本运行时
- **包含**：
  1. 脚本解析和执行
  2. 相机系统运行时
  3. 事件触发器（仅用于启动/停止脚本）
  4. 网络通信客户端

## 3. 核心相机组件设计

### 3.1 CameraState（相机状态控制器）

#### **类职责**
- 管理相机的**视觉属性**，纯数学计算，无状态依赖
- 提供属性插值和动画功能
- 支持平滑过渡和关键帧动画

#### **接口定义**
```java
public interface ICameraState {
    // 获取当前相机状态
    CameraStateData getCurrentState(double partialTicks);
    
    // 插值到目标状态
    void interpolateTo(CameraStateData target, float progress);
    
    // 从实体获取状态
    void setFromEntity(Entity entity);
    
    // 应用状态到游戏渲染器
    void applyToRenderer();
}
```

#### **CameraStateData数据结构**
```java
public class CameraStateData {
    private float yaw;      // 偏航角（弧度）
    private float pitch;    // 俯仰角（弧度）
    private float roll;     // 翻滚角（弧度）
    private float fov;      // 视野角度（度）
    private float near;     // 近裁剪面
    private float far;      // 远裁剪面
    
    // 插值方法
    public CameraStateData interpolate(CameraStateData target, float t);
}
```

### 3.2 CameraTransform（相机变换控制器）

#### **类职责**
- 管理相机的**世界位置**，支持多种定位模式
- 每帧最多执行一次坐标变换（性能优化）
- 支持绝对坐标和相对目标定位

#### **接口定义**
```java
public interface ICameraTransform {
    // 获取当前位置
    Vec3 getPosition(double partialTicks);
    
    // 定位模式
    enum PositioningMode {
        ABSOLUTE,           // 绝对世界坐标
        RELATIVE_TO_ENTITY, // 相对实体定位
        RELATIVE_TO_BLOCK,  // 相对方块定位
        RELATIVE_TO_STRUCTURE // 相对结构定位
    }
    
    // 设置目标
    void setTarget(@Nullable Object target);
    
    // 更新变换
    void update(double partialTicks);
}
```

#### **定位目标接口**
```java
public interface ITargetProvider {
    // 获取目标位置
    Vec3 getTargetPosition();
    
    // 目标类型
    enum TargetType {
        ENTITY,
        BLOCK_POSITION,
        STRUCTURE_CENTER,
        CUSTOM
    }
}
```

### 3.3 相机实体实现

#### **核心实体设计**
```java
// 禁止继承Player类，直接继承Entity
public class CinematicCameraEntity extends Entity {
    
    private final CameraState cameraState;
    private final CameraTransform cameraTransform;
    
    public CinematicCameraEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.cameraState = new CameraStateImpl();
        this.cameraTransform = new CameraTransformImpl();
        
        // 禁用物理和碰撞
        this.noPhysics = true;
        this.setNoGravity(true);
        this.setInvulnerable(true);
    }
    
    @Override
    public void tick() {
        super.tick();
        
        // 更新变换和状态
        cameraTransform.update(1.0f);
        cameraState.update(1.0f);
        
        // 应用相机状态到渲染器
        if (level().isClientSide()) {
            cameraState.applyToRenderer();
        }
    }
    
    // 禁用不必要的玩家逻辑
    @Override
    public boolean isAffectedByFluids() { return false; }
    
    @Override
    public boolean isPushable() { return false; }
    
    @Override
    public boolean isPickable() { return false; }
}
```

## 4. 时间轴系统设计

### 4.1 时间轴数据结构

#### **主时间轴（Master Timeline）**
```java
public class MasterTimeline {
    private final double duration; // 总时长（秒）
    private final List<TimelineTrack> tracks;
    private double currentTime; // 当前时间（秒）
    
    // 时间转换：秒 <-> Minecraft tick
    public static double ticksToSeconds(long ticks) {
        return ticks / 20.0;
    }
    
    public static long secondsToTicks(double seconds) {
        return (long) (seconds * 20.0);
    }
}
```

#### **相机轨道（Camera Track）**
```java
public class CameraTrack extends TimelineTrack {
    private final List<CameraKeyframe> keyframes;
    
    // 关键帧数据结构
    public static class CameraKeyframe {
        private final double time; // 时间（秒）
        private final CameraStateData stateData;
        private final TransformKeyframe transformData;
        private final InterpolationType interpolation;
        
        public enum InterpolationType {
            LINEAR,
            SMOOTH,
            EASE_IN,
            EASE_OUT,
            EASE_IN_OUT,
            BEZIER
        }
    }
}
```

#### **事件轨道（Event Track）**
```java
public class EventTrack extends TimelineTrack {
    private final List<TimelineEvent> events;
    
    // 时间轴事件接口
    public interface TimelineEvent {
        double getTime();
        void trigger();
        String getEventType();
        
        // 支持的事件类型
        enum EventType {
            SOUND_EVENT,      // 音效事件
            PARTICLE_EVENT,   // 粒子效果
            MOD_EVENT,        // 模组事件（第三方）
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

## 7. 脚本格式设计

### 7.1 脚本JSON结构
```json
{
  "metadata": {
    "id": "end_entry_cinematic",
    "version": 1,
    "author": "整合包作者",
    "description": "末地入口过场动画"
  },
  "timeline": {
    "duration": 30.5,
    "tracks": [
      {
        "type": "camera",
        "keyframes": [
          {
            "time": 0.0,
            "state": {
              "yaw": 0.0,
              "pitch": -0.349,
              "roll": 0.0,
              "fov": 70.0
            },
            "transform": {
              "mode": "absolute",
              "position": { "x": 0, "y": 64, "z": 0 }
            },
            "interpolation": "smooth"
          },
          {
            "time": 10.0,
            "state": {
              "yaw": 1.571,
              "pitch": 0.0,
              "roll": 0.0,
              "fov": 90.0
            },
            "transform": {
              "mode": "relative_to_entity",
              "entity_id": "minecraft:ender_dragon",
              "offset": { "x": 0, "y": 3, "z": 5 }
            },
            "interpolation": "linear"
          }
        ]
      },
      {
        "type": "event",
        "events": [
          {
            "time": 5.0,
            "type": "sound",
            "sound": "minecraft:entity.ender_dragon.growl",
            "volume": 1.0,
            "pitch": 1.0
          },
          {
            "time": 15.0,
            "type": "mod:animation",
            "animation_id": "custom_dragon_roar",
            "target": "ender_dragon"
          }
        ]
      }
    ]
  }
}
```

## 8. 构建和部署策略

### 8.1 产品版本配置

#### **完整版本构建（包含编辑器）**
```bash
./gradlew :forge:build -Pbuild.editor=true
```

#### **仅播放器版本构建**
```bash
./gradlew :forge:build -Pbuild.editor=false
```

### 8.2 Gradle配置
```gradle
// gradle.properties
build.editor=true  # 是否包含编辑器模块
build.version=1.0.0

// 条件编译
sourceSets {
    main {
        java {
            if (project.hasProperty('build.editor') && project.property('build.editor') == 'true') {
                srcDir 'editor/src/main/java'
            }
            srcDir 'player/src/main/java'
            srcDir 'core/src/main/java'
        }
    }
}
```

## 9. 开发路线图

### 阶段1：核心架构实现（3-4周）
- [ ] 实现CameraState/CameraTransform核心组件
- [ ] 建立多加载器项目结构
- [ ] 设计核心接口和抽象层
- [ ] 创建基础网络通信协议
- [ ] 实现时间轴基础数据结构

### 阶段2：播放器模块开发（4-5周）
- [ ] 实现轻量级脚本解析和执行引擎
- [ ] 创建相机实体系统（继承Entity而非Player）
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

### 10.1 相机实体注册
```java
// 注册相机实体类型
public class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = 
        DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MODID);
    
    public static final RegistryObject<EntityType<CinematicCameraEntity>> CINEMATIC_CAMERA =
        ENTITY_TYPES.register("cinematic_camera", () -> 
            EntityType.Builder.of(CinematicCameraEntity::new, MobCategory.MISC)
                .sized(0.001f, 0.001f) // 极小碰撞箱
                .clientTrackingRange(0) // 不进行客户端追踪
                .build("cinematic_camera"));
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

### 10.3 关键帧插值系统
```java
public class KeyframeInterpolator {
    
    public static CameraStateData interpolateState(
            CameraStateData from, 
            CameraStateData to, 
            float progress, 
            InterpolationType type) {
        
        CameraStateData result = new CameraStateData();
        
        // 使用不同的插值函数
        switch (type) {
            case LINEAR:
                result.yaw = lerpAngle(from.yaw, to.yaw, progress);
                result.pitch = lerpAngle(from.pitch, to.pitch, progress);
                result.roll = lerp(from.roll, to.roll, progress);
                result.fov = lerp(from.fov, to.fov, progress);
                break;
                
            case SMOOTH:
                float easeProgress = smoothstep(progress);
                result.yaw = lerpAngle(from.yaw, to.yaw, easeProgress);
                // ... 其他属性类似
                break;
                
            case BEZIER:
                // 使用贝塞尔曲线插值
                float bezierProgress = cubicBezier(progress, 0.42, 0, 0.58, 1);
                result.yaw = lerpAngle(from.yaw, to.yaw, bezierProgress);
                break;
        }
        
        return result;
    }
    
    private static float lerpAngle(float a, float b, float t) {
        // 处理角度环绕
        float diff = b - a;
        while (diff > Math.PI) diff -= 2 * Math.PI;
        while (diff < -Math.PI) diff += 2 * Math.PI;
        return a + diff * t;
    }
}
```

### 10.4 构建配置示例
```gradle
// settings.gradle
include 'core'
include 'forge'
include 'fabric'
include 'neoforge'
include 'editor'

// 版本配置
ext {
    minecraft_version = '1.20.1'
    forge_version = '47.1.0'
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
- 功能：脚本播放引擎、相机实体系统、事件触发器

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

**文档状态**：完成技术规范修订  
**最后更新**：2026/3/25  
**下一步**：开始实施阶段1 - 核心架构实现  
**备注**：此文档基于Minecraft模组架构设计提示词编写，确保符合所有架构原则和要求
