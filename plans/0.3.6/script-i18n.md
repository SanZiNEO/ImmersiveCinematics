# 0.3.6 脚本文本 i18n：文本作为第三种资源（长期计划·方向稿）

> 本文是 0.3.6 的长期计划方向稿。
> - 已确认的写“已确认”
> - 确定不了的只写方向和可能的问题
> - 字段名、接口、JSON、迁移步骤等执行时再定
>
> 相关文档：
> - [脚本格式](../../docs/SCRIPT_FORMAT.md)
> - [AI 脚本指南](../../docs/AI_SCRIPTING_GUIDE.md)
> - [可变画面](./variable-frame.md)
> - [WebUI 编辑器](./editor-webui-migration.md)
> - [模板体系](./templates.md)

---

## 1. 定位（已确认）

**文本 = 第三种资源**，与图片 / 音频同构：放 `immersive_cinematics/resource/` 下、由客户端按需读取；脚本里只写引用 key，译文集中管理——**改译文不动脚本**。

由此直接继承图片 / 音频已有的三条性质，**零协议改动**：

1. **打包分发同待遇**：资源随资源目录走，不进脚本、不进存档；
2. **多人服同待遇**：脚本 JSON 走网络下发（`S2CPlayScriptPacket`），资源由各客户端本地读取，不进服务器流量（§8-4）；
3. **缺失不阻塞**：资源缺失只记日志，播放照常（§8-1）。

**不属于本文**：模板体系的参数化（见 `templates.md`）、编辑器 UI 自身的翻译（那是编辑器 i18n 字典，§8-5）、`EVENT` 的命令文案、任何“脚本变量 / 占位符”系统。

---

## 2. 资源形态（方向）

### 2.1 目录与文件

```text
<游戏目录>/immersive_cinematics/resource/
├── intro/bgm.ogg            # 现有：音频（支持子路径）
├── overlay.png              # 现有：图片
└── lang/                    # 新增：文本资源
    ├── zh_cn.json
    └── en_us.json
```

- 一个语言一个文件，内容是**扁平字典** `key → 译文`——与 MC 自己的 lang 文件同构（`Language.loadFromJson` 读的就是扁平 `JsonObject`，§8-5）。
- 语言代码与 MC 一致（`zh_cn` / `en_us` / `ja_jp` …）：客户端当前语言代码直接来自 `LanguageManager.getSelected()`（§8-5），**零映射表**。
- 作者可只提供自己需要的语言；缺哪个语言走回退链（§3.2）。
- 作用域：**全局一份**，不按脚本分文件。理由：key 自带命名空间（如 `chapter1.boss.intro`），集中一份便于翻译者一次翻完、便于多脚本复用同一句文案，且脚本改名 / 搬家不动译文。
  - 备选（待定，§6-7）：按脚本分文件 `resource/lang/<脚本id>/<语言>.json`——便于按脚本整体拷贝，但跨脚本复用文案会重复。
    → **落地：不采用**，仍是全局一份 `resource/lang/<语言>.json`（跨脚本复用同一句文案是常态）。

### 2.2 引用语法（倾向 `@lang:<key>`）

脚本里**只写引用 key**，`text` 字段形如 `@lang:chapter1.boss.intro`。

**为什么必须是显式前缀**：

| 方案 | 问题 |
|---|---|
| 无前缀（`text` 命中字典就替换） | 字幕是任意文案，裸 key 与正常文本**必然歧义**（`intro` 既可能是 key 也可能是文案）——脚本里少写一个字符，字幕就凭空变成另一句话，且无任何报错 |
| `%key` / `$key` | 与 MC lang 的 `%s` / `%d` 占位符语义打架（`TranslatableContents` 的 `FORMAT_PATTERN`，§8-5） |
| `{key}` | 与文案里正常出现的大括号打架 |
| `text` 直接就是 key（全部走 lang 文件） | 破坏向后兼容：所有现存脚本的字幕会瞬间变成“key 未找到” |

**`@lang:` 的理由**：① 一个前缀让解析规则一句话说清——`text` 以 `@lang:` 开头才查表，否则原样渲染，**与普通字符串零歧义**；② 现有 `text` 没有任何前缀约定（§8-2），`@` 开头不与任何既有语义冲突；③ 与 `@` 家族（如命令里的 `@a`）视觉一致，作者一看就知道“这是引用不是文案”。

**待定**：以 `@lang:` 开头的**字面**文案怎么写（转义 `@@lang:`？）——见 §6-6。
→ **落地（已实现）**：`@@lang:<key>` 表示字面量 `@lang:<key>`（多写一个 `@`），在查表之前处理，见 §6-6。

### 2.3 key 命名

建议 `<脚本id>.<用途>`（如 `boss_fight.intro`），命名空间段沿用 `meta.id` 的字符约束（`^[a-zA-Z0-9_]{1,32}$`，§8-3）。具体规范执行时定；**多脚本共用一份字典，命名空间是唯一的防撞手段**。

---

## 3. 运行时解析（方向）

### 3.1 加载

按**客户端当前语言**加载对应文件：`Minecraft.getInstance().getLanguageManager().getSelected()` 取代码（§8-5）→ 读 `resource/lang/<code>.json`。

字幕是**每个客户端各自渲染**的（脚本 JSON 下发到客户端、覆盖层在客户端绘制），所以“按客户端当前语言”天然成立——同一场放映，中文客户端看中文、英文客户端看英文，**服务端不需要知道任何语言信息**。

### 3.2 回退链

```text
当前语言（如 zh_cn） → en_us → 原样显示引用串（@lang:<key>）
```

理由：这条链**就是 MC 自己的做法**，作者与玩家都熟悉——

- `LanguageManager.onResourceManagerReload` 构造的加载列表是 `[en_us, 当前语言]`（当前语言不是 en_us 且存在时追加），§8-5；
- `ClientLanguage.loadFrom` 按列表顺序读文件、**后读的覆盖先读的**（`map::put`）——于是 en_us 是兜底、当前语言是覆盖，§8-5；
- 单条 key 缺失时，`Language.getOrDefault(key)` 默认**返回 key 本身**，§8-5。

第三段“原样显示引用串”是刻意的：**不静默**——玩家看到 `@lang:boss.intro` 就知道作者漏了 key，而不是看到一片空白。

### 3.3 解析落点

`SubtitleLayer` 内部存的是 `String text`（`SubtitleLayer.java:24`），渲染时才 `Component.literal`（`:79`）——所以解析**输入是 String、输出还是 String**，落在 `OverlayTrackPlayer` 的 subtitle 分支（`OverlayTrackPlayer.java:147-152` 的 `setText(clip.getString("text",""))`）或 `SubtitleLayer.setText`（`:103-105`）都行。

**不引入 `Component.translatable` 体系**：那是 MC 资源包 + `ResourceManager` 的世界（assets 下的 lang 文件、随资源包重载），把作者内容塞进去会同时丢掉“本地目录读取 / 不走服务器 / 不随资源包分发”三条性质。两层的区分见 §8-5。

### 3.4 缓存与重载

**与图片 / 音频现状一致**：首次解析时读入并缓存（进程内静态 map），MC 的资源重载**不影响**我们——现状本来就没有注册任何 `ResourceManagerReloadListener`，`TextureLoader.clearCache()` 的注释虽写“在资源重载时调用”但**全工程无调用方**（§8-6）。

即：**改译文后重启客户端生效**。是否需要更快的路径（编辑器预览通道内单独重读）→ 待定（§6-4）。

### 3.5 多行

`text` 支持 `\n` 换行（`SubtitleLayer.java:55` 的 `text.split("\n", -1)`）；译文里同样允许 `\n`。解析发生在分行**之前**（key 查表先于分行）。

---

## 4. 范围（方向）

| 字段 | 是否进 i18n | 理由 |
|---|---|---|
| OVERLAY `subtitle` 的 `text` | **是（必备）** | 唯一的“文本内容”字段（`TrackSchemas.java:152`）；字幕就是要给人读的 |
| `meta.description` | **是（顺手）** | 同一个解析函数即可覆盖；它是给人读的说明（编辑器属性面板 / 架构图 tooltip 显示，§8-3） |
| `meta.name` | **否** | 它是脚本的可读标识，被脚本列表与架构图节点当作唯一可检索名（`ScriptGraphService.java:167`、`:325-327`）。翻译后同一脚本在不同语言下显示不同名字，**破坏可检索性**；作者想给多语言显示名，用 `description` |
| `meta.id` | **否** | 脚本 id，格式受限且被 `requires` 依赖网当作键（`ScriptParser.java:86-87`；`ScriptGraphService.java:22-26`）。id 必须全局稳定唯一 |
| `meta.author` | 否 | 人名，不翻译 |
| `EVENT` 的 `command` | 否（本期） | 命令要走服务端执行，属于另一条链路 |

---

## 5. 编辑器（方向）

- **key 输入**：`text` 仍是字符串字段（`FieldRenderer.vue:37` → `StringField` → `CommitTextField`），作者手写 `@lang:<key>`。这一步**零改动**。
- **资源浏览**：⚠️ 现状纠正——**目前编辑器没有任何资源浏览控件**。`path`（图片）字段也是纯文本输入：`FieldRenderer.vue` 只对 `REGISTRY_KEYS`（`item` / `entity` / `biome` / `sound` 等注册表键）走 `RegistryStringField` 下拉（`:22-29`、`:37`），`path` 不在其中；后端 `WebEditorApi` 只有 `script.*` / `registry.*` / `schema.get` / `script.graph` 这些命令（`:36-46`），**没有列举 `resource/` 目录的接口**（§8-7）。
  - 所以这一步是**新增**而不是复用现成控件：后端加一个列举 `resource/lang/*.json` 的 key（顺带可列 `resource/` 下的 png/gif/ogg，让 `path` 也受益），前端加一个 key 选择控件。
- **lang 文件编辑面板**：后置（可选）——key × 语言的矩阵视图，编辑译文。

---

## 6. 可能的问题 / 待定

> **0.3.6 已落地**：以下逐条标注实现结果，代码位置与验证见 §9。

1. **回退链细节**：是否要做语言族回退（`zh_cn` → 任意 `zh_*`）？**倾向不做**——MC 自己也不做（`ClientLanguage` 只按精确代码加载，§8-5），保持一致比“更聪明”重要。
   → **落地（已实现）**：只做精确匹配 + `en_us` 兜底，无语言族回退。
2. **语言代码命名**：与 MC 一致（`zh_cn` / `en_us`），零映射。文件名大小写：Windows 不敏感 / Linux 敏感，**倾向严格小写**（与 MC 资源包约定一致），否则同一份资源在两端表现不同。
   → **落地（已实现）**：语言代码按 `^[a-z0-9_]{2,16}$` 约束（严格小写），不合格一律按 `en_us` 处理（同时天然挡住 `../` 之类的路径穿越）；编辑器列举 `resource/lang/*.json` 时同样只认小写文件名。
3. **缺 key 提示**：运行时 debug 日志（与图片 / 音频“缺失只记日志、不阻塞播放”同款，§8-1）；**编辑器校验期**是否把缺 key 报为问题 → 待定（倾向警告不阻塞——资源目录本来就可以后补）。
   → **落地（已实现）**：运行时 = debug 日志 + **原样显示引用串**；编辑器 = 字幕字段下方的黄色提示（缺 key / 缺文件），**不进校验问题列表、不阻塞保存与播放**。
4. **编辑器预览的翻译切换**：预览器是否跟随编辑器 UI 语言 / 能否手动切语言预览 → 待定。
   → **未做（保留待定）**：预览走游戏内渲染路径（`editor.pushScript` → 客户端播放），因此按**客户端语言**解析；编辑器没有“手动切语言预览”的开关。将来要做需要给预览通道单独传语言。
5. **模板生成文本**：模板参数产出的字幕文案要不要直接产 key（而不是产文案）→ 待定，依赖 `templates.md` 的模板体系重建。
   → **未做（保留待定）**：模板仍产文案，不产 key。
6. **`@lang:` 转义**：以 `@lang:` 开头的字面文案的写法 → 待定。
   → **落地（已实现）**：`@@lang:<key>` → 字面量 `@lang:<key>`（多写一个 `@`），在查表之前处理，不需要客户端状态。
7. **key 命名规范与撞键检查**：多脚本共用一份字典 → 执行时定；可选做“编辑器校验期列出重复 key”。
   → **落地（部分实现）**：key 语法定死为“一段或多段 `[a-zA-Z0-9_]{1,32}` 用 `.` 连接、总长 ≤ 256”（`LangResources.KEY_PATTERN`），非法 key 按“未找到”处理（原样显示引用串）。**撞键检查不做**：字典是扁平 `key → 译文`，同一文件内重复 key 由 JSON 语义天然合并（取后值）；跨脚本复用同一 key 是设计意图（集中一份字典），不是冲突。
8. **占位符**：MC lang 支持 `%s` 参数替换（`TranslatableContents.decomposeTemplate`，§8-5），但字幕目前没有参数来源（脚本没有变量系统）→ **本期不做参数化**，译文里的 `%` 按字面处理。
   → **落地（已实现）**：不做参数化——解析不经过 `TranslatableContents`，译文里的 `%` 按字面渲染。

---

## 7. 落地顺序（方向）

1. **lang 资源加载 + `@lang:` 解析 + 回退链**：读 `resource/lang/<code>.json`、缓存、按 §3.2 回退；覆盖字幕 `text` 与 `meta.description`。交付物 = 一个“文本资源解析器”（底层工具，先底层后应用）。
   → ✅ **已实现**：`util/LangResources`（解析器）+ 两个落点（`OverlayTrackPlayer` 字幕分支、`ScriptGraphService` 的 `description` 读取处）。
2. **编辑器 key 输入 / 浏览**：后端列举接口 + 前端 key 选择控件（`path` 顺带受益）。
   → ✅ **已实现**：后端 `resource.list`（`ResourceFileService`）+ 前端 `ResourceStringField.vue`（`text` = key 选择，`path` = 文件选择）。
3. **（可选）lang 文件编辑面板**：key × 语言矩阵。
   → ❌ **未做（本期可选，后置）**：编辑器只读列举，不写语言文件。

**排期**：0.3.6 最后一期。

---

## 8. 现状调查（2026-10-07，逐条带证据）

MC 侧证据取自 1.20.1 loom 源码包
`.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-d95c7b3016/1.20.1-loom.mappings.1_20_1.layered+hash.2198-v2/minecraft-merged-d95c7b3016-1.20.1-loom.mappings.1_20_1.layered+hash.2198-v2-sources.jar`
（下文记作 `MC源:`），行号为包内行号。

### 8-1 资源目录与图片 / 音频引用

- 资源根目录 `<gameDir>/immersive_cinematics/resource/`：`ResourcePath.getBasePath()`（`util/ResourcePath.java:24-28`），常量 `RESOURCE_DIR = "resource"`（`:19`）；`resolve(fileName)` = 根目录直接 resolve（`:36-37`，**支持子路径**）；`exists`（`:43-45`）；`ensureDir`（`:50-56`）。
- 类注释明写三条性质：**“单机/联机/编辑器共用同一份，不走服务器流量、不做文件同步；资源缺失时由各加载点记录日志，不阻塞脚本播放”**（`ResourcePath.java:11-14`）。
- 引用语法 = **纯文件名（可含子路径），无前缀**：图片 `path` → `TextureLoader.loadTexture(path)`（`OverlayTrackPlayer.java:131-143`）；音频 `sound` + `source`（`TrackSchemas.java:103-104`），`source != "minecraft"` 时 `ResourcePath.resolve(fileName)`（`CinematicAudioInstance.java:231-238`）。
- 加载与缓存：`TextureLoader.loadTexture` 查 `textureCache` → `ResourcePath.resolve` → 不存在记 debug 日志返回 null（`TextureLoader.java:54-64`）；静态图 `NativeImage` + `DynamicTexture`（`:72-83`），GIF 走 `STBImage.stbi_load_gif_from_memory`、帧数 ≤256、单边 ≤1024（`:41-42`、`:89-158`）；三份静态缓存 `textureCache` / `sizeCache` / `gifCache`（`:35-39`）；以 `immersive_cinematics:<文件名>` 注册（`docs/modules/util.md:17`）。
- 文档口径：`docs/SCRIPT_FORMAT.md:39`（“音频/图片已有子路径支持”）、`:520`（图片 `path`）、`:448-449`（`sound` / `source`）；`docs/AI_SCRIPTING_GUIDE.md:201`（AUDIO）、`:221`（图片 `path`）。
- **英文命名约束**：音频构造时对非 ASCII 文件名告警——“音频文件名包含非 ASCII 字符: {} — Windows 下可能无法解码，建议使用英文命名”（`CinematicAudioInstance.java:71-73`）；AI 指南列为脚本常见坑第 13 条：“Windows 下 stb 解码走 ANSI 代码页，中文路径打不开。**资源文件统一英文命名**”（`docs/AI_SCRIPTING_GUIDE.md:512`）；`CHANGELOG.md:98`“音频资源统一为英文命名，避免部分系统下无法解码”。→ 图片侧读文件走 Java `Files.newInputStream`（`TextureLoader.java:73`）**没有**对应检查，只有文档告诫。

### 8-2 字幕轨现状

- 解析：`OverlayTrackPlayer.createLayer` 的 `subtitle` 分支 `sl.setText(clip.getString("text", ""))`——**原样透传，无任何引用 / 前缀语法**（`OverlayTrackPlayer.java:147-152`）。
- 字段定义：`clips.put("text", new FieldDef("string", ""))`（`TrackSchemas.java:152`）。
- 渲染：`SubtitleLayer` 存 `String text`（`SubtitleLayer.java:24`），`render` 里 `text.split("\n", -1)` 分行（`:55`）、逐行 `Component.literal` + `font.drawString`（`:79`）——**纯字面量，不走 `translatable`**。
- 既有坑（与 i18n 无关但同层）：`alpha < 4` 直接 return（MC `Font` 会把低 alpha 补成全不透明，`SubtitleLayer.java:44-52`）。

### 8-3 meta 字段与编辑器显示

- 字段定义与默认值：`id`(string,"untitled",required)、`name`(string,"Untitled",required)、`author`(string,"")、`version`(int,3,required)、`description`(string,"")（`script/schema/MetaSchemas.java:16-20`）。
- 解析：`id` / `name` / `author` 必填、`version` 必填、`description` 缺省 `""`（`ScriptParser.java:80-84`）；`id` 必须匹配 `^[a-zA-Z0-9_]{1,32}$`（`:86-87`）。
- 编辑器显示：
  - 属性面板用 schema 驱动动态表单渲染全部 meta 字段（`editor/src/components/PropertyPanel.vue:6,28`）；
  - 脚本列表**只显示相对路径**（`editor/src/components/ScriptList.vue:31-34`）；
  - 架构图节点把 `name` / `author` / `description` 打进节点（`webui/ScriptGraphService.java:167-169`），前端在节点与 tooltip 里显示（`editor/src/components/ScriptGraph.vue:399-405`），`name` 为空时回落路径（`ScriptGraphService.java:325-327`）。

### 8-4 多人服资源分发现状

- **脚本走网络**：服务端从 `<serverDir>/immersive_cinematics/scripts` 加载（`ScriptManager.java:32,45`），播放时 `S2CPlayScriptPacket` 携带**完整脚本 JSON** 下发（`S2CPlayScriptPacket.java:40-49`；`docs/modules/network.md`“S2CPlayScriptPacket（服务端→客户端，play_script）：携带脚本原始 JSON”）。
- **资源不走网络**：客户端一律从本地游戏目录读（`ResourcePath.java:11-14` 明写“不走服务器流量、不做文件同步”）。
- → i18n 资源放 `resource/lang/` 即**天然与图片 / 音频同待遇**：不新增包、不改协议、不新增同步逻辑。
- 附带事实（若将来 key 要走网络）：`FriendlyByteBuf.MAX_STRING_LENGTH = Short.MAX_VALUE`（32767），`readUtf()` 默认此上限、`readUtf(int)` 可指定，`getMaxEncodedUtfLength(i) = i*3`，组件序列化用 262144（`MC源: net/minecraft/network/FriendlyByteBuf.java:91-92,612-617,651`）。**当前设计不需要**——脚本 JSON 里已经是 key 字符串，随现有 play 包走即可。

### 8-5 客户端语言机制与两层翻译

- **取当前语言**：`Minecraft.getLanguageManager()`（`MC源: net/minecraft/client/Minecraft.java:2164-2165`）→ `LanguageManager.getSelected()` 返回语言代码（`MC源: …/language/LanguageManager.java:77-79`）；常量 `DEFAULT_LANGUAGE_CODE = "en_us"`（`:33`）。
- **MC 原生回退链**：`onResourceManagerReload` 组列表 `[en_us, 当前语言]`（`LanguageManager.java:63-65`）→ `ClientLanguage.loadFrom`（`:68`）→ 逐语言读 `lang/<code>.json`（`ClientLanguage.java:41`）、`Language.loadFromJson(inputStream, map::put)` **后读覆盖先读**（`:59`）→ 单条缺失时 `getOrDefault` 返回 key 本身（`Language.java:90-91`）；默认语言常量 `Language.DEFAULT = "en_us"`（`Language.java:34`）。
- **`Component.translatable` 机制**（模组 UI 翻译层）：`Component.translatable(key, args…)`（`MC源: net/minecraft/network/chat/Component.java:149-161`）→ `TranslatableContents.decompose()` 里 `Language.getInstance().getOrDefault(key)`，`%s` 占位符由 `FORMAT_PATTERN` 处理（`TranslatableContents.java:39,47,53,64`）。
- **模组 lang 文件**：`common/src/main/resources/assets/immersive_cinematics/lang/{zh_cn,en_us}.json`（各 37 行），扁平 `key → 译文`（`zh_cn.json:1-37`），key 形如 `key.immersive_cinematics.*` / `config.*` / `message.*` / `hud.*`；消费点 `Component.translatable(...)`（`client/ConfigScreen.java:18,28-36`；`control/CinematicKeyBindings.java:63`；`control/SkipHudRenderer.java:46-47,83-84`）与 `I18n.get(...)`（`webui/WebPreviewScreen.java:230-245`）。
- **两层区分（本文的立论基础）**：
  - **模组 UI 翻译** = assets 下的 lang 文件 + `ResourceManager` + 资源包 + 随资源重载刷新（`LanguageManager` 自己就是 reload 监听器：`MC源: Minecraft.java:475`）→ 归 MC 管；
  - **作者内容资源** = `<gameDir>/immersive_cinematics/resource/` + 本地文件读取 + 不进资源包 + 不参与 reload → 归本模组管。
  - 本文的 i18n 属于**第二层**，所以不能复用第一层的 `translatable` 通道（否则丢掉“本地目录 / 不走服务器 / 不随资源包”）。
- **编辑器自身的 i18n**：`editor/src/i18n/index.ts`（`zh_cn` / `en_us` 扁平字典、`%s` / `%d` 占位、缺 key 回退 en_us 再回退 key 本身、`switchLang`）——这是**编辑器 UI** 的翻译，与作者内容无关，也不与模组 lang 文件共用。

### 8-6 资源重载语义现状

- 图片 / 音频**不参与 MC 资源重载**：mod 未注册任何 `ResourceManagerReloadListener`（在 `common/src`、`fabric/src`、`forge/src` 下 grep `ReloadListener` / `onResourceManagerReload` **无命中**）。
- `TextureLoader.clearCache()` 的 javadoc 写“在资源重载时调用”（`TextureLoader.java:193`），但**全工程无调用方**（grep `clearCache` 仅命中定义处 `:194`）——即缓存生命周期 = 客户端进程（`:35-39` 三个静态 map）。
- `/icinematics reload` 只重载脚本：命令注册（`command/CinematicCommand.java:99-101`）、实现（`:213-227`，注释“reload = 重新加载持有的脚本（不做任何文件同步）”）。
- → 现状 = **加载一次、进程内缓存、无热重载**。§3.4 的“与现状一致”即指此。

### 8-7 编辑器前端既有控件

- 字段渲染分发：`FieldRenderer.vue` 按 `field.type` 选组件（`:35-49`）；`string` 时仅当 `fieldKey ∈ REGISTRY_KEYS`（`item` / `target` / `entity` / `biome` / `dimension` / `from_dimension` / `advancement` / `structure` / `look_at_target_structure` / `stage` / `sound`，`:22-25`）才用 `RegistryStringField` 下拉，否则 `StringField`（`:28-29,37`）。
- `StringField.vue` = `CommitTextField`（纯文本输入）。
- → `path`（图片）与 `text`（字幕）**都是纯文本输入，没有资源浏览控件**。
- 后端命令面：`WebEditorApi` 支持 `hello` / `script.list|load|save|delete|new|validate` / `registry.query|get` / `schema.get` / `script.graph`（`:36-46`）；`script.list` → `ScriptFileService.listScripts()`（`:86-90`），递归 walk `immersive_cinematics/scripts`（`ScriptFileService.java:19,24-34`）；`WebRegistryService` 只列注册表键（`WebRegistryService.java:79-84`）。**没有列举 `resource/` 的接口**。

### 8-8 模板生成文本（范围外，仅记录）

- 模板系统在 `script/template/`（`TemplateRegistry` / `TemplateScriptAssembler` / 各 `ClipTemplate`），`/icinematics template` 生成脚本到 `scripts/generated/`（`command/CinematicCommand.java:281-303`）。
- 模板产出的字幕文案同样是字面量 → 若要 i18n，模板参数应产出 key（§6-5，依赖 `templates.md`）。

---

### 未验证 / 保留

- **图片侧是否有非 ASCII 文件名检查**：**未发现**（`TextureLoader` 无对应检查，只有文档告诫）——这是 grep 结论，未逐一核对 stb / NativeImage 的内部行为。
- **编辑器预览链路对字幕的显示是否与游戏内渲染完全同路**：**未核实**（本文只依赖“字幕在客户端本地渲染”这一点，该点由 `SubtitleLayer` 处在 common 的客户端渲染路径直接成立）。
- **MC 版本差异**：本文 MC 侧证据全部取自 1.20.1 源码包；跨 MC 版本的 `LanguageManager` / `ClientLanguage` API 形态未核对（模组多版本支持是目标，执行时再查）。

---

## 9. 实现记录（0.3.6 落地）

### 9-1 代码清单

| 文件 | 变更 |
|------|------|
| `common/.../util/LangResources.java` | **新增**：文本资源解析器。`PREFIX="@lang:"` / `ESCAPED_PREFIX="@@lang:"` / `LANG_DIR="lang"` / `FALLBACK_LANGUAGE="en_us"`；`resolve(raw)`（客户端绑定：`LanguageManager.getSelected()` + `ResourcePath.getBasePath()/lang`）与 `resolve(raw, language, langDir)`（纯函数，自检/复用）；`readDictionary(Path)`（扁平字典读取，编辑器列举复用同一口径）；`isReference` / `clearCache`。缓存 = 静态 map，键 = `语言目录绝对路径|语言`，缺失/失败缓存空 map |
| `common/.../script/OverlayTrackPlayer.java` | 字幕分支 `sl.setText(...)` 前接 `LangResources.resolve(...)`（`text` 是 clip 级字段，层创建时解析一次） |
| `common/.../webui/ScriptGraphService.java` | 节点 `description` 接 `LangResources.resolve(...)` |
| `common/.../webui/ResourceFileService.java` | **新增**：`resource.list` 的列举实现。`list(kind, dir)`（客户端绑定）/ `list(Path root, kind, dir)`（纯函数）；`kind = lang/image/audio/all`；`resolveSafe` / `normalizeRelative` 路径安全 |
| `common/.../webui/WebEditorApi.java` | 新增命令 `resource.list` → `resource.list.result`（`{kind, dir, languages[], entries[]}` 或 `{kind, dir, files[]}`） |
| `editor/src/components/fields/ResourceStringField.vue` | **新增**：资源字段控件（datalist 候选 + 译文预览 + 缺失警告） |
| `editor/src/components/fields/resourceHint.ts` | **新增**：字段提示的纯逻辑（`langHint` / `fileHint` / `translationOf`），与 SFC 分离便于自检 |
| `editor/src/components/fields/FieldRenderer.vue` | `text` / `description` / `path` 三个 string 字段改走 `ResourceStringField` |
| `editor/src/store.ts` / `types.ts` | `resourceList(kind, dir)` + `ResourceListResult` / `ResourceLangEntry` 类型 |
| `editor/src/i18n/{zh_cn,en_us}.ts` | `resource.*` 文案 |
| `docs/SCRIPT_FORMAT.md` | §0 资源树加 `lang/`；§1a `description`、§9 `text` 标注支持引用；新增 §12（语法 / 回退链 / 适用范围 / 编辑器支持 / 示例） |
| `docs/AI_SCRIPTING_GUIDE.md` | OVERLAY 字段表补 `text` 行（`@lang:` 一句话说明） |
| `docs/modules/editor.md` | 命令表加 `resource.list`；新增 `ResourceFileService` 与前端资源控件条目；删除已过时的「本地服务无 token / Origin 校验」已知问题 |
| `docs/modules/util.md` | 新增「文本资源（脚本 i18n）」条目（`LangResources`） |
| `docs/modules/overlay.md` | 字幕层补「创建层时经 `LangResources.resolve` 解析」说明 |
| `CHANGELOG.md` | 0.3.6 新增：字幕/描述多语言 + 编辑器资源字段 |

### 9-2 与方向稿的偏差 / 补充

1. **`meta.description` 的解析落点** = `ScriptGraphService` 的节点读取处（唯一 Java 显示点），**不是** `ScriptMeta.getDescription()`。理由：该方法目前没有任何消费方，而 `ScriptParser` 在服务端也会跑——在 getter 里解析会把客户端语言依赖带进服务端路径（`ResourcePath` / `LanguageManager` 都是客户端状态）。
2. **编辑器列举接口比方向稿多一个可选 `dir`**（资源根下的子目录，缺省整棵树、`lang` 缺省 `lang/`）：方向稿只要求「列举 lang 或整棵树」，加 `dir` 是为了让 `path` 字段在子目录多时可用，并给路径安全校验一个真实入口（`../` 一律拒绝）。
3. **前端控件按字段 key 分派**（`text` / `description` → lang key，`path` → 图片文件），不是新的字段类型：schema（`TrackSchemas` / `MetaSchemas`）零改动，`FieldRenderer` 里多一张 `RESOURCE_KEYS` 表。`description` 一并纳入是因为它在 §4 范围表里就是“顺手支持”的字段，运行时能解析、编辑器也该能选 key。
4. **编辑器不做语言文件写入**（§7-3 的矩阵面板仍未做）：本期编辑器只读列举。
5. **`LangResources.resolve(String)` 在无客户端状态时原样返回**（`Minecraft.getInstance()` 为 null / 抛错，如无头自检）：不解析、不崩溃，游戏内始终有客户端状态。

### 9-3 验证（0.3.6 落地时）

- `sh gradlew compileJava`：通过（仅既有 deprecation 警告）。
- 无头自检（临时 harness，未进仓库；`common` 主源集 classpath + 回环 socket 伪造 WS 会话）：
  - 解析 / 回退链 / 转义 / 非法 key / 语言代码归一 / 缓存 / 脚本 JSON → `clip.text` → 解析 / `meta.name|id|author` 保持字面 —— 全绿；
  - `OverlayTrackPlayer` 字幕分支确实经过 `LangResources`（用 `@@lang:` 转义在无客户端状态下也能观察到处理结果）；
  - `resource.list` 的四种 kind、语言清单严格小写过滤、key 并集与各语言译文；
  - 路径安全：`../..` / `..` / `intro/../../..` / `..\..\windows` / `/etc` / `C:/Windows` / 未知 kind 全部拒绝，根内 `intro/../overlay.png` 允许；
  - `resource.list` 命令分发（非 `unknown type`、id 回显；无头环境下按预期落到错误帧，游戏内走 `ResourceFileService`）。
- 编辑器：`npx vite build` 通过（新组件进包），改动过的 `.ts` 通过 `tsc --noEmit`；临时 SSR 冒烟（未进仓库）渲染 `ResourceStringField` 的 text / description / path 三种字段，并断言 14 条提示逻辑（译文回落 en_us、缺 key / 缺文件警告、字典为空不误报、换行显示为 ⏎）——21 条全绿。
- **未覆盖**：游戏内实际渲染（需要启动客户端）；`ScriptGraphService` 的 description 解析在真实客户端语言下的取值（无头环境无 `LanguageManager`，仅验证了接线）。
