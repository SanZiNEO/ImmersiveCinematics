# 异常处理规范：去 try/catch 吞异常（报错必须可见）

**版本**: 0.3.5
**类型**: 代码规范 / 可维护性
**状态**: 📋 规范已定，按阶段执行
**关联**: 本轮呼吸扰动 bug（异常被渲染链路 catch 藏住，作者无法定位）

---

## 一句话定义

我们是一个**游戏模组**，不是生产环境。作者要的是"出问题能顺着报错三分钟内定位"。
因此：**业务/播放/渲染逻辑一律不包 try/catch，有错就让异常直接抛出、可见可查**；
只剩极少数"缺失是合法结果"的边界场景允许捕获，且必须显式记录、注释原因。
**任何空体的 `catch { }` / `catch (ignored) {}` 一律清除。**

---

## 背景与现状（为什么要立这条规范）

全模组统计（153 个 Java 文件）：

| 指标 | 数量 |
|---|---|
| `try {` | 83 |
| `catch (` | 87 |
| └ `catch (Exception ...)` | 55 |
| └ **空体 `catch { }`（异常完全吞掉，不打印不记录）** | **7 处** |

**实际危害案例（呼吸扰动 bug）**：
- 呼吸计算在 `CameraTrackPlayer` 中抛异常，被 `ScriptPlayer.onRenderFrame` 的
  `catch (Exception e) { ErrorLog.log(...) }` 捕获——只写进
  `logs/immersive_cinematics/script-errors.log` 文件 + debug 级控制台。
- 作者**控制台看不到、没翻文件 = "表面无报错"**，只看到"相机钉住不动"，无从定位。
- 结论：层层捕获 + 低可见度日志 = 把问题变成玄学。必须反过来。

---

## 规范（硬性规则）

### 规则 1：禁止空体 catch
- 空体 `catch { }` / `catch (ignored) {}` **一处都不许再留**。
- 已有的 7 处逐一处理（见执行计划）。

### 规则 2：业务逻辑不包 try/catch
- 播放、渲染、相机、触发器求值、脚本解析等**功能路径一律不 try/catch**。
- 有错就让异常自然上抛，由**明确入口**暴露：
  - 客户端致命错 → 崩溃报告 + 控制台堆栈（作者一眼看到）
  - 非致命错 → 该入口打印 ERROR + 完整堆栈后继续（不许静默跳过）
- 禁止"一个方法包一层、层层往上吞"的写法。

### 规则 3：必须保留 catch 的白名单（仅这几类，且有硬约束）
| 场景 | 约束 |
|---|---|
| 资源清理（close/删除临时文件） | 打印 ERROR（带异常），不吞 |
| 可选探测读取（如读驱动版本、读文件成功与否无所谓） | 可最小化，但须注释"缺失是合法结果"，且能打就打印 |
| `InterruptedException`（线程 join/休眠） | 恢复中断位或打印，不吞 |
| 防御性解析（非法输入降级） | 必须打印 message；禁止空体 |
| 编辑器 UI 反射/可选组件 | 打印；禁止静默 layout 失败 |

### 规则 4：catch 必须带异常对象
- 任何 `catch` 都必须打印**异常本身（含 message + 堆栈）**，禁止只打自定义文案不带 `e`。
- 统一走 `ErrorLog`（含堆栈）+ 控制台 ERROR 双通道。

### 规则 5：渲染/播放链路异常可见化
- `ScriptPlayer.onRenderFrame` 等每帧驱动点：异常从 `catch` 改为 **控制台 ERROR（完整堆栈）**；
  能直接抛让上层暴露的，直接抛。
- 宁可"崩给你看"，不要"停了不说"。

---

## 参考：example/ 下成熟模组的写法

| 模组 | 文件数 | try | catch | 空体 catch | 主动 throw |
|---|---|---|---|---|---|
| sodium-1.20.1-stable | 393 | 59 | 51 | 5（均为可选探测/资源清理） | **140** |
| voxy-dev | 227 | 114 | 117 | 5（命令可选参数/反射探测） | **560** |

要点：
- 它们**业务逻辑处处 `throw`**（错误往上抛、可见），catch 只在**边界**（可选探测、资源清理、中断）出现。
- 它们也吞极个别读取，但那是"读不到本来就是预期之一"的地方，不是藏错。
- 我们的目标是：`throw/报错可见` 远多于 `catch`，而不是反过来。

---

## 执行计划（分阶段，每批 gradlew build 验证）

### 第 1 批：清掉 7 处空体 catch
| 文件:行 | 处理 |
|---|---|
| `command/CinematicCommand.java:50`（补全文件流） | 打印 ERROR 或删 catch |
| `editor/area/LeftPanelArea.java:566` | 打印 |
| `editor/trigger/StructureEditor.java:43` | 打印 |
| `editor/widget/UIFloatInput.java:123`（NumberFormat 输入降级） | 打印 message（合法场景注释） |
| `script/ScriptValidator.java:206 / 289` | 打印 |
| `trigger/server/store/TriggerStateStore.java:146`（删临时文件） | 保留安全清理 + 打印 |

### 第 2 批：播放/渲染链路可见化（本轮呼吸 bug 藏身处）
- `ScriptPlayer.onRenderFrame`：catch → 控制台 ERROR + 完整堆栈（或直接抛）
- `ScriptPlayer.replaceScript/cleanupTrackPlayers`：同上
- `CameraTrackPlayer`、`ScriptManager`、`CameraManager.pushScript`：能直接抛的直接抛，解析失败打印 ERROR

### 第 3 批：script / trigger / editor / command / util 各包 catch(Exception) 复查
- 逐个判定：删 / 直接抛 / 可见化 / 进白名单标注
- 目标：`catch(Exception)` 从 55 处降到个位数

### 第 4 批：收尾
- 全量统计复查（regex）：空体 catch = 0，catch 内不带 `e` 的 = 0
- `gradlew build` 全平台通过
- 更新本规范文档的统计数字

---

## 验收标准

1. 全模组空体 `catch { }` 数量 = **0**。
2. 任一 catch 都包含异常对象（message/堆栈），无"只打文案不带 e"。
3. 播放/渲染/相机/触发器功能路径无静默捕获；出错能看到**具体文件:行 + 堆栈**。
4. 修复本次呼吸 bug 后：开启呼吸若仍有问题，控制台直接给出异常与堆栈，作者可立即定位。

---

## 关联

- 呼吸扰动 v2：`script/BreathDisturbance.java`、`script/CameraTrackPlayer.java`
- 渲染链路：`script/ScriptPlayer.java`（onRenderFrame catch 藏异常点）
- 日志：`util/ErrorLog.java`（双通道：控制台 ERROR + 文件堆栈）
