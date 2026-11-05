"""icdev：ImmersiveCinematics 本地测试装置的常驻服务核心。

模块划分（互不反向依赖）：
  errors   错误类型与错误码
  paths    仓库/运行目录/快照根
  win32    Win32 窗口与输入（PostMessage）、Job Object
  process  进程枚举、所有权三元组、终止
  launch   loom classpath 缓存、java 直启
  logs     日志读取、过滤、正则等待
  rcon     RCON 客户端（连接复用）
  config   immersive_cinematics.json 读写与校验
  snapshot 运行目录快照/还原
  pixels   亮像素簇与两图差异
  diag     诊断会话 manifest 与摘要
  rig      装置状态机（依赖上面全部）
  server   MCP server（stdio JSON-RPC）
"""

__version__ = "0.1.0"
