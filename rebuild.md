# Immersive Cinematics 完全重构技术规范

## 1. 项目概述

### 1.1 核心目标
- **消除不必要的复杂性和开销**
- **增强模块化、性能和部署灵活性**
- **为整合包作者提供专业的过场动画制作工具**

### 1.2 重构原则
1. **关注点分离** - 将编辑器与播放器完全分离
2. **最小化依赖** - 每个模块只包含必要功能
3. **性能优先** - 减少坐标转换和计算开销
4. **部署灵活** - 支持两种产品版本

## 2. 基础摄像机系统重构

### 2.1 摄像机组件分解
将单体摄像机分解为两个独立控制的组件：

#### **组件A：摄像机属性控制器 (CameraPropertiesController)**
- **职责**: 管理内在摄像机属性
- **包含属性**:
  - 偏航角 (Yaw)
  - 俯仰角 (Pitch) 
  - 视场角 (FOV)
  - 滚转角 (Roll)
- **设计原则**:
  - 纯数学计算，无状态依赖
  - 可独立于位置控制工作
  - 支持平滑插值和动画

#### **组件B：摄像机变换控制器 (CameraTransformController)**
- **职责**: 管理世界空间定位
- **包含功能**:
  - **绝对世界坐标** - 直接指定XYZ位置
  - **相对定位目标** - 动态追踪实体/结构/方块
  - **路径插值** - 基于时间的平滑移动
- **输出**: 最终世界位置向量
- **设计原则**:
  - 最小化坐标转换（目标：每帧单次优化转换）
  - 支持多种定位模式

### 2.2 继承结构指令
- **禁止继承 `Player` 类** - 避免玩家特定逻辑的开销（饥饿、背包等）
- **应继承基础类** - 如 `Entity` 或等效基类，提供必要的世界交互能力
- **目标**: 构建轻量级摄像机实体系统

### 2.3 摄像机系统架构

```java
// 基础接口
public interface ICameraComponent {
    void update(double partialTicks);
    void applyToRenderer();
}

// 属性控制器实现
public class CameraPropertiesController implements ICameraComponent {
    private float yaw;
    private float pitch;
    private float fov;
    private float roll;
    
    // 属性插值和动画方法
    public void interpolateTo(CameraProperties target, float progress);
    public void setFromEntity(Entity entity);
}

// 变换控制器实现  
public class CameraTransformController implements ICameraComponent {
    private Vec3 worldPosition;
    private ITargetProvider targetProvider;
    
    // 定位模式
    public enum PositioningMode {
        ABSOLUTE,           // 绝对坐标
        RELATIVE_TO_ENTITY, // 相对实体
        FOLLOW_PATH,        // 跟随路径
        LOOK_AT_TARGET      // 注视目标
    }
    
    // 每帧更新位置
    public void update(double partialTicks) {
        worldPosition = calculateFinalPosition(partialTicks);
    }
}

// 复合摄像机
public class CinematicCamera {
    private CameraPropertiesController properties;
    private CameraTransformController transform;
    
    public void update(double partialTicks) {
        transform.update(partialTicks);
        properties.update(partialTicks);
        applyToGameRenderer();
    }
}
```

## 3. 整体项目架构重构

### 3.1 清晰的客户端-服务器模型

#### **服务器端 (Server Module)**
- **职责**:
  1. 脚本管理（存储、版本控制）
  2. 向连接的客户端分发摄像机脚本
  3. 维护注册表（记录哪些客户端/玩家接收了哪些数据包）
- **不包含**: 脚本解析或运行时播放逻辑
- **设计要点**:
  - 使用高效的数据包序列化
  - 支持增量更新
  - 提供脚本发现和订阅机制

#### **客户端 (Client Module)**
- **职责**:
  1. 解析接收的脚本
  2. 本地执行摄像机序列
  3. 轻量级运行时管理
- **不包含**: 脚本创建、编辑器UI、复杂服务器逻辑
- **设计要点**:
  - 最小化内存占用
  - 专注于播放性能
  - 支持本地缓存

### 3.2 核心应用关注点分离

#### **编辑器模块 (Editor Module)**
- **定位**: 专用工具，用于创作和编辑摄像机脚本
- **技术栈**: 使用第三方库实现专业UI
- **功能**:
  - 可视化时间轴编辑器
  - 3D场景预览
  - 路径绘制工具
  - 事件触发器配置
  - 脚本导出和分享

#### **播放器模块 (Player Module)**
- **定位**: 独立轻量级模块，专注于脚本播放
- **设计原则**:
  - 零依赖编辑器UI组件
  - 最小化运行时开销
  - 专注于播放稳定性和性能
- **功能**:
  - 脚本解析和验证
  - 实时播放引擎
  - 事件触发器执行
  - 资源管理

## 4. 部署策略

### 4.1 双产品版本支持

#### **完整版本 (Full Version)**
- **包含模块**: 编辑器 + 播放器
- **目标用户**: 内容创作者、整合包作者
- **功能集**:
  - 完整的脚本创作工具
  - 可视化编辑界面
  - 实时预览和调试
  - 脚本导出功能
- **构建配置**: `editor=true, player=true`

#### **运行时仅版本 (Runtime-Only Version)**
- **包含模块**: 仅播放器
- **目标用户**: 最终用户、整合包玩家
- **功能集**:
  - 脚本播放引擎
  - 事件触发系统
  - 基本配置选项
  - 最小化资源占用
- **构建配置**: `editor=false, player=true`

### 4.2 构建系统配置

```gradle
// gradle.properties
build.editor=true
build.player=true

// 条件编译配置
sourceSets {
    main {
        java {
            if (!project.hasProperty('build.editor') || project.property('build.editor') == 'true') {
                srcDir 'src/editor/java'
            }
            if (!project.hasProperty('build.player') || project.property('build.player') == 'true') {
                srcDir 'src/player/java'
            }
            srcDir 'src/core/java' // 核心库始终包含
        }
    }
}
```

## 5. 技术架构设计

### 5.1 核心库结构

```
src/
├── core/                          # 核心库（共享）
│   ├── camera/
│   │   ├── CameraPropertiesController.java
│   │   ├── CameraTransformController.java
│   │   └── CinematicCamera.java
│   ├── timeline/
│   │   ├── TimelineEvent.java
│   │   ├── TimelinePlayer.java
│   │   └── TimelineSerializer.java
│   ├── events/
│   │   ├── EventTrigger.java
│   │   ├── EventRegistry.java
│   │   └── EventExecutor.java
│   └── network/
│       ├── ScriptPacket.java
│       ├── RegistryPacket.java
│       └── NetworkHandler.java
├── editor/                        # 编辑器模块（可选）
│   ├── ui/
│   │   ├── TimelineEditor.java
│   │   ├── CameraPreview.java
│   │   └── PathEditor.java
│   ├── tools/
│   │   ├── ScriptExporter.java
│   │   ├── ScriptImporter.java
│   │   └── PreviewGenerator.java
│   └── integration/
│       ├── UIEngine.java
│       └── ThirdPartyBridge.java
└── player/                       # 播放器模块（必选）
    ├── runtime/
    │   ├── ScriptPlayer.java
    │   ├── CameraRenderer.java
    │   └── ResourceManager.java
    └── triggers/
        ├── WorldTrigger.java
        ├── EntityTrigger.java
        └── CustomTrigger.java
```

### 5.2 脚本格式设计

```json
{
  "metadata": {
    "id": "end_entry_cinematic",
    "version": "1.0",
    "author": "整合包作者",
    "description": "末地入口过场动画"
  },
  "properties": {
    "duration": 200,
    "loop": false,
    "priority": "normal"
  },
  "camera": {
    "properties_controller": {
      "yaw": { "start": 0, "end": 360, "interpolation": "linear" },
      "pitch": { "start": -20, "end": 20, "interpolation": "smooth" },
      "fov": { "start": 70, "end": 90, "interpolation": "ease_in_out" }
    },
    "transform_controller": {
      "mode": "follow_path",
      "path": {
        "type": "bezier",
        "points": [
          { "x": 0, "y": 64, "z": 0 },
          { "x": 10, "y": 70, "z": 10 },
          { "x": 20, "y": 65, "z": 0 }
        ]
      },
      "target": {
        "type": "entity",
        "entity_id": "minecraft:ender_dragon"
      }
    }
  },
  "events": [
    {
      "type": "entity_appear",
      "time": 50,
      "entity": "minecraft:ender_dragon",
      "position": { "x": 0, "y": 70, "z": 0 }
    },
    {
      "type": "particle_effect",
      "start": 100,
      "end": 150,
      "effect": "minecraft:dragon_breath",
      "position": "follow_camera"
    }
  ]
}
```

### 5.3 网络通信协议

```java
// 服务器到客户端的数据包
public class ScriptDistributionPacket {
    private String scriptId;
    private byte[] scriptData; // 压缩的脚本内容
    private int version;
    private boolean isUpdate; // 增量更新标志
}

// 客户端到服务器的确认包
public class ScriptReceiptPacket {
    private String scriptId;
    private String playerId;
    private int receivedVersion;
    private boolean success;
}

// 服务器注册表
public class ScriptRegistry {
    private Map<String, Set<UUID>> scriptToPlayers; // 脚本->玩家映射
    private Map<UUID, Set<String>> playerToScripts; // 玩家->脚本映射
}
```

## 6. 性能优化策略

### 6.1 坐标转换优化
- **目标**: 每帧最多一次坐标转换
- **策略**:
  1. 预计算转换矩阵
  2. 使用四元数进行旋转插值
  3. 批量处理位置更新
  4. 缓存常用计算结果

### 6.2 内存管理
- **对象池**: 重用事件和路径对象
- **懒加载**: 按需加载脚本资源
- **增量更新**: 只传输变更部分
- **资源卸载**: 及时释放未使用资源

### 6.3 渲染优化
- **LOD系统**: 根据距离调整细节
- **剔除算法**: 避免不可见物体的计算
- **批处理**: 合并相似渲染操作
- **异步加载**: 非关键资源后台加载

## 7. 开发路线图

### 阶段1：基础架构 (2-3周)
- [ ] 设计并实现核心摄像机组件
- [ ] 建立基础项目结构
- [ ] 实现核心网络通信协议
- [ ] 创建基础脚本格式和解析器

### 阶段2：播放器模块 (3-4周)
- [ ] 实现轻量级播放器引擎
- [ ] 开发事件触发器系统
- [ ] 优化运行时性能
- [ ] 创建测试脚本和验证工具

### 阶段3：编辑器模块 (4-5周)
- [ ] 集成第三方UI库
- [ ] 开发可视化时间轴编辑器
- [ ] 实现3D预览系统
- [ ] 创建脚本导出/导入功能

### 阶段4：集成测试 (2-3周)
- [ ] 端到端功能测试
- [ ] 性能基准测试
- [ ] 兼容性测试
- [ ] 用户验收测试

### 阶段5：文档和部署 (1-2周)
- [ ] 编写技术文档
- [ ] 创建用户指南
- [ ] 准备发布包
- [ ] 建立持续集成流程

## 8. 关键优势

### 8.1 架构优势
1. **清晰的关注点分离** - 编辑器与播放器完全解耦
2. **灵活部署选项** - 支持两种产品版本
3. **最小化运行时开销** - 播放器模块极致轻量

### 8.2 性能优势
1. **减少坐标转换** - 每帧单次优化转换
2. **消除冗余计算** - 避免继承Player类的开销
3. **高效网络通信** - 增量更新和压缩传输

### 8.3 开发优势
1. **模块化设计** - 独立开发和测试每个模块
2. **扩展性强** - 易于添加新功能
3. **维护性好** - 清晰的架构和职责划分

## 9. 风险缓解策略

### 9.1 技术风险
- **第三方UI库集成**：准备备用方案，评估多个库的兼容性
- **网络同步问题**：实现完善的回退和重试机制
- **性能目标**：设立明确的性能基准，持续监控

### 9.2 开发风险
- **模块间依赖**：定义清晰的API契约，使用接口隔离
- **构建复杂性**：自动化构建流程，提供详细文档
- **测试覆盖**：建立全面的测试套件，包括单元、集成和性能测试

## 10. 成功标准

1. **性能指标**：播放器模块运行时内存占用 < 50MB
2. **帧率目标**：摄像机动画播放时保持60+ FPS
3. **网络效率**：脚本传输延迟 < 100ms
4. **用户体验**：编辑器响应时间 < 200ms
5. **兼容性**：支持Minecraft 1.20.1+，Forge和Fabric

---

**最后更新**: 2026/3/25  
**状态**: 技术规范完成，待实施