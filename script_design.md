# ImmersiveCinematics 脚本格式设计规范

## 1. 设计原则

### 1.1 核心设计理念
1. **静态数据**：脚本只包含编辑时确定的数据，不包含运行时的动态状态
2. **多轨道支持**：支持类似剪辑软件的多轨道并行编辑
3. **触发器系统**：支持多种自动触发机制
4. **时间相对性**：所有时间都相对于总时长计算，便于player模块调度
5. **模块化设计**：支持第三方模组事件扩展

### 1.2 关键决策
1. **移除动态字段**：如 `"current_time"` 等播放状态字段不应保存在脚本中
2. **片段化结构**：每个轨道由多个片段（clips）组成，支持单时间轴上的多事件
3. **时间计算规则**：所有时间都基于 `"total_duration"` 的相对时间
4. **播放器调度**：时间调度计算由player模块负责，脚本只提供数据

## 2. 脚本JSON结构

### 2.1 完整JSON结构示例

```json
{
  "metadata": {
    "id": "multi_track_cinematic",
    "version": 1,
    "author": "整合包作者",
    "description": "多轨道运镜过场动画示例",
    "created_timestamp": "2026-03-31T12:00:00Z"
  },
  "triggers": [
    {
      "id": "auto_start_trigger",
      "type": "time",
      "conditions": {
        "trigger_time": 0.0
      },
      "actions": [
        {
          "type": "start_playback",
          "script_id": "multi_track_cinematic"
        }
      ]
    },
    {
      "id": "position_trigger",
      "type": "location",
      "conditions": {
        "position": { "x": 100.0, "y": 64.0, "z": 200.0 },
        "radius": 10.0,
        "player_count": 1
      },
      "actions": [
        {
          "type": "start_playback",
          "script_id": "multi_track_cinematic"
        }
      ]
    }
  ],
  "master_timeline": {
    "total_duration": 45.0,
    "tracks": [
      {
        "id": "camera_track_1",
        "type": "camera",
        "start_offset": 0.0,
        "segments": [
          {
            "id": "segment_1",
            "start_time": 0.0,
            "duration": 10.0,
            "type": "keyframe_sequence",
            "keyframes": [
              {
                "time": 0.0,
                "state": {
                  "position": { "x": 10.5, "y": 64.2, "z": -15.3 },
                  "rotation": { "yaw": 0.0, "pitch": -0.349, "roll": 0.0 },
                  "fov": 70.0
                },
                "interpolation": "smooth"
              },
              {
                "time": 10.0,
                "state": {
                  "position": { "x": 25.3, "y": 72.1, "z": 8.7 },
                  "rotation": { "yaw": 1.571, "pitch": 0.0, "roll": 0.0 },
                  "fov": 90.0
                },
                "interpolation": "ease_in_out"
              }
            ]
          },
          {
            "id": "segment_2",
            "start_time": 10.0,
            "duration": 8.0,
            "type": "keyframe_sequence",
            "keyframes": [
              {
                "time": 0.0,
                "state": {
                  "position": { "x": 15.7, "y": 68.3, "z": 12.4 },
                  "rotation": { "yaw": 2.094, "pitch": -0.175, "roll": 0.0 },
                  "fov": 75.0
                },
                "interpolation": "linear"
              },
              {
                "time": 8.0,
                "state": {
                  "position": { "x": 5.1, "y": 80.5, "z": 22.9 },
                  "rotation": { "yaw": 3.142, "pitch": 0.175, "roll": 0.0 },
                  "fov": 60.0
                },
                "interpolation": "smooth"
              }
            ]
          }
        ]
      },
      {
        "id": "audio_track_1",
        "type": "audio",
        "start_offset": 12.0,
        "segments": [
          {
            "start_time": 0.0,
            "duration": 20.0,
            "type": "audio_clip",
            "sound": "minecraft:ambient.cave",
            "volume": 0.8,
            "pitch": 1.0,
            "loop": true,
            "fade_in": 0.5,
            "fade_out": 0.5
          }
        ]
      },
      {
        "id": "event_track_1",
        "type": "event",
        "start_offset": 5.0,
        "segments": [
          {
            "start_time": 0.0,
            "duration": 2.0,
            "type": "particle_effect",
            "particle": "minecraft:explosion",
            "position": { "x": 20.0, "y": 70.0, "z": 10.0 },
            "count": 10,
            "speed": 0.5
          }
        ]
      },
      {
        "id": "mod_event_track_1",
        "type": "mod_event",
        "start_offset": 25.0,
        "segments": [
          {
            "start_time": 2.0,
            "duration": 3.0,
            "type": "mymod:animation",
            "animation_id": "dragon_special_attack",
            "target_entity": "ender_dragon",
            "parameters": {
              "speed": 1.5,
              "intensity": 0.8
            }
          }
        ]
      }
    ]
  },
  "playback_settings": {
    "loop": false,
    "speed_multiplier": 1.0,
    "default_volume": 1.0,
    "crossfade_enabled": true,
    "crossfade_duration": 0.5
  }
}
```

### 2.2 数据结构详解

#### **2.2.1 元数据部分 (metadata)**
| 字段 | 类型 | 说明 | 必填 |
|------|------|------|------|
| `id` | string | 脚本唯一标识符，用于脚本管理和分发 | 是 |
| `version` | integer | 脚本版本号，用于更新管理 | 是 |
| `author` | string | 作者信息 | 否 |
| `description` | string | 脚本描述 | 否 |
| `created_timestamp` | string | 创建时间戳（ISO 8601格式） | 否 |

#### **2.2.2 触发器部分 (triggers)**
| 字段 | 类型 | 说明 | 必填 |
|------|------|------|------|
| `id` | string | 触发器唯一标识符 | 是 |
| `type` | string | 触发器类型：`time`, `location`, `event`, `custom` | 是 |
| `conditions` | object | 触发条件，类型不同结构不同 | 是 |
| `actions` | array | 触发后执行的动作列表 | 是 |

**时间触发器条件 (time类型)**：
```json
"conditions": {
  "trigger_time": 12.5,  // 触发时间（秒）
  "repeat_interval": 0.0 // 重复间隔，0表示不重复
}
```

**位置触发器条件 (location类型)**：
```json
"conditions": {
  "position": { "x": 100.0, "y": 64.0, "z": 200.0 },
  "radius": 10.0,
  "player_count": 1,     // 触发所需玩家数量
  "team_required": null, // 可选的队伍要求
  "entity_type": "player" // 实体类型要求
}
```

#### **2.2.3 主时间轴部分 (master_timeline)**
| 字段 | 类型 | 说明 | 必填 |
|------|------|------|------|
| `total_duration` | number | 脚本总时长（秒），由编辑器计算保存 | 是 |
| `tracks` | array | 时间轴轨道数组 | 是 |

**轨道对象结构**：
```json
{
  "id": "camera_track_1",
  "type": "camera",
  "start_offset": 0.0,
  "segments": [...]
}
```

| 轨道字段 | 类型 | 说明 |
|----------|------|------|
| `id` | string | 轨道唯一标识符 |
| `type` | string | 轨道类型：`camera`, `audio`, `event`, `mod_event`, `custom` |
| `start_offset` | number | 轨道在总时间轴中的开始时间（秒） |
| `segments` | array | 轨道片段数组 |

**轨道类型说明**：
1. **camera类型**：控制相机的位置、旋转、视野等属性
2. **audio类型**：控制背景音乐、音效的播放
3. **event类型**：触发游戏内事件（粒子效果、命令执行等）
4. **mod_event类型**：第三方模组扩展的事件轨道
5. **custom类型**：用户自定义的轨道类型

#### **2.2.4 片段结构 (segments)**
| 字段 | 类型 | 说明 | 必填 |
|------|------|------|------|
| `id` | string | 片段唯一标识符（可选，用于复杂轨道） | 否 |
| `start_time` | number | **片段在轨道中的相对开始时间**（秒） | 是 |
| `duration` | number | **片段持续时间**（秒） | 是 |
| `type` | string | 片段类型，不同类型包含不同数据 | 是 |
| `data` | object | 类型特定的数据（对于keyframe_sequence是`keyframes`） | 是 |

**片段类型**：
1. **keyframe_sequence**：关键帧序列，包含 `keyframes` 数组
2. **audio_clip**：音频片段，包含音频参数
3. **particle_effect**：粒子效果片段
4. **command_sequence**：命令执行序列
5. **custom_event**：自定义事件片段

**关键帧结构 (keyframe_sequence)**：
```json
{
  "time": 5.0,  // 关键帧在片段中的相对时间（秒）
  "state": {
    "position": { "x": 10.5, "y": 64.2, "z": -15.3 },
    "rotation": { "yaw": 0.0, "pitch": -0.349, "roll": 0.0 },
    "fov": 70.0
  },
  "interpolation": "smooth"
}
```

**插值类型 (interpolation)**：
```json
{
  "LINEAR": "线性插值（匀速）",
  "SMOOTH": "平滑插值（缓入缓出）",
  "EASE_IN": "缓入（开始慢，结束快）",
  "EASE_OUT": "缓出（开始快，结束慢）",
  "EASE_IN_OUT": "缓入缓出（两端慢，中间快）"
}
```

#### **2.2.5 播放设置 (playback_settings)**
| 字段 | 类型 | 说明 | 默认值 |
|------|------|------|--------|
| `loop` | boolean | 是否循环播放 | `false` |
| `speed_multiplier` | number | 播放速度倍率（1.0为正常速度） | `1.0` |
| `default_volume` | number | 默认音量（0.0-1.0） | `1.0` |
| `crossfade_enabled` | boolean | 是否启用轨道间交叉淡入淡出 | `true` |
| `crossfade_duration` | number | 交叉淡入淡出时长（秒） | `0.5` |

## 3. 时间计算规则

### 3.1 核心概念

1. **总时长 (total_duration)**：脚本的完整播放时长，由编辑器自动计算
   - 计算方法：所有轨道中 `start_offset + max(segment.start_time + segment.duration)` 的最大值

2. **轨道开始偏移 (start_offset)**：轨道在总时间轴中的开始时间
   - 示例：`start_offset: 12.0` 表示轨道在第12秒开始

3. **片段相对开始时间 (segment.start_time)**：片段在轨道中的相对开始时间
   - 示例：`start_time: 5.0` 表示片段在轨道开始后的第5秒开始

4. **片段持续时间 (segment.duration)**：片段的播放时长
   - 示例：`duration: 8.0` 表示片段持续8秒

### 3.2 计算示例

假设有以下脚本结构：

```json
{
  "master_timeline": {
    "total_duration": 50.0,
    "tracks": [
      {
        "id": "camera_track_1",
        "start_offset": 0.0,
        "segments": [
          {
            "start_time": 0.0,
            "duration": 10.0,
            "keyframes": [...]
          },
          {
            "start_time": 10.0,  // 第一个片段结束后
            "duration": 8.0,
            "keyframes": [...]
          }
        ]
      },
      {
        "id": "audio_track_1",
        "start_offset": 12.0,    // 当第二个片段开始时
        "segments": [...]
      }
    ]
  }
}
```

**时间线图示**：
```
总时间线 (0-50秒)
├── 相机轨道1 (0-18秒)
│   ├── 片段1: 0-10秒 (轨道相对时间: 0-10秒)
│   └── 片段2: 10-18秒 (轨道相对时间: 10-18秒)
└── 音频轨道1 (12-32秒)
    └── 音频片段: 0-20秒 (轨道相对时间: 0-20秒)
```

**关键时间点**：
- 相机轨道1片段1：总时间 0-10秒
- 相机轨道1片段2：总时间 10-18秒  
- 音频轨道1：总时间 12-32秒（12 = start_offset, 32 = 12 + duration）

### 3.3 调度计算规则（由player模块负责）

1. **时间转换公式**：
   ```
   轨道相对时间 = 总时间 - 轨道start_offset
   片段内时间 = 轨道相对时间 - 片段start_time
   ```

2. **有效时间判断**：
   - 当 `总时间 >= start_offset` 且 `总时间 <= start_offset + max_segment_end_time` 时，轨道有效
   - 当 `轨道相对时间 >= segment.start_time` 且 `轨道相对时间 <= segment.start_time + segment.duration` 时，片段有效

3. **player模块调度职责**：
   - 维护当前播放时间
   - 根据总时间计算每个轨道的相对时间
   - 确定每个片段是否应该被激活
   - 处理关键帧插值计算
   - 管理触发器检测和触发

## 4. 触发器系统设计

### 4.1 触发器类型

#### **时间触发器 (time)**
```json
{
  "id": "time_trigger_1",
  "type": "time",
  "conditions": {
    "trigger_time": 25.0,
    "repeat_interval": 0.0,
    "jitter": 0.5
  },
  "actions": [
    {
      "type": "start_playback",
      "script_id": "special_effect"
    }
  ]
}
```

#### **位置触发器 (location)**
```json
{
  "id": "location_trigger_1",
  "type": "location",
  "conditions": {
    "position": { "x": 150.0, "y": 70.0, "z": -50.0 },
    "radius": 15.0,
    "player_count": 1,
    "team_required": "red",
    "entity_type": "player"
  },
  "actions": [
    {
      "type": "play_sound",
      "sound": "minecraft:entity.ender_dragon.growl",
      "volume": 1.0
    }
  ]
}
```

#### **事件触发器 (event)**
```json
{
  "id": "event_trigger_1",
  "type": "event",
  "conditions": {
    "event_type": "player_interact",
    "target": "special_button",
    "data": {
      "interaction_type": "right_click"
    }
  },
  "actions": [
    {
      "type": "execute_command",
      "command": "say 特殊按钮被触发了！"
    }
  ]
}

### 4.2 动作系统

#### **动作类型**
```json
{
  "start_playback": {
    "description": "开始播放指定脚本",
    "parameters": {
      "script_id": "脚本唯一标识符"
    }
  },
  "stop_playback": {
    "description": "停止播放指定脚本",
    "parameters": {
      "script_id": "脚本唯一标识符"
    }
  },
  "play_sound": {
    "description": "播放音效",
    "parameters": {
      "sound": "音效资源标识",
      "volume": "音量(0.0-1.0)",
      "pitch": "音高(0.5-2.0)",
      "loop": "是否循环"
    }
  },
  "execute_command": {
    "description": "执行游戏命令",
    "parameters": {
      "command": "命令字符串"
    }
  },
  "spawn_particle": {
    "description": "生成粒子效果",
    "parameters": {
      "particle": "粒子类型",
      "position": "生成位置",
      "count": "粒子数量",
      "speed": "粒子速度"
    }
  },
  "custom_action": {
    "description": "第三方模组自定义动作",
    "parameters": "自定义结构"
  }
}
```

#### **动作执行流程**
1. **条件检测**：player模块持续检测所有触发器的条件
2. **动作排队**：条件满足时，将动作加入执行队列
3. **动作执行**：按照优先级顺序执行动作
4. **结果反馈**：记录执行结果，支持错误处理和重试

## 5. 第三方模组扩展系统

### 5.1 扩展接口设计

#### **事件扩展接口**
```java
public interface IEventPlugin {
    // 注册事件类型
    void registerEventTypes(EventRegistry registry);
    
    // 创建事件实例
    @Nullable
    TimelineEvent createEvent(String eventType, JsonObject data);
    
    // 验证事件数据
    boolean validateEvent(JsonObject data);
}
```

#### **动作扩展接口**
```java
public interface IActionPlugin {
    // 注册动作类型
    void registerActionTypes(ActionRegistry registry);
    
    // 执行动作
    ActionResult executeAction(String actionType, JsonObject parameters);
    
    // 验证动作参数
    boolean validateAction(JsonObject parameters);
}
```

### 5.2 模组事件示例

#### **骨骼动画事件**
```json
{
  "type": "mymod:animation",
  "animation_id": "dragon_special_attack",
  "target_entity": "ender_dragon",
  "parameters": {
    "speed": 1.5,
    "intensity": 0.8,
    "target_position": { "x": 120.0, "y": 70.0, "z": 45.0 }
  }
}
```

#### **自定义效果事件**
```json
{
  "type": "othermod:custom_effect",
  "effect_type": "time_warp",
  "duration": 10.0,
  "parameters": {
    "time_scale": 0.5,
    "affect_players": true,
    "visual_distortion": true
  }
}
```

### 5.3 插件注册机制

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

#### **模组初始化示例**
```java
public class MyModPlugin implements IEventPlugin, IActionPlugin {
    
    @Override
    public void registerEventTypes(EventRegistry registry) {
        registry.registerEventType("mymod:animation", data -> {
            String animationId = data.get("animation_id").getAsString();
            String targetEntity = data.get("target_entity").getAsString();
            JsonObject params = data.getAsJsonObject("parameters");
            return new MyModAnimationEvent(animationId, targetEntity, params);
        });
    }
    
    @Override
    public void registerActionTypes(ActionRegistry registry) {
        registry.registerActionType("mymod:custom_action", parameters -> {
            // 执行自定义动作
            return executeCustomAction(parameters);
        });
    }
}
```

## 6. 时间轴调度算法

### 6.1 调度核心逻辑

#### **时间步进算法**
```java
public class TimelineScheduler {
    private double currentTime = 0.0;
    private final MasterTimeline timeline;
    private final Map<String, TrackState> trackStates = new HashMap<>();
    
    public void update(double deltaTime) {
        // 更新当前时间
        currentTime += deltaTime * playbackSpeed;
        
        // 限制时间范围
        if (currentTime < 0) currentTime = 0;
        if (currentTime > timeline.getTotalDuration()) {
            if (loopEnabled) {
                currentTime %= timeline.getTotalDuration();
            } else {
                currentTime = timeline.getTotalDuration();
                stop();
            }
        }
        
        // 更新所有轨道
        for (TimelineTrack track : timeline.getTracks()) {
            updateTrack(track, currentTime);
        }
        
        // 检测触发器
        detectTriggers(currentTime);
    }
    
    private void updateTrack(TimelineTrack track, double globalTime) {
        // 计算轨道相对时间
        double trackRelativeTime = globalTime - track.getStartOffset();
        
        // 检查轨道是否在活动范围内
        if (trackRelativeTime < 0 || trackRelativeTime > track.getMaxDuration()) {
            return; // 轨道不在活动时间范围内
        }
        
        // 查找当前活动的片段
        TimelineSegment activeSegment = findActiveSegment(track, trackRelativeTime);
        if (activeSegment == null) return;
        
        // 计算片段内时间
        double segmentTime = trackRelativeTime - activeSegment.getStartTime();
        
        // 更新片段状态
        updateSegment(activeSegment, segmentTime);
    }
}
```

### 6.2 关键帧插值算法

#### **插值算法实现**
```java
public class KeyframeInterpolator {
    
    public static CameraStateData interpolateState(
            CameraStateData from, 
            CameraStateData to, 
            float progress, 
            InterpolationType type) {
        
        CameraStateData result = new CameraStateData();
        
        // 应用插值曲线
        float adjustedProgress = applyInterpolationCurve(progress, type);
        
        // 位置插值（线性）
        result.positionX = lerp(from.positionX, to.positionX, adjustedProgress);
        result.positionY = lerp(from.positionY, to.positionY, adjustedProgress);
        result.positionZ = lerp(from.positionZ, to.positionZ, adjustedProgress);
        
        // 角度插值（处理环绕）
        result.yaw = lerpAngle(from.yaw, to.yaw, adjustedProgress);
        result.pitch = lerpAngle(from.pitch, to.pitch, adjustedProgress);
        result.roll = lerpAngle(from.roll, to.roll, adjustedProgress);
        
        // 视野插值
        result.fov = lerp(from.fov, to.fov, adjustedProgress);
        
        return result;
    }
    
    private static float applyInterpolationCurve(float progress, InterpolationType type) {
        switch (type) {
            case LINEAR:
                return progress;
            case SMOOTH:
                return progress * progress * (3.0f - 2.0f * progress);
            case EASE_IN:
                return progress * progress;
            case EASE_OUT:
                return 1.0f - (1.0f - progress) * (1.0f - progress);
            case EASE_IN_OUT:
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
}
```

## 7. 版本兼容性与迁移

### 7.1 版本控制规则

#### **版本号格式**
- 格式：`主版本.次版本.修订版本`
- 示例：`1.0.0`, `1.1.0`, `2.0.0`

#### **版本升级规则**
1. **修订版本升级**：向后兼容的bug修复或小改进
2. **次版本升级**：新增功能，向后兼容
3. **主版本升级**：不兼容的重大改变

### 7.2 迁移策略

#### **自动迁移**
- 播放器模块检测脚本版本
- 自动应用版本转换规则
- 兼容旧格式数据

#### **手动迁移工具**
- 提供命令行工具进行脚本升级
- 支持批量转换旧版本脚本
- 生成迁移报告和错误日志

### 7.3 向后兼容保证

#### **兼容性规则**
1. 旧版本脚本可以在新版本播放器中运行
2. 新版本脚本可以在旧版本播放器中运行（功能降级）
3. 第三方插件支持多版本API

---

**文档状态**：完成脚本格式设计规范  
**最后更新**：2026/3/31  
**下一步**：根据此规范实现脚本解析和序列化模块
