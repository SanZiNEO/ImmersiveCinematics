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
| [script.md](./script.md) | `script/` | 脚本解析/加载、6 种轨道、各 TrackPlayer、schema |
| [trigger.md](./trigger.md) | `trigger/server/` | 23 种触发器、引擎、状态存储、事件管理 |
| [network.md](./network.md) | `trigger/network/` | 10 个网络包、播放/停止/暂停/投票/状态同步链路 |
| [editor.md](./editor.md) | `editor/` | 时间轴编辑器、撤销重做、保存管线、已知 bug |
| [overlay.md](./overlay.md) | `overlay/` | 覆盖层五种（黑边/fade/图片/字幕/画中画）、OverlayManager |
| [control.md](./control.md) | `control/` | 运行时行为、跳过、投票、输入屏蔽、HUD 白名单 |
| [mixin.md](./mixin.md) | `mixin/`（资源声明） | 10 个 mixin 各司其职 |
| [command.md](./command.md) | `command/` | /icinematics 命令 |
| [handler.md](./handler.md) | `handler/` | 服务端/客户端事件注册 |
| [client.md](./client.md) | `client/` | ConfigScreen、EditorBridgeImpl |
| [util.md](./util.md) | `util/` | 工具类 |
| [core.md](./core.md) | `common/`（根包入口） | 模组初始化、23 种触发器注册、全局配置项 |
