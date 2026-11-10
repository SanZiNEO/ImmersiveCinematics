> **文档状态：现行参考与历史模块说明并存**
>
> 脚本字段以 [SCRIPT_FORMAT.md](../SCRIPT_FORMAT.md) 为准；媒体、外部工具和固定预览能力见按当前源码整理的 [media.md](./media.md)。其余模块文档仍保留各自的历史警告；本次仅同步已核实的媒体相关内容，不表示历史说明全文已经复核或完成功能验收。
>
> 当前规划请先查阅 [0.3.6 计划状态索引](../../plans/0.3.6/README.md) 与 [I 轮实施基准](../../plans/0.3.6/I-script-core/README.md)，再按状态查阅对应未完成轮次。
>
> 计划目标不代表已经实现；核实当前代码行为时须结合源码，不得将旧文或未实现计划当作现状。

# ImmersiveCinematics 模块文档索引

按模组包结构组织，每包一份文档，用树形结构介绍**功能**（已做/计划做），不是代码讲解。

## 状态标记约定

- ✅ 可用
- ⚠️ 部分可用 / 有已知问题
- ❌ 不可用（bug 或未接入口）
- ⏳ 计划中

## 文档列表

| 文档 | 对应包路径 | 内容范围 |
|---|---|---|
| [camera.md](./camera.md) | `camera/` | 相机接管、虚拟时钟、播放调度、预览、roll、追踪、呼吸 |
| [script.md](./script.md) | `script/` | 脚本解析/加载、7 种内置轨道、各 TrackPlayer、Java Schema 元数据；音频与视频分流 |
| [trigger.md](./trigger.md) | `trigger/server/` | 23 种触发器、引擎、状态存储、事件管理、区块预加载/相机锚点/实体同步 |
| [network.md](./network.md) | `trigger/network/` | 网络包、播放/停止/暂停/投票/状态同步/预加载链路 |
| [editor.md](./editor.md) | `webui/`（后端）+ `editor/`（发行物） | WebSocket 协议、脚本文件服务、固定 1080p60 编码/原始帧预览、预览屏与飞控 |
| [media.md](./media.md) | `media/` + `webui/`（预览） | 现行源码参考：FFmpeg 固定目录、视频覆盖、音频解码、共享捕获、H.264/RGBA 预览及限制 |
| [overlay.md](./overlay.md) | `overlay/` + `media/video/` | 黑边、fade、图片/视频与字幕；画中画由 CAMERA lane 实现 |
| [control.md](./control.md) | `control/` | 运行时行为、跳过、投票、输入屏蔽、HUD 白名单 |
| [mixin.md](./mixin.md) | `mixin/`（资源声明） | 相机/渲染、HUD 隐藏、输入、区块预加载/刷怪、音频听者与共享帧捕获 |
| [command.md](./command.md) | `command/` | /icinematics 命令 |
| [handler.md](./handler.md) | `handler/` | 服务端/客户端事件注册 |
| [client.md](./client.md) | `client/` | ConfigScreen、lane 渲染组件 |
| [util.md](./util.md) | `util/` | 工具类 |
| [core.md](./core.md) | `common/`（根包入口） | 模组初始化、23 种触发器注册、全局配置项 |
