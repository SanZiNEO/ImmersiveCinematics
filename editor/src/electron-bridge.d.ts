// preload.cjs 通过 contextBridge 暴露给渲染进程的 API（见 editor/electron/preload.cjs）。
// 浏览器 dev（vite）下 window.electronWindow 不存在，因此声明为可选。

interface ElectronBridge {
  minimize(): void
  maximize(): void
  close(): void
  /** 读取 mod 写入的 WebUI 握手 token；文件不存在（游戏未开服）时返回 ''。 */
  getWebuiToken(): string
}

interface Window {
  electronWindow?: ElectronBridge
}
