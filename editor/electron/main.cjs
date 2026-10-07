const { app, BrowserWindow, ipcMain } = require('electron')
const fs = require('fs')
const os = require('os')
const path = require('path')

// WebUI 握手 token 文件：由 mod（WebEditorServer.start()）写入，路径与 Java 侧一致。
// IC_WEBUI_TOKEN_FILE 可覆盖（多实例 / 测试）。
function webuiTokenFile() {
  const override = process.env.IC_WEBUI_TOKEN_FILE
  if (override && override.trim()) return override.trim()
  return path.join(os.homedir(), '.immersivecinematics', 'webui-token')
}

function readWebuiToken() {
  try {
    return fs.readFileSync(webuiTokenFile(), 'utf8').trim()
  } catch {
    return ''
  }
}

function createWindow() {
  const win = new BrowserWindow({
    width: 1920,
    height: 1080,
    minWidth: 1280,
    minHeight: 720,
    frame: false,
    backgroundColor: '#151519',
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'),
      nodeIntegration: false,
      contextIsolation: true,
    },
  })
  win.loadFile(path.join(__dirname, '..', 'dist', 'index.html'))
}

// WebUI 握手 token 查询（渲染进程每次重连都会问一次）
ipcMain.on('webui:token', (event) => {
  event.returnValue = readWebuiToken()
})

// 自绘窗口控制按钮
ipcMain.on('window:minimize', (event) => {
  BrowserWindow.fromWebContents(event.sender)?.minimize()
})
ipcMain.on('window:maximize', (event) => {
  const win = BrowserWindow.fromWebContents(event.sender)
  if (!win) return
  if (win.isMaximized()) win.unmaximize()
  else win.maximize()
})
ipcMain.on('window:close', (event) => {
  BrowserWindow.fromWebContents(event.sender)?.close()
})

app.whenReady().then(() => {
  createWindow()
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow()
  })
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit()
})
