# 跳过投票与跳过 HUD

> 状态：已实现（H-2，2026-11-03；H-6 补齐客户端按播放实例分流票数（H2-R1）与多实例票数分行呈现（H2-R2），并改为「投票只计票、不退出播放」的新语义，2026-11-04）。
> 范围：多人播放的跳过投票统计与强停；客户端长按提示与票数显示。
> 关联：`docs/SCRIPT_FORMAT.md`、`docs/modules/control.md`、`docs/modules/network.md`、`docs/modules/trigger.md`

## 一、摘要

多人观看同一段过场时，每位观众都可以长按跳过键投出一票。**投票只计票，不结束投票者自己的播放**：投票者继续看，票数累计到本场比例要求后，服务端统一向这一批所有在场者发停止包。客户端画长按提示与票数：两个元素排在同一水平线，各自按元素中心的屏幕百分比定位，缺省长按块 `(95, 90)` 贴右下、票数 `(50, 90)` 水平居中。已投票者不再看到长按提示、也不再响应长按。强停判定与票数广播共用同一分母（在场人数）与同一阈值函数。

## 二、为什么做

### 2.1 问题

- 两人观看、比例为全票、一人跳过时：强停判定认为还差一票（正确），广播判定却按“一人跳过 / 一人在看 = 100%”认为已经达标，于是不再发包；HUD 停在 0/2，剩下的人看不到已经有一票。
- 三人观看、默认全票、一人跳过时：广播路径抛空指针，结束回执链在该点中断，后面的收尾（如相机中心释放）不执行。
- 强停阈值没有“至少一票”的下限；总人数为零时会得到“零票即通过”。这条路径目前被“观众清空即移除账本”挡住，但公式本身不成立。
- 长按提示在右下、票数在底部居中，两者不在同一行；位置写死，不能按分辨率或界面缩放调整。
- 单人存档开启 LAN 发布后，房主客户端仍按“本地服”处理，不显示票数。
- 旧语义里“长按 = 自己退出播放”：投票者先离开这一批，服务端再把他算进已跳过集合。分母因此必须靠“在场 + 已跳过”拼回来，任一路径漏加就会算错比例；投票者也看不到自己投完之后剩下的人还在看什么。

### 2.2 目标

- **投票只计票，不退出播放**：投票者仍留在观众集合里，继续看；票数达比例后由服务端统一停掉这一批的全部在场者。
- 同一场播放的强停与广播使用同一分母（在场人数）和同一阈值函数，并保证至少一票。
- 有人投票且未达阈值时，这一批的在场者收到最新票数与总人数；达到阈值时统一停止并清理账本。
- 已投票者不再显示长按提示、不再响应长按（每人每实例只投一票）。
- 投票路径不再出现空指针。
- 长按提示与票数排在同一水平线，各自用百分比中心坐标定位（共 4 个键）。
- 单人未发布只画长按提示；真多人或已发布 LAN 才画票数。
- 比例只接受 10~100；越界或非法值在解析期报错，不静默回落。
- 总开关关闭时两个元素都不画。

### 2.3 非目标

- 点击投票、投票倒计时等交互面板。
- 按玩家或队伍加权的投票。
- 投票历史与审计记录。
- 跳过 HUD 的主题、图标与字体。
- 允许比例为 0。
- 客户端判定；判定仍在服务端。

## 三、方案

多人播放时，每位观众长按跳过键，进度环走满即视为投出一票——**本机继续播放**，只有票数记账。服务端按本场在场人数计算所需票数：向上取整，且至少一票；达到阈值立即向这一批所有在场者发停止包，未达到则把当前票数与总人数推给在场者（包里带“你已投过票”的标记）。已投票者不再收到长按提示、也不再响应长按。比例可由脚本覆盖，取值范围 10~100，缺省使用全局配置。

HUD 上，长按提示与票数排在同一水平线，各自用屏幕百分比坐标定位，坐标指元素中心，左上为 (0,0)、右下为 (100,100)。缺省 `skipHud` 为 `(95, 90)` 贴右下、`voteHud` 为 `(50, 90)` 水平居中；两者互不影响。不做越界保护，坐标由使用者自己适配。单人存档未发布时不显示票数；真多人或已发布 LAN 才显示。总开关关闭时两个元素都不画。

## 四、技术细节

### 4.1 核心模型

- 播放账本：按脚本与播放实例各一条，持有在场观众、已投票者、本场生效比例与事件时间线。
- 投票判定：单一“所需票数”函数，输入在场人数与比例，输出至少为一的整数。
- 广播：每次投票向在场观众逐人发包，包内携带脚本、播放实例、票数、总人数与“你已投票”标记。
- 客户端缓存：按 `(scriptId, instanceId)` 一份不可变快照（票数 + 总人数 + 本人是否已投票），收到包即覆盖对应分片，脚本停止或结束只清该实例；本地另有一份“我刚投过”标记，覆盖投票到服务端回包之间的往返窗口。
- HUD：长按提示块（环形进度、键位图标、文案）与票数文本是两个元素，各自按元素中心定位，缺省落在同一水平线。

### 4.2 契约

- 账本在首个观看者登记时创建，最后一名观看者移除时销毁；投票集合随账本销毁，不持久化、不跨会话。
- 跳过投票来自客户端的长按：`C2SSkipVotePacket(scriptId, instanceId)` → 服务端只对“本实例在场观众”记账（`FactLedger` 幂等），投票者**不离开**观众集合。
- 达到阈值时由服务端向该批所有在场者发停止包；客户端停止对应实例后按正常结束回执上报，服务端再按实例精确移除账本。
- 结束回执仍可能带跳过原因（其它结束路径）；该路径只补记账与投票者标记，不做达标判定——强停判定只有投票路径一处。
- 客户端缓存只由服务端包覆盖，本地不推断；停止、结束按实例清零，世界退出清全部。
- HUD 只读缓存与相机状态，不写任何状态。

### 4.3 数据形态

- 比例：脚本元数据 `skip_vote_ratio`（整数 10~100）；缺省时回落全局 `skipVoteRatio`（默认 100），越界或非法值在解析期报错。账本创建时解析一次。
- 集合：观众与已投票者都按玩家 UUID 存储；投票者同时存在于两个集合里（观众用于分母与停包，投票者用于“已投票”判定）。
- 包：C2S `scriptId`、`instanceId`；S2C `scriptId`、`instanceId`、`voterCount`、`totalViewers`、`youVoted`（`write` / `read` 同序）。客户端以 `scriptId + "\u0000" + instanceId` 为键分片缓存，`instanceId` 为空串时与无实例 id 的来源同键。
- 配置：`showSkipHud`、`skipHoldThresholdMs`、`skipVoteRatio`，以及两个 HUD 元素的中心坐标键：`skipHudX` / `skipHudY`（0~100，缺省 95/90）与 `voteHudX` / `voteHudY`（0~100，缺省 50/90）。

### 4.4 边界

- 服务端权威；客户端只显示，不参与判定。
- 非本实例观众的投票直接丢弃：批次外的玩家不能决定别人的播放。
- 单人未发布不显示票数；真多人或已发布 LAN 才显示票数。
- 账本按实例隔离，同脚本多实例互不影响；每个实例独立统计票数与总人数。
- 票数只在有人投票或有人真正离开这一批时推进；没有周期广播。
- HUD 只在脚本播放模式、未显式隐藏跳过提示、总开关开启时绘制；长按块额外要求顶层实例可跳过且本人未投票。
- 达到阈值即统一停止，不再发普通更新包（停止包会覆盖同一帧的票数更新）。

## 五、为什么不选别的

| 备选方案 | 做法 | 代价 | 为什么没选 |
|---|---|---|---|
| 沿用两套分母 | 强停按总人数、广播按在场人数 | HUD 停更或误判达标 | 同一信号两套口径 |
| 投票者先退出播放，再算进已跳过集合 | 投票 = 本地退出；服务端用「在场 + 已跳过」把分母拼回来 | 任一路径漏加即算错比例；投票者自己也看不到投票后的进度 | 投票只计票、不退出（现行） |
| 允许比例为 0 | 照抄原版睡觉规则 | 任意一人投票即可跳过，绕过作者意图 | 不允许 |
| 广播按「数值没变」节流 | 与 `SleepStatus.update()` 同款，只发变化 | 包里带逐人的 `youVoted`，同一组数值对不同观众内容不同；按数值节流会让部分观众的已投票状态永远停在旧值 | 投票是低频事件，逐人直发更简单 |
| HUD 用像素坐标 | 固定像素偏移 | 分辨率或界面缩放变化即错位 | 不可移植 |
| 坐标放脚本元数据 | 每个脚本一套坐标 | 冗余，作者容易误改，界面偏好不属于内容 | 只放全局 |
| 用原版动作栏显示票数 | 调动作栏消息 | 与隐藏动作栏冲突，无进度环，样式受限 | 自绘 |
| 独立投票面板 | 点击投票 | 交互与网络成本大 | 超出本轮 |

**本方案的缺点**：

- 票数只在有人投票或有人真正离开这一批时更新，观众看不到周期性刷新。
- 百分比中心定位不做越界保护，极端坐标会被裁掉一半。
- 停止是逐个发包，不是所有人同一帧结束。
- 比例下限 10 会挡住“任意一人即可跳过”的轻量场景。
- 依赖服务端发包，丢包时 HUD 可能滞后（本地已投票标记只保证自己不再重复投票，不保证票数及时）。

## 六、实现结果

状态：已实现（H-2，2026-11-03；H-6 语义调整，2026-11-04）。

投票判定、解析期校验与跳过 HUD 布局均按设计落地。H-6 起语义改为「投票只计票、自己继续播放、达比例统一停」：投票者留在 `viewers` 里，分母因此就是 `viewers.size()`，不再需要「在场 + 已跳过」拼分母。`viewers` 清空时仍提前返回，但已先清账本；此时没有在场者可停、可通知，判定没有对象。

| 设计目标 | 实现 | 说明 |
|---|---|---|
| 新增统一的“所需票数”函数，强停与广播共用 | 已实现：`ScriptEventManager.neededVotes(totalViewers, ratio)` 是唯一实现；`ScriptPlayback.neededVotes()` 传 `viewers.size()` 调用 | 一致 |
| 阈值至少为一票 | 已实现：`Math.max(1, Mth.ceil(totalViewers * ratio / 100f))` | 对齐 `SleepStatus.sleepersNeeded` |
| 投票只计票，不退出播放 | 已实现（H-6）：新增 `C2SSkipVotePacket` → `ScriptEventManager.onSkipVote`，只记 `FactLedger` + `skipVoters`；客户端长按不再调 `requestExit` | 语义变更，覆盖 H-2 的旧口径 |
| 达比例统一停这一批全部在场者 | 已实现：`onSkipVote` 判定 `votes >= needed` 后对 `pb.viewers` 逐个发 `S2CStopScriptPacket` 并 `removePlayback` | 唯一强停判定点 |
| 结束回执不再做强停判定 | 已实现：`onPlayerFinished` 保留 SKIPPED → 记账 + `skipVoters.add`（幂等），删掉原「达标就强停」分支；日志分母用 `viewers.size() + 1` | 两条路径不再各判一次 |
| 观众清空不提前返回，走同一判定收尾 | `viewers` 为空仍提前返回，但先 `removePlayback` 清账本 | 收尾语义已达成，字面路径未改 |
| 广播带「你已投票」并逐人发送 | 已实现：`S2CSkipVoteUpdatePacket` 增加 `youVoted`，`broadcastSkipVote` 逐人按 `skipVoters.contains(vid)` 填；`lastBroadcastVotes` / `lastBroadcastTotal` 节流已删除 | 逐人内容不同，按数值节流会漏发状态变化 |
| 修复广播路径的空指针 | 已实现：`ScriptPlayback.server` 在 `addViewer` 构造时传入，广播与强停都从它取玩家表 | 字段不再悬空 |
| 非法比例在解析期报错 | 已实现：非整数或越界（含 0）抛 `ScriptParseException`；缺省或 JSON null 仍回落全局配置 | 不再静默回落 |
| HUD 同一行、百分比中心坐标、四个全局键 | 已实现：长按块与票数各按元素中心百分比定位，缺省 (95,90) / (50,90)；新增 skipHudX / skipHudY / voteHudX / voteHudY | 不做越界保护 |
| 显示条件为“非单人，或单人已发布 LAN” | 已实现：`shouldShowVotes` 先判非本地服，再取 `getSingleplayerServer()` 检查 `isPublished()` | 对齐 `announceSleepStatus` |
| 总开关关闭时两者都不画 | 已实现 | 一致 |
| 只有比例可由脚本覆盖 | 已实现：脚本比例优先，缺省回落全局；四个坐标键只在全局配置面 | 一致 |
| 已投票者不再显示提示、不再响应长按 | 已实现（H-6）：`CinematicKeyBindings` 长按分支先查 `ClientScriptReceiver.hasVoted`，`SkipHudRenderer` 长按块门控同一条件 | 本地标记 + 服务端 `youVoted` 双保险 |
| 客户端票数按播放实例隔离 | 已实现（H-6）：`S2CSkipVoteUpdatePacket` 增加 `instanceId`，`ClientScriptReceiver` 以 `scriptId + "\u0000" + instanceId` 为键存 `SkipVote` 快照；`resetSkipVote(scriptId, instanceId)` 只清该实例，无参重载清全部 | 同脚本多实例 / 多脚本并存互不覆盖 |

**口径已收敛到一处**：强停判定只存在于 `ScriptEventManager.onSkipVote`，阈值函数只有一个，分母恒为 `viewers.size()`（投票者不退出）。默认全票比例下「2 人 1 票」正常广播 `1/2`，第二票到达时统一停止，不再出现浮点比例与整数阈值两套算法。

### 6.1 多实例呈现（H-6 / H2-R2）

多实例同时可跳过时，票数不再只画顶层实例那一条，而是**每个可呈现的实例一行**（`SkipHudRenderer.renderVotes`）：

- 行条件：`!isEditorDriven()` && `player().isPlaying()` && `isSkippable()`，且该实例在缓存里有自己的快照（`ClientScriptReceiver.getSkipVote(scriptId, instanceId)` 非空且 `total > 0`）。
- 行文本：**单批**（只有一个可呈现实例）用 `hud.immersive_cinematics.skip_vote` = `"已投票跳过: %s/%s"`（`voters, total`），不重复脚本名；**多批**并存时每行用 `hud.immersive_cinematics.skip_vote_named` = `"%s: 已投票跳过: %s/%s"`（`name, voters, total`）。`name` 取 `instance.script().getMeta().getName()`，为 null 或空白时回落 `instance.scriptId()`——未命名脚本靠脚本 id 仍能辨认，同脚本多实例同名时靠行序与堆叠位置区分。
- 位置：顶层实例那一行的中心仍是 `round(sw * voteHudX / 100)`, `round(sh * voteHudY / 100)`，`drawString` 的 x 用 `vcx - voteW / 2`、y 用 `vcy - font.lineHeight / 2`——**单实例路径逐像素不变**；其余实例按 `CameraManager.INSTANCE.instances()` 顺序（顶层除外）向下堆叠，行距 `font.lineHeight + 2`，每行仍按自身文本宽度水平居中于 `voteHudX`。顶层实例没有票数时不占位，后续行从第一行起排。
- 门控：`Config.showSkipHud` + `hide_skip_hud` 仍是整块开关（长按块与全部票数行一起消失）；长按块只对顶层实例、顶层可跳过、且本人尚未投票时绘制（`ClientScriptReceiver.hasVoted`），票数行不受这些条件约束。

## 附录：重写前原文

> 以下为 H9 定稿前的原文存档，描述的是**当时**的旧语义（投票 = 自己退出播放、分母「在场 + 已跳过」）。
> 现行语义见正文：投票只计票、投票者继续播放、达比例由服务端统一停这一批全部在场者，分母为 `viewers.size()`。

### 跳过投票与跳过 HUD（H9）

> 现状：投票与 HUD **都有实现，但不完整**——判定口径两处不一致、缺下限保护、HUD 两行分居。
> 目标：投票判定对齐 MC 原版的「睡觉跳过夜晚」，HUD 合成一行、位置可配。

---

#### 1. 现状

##### 1.1 跳过投票（服务端）

`ScriptEventManager` 按实例维护 `skipVoters` 集合，比例来自脚本 `meta.skip_vote_ratio`（10~100），缺省回落全局 `Config.skipVoteRatio`（10~100，默认 100）。

```java
// ScriptEventManager.onScriptFinished
if (reason == CompletionReason.SKIPPED) {
    pb.skipVoters.add(uuid);
}
broadcastSkipVote(pb);
if (reason == CompletionReason.SKIPPED) {
    int total = pb.viewers.size() + pb.skipVoters.size();
    int needed = Mth.ceil(total * pb.skipVoteRatio / 100f);      // 强停判定
    if (pb.skipVoters.size() >= needed) {
        // 给剩余 viewers 发 S2CStopScriptPacket
    }
}

// ScriptEventManager.broadcastSkipVote
if (pb.viewers.isEmpty()) return;
float ratio = (float) pb.skipVoters.size() / pb.viewers.size() * 100f;   // 广播判定
boolean skip = ratio >= pb.skipVoteRatio;
if (!skip) {
    // 给 viewers 发 S2CSkipVoteUpdatePacket(voterCount, totalViewers)
}
```

客户端：`ClientScriptReceiver` 缓存票数，`SkipHudRenderer` 读缓存显示。

##### 1.2 跳过 HUD（客户端）

`SkipHudRenderer.renderInternal`（`control/SkipHudRenderer.java:40-89`）：

| 元素 | 位置 | 代码 |
|---|---|---|
| 长按跳过（环形进度 + 键位图标 + 文字） | **右下角** | `baseX = sw - MARGIN_RIGHT - totalWidth`、`baseY = sh - MARGIN_BOTTOM - totalHeight` |
| 投票票数（仅多人） | **底部居中**，与上面不同行 | `(sw - voteW) / 2, sh - 30`；`if (!mc.isLocalServer())` |

##### 1.3 已知缺陷

| # | 问题 | 后果 | 证据 |
|---|---|---|---|
| 1 | 两处分母不一致：强停用 `viewers + skipVoters`，广播用 `viewers` | 2 人、ratio=100、1 人跳过：强停判定 `needed=2` 不强停（对），但广播判定 `1/1=100% >= 100` → 判为「已够」→ **不发更新包**，HUD 停在 0/2，玩家看不到已有 1 票 | `ScriptEventManager:107` vs `:121` |
| 2 | 缺 `max(1, …)` | `total=0` 时 `needed=0`，`0 >= 0` 为真 → 误判「票够了」 | `:107` |
| 3 | 全员跳过直接 `return` | `viewers.isEmpty()` 时既不发包也不清账本；此时本该按 `skipVoters.size() >= needed` 收尾 | `:120` |
| 4 | 广播无节流 | 每次 finish 都发；MC 原版是「状态有变化才广播」 | `:119-131` |
| 5 | `pb.server` 空指针 | 字段声明了从不赋值，但 `broadcastSkipVote` 读它 → 多人服投票 NPE | `:336` / `:346-355` / `:126` |

---

#### 2. MC 原版参考：睡觉跳过夜晚

##### 2.1 `net.minecraft.server.players.SleepStatus`（1.20.1，反编译原文）

```java
public class SleepStatus {
   private int activePlayers;     // 非旁观玩家总数（每次 update 重算）
   private int sleepingPlayers;   // 其中睡着的

   public boolean areEnoughSleeping(int i) {
      return this.sleepingPlayers >= this.sleepersNeeded(i);
   }

   public boolean areEnoughDeepSleeping(int i, List<ServerPlayer> list) {
      int j = (int)list.stream().filter(Player::isSleepingLongEnough).count();
      return j >= this.sleepersNeeded(i);
   }

   public int sleepersNeeded(int i) {
      return Math.max(1, Mth.ceil(this.activePlayers * i / 100.0F));
   }

   public void removeAllSleepers() { this.sleepingPlayers = 0; }
   public int amountSleeping() { return this.sleepingPlayers; }

   public boolean update(List<ServerPlayer> list) {
      int i = this.activePlayers;
      int j = this.sleepingPlayers;
      this.activePlayers = 0;
      this.sleepingPlayers = 0;

      for (ServerPlayer serverPlayer : list) {
         if (!serverPlayer.isSpectator()) {
            this.activePlayers++;
            if (serverPlayer.isSleeping()) {
               this.sleepingPlayers++;
            }
         }
      }

      return (j > 0 || this.sleepingPlayers > 0) && (i != this.activePlayers || j != this.sleepingPlayers);
   }
}
```

##### 2.2 调用点

```java
// ServerLevel.canSleepThroughNights —— ① 阈值上限开关
public boolean canSleepThroughNights() {
   return this.getGameRules().getInt(GameRules.RULE_PLAYERS_SLEEPING_PERCENTAGE) <= 100;
}

// ServerLevel.tick —— 达到阈值就跳夜
int i = this.getGameRules().getInt(GameRules.RULE_PLAYERS_SLEEPING_PERCENTAGE);
if (this.sleepStatus.areEnoughSleeping(i) && this.sleepStatus.areEnoughDeepSleeping(i, this.players)) {
    if (this.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)) { ... }
}

// ServerLevel.announceSleepStatus —— 三条同时满足才广播
private void announceSleepStatus() {
   if (this.canSleepThroughNights()) {                                        // ① 阈值 <= 100
      if (!this.getServer().isSingleplayer() || this.getServer().isPublished()) {  // ② 非单人 或 已 LAN 发布
         int i = this.getGameRules().getInt(GameRules.RULE_PLAYERS_SLEEPING_PERCENTAGE);
         Component component = this.sleepStatus.areEnoughSleeping(i)
            ? Component.translatable("sleep.skipping_night")
            : Component.translatable("sleep.players_sleeping",
                  this.sleepStatus.amountSleeping(), this.sleepStatus.sleepersNeeded(i));
         for (ServerPlayer serverPlayer : this.players) {
            serverPlayer.displayClientMessage(component, true);               // ③ 动作栏（actionBar）
         }
      }
   }
}

// ServerLevel.updateSleepingPlayerList —— ④ 只在状态变化时广播
public void updateSleepingPlayerList() {
   if (!this.players.isEmpty() && this.sleepStatus.update(this.players)) {
      this.announceSleepStatus();
   }
}
```

**四个条件，缺一不可**：阈值 ≤ 100 / 非单人 或 已发布 / 动作栏显示 / 状态变化才广播。

##### 2.3 四个可抄的设计点

| 点 | MC 做法 | 映射到跳过投票 |
|---|---|---|
| **单一分母** | `activePlayers` 每次 `update` 重算（排除旁观） | 总观看者 = `viewers.size() + skipVoters.size()`，只在一处算 |
| **单一阈值函数** | `sleepersNeeded(pct) = max(1, ceil(active * pct / 100))` | 同样一个方法，强停与广播共用 |
| **判定/广播/执行同源** | `areEnoughSleeping` 只有一个实现，三处都调它 | 删掉广播里的浮点 `ratio` 算法 |
| **变化才广播** | `update()` 返回布尔，调用方据此节流 | `broadcastSkipVote` 返回「票数是否变化」 |

---

#### 3. 目标

##### 3.1 投票判定：对齐 MC

在 `ScriptPlayback` 上加一个方法，等价 `sleepersNeeded`：

```java
/** 本实例当前需要多少票才算通过（max(1, ceil(总观看者 × 比例 / 100))）。 */
int neededVotes() {
    int total = this.viewers.size() + this.skipVoters.size();
    return Math.max(1, Mth.ceil(total * this.skipVoteRatio / 100f));
}
```

改动：

1. `onScriptFinished` 的强停判定：`pb.skipVoters.size() >= pb.neededVotes()`
2. `broadcastSkipVote`：删掉 `float ratio = …` 那套，改成同一个 `pb.neededVotes()`；发包时 `totalViewers` 传 `viewers.size() + skipVoters.size()`（与阈值同分母）
3. `viewers.isEmpty()` 不再提前 `return`，走同一判定收尾（清账本）
4. `broadcastSkipVote` 返回 `boolean`（票数或总人数变了才发包），对齐 MC 的 `update()`
5. 顺手修 `pb.server` 空指针（改为从调用方传入 `MinecraftServer`，或删除该字段）

##### 3.2 比例下限：不允许 0

MC 的 gamerule 允许 `0`（等价「一人睡就跳夜」，因为 `max(1, …)` 兜底）。**本项目不允许 0**：

- 有些内容**不允许被跳过**（强制过场 / 关键剧情），比例为 0 等于「任意一人投票就跳过」，会绕过作者的意图。
- 因此 `skip_vote_ratio` / `Config.skipVoteRatio` 的范围保持 **10~100**（与现有校验一致），0~9 直接拒绝（解析期报错，而不是静默回落到 10）。
- `max(1, …)` 仍然保留——它防的是 `total = 0` 的除零/恒真，不是用来支持 0 比例。

##### 3.3 HUD：两个元素同一水平线 + 各自位置可配

现状两行分居（长按在右下、投票在底部居中）。目标：

```
        [◯ C] 长按 C 跳过动画            2/3 已投票
        ↑ 长按块中心 skipHud(95,90)      ↑ 票数中心 voteHud(50,90)
```

- **排在同一水平线**：长按提示与票数都用屏幕百分比坐标定位，缺省 y 都是 90，视觉上落在同一行。
- **各自定位**：长按块以 `skipHudX/skipHudY` 为元素中心（缺省 `(95, 90)` 贴右下），票数以 `voteHudX/voteHudY` 为元素中心（缺省 `(50, 90)` 水平居中）；坐标指元素中心，左上为 `(0, 0)`、右下为 `(100, 100)`。
- **两者互不影响**：长按块宽度随键位名变化，票数宽度随位数变化，各自按自己的中心回推左上角；改一个键不会带动另一个元素。
- **不做越界保护**：作者填什么就摆到哪，超出屏幕由作者自己调（与「一切按比例、最后一步才映射像素」一致）。
- **缺省值**：`(95, 90)` 与 `(50, 90)`——长按块贴右下、票数水平居中并落在物品栏上方，两者都留出底部空间。
- **显示条件抄 MC**：`!isSingleplayer() || isPublished()`——真多人，或单人存档已开 LAN 发布。客户端等价判定 `!mc.isLocalServer() || (mc.getSingleplayerServer() != null && mc.getSingleplayerServer().isPublished())`；单人存档（未发布）时只画 `长按 C 跳过动画`。
- 长按进度环、图标、文字之间的相对位置不变。

---

#### 4. 配置面

| 层级 | 字段 | 范围 | 缺省 |
|---|---|---|---|
| 全局 config | `skipVoteRatio` | 10~100（int） | 100 |
| 全局 config | `skipHudX` | 0~100（int，百分比） | 95 |
| 全局 config | `skipHudY` | 0~100（int，百分比） | 90 |
| 全局 config | `voteHudX` | 0~100（int，百分比） | 50 |
| 全局 config | `voteHudY` | 0~100（int，百分比） | 90 |
| 脚本 meta（**唯一可覆盖项**） | `skip_vote_ratio` | 10~100 | null → 回落全局 |

- **只有投票比例是脚本级**：它描述「这段内容允许多少比例的人跳过」，属脚本内容属性，作者可以按剧情严格程度调。
- **HUD 位置是纯全局**：玩家自己的界面偏好，与脚本内容无关。放进 meta 会让每个脚本各带一套 UI 坐标——冗余、会被作者无意改掉、也不是作者该管的事。
- 坐标语义：左上 `(0, 0)`、右下 `(100, 100)`，**指的是各元素自己的对称中心**；不做溢出保护，作者自己适配。
- 两个元素各自定位：长按块跟 `skipHudX/Y` 走，票数跟 `voteHudX/Y` 走，互不影响。
- `skip_vote_ratio` 缺失 = 用全局；写了非法值 = 解析期报错（不静默回落）。
- `showSkipHud` 总开关保留（关掉后两个元素都不画）。

---

#### 5. 验收

**投票**
- [ ] 2 人、ratio=100、1 人跳过：HUD 显示 `1/2`，不强停（现状会漏掉这个更新）。
- [ ] 2 人、ratio=100、2 人跳过：全部跳过，账本清理，无 NPE。
- [ ] 3 人、ratio=50、2 人跳过：`needed = ceil(3×0.5) = 2` → 强停剩余 1 人。
- [ ] 3 人、ratio=10、1 人跳过：`needed = max(1, ceil(0.3)) = 1` → 强停（下限保护生效）。
- [ ] `total = 0`（观看者全退）：不误判、不抛异常。
- [ ] `skip_vote_ratio = 0` / `5`：解析期报错，不是静默回落。
- [ ] 多人服投票不再 NPE（`pb.server` 修复后）。

**HUD**
- [ ] 单人（未发布）：只画 `长按 C 跳过动画`，长按块中心落在 `skipHudX/Y`（缺省 `(95, 90)`），票数不画。
- [ ] 多人：同一行显示 `长按 C 跳过动画` 与 `2/3 已投票`，前者中心在 `skipHudX/Y`、后者中心在 `voteHudX/Y`。
- [ ] 改 `skipHudX/Y`：只有长按块移动，票数不动；改 `voteHudX/Y`：只有票数移动。
- [ ] 填 `(50, 50)`：该元素中心落在屏幕正中（验证中心对齐语义）。
- [ ] 填 `(0, 0)`：该元素中心贴屏幕左上角（元素一半在屏幕外，不崩）。
- [ ] 填 `(100, 100)`：该元素中心贴屏幕右下角（元素一半在屏幕外，不崩）。
- [ ] 窗口从 1280×720 到 2560×1440：坐标相对位置一致（百分比而非像素）。
- [ ] `showSkipHud = false`：两个元素都不绘制。

---

#### 6. 不在本轮

- 投票 UI 的交互（点击投票、投票倒计时）——本版本投票由玩家主动跳过触发，不做独立面板。
- 投票权重（按玩家 / 按队伍加权）——与 MC 原版一致，一人一票。
- 投票历史与审计记录（归 H7 事实层的消费面，不在本条）。
- 跳过 HUD 的样式主题（图标/字体）——只改布局与位置。
