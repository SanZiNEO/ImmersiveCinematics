# ImmersiveCinematics Editor 功能清单

> 来源：模组 Java 编辑器代码（EditorScreen / panel / trigger / preset / schema / CameraManager）。
> 飞行控制按计划在游戏侧实现，Editor 只负责脚本编辑，不负责飞控面板。

---

## 1. 脚本管理

- [ ] 脚本列表（递归目录）
- [ ] 新建脚本
- [ ] 打开脚本
- [ ] 保存脚本
- [ ] 删除脚本
- [ ] 保存前校验
- [ ] 脚本 ID / 名称处理
- [ ] 当前脚本内容预览（左侧）

---

## 2. 脚本 Meta 字段

### 基础
- [ ] id
- [ ] name
- [ ] author
- [ ] version
- [ ] description
- [ ] dimension

### 运行时
- [ ] block_keyboard
- [ ] block_mouse
- [ ] block_mob_ai
- [ ] hide_hud
- [ ] render_player_model
- [ ] pause_when_game_paused

### HUD 隐藏组
- [ ] hide_arm
- [ ] suppress_bob
- [ ] suppress_distortion
- [ ] hide_chat
- [ ] hide_scoreboard
- [ ] hide_action_bar
- [ ] hide_title
- [ ] hide_subtitles
- [ ] hide_hotbar
- [ ] hide_crosshair
- [ ] hide_bossbar
- [ ] hide_skip_hud
- [ ] hud_layers

### 相机刷怪
- [ ] camera_mob_spawn
- [ ] camera_mob_radius
- [ ] camera_mob_ai

### 播放控制
- [ ] interruptible
- [ ] skippable
- [ ] hold_at_end
- [ ] priority
- [ ] skip_vote_ratio

---

## 3. 轨道

### 轨道类型
- [ ] CAMERA
- [ ] LETTERBOX
- [ ] AUDIO
- [ ] EVENT
- [ ] MOD_EVENT
- [ ] OVERLAY

### 轨道操作
- [ ] 添加轨道
- [ ] 删除轨道
- [ ] 重命名轨道
- [ ] 显隐轨道
- [ ] 锁定轨道
- [ ] 静音轨道
- [ ] 选择轨道

---

## 4. Clip 字段

### CAMERA
- [ ] start_time
- [ ] duration
- [ ] transition
- [ ] interpolation
- [ ] loop
- [ ] loop_count
- [ ] loop_mode
- [ ] curve（贝塞尔）
- [ ] dimension
- [ ] transition_duration
- [ ] orient
- [ ] yaw_offset
- [ ] pitch_offset
- [ ] cam_breath_enabled
- [ ] cam_breath_intensity
- [ ] cam_breath_seed
- [ ] cam_breath_type
- [ ] cam_breath_speed
- [ ] cam_breath_trauma
- [ ] cam_breath_decay

### AUDIO
- [ ] start_time
- [ ] duration
- [ ] sound
- [ ] source
- [ ] volume
- [ ] pitch
- [ ] loop
- [ ] fade_in
- [ ] fade_out
- [ ] attenuation
- [ ] position_mode
- [ ] category

### EVENT
- [ ] start_time
- [ ] duration
- [ ] event_type

### MOD_EVENT
- [ ] start_time
- [ ] duration
- [ ] event_type
- [ ] data

### OVERLAY
- [ ] start_time
- [ ] duration
- [ ] layer_type
- [ ] interpolation
- [ ] color
- [ ] path
- [ ] text
- [ ] z_index

---

## 5. 关键帧字段

### CAMERA
- [ ] time
- [ ] position
- [ ] position_mode
- [ ] yaw
- [ ] pitch
- [ ] roll
- [ ] yaw_base
- [ ] pitch_base
- [ ] yaw_base_selector
- [ ] yaw_base_from
- [ ] yaw_base_to
- [ ] look_at
- [ ] look_at_selector
- [ ] look_at_target_x
- [ ] look_at_target_y
- [ ] look_at_target_z
- [ ] look_at_target_structure
- [ ] look_at_target
- [ ] follow
- [ ] follow_selector
- [ ] fov
- [ ] zoom

### AUDIO
- [ ] time
- [ ] volume
- [ ] x
- [ ] y
- [ ] z

### EVENT
- [ ] time
- [ ] event_type
- [ ] command
- [ ] position

### MOD_EVENT
- [ ] time
- [ ] event_type
- [ ] data

### OVERLAY
- [ ] time
- [ ] opacity
- [ ] x
- [ ] y
- [ ] font_scale
- [ ] scale_x
- [ ] scale_y

### LETTERBOX
- [ ] time
- [ ] aspect_ratio

---

## 6. 时间轴交互

### Clip
- [ ] 添加 Clip
- [ ] 删除 Clip
- [ ] 移动 Clip
- [ ] 左右拉伸 Clip
- [ ] 拆分 Clip
- [ ] 复制 / 粘贴 Clip
- [ ] 多选 Clip

### 关键帧
- [ ] 添加关键帧
- [ ] 删除关键帧
- [ ] 移动关键帧
- [ ] 选择关键帧

### 时间轴显示
- [ ] 标尺
- [ ] 播放头
- [ ] 缩放
- [ ] 滚动
- [ ] 吸附
- [ ] Frame All
- [ ] Marker
- [ ] A-B 循环点

---

## 7. 预览

- [ ] 实时画面
- [ ] 播放 / 暂停 / 停止
- [ ] 时间跳转
- [ ] 相机参数滑杆（yaw/pitch/roll/fov/zoom）
- [ ] 相机 Gizmo
- [ ] 当前关键帧高亮

> 飞行取景：不在 Editor 内实现，游戏侧负责。

---

## 8. 触发器

### 通用字段
- [ ] id
- [ ] type
- [ ] repeatable
- [ ] delay
- [ ] exit_buffer
- [ ] on_enter
- [ ] conditions

### 触发器类型
- [ ] login
- [ ] command
- [ ] location
- [ ] advancement
- [ ] biome
- [ ] entity_kill
- [ ] entity_interact
- [ ] block_interact
- [ ] item_on_interact
- [ ] dimension_change
- [ ] dimension
- [ ] item_craft
- [ ] item_use
- [ ] item_consume
- [ ] item_release
- [ ] item_instant_use
- [ ] item_use_interrupt
- [ ] item_pickup
- [ ] item_drop
- [ ] xp
- [ ] observation
- [ ] inventory
- [ ] structure
- [ ] gamestage

---

## 9. 预设

- [ ] 轨道环绕预设（OrbitCircle）
  - [ ] 中心 X / Y / Z
  - [ ] 半径
  - [ ] 高度
  - [ ] 时长
- [ ] 预设生成脚本

---

## 10. 自动补全 / 注册表数据

- [ ] 物品
- [ ] 实体
- [ ] 结构
- [ ] 进度
- [ ] 生物群系
- [ ] 维度
- [ ] 目标选择器

---

## 11. 编辑体验

- [ ] 撤销 / 重做
- [ ] 剪贴板（复制 / 剪切 / 粘贴）
- [ ] 快捷键
- [ ] 保存校验错误提示

---

## 12. 布局骨架

- [ ] 左侧脚本内容展示（只读、可复制）
- [ ] 右编辑器：
  - [ ] 左面板（脚本 / 轨道 / 预设 等 Tab）
  - [ ] 中间预览
  - [ ] 右面板（属性 / 触发器 等 Tab）
- [ ] 底部时间轴：
  - [ ] 顶部横排工具栏（靠右）
  - [ ] 左侧竖排工具
  - [ ] 时间轴画布
- [ ] 面板可拖拽调大小
