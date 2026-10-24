# 跳过投票与跳过 HUD

> 状态：实现中（投票与 HUD 已有实现；强停与广播仍用两套分母，HUD 仍为两行固定位置）
> 范围：多人播放的跳过投票统计与强停；客户端长按提示与票数显示。
> 关联：`docs/SCRIPT_FORMAT.md`、`docs/modules/control.md`、`docs/modules/network.md`、`docs/modules/trigger.md`

## 一、摘要

多人观看同一段过场时，每位观众都可以长按跳过键投出一票；服务端按本场观众总数与脚本配置的比例判断是否强制停止剩余观众，并把当前票数推给客户端。客户端在右下角画长按提示，在底部居中画票数。投票与 HUD 都已在代码中，但强停判定与广播判定仍使用不同分母，HUD 仍是两行固定位置，未达到设计口径。

## 二、为什么做

### 2.1 问题

- 两人观看、比例为全票、一人跳过时：强停判定认为还差一票（正确），广播判定却按“一人跳过 / 一人在看 = 100%”认为已经达标，于是不再发包；HUD 停在 0/2，剩下的人看不到已经有一票。
- 三人观看、默认全票、一人跳过时：广播路径抛空指针，结束回执链在该点中断，后面的收尾（如相机中心释放）不执行。
- 强停阈值没有“至少一票”的下限；总人数为零时会得到“零票即通过”。这条路径目前被“观众清空即移除账本”挡住，但公式本身不成立。
- 长按提示在右下、票数在底部居中，两者不在同一行；位置写死，不能按分辨率或界面缩放调整。
- 单人存档开启 LAN 发布后，房主客户端仍按“本地服”处理，不显示票数。

### 2.2 目标

- 同一场播放的强停与广播使用同一分母（在场观众 + 已跳过者）和同一阈值函数，并保证至少一票。
- 有人跳过且未达阈值时，剩余观众收到最新票数与总人数；达到阈值时强制停止剩余观众并清理账本。
- 投票路径不再出现空指针。
- 长按提示与票数排在同一水平线，各自按百分比中心坐标定位；调一个不动另一个。
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

多人播放时，每位观众长按跳过键，进度环走满即视为投出一票。服务端按本场总观众数计算所需票数：向上取整，且至少一票；达到阈值立即停止其余观众，未达到则把当前票数与总人数推给仍在观看的人。比例可由脚本覆盖，取值范围 10~100，缺省使用全局配置。

HUD 上，长按提示与票数排在同一水平线。长按提示默认贴右下，票数默认水平居中；两者各自用屏幕百分比坐标定位，坐标指元素中心，左上为 (0,0)、右下为 (100,100)。不做越界保护，坐标由使用者自己适配。单人存档未发布时只显示长按提示；真多人或已发布 LAN 才显示票数。总开关关闭时两者都不画。

## 四、技术细节

### 4.1 核心模型

- 播放账本：按脚本与播放实例各一条，持有在场观众、已跳过者、本场生效比例与事件时间线。
- 投票判定：单一“所需票数”函数，输入总观众数（在场 + 已跳过）与比例，输出至少为一的整数。
- 广播：状态变化时向剩余观众发包，包内携带脚本、票数与总人数。
- 客户端缓存：票数与总人数两个整数，收到包即全量覆盖，脚本停止或结束时清零。
- HUD：长按提示块（环形进度、键位图标、文案）与票数文本，各自独立定位。

### 4.2 契约

- 账本在首个观看者登记时创建，最后一名观看者移除时销毁；投票集合随账本销毁，不持久化、不跨会话。
- 跳过事件来自客户端的结束回执（完成原因为跳过）：服务端先移除观看者，再把该玩家加入已跳过集合。
- 强停由服务端发停止包；客户端停止对应实例后按正常结束回执上报，服务端再按实例精确移除账本。
- 客户端缓存只由服务端包覆盖，本地不推断；停止、结束与自然退出都清零。
- HUD 只读缓存与相机状态，不写任何状态。

### 4.3 数据形态

- 比例：脚本元数据 `skip_vote_ratio`（整数 10~100）；缺省时回落全局 `skipVoteRatio`（默认 100），越界或非法值在解析期报错。账本创建时解析一次。
- 集合：观众与已跳过者都按玩家 UUID 存储。
- 包：`scriptId`、`voterCount`、`totalViewers`；客户端缓存当前播放的票数与总人数。
- 配置：`showSkipHud`、`skipHoldThresholdMs`、`skipVoteRatio`，以及四个 HUD 百分比坐标键 `skipHudX` / `skipHudY` / `voteHudX` / `voteHudY`（0~100，缺省 95/90、50/90）。

### 4.4 边界

- 服务端权威；客户端只显示，不参与判定。
- 单人未发布不显示票数；真多人或已发布 LAN 才显示票数。
- 账本按实例隔离，同脚本多实例互不影响；每个实例独立统计票数与总人数。
- 票数在观众跳过或结束时推进；没有周期广播，无人跳过就没有更新。
- HUD 只在脚本播放模式、顶层实例可跳过、未显式隐藏跳过提示、总开关开启时绘制。
- 达到阈值即强停剩余观众，不再发普通更新包。

## 五、为什么不选别的

| 备选方案 | 做法 | 代价 | 为什么没选 |
|---|---|---|---|
| 沿用两套分母 | 强停按总人数、广播按在场人数 | HUD 停更或误判达标 | 同一信号两套口径 |
| 统一按在场人数 | 强停与广播都用在场观众 | 跳过者被排除，两人一票即 100% | 比例被放大 |
| 允许比例为 0 | 照抄原版睡觉规则 | 任意一人投票即可跳过，绕过作者意图 | 不允许 |
| HUD 用像素坐标 | 固定像素偏移 | 分辨率或界面缩放变化即错位 | 不可移植 |
| 坐标放脚本元数据 | 每个脚本一套坐标 | 冗余，作者容易误改，界面偏好不属于内容 | 只放全局 |
| 用原版动作栏显示票数 | 调动作栏消息 | 与隐藏动作栏冲突，无进度环，样式受限 | 自绘 |
| 独立投票面板 | 点击投票 | 交互与网络成本大 | 超出本轮 |

**本方案的缺点**：

- 票数只在有人跳过时更新，观众看不到周期性刷新。
- 百分比中心定位不做越界保护，极端坐标会被裁掉一半。
- 强停是逐个停止剩余观众，不是所有人同一帧结束。
- 比例下限 10 会挡住“任意一人即可跳过”的轻量场景。
- 依赖服务端发包，丢包时 HUD 可能滞后。

## 六、实现结果

状态：实现中。

投票统计、强停、客户端缓存与跳过 HUD 均已在代码中；统一阈值、下限保护、空指针修复、单行可配 HUD、LAN 发布判定尚未落地。

| 原设计 | 实际实现 | 偏离原因 |
|---|---|---|
| 新增统一的“所需票数”函数，强停与广播共用 | 未新增；强停按在场 + 已跳过计算，广播按在场人数算浮点比例 | 未实施 |
| 阈值至少为一票 | 没有下限；靠“观众清空即移除账本”规避总人数为零 | 未实施 |
| 观众清空不提前返回，走同一判定收尾 | 仍提前返回，但会先移除账本 | 账本清理已做，强停收尾未做 |
| 广播返回“是否变化”并据此节流 | 无返回值、无节流；只在未达阈值时发包 | 未实施 |
| 修复广播路径的空指针 | 字段仍声明未赋值，广播仍读它 | 未实施，风险仍在 |
| 非法比例在解析期报错 | 越界仅告警并置空，回落到全局配置 | 宽松解析是既有口径 |
| HUD 同一行、百分比中心坐标、四个全局键 | 长按右下、票数底部居中（固定偏移 30），无坐标键 | 未实施 |
| 显示条件为“非单人，或单人已发布 LAN” | 仅判断“是否本地服” | 未实施 |
| 总开关关闭时两者都不画 | 已实现 | 一致 |
| 只有比例可由脚本覆盖 | 已实现：脚本比例优先，缺省回落全局 | 一致 |

**两处口径的现状与风险**：强停用 `viewers + skipVoters` 作分母，广播用 `viewers` 作分母；两者在有人跳过且仍有观众时分母不同，阈值判定可能相反。当前可直接观察到的后果是 HUD 停更，以及广播路径的空指针：默认全票比例下，只要有人跳过且剩余观众不少于两人，就会走到空指针分支。修复前，多人服应避免依赖默认全票比例；修复方向见上表前五行。

## 附录：重写前原文

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

##### 3.3 HUD：一行居中 + 位置可配

现状两行分居（长按在右下、投票在底部居中）。目标：

```
             [◯ C] 长按跳过    2/3 已投票
                        ↑ 同一行、整组居中
```

- **同一行**：两者垂直位置相同（同一个 y）；水平方向各自定位——长按跳过在右下角，投票水平居中。
- **长按跳过**：缺省保持现有视觉位置——**右下角**（右边缘留 2px、底边留 4px，见 `SkipHudRenderer` 的 `MARGIN_RIGHT` / `MARGIN_BOTTOM`）。
- **投票**：与长按跳过**同一行**（同一个 y），**水平居中**（x = 50）。
- **两个元素各自独立定位**：长按跳过用 `skipHudX/Y`，投票用 `voteHudX/Y`，互不影响。
- **对齐点是各自的对称中心**（不是左上角——左上角没法表达「居中」）：填 `(50, 50)` 表示该元素中心落在屏幕正中。
- **位置可配**：百分比坐标，左上 `(0, 0)`、右下 `(100, 100)`，指的是**元素中心点**落在哪。
- **不做溢出保护**：作者填什么就摆到哪，超出屏幕由作者自己调（与「一切按比例、最后一步才映射像素」一致）。
- **缺省值**：长按跳过 `(95, 90)`、投票 `(50, 90)`——x 按右下角 / 水平居中定位，y = 90 落在物品栏上方、留出底部空间；对象宽度随键位名与票数文本变化，实现时按实测校准，作者可自行调整。
- **显示条件抄 MC**：`!isSingleplayer() || isPublished()`——真多人，或单人存档已开 LAN 发布。客户端等价判定 `!mc.isLocalServer() || (mc.getSingleplayerServer() != null && mc.getSingleplayerServer().isPublished())`；单人存档（未发布）时只画长按跳过。
- 长按进度环、图标、文字之间的相对位置不变，只是整块平移。

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
- **两个 HUD 位置都是纯全局**：玩家自己的界面偏好，与脚本内容无关。放进 meta 会让每个脚本各带一套 UI 坐标——冗余、会被作者无意改掉、也不是作者该管的事。
- 坐标语义：左上 `(0, 0)`、右下 `(100, 100)`，**指的是元素对称中心**；不做溢出保护，作者自己适配。
- `skipHudX/Y` 与 `voteHudX/Y` 相互独立：调一个不动另一个。
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
- [ ] 单人（未发布）：只画长按跳过，其中心落在 `skipHudX/Y`（缺省右下角）。
- [ ] 多人：投票水平居中、与长按跳过同一行（缺省 y 相同）。
- [ ] 只改 `skipHudX/Y`：长按跳过移动，投票不动。
- [ ] 只改 `voteHudX/Y`：投票移动，长按跳过不动。
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
