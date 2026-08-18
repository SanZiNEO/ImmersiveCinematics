# 脚本文件夹管理（Script Folder Organization）：子目录组织 + 递归加载

## 一句话定义

脚本/资源目录支持**子文件夹组织**：作者按章节/场景/剧情线建文件夹放脚本，加载递归遍历；音频图片已天然支持（path 写子路径），脚本侧补递归加载。

## 现状

| 类型 | 目录 | 子目录支持 |
|---|---|---|
| 脚本 | `<游戏目录>/immersive_cinematics/scripts/` | ❌ `Files.list` 只读一层，子文件夹不加载 |
| 音频/图片 | `<游戏目录>/immersive_cinematics/resource/` | ✅ `ResourcePath.resolve` = 根目录.resolve(fileName)，path 写 `"sub/bgm.ogg"` 即可 |

## 方案

### 1. 脚本递归加载

```java
// loadFromDir：Files.list → Files.walk（递归）
try (Stream<Path> stream = Files.walk(dir)) {
    jsonFiles = stream.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".json"))
                      .collect(toList());
}
```

- reload / 启动加载共用（reload 已走 loadAll）
- 脚本 id 仍是 meta.id（全局唯一），**子目录只是文件组织，不影响 id 语义**；同 id 冲突按相对路径提示（后者覆盖或跳过，沿用现有 `scripts.containsKey` 逻辑）

### 2. 编辑器脚本列表显示相对路径

- 脚本列表条目：`文件名` → `相对子目录/文件名`（如 `dungeon/enter.json`），方便区分同名文件
- 命令 `/icinematics play` 的 Tab 补全同步显示相对路径

### 3. 目录约定（写进文档）

```
immersive_cinematics/
├── scripts/
│   ├── intro/            # 序章脚本
│   │   ├── welcome.json
│   │   └── village.json
│   ├── chapter1/         # 第一章脚本
│   │   └── boss_fight.json
│   └── showcase_01.json  # 根目录平铺也可以（兼容现状）
└── resource/
    ├── intro/            # 音频/图片按同样结构组织
    │   └── bgm.ogg
    └── overlay.png       # 根目录平铺也可以
```

## 边界与待定

1. 脚本 id 是否带目录前缀（`intro/welcome` vs `welcome`）——倾向**不带**（id 保持脚本内声明，目录纯组织），但同 id 冲突时提示
2. 递归深度限制（如 ≤ 5 层，防异常目录结构）
3. 隐藏目录/非 .json 文件跳过（现有过滤保留）
4. 编辑器新建脚本时是否引导选目录（初版不需要，作者手动建文件夹即可）

## 复杂度

- 脚本递归加载：`Files.list` → `Files.walk` 几行
- 编辑器列表显示相对路径：脚本列表构建处取相对路径
- 文档目录约定：几行
- 总量很小（~30 行），与"作者友好"的诉求匹配

## 执行前再看 / 具体方案

- **项目文件**：`common/src/main/java/com/immersivecinematics/immersive_cinematics/script/ScriptManager.java`（`loadFromDir` 用 `Files.list`）、`command/CinematicCommand.java`（`SCRIPT_SUGGESTIONS` 与 `findScriptFile` 只搜根目录）。
- **改法**：
  - `Files.list` → `Files.walk(dir)`（限制深度 ≤5），过滤 `Files.isRegularFile && .json`。
  - 命令补全：`dir.relativize(p)` 后去掉 `.json` 作为建议；`findScriptFile` 支持子路径但保留路径穿越防护。
  - 编辑器脚本列表改为显示相对路径（执行时 grep `getAllScripts` 或脚本列表构建处）。
- **执行时再看**：`ScriptManager.java`、`CinematicCommand.java`、编辑器脚本列表 UI。
