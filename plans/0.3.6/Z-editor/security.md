# 握手身份校验

> 状态：已实现（2026-10-07；游戏内实际连接未验证）
> 范围：`WebEditorServer` 的握手阶段身份校验；不改协议信封、消息类型与二进制帧
> 关联：[`server-protocol.md`](server-protocol.md)

## 一、威胁模型

`WebEditorServer` 只绑 `127.0.0.1` 挡不住“用户浏览器里的任意网页”——浏览器会替网页连回环地址。恶意页面或本地程序可连 `ws://127.0.0.1:8765/ws` 冒充编辑器读写脚本、控制预览。加固 = **握手阶段的身份校验**。

## 二、方案

任一不过 → 403 + 关连接 + `[IC-WebUI] rejected <addr>: <原因>` 日志，不进入会话。

| 道 | 校验 | 拦住谁 |
|---|---|---|
| 1 | **Origin 白名单**：浏览器在 WebSocket 握手里强制附带 `Origin` 且网页无法伪造。放行 `file://` / `null`（Electron 打包态页面）、`http(s)://localhost\|127.0.0.1\|::1[:port]`（vite dev server）；无 `Origin` 的非浏览器客户端放行到第二道 | 用户浏览器里的任意网页（`https://evil.com` 直接拒） |
| 2 | **每次启动随机 token**：`start()` 用 `SecureRandom` 生成 32 字节（64 位十六进制）写入 `<user.home>/.immersivecinematics/webui-token`（路径可用环境变量 `IC_WEBUI_TOKEN_FILE` 覆盖），客户端以 `ws://127.0.0.1:8765/ws?token=...` 携带，服务端用 `MessageDigest.isEqual` 常量时间比对；`stop()` 删除该文件 | 本地程序、本地文件型页面（读不到 token 文件）；token 每次开服轮换，旧值立刻失效 |

**为什么两道都要**：只做 Origin——`file://` / `null` 必须在白名单里（Electron 打包态就是 `file://` 页面），“用户本地打开的一个 HTML 文件”也落在这一档，Origin 挡不住，token 挡得住；只做 token——Origin 是浏览器强制、不可伪造的，能第一时间把网页挡在门外，不给 token 留爆破面。两者互补，都不是可选项。

**不改协议**：token 只出现在握手 URL 的 query 里，`{type,data,id}` 信封、全部消息类型与二进制帧格式不变。

## 三、两端改动点

| 端 | 文件 | 改动 |
|---|---|---|
| Java | `webui/WebEditorServer.java` | 新增 `token` 字段与 `tokenFilePath()` / `generateToken()` / `writeTokenFile()` / `deleteTokenFile()` / `tokenMatches()` / `isAllowedOrigin()` / `reject()`；`start()` 绑端口成功后生成并写 token 文件，`stop()` 置空并删文件；`handleSocket()` 的 `/ws` 分支在 `upgradeWebSocket()` **之前**跑两道校验；`parsePath()` 改为剥离 query，新增 `parseQueryParam()` 取 token |
| 前端 | `editor/src/store.ts` | 新增 `authToken()`；`connect()` 每次重连重新取 token 并拼进 URL（`ws://127.0.0.1:8765/ws?token=...`）；取不到时打日志 + 置 `state.error`，仍按 1.5s 重连（游戏开服写出 token 后自动接上） |
| 前端 | `editor/electron/main.cjs` | 新增 `webuiTokenFile()` / `readWebuiToken()` 与 `webui:token` 同步 IPC handler：主进程读 token 文件，路径算法与 Java 侧一致（同样认 `IC_WEBUI_TOKEN_FILE`） |
| 前端 | `editor/electron/preload.cjs` | 暴露 `electronWindow.getWebuiToken()`（`ipcRenderer.sendSync('webui:token')`）给渲染进程 |
| 前端 | `editor/src/electron-bridge.d.ts`（新增） | `window.electronWindow` 的类型声明（浏览器 dev 下不存在，声明为可选） |

**渲染进程为什么绕主进程**：打包态页面是 `file://`，渲染进程读不了磁盘文件；主进程读、preload 同步 IPC 递过来是最短通路。每次 `connect()` 调一次，因此游戏重启轮换 token 后重连自动取到新值；token 文件不存在时（游戏未按 F9 开服）编辑器不假装连上，日志与 `state.error` 明说原因。

## 四、验证（2026-10-07，非游戏内）

`sh gradlew compileJava` 通过；`cd editor && npm run build` 通过；一次性冒烟脚本对真实 `WebEditorServer` 打 11 组握手——坏 Origin（`https://evil.com`）、缺 token、空 token、错 token、无 Origin 无 token 全部 403，`file://` + 正确 token、`http://localhost:5173` + 正确 token、无 Origin + 正确 token 全部 101，`stop()` 后 token 文件确实被删除；再用**真实 Electron 客户端**跑通端到端：token 文件在位时服务端日志出现 `editor client connected`（该客户端要么不带 `Origin`、要么落在白名单内，否则会被 `origin not allowed` 拒掉），把 token 文件移走后同一客户端 10 次重连全部 `rejected ... invalid or missing token`。

**游戏内实际连接未验证**（本机无 MC 运行环境）。
