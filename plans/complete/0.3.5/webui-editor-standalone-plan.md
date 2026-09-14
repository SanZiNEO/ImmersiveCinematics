# 0.3.5 独立 Editor 计划（Standalone Editor）

**状态**: 方案讨论，未开始实现  
**目标**: 独立 Editor 第一版覆盖现有 Java 编辑器全部核心功能（含触发器），修掉已知问题，不做动效  
**技术方向**: Editor 是独立 Windows 小工具 + Web UI，不打包进 mod；Editor 作为客户端连接 mod 服务端

---

## 1. 命名与仓库位置

- 仓库根目录叫：
  ```text
  editor/
  ```
- Editor 源码独立于 mod，不参与 mod 打包。
- mod 是服务端，Editor 是客户端。

---

## 2. 总体架构

```text
┌───────────────────────────────────────────┐
│   Editor 独立小工具（editor/）              │
│   Windows 启动器 + Web UI                  │
│   EditorClient（连接 mod）                 │
└───────────────┬───────────────────────────┘
                │ 连接 mod 的固定端口
                ▼
┌───────────────────────────────────────────┐
│   ImmersiveCinematics Mod（Java）          │
│   本地 WebSocket 服务端（已具备）           │
│   帧流 / 文件 / Schema / 播放 / 触发器      │
│   已有 CameraManager / Schema / 文件        │
└───────────────────────────────────────────┘
```

### 运行方式

1. 作者先启动游戏。
2. 游戏内按编辑器按键：
   - mod 启动本地服务；
   - 提示作者下载并启动 Editor。
3. 作者启动 Editor：
   - Editor 作为客户端连接 `ws://127.0.0.1:<固定高端口>/ws`；
   - 握手成功后直接进入编辑。

### 关键决定

- **mod 是服务端，Editor 是客户端。**
- 固定高端口，例如 `8765`，可配置但默认固定。
- **只支持 Windows**，不做跨平台。
- Editor 不依赖 mod 托管静态文件，自带启动器和 UI。

---

## 3. 通用性设计

Editor 要设计成“不同模组都能用同一个 Web UI”：

- UI 层面不写死 ImmersiveCinematics 专用逻辑。
- 通过 `schema` / `metadata` 驱动。
- 核心协议设计成通用结构：
  ```json
  { "type": "...", "data": {...}, "id": "..." }
  ```
- 每个模组只需实现同一套服务端协议，Editor 无需为每个模组重写 UI。
- 这样以后迁移到其他 mod，或换版本，只改适配层。

---

## 4. Editor 发布形态

- 独立 Windows 小工具。
- 包含：
  - 启动器 / 可执行程序
  - 本地 Web UI 静态资源
  - EditorClient
- 发布为一个 release 包。

---

## 5. 第一版功能范围

目标：

> 覆盖现有 Java 编辑器全部核心功能，含触发器，修掉已知问题，不做动效。

### 功能清单

1. **连接**
   - 连接 mod 服务端
   - 握手 / 状态显示
   - 断线重连

2. **脚本管理**
   - 脚本列表
   - 新建 / 打开 / 保存 / 删除
   - 脚本结构预览

3. **元数据编辑**
   - 脚本 meta
   - schema 动态表单

4. **触发器编辑（第一版必须）**
   - 触发器列表
   - 新增 / 删除触发器
   - 触发器类型、条件、延迟、repeatable 等
   - 触发器是 meta 数据的一个分支，按 schema 动态生成表单

5. **轨道编辑**
   - CAMERA / LETTERBOX / AUDIO / EVENT / MOD_EVENT / OVERLAY
   - 添加 / 删除轨道
   - 显隐 / 锁定 / 静音

6. **时间轴**
   - 底部时间轴
   - 轨道行
   - 片段显示 / 选中 / 添加 / 删除 / 移动
   - 关键帧显示 / 选中 / 添加 / 删除 / 移动
   - 播放头拖动
   - 播放 / 暂停 / 停止 / 跳转

7. **属性面板**
   - 动态字段
   - bool / enum / float / int / string / tristate
   - position / bezier_curve / look_at_target 等基础编辑

8. **预览**
   - 720p raw 画面
   - 当前时间
   - 简单相机控制：yaw / pitch / roll / fov / zoom

9. **基础编辑体验**
   - 撤销 / 重做
   - 复制 / 粘贴 / 删除
   - 核心快捷键

10. **bug 修复**
    - 保存时精简冗余默认字段
    - 旧 Java 编辑器已知问题不再保留

### 不做

- 动效 / 动画过渡
- 高级自动补全（后补）
- 高级预设库（后补）
- 飞控 Web 面板（后补）

---

## 6. 现代编辑器布局

- **底部时间轴**。
- 左侧竖向脚本结构栏。
- 中间预览 + 属性。
- 各面板/边框**可自由调节大小**，参考开源编辑器（lossless-cut / olive）的边框拖拽交互。

示例：

```text
┌─────────────────┬──────────────────────────────────────┐
│  左侧竖栏        │  预览区域                            │
│  脚本结构 / 列表  │                                      │
│                 ├──────────────────────────────────────┤
│                 │  属性面板（可折叠）                    │
├─────────────────┴──────────────────────────────────────┤
│  底部时间轴（可调节高度）                                │
└────────────────────────────────────────────────────────┘
```

- 边框可拖拽调节。
- 组件各自独立文件，禁止单个文件三五千行。
- 交互参考成熟 Web 编辑器。

---

## 7. 代码组织要求

- 组件化：
  - `EditorClient`
  - `ScriptList`
  - `ScriptStructure`
  - `Timeline`
  - `PropertyPanel`
  - `Preview`
  - `TriggerPanel`
  - `fields/*`
- 每个组件一个职责。
- 架构写好后，后续不同 mod 共用 UI 只改协议/适配层。

---

## 8. 通信协议

### 8.1 握手

```text
mod 服务端监听固定高端口
Editor 客户端连接
Editor → mod: hello
mod → Editor: hello_ack
```

### 8.2 核心消息

```text
script.list
script.load
script.save
script.delete

trigger.list      （或从 doc 中读）
trigger.save

editor.seek
editor.play
editor.pause
editor.stop

playback.state

schema.get
schema.data
```

### 8.3 帧流

继续使用现有二进制帧协议：

```text
[1 byte type]
[2 bytes frameId]
[2 bytes width]
[2 bytes height]
[payload]
```

第一版：720p raw，60fps 发送节流。

---

## 9. Java 侧新增

1. `WebSocketServer` 已有，继续增强。
2. `EditorServerApi`
   - script / playback / schema / trigger 消息路由
3. `ScriptFileService`
   - 抽自旧 `EditorScreen`
4. `PlaybackService`
   - 映射到 `CameraManager`
5. `SchemaService`
   - 返回 `schema.get`
6. 配置
   - 端口固定高端口
   - 是否启用 Editor 模式
   - 是否自动提示下载 Editor

---

## 10. Editor 前端技术栈

- Vue 3
- TypeScript
- Vite
- 轻量状态管理（Pinia 或 composable）
- 时间轴第一版用自定义 HTML/CSS 组件实现
- 参考：
  - `example/editor/lossless-cut`（Electron/TS Web UI）
  - `example/editor/olive`（时间轴布局）

---

## 11. 实施阶段

```text
Phase A：Editor Windows 启动器骨架 + mod 服务端确认
Phase B：EditorClient 连接 mod + hello 握手
Phase C：脚本列表 / 打开 / 保存 / 删除
Phase D：schema 动态表单 + meta 编辑
Phase E：触发器编辑
Phase F：轨道列表 + 底部时间轴基础
Phase G：属性面板 + 播放控制 + 预览
Phase H：撤销重做 + 基础编辑体验
Phase I：布局打磨（可调节边框 / 左侧结构栏 / 底部时间轴）
Phase J：独立 Windows Release + 游戏内提示
Phase K：旧 Java 编辑器退役
```

---

## 12. 参考源码

本地已有：

- `example/editor/lossless-cut`  
  可参考 Web 技术 UI 架构、组件拆分、时间轴交互。
- `example/editor/olive`  
  可参考视频编辑器整体布局、时间轴、面板边框调节。

---

## 13. 待确认

1. 固定高端口具体用哪个？  
   - 建议 `8765`，或者你指定。

2. mod 服务端是否在游戏启动时就监听，还是按编辑器按键后才监听？  
   - 建议：按编辑器按键后才监听，避免平时开端口。

3. Editor 启动器形态：
   - `.exe` + 资源？
   - 还是 `.bat` / 脚本 + 本地服务？
   - 建议：先做成可执行 / 压缩包，内部启动本地 Web UI 服务。

4. 触发器具体覆盖哪些类型？
   - 现有 Java 编辑器里的触发器全部搬过来就行。
