const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('electronWindow', {
  minimize: () => ipcRenderer.send('window:minimize'),
  maximize: () => ipcRenderer.send('window:maximize'),
  close: () => ipcRenderer.send('window:close'),
  // WebUI 握手 token：由主进程读取 mod 写下的 token 文件（渲染进程是 file:// 页面，读不了文件）。
  // 用同步 IPC：每次 connect() 调用一次，游戏重启轮换 token 后重连能自动取到新值。
  getWebuiToken: () => ipcRenderer.sendSync('webui:token'),
})
