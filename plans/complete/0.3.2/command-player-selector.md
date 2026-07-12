# ⑦ 指令播放语法增强 — 逐行修复指南

**版本**: 0.3.2  
**类型**: Bug 修复 + 功能增强  
**执行方式**: 直接照做，无视解

---

## 背景

当前 `/icinematics play` 用了 `Minecraft.getInstance()` 纯客户端类，独立服务器崩溃。只对发送者播放，无法向其他玩家推送。

修复: 添加玩家选择器参数，服务端解析脚本后走 S2C 网络包分发。

---

## 步骤 1: CinematicCommand.java — 新增 import

文件: `src/main/java/com/immersivecinematics/immersive_cinematics/command/CinematicCommand.java`

在第 10 行 `import com.mojang.brigadier.arguments.StringArgumentType;` 之后加:

```java
import com.mojang.brigadier.arguments.BoolArgumentType;
```

在第 11 行后加:

```java
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;
import java.util.Collection;
import com.immersivecinematics.immersive_cinematics.trigger.network.S2CPlayScriptPacket;
import com.immersivecinematics.immersive_cinematics.trigger.network.S2CStopScriptPacket;
```

---

## 步骤 2: CinematicCommand.java — 注册玩家选择器参数

找到第 52-65 行的 `register()` 方法。

**2a)** 把 `play` 命令从当前:

```java
.then(Commands.literal("play")
        .then(Commands.argument("file", StringArgumentType.greedyString())
                .suggests(SCRIPT_SUGGESTIONS)
                .executes(CinematicCommand::playScript)))
```

改为:

```java
.then(Commands.literal("play")
        .then(Commands.argument("file", StringArgumentType.greedyString())
                .suggests(SCRIPT_SUGGESTIONS)
                .executes(CinematicCommand::playScript)
                .then(Commands.argument("players", EntityArgument.players())
                        .executes(CinematicCommand::playScript))))
```

**2b)** 把 `stop` 命令从当前:

```java
.then(Commands.literal("stop")
        .executes(CinematicCommand::stopScript))
```

改为:

```java
.then(Commands.literal("stop")
        .executes(CinematicCommand::stopScript)
        .then(Commands.argument("players", EntityArgument.players())
                .executes(CinematicCommand::stopScript)))
```

---

## 步骤 3: CinematicCommand.java — 重写 playScript()

找到第 68-126 行的 `playScript()` 方法。

**替换全部内容为**:

```java
private static int playScript(CommandContext<CommandSourceStack> context) {
    String filePath = StringArgumentType.getString(context, "file");
    CommandSourceStack source = context.getSource();
    MinecraftServer server = source.getServer();

    Path globalDir = server.getServerDirectory().toPath().toAbsolutePath().resolve(GLOBAL_SCRIPT_DIR);
    Path worldDir = server.getWorldPath(WORLD_SCRIPT_DIR);

    Path scriptPath = findScriptFile(filePath, globalDir, worldDir);
    if (scriptPath == null) {
        source.sendFailure(Component.literal("\u00a7c脚本文件不存在: " + filePath +
                "\n\u00a77搜索路径:" +
                "\n\u00a77  1. " + globalDir.resolve(filePath) +
                "\n\u00a77  2. " + globalDir.resolve(filePath + ".json") +
                "\n\u00a77  3. " + worldDir.resolve(filePath) +
                "\n\u00a77  4. " + worldDir.resolve(filePath + ".json") +
                "\n\u00a77请将 .json 脚本文件放入: " + globalDir));
        return 0;
    }

    String json;
    try {
        json = Files.readString(scriptPath);
    } catch (IOException e) {
        source.sendFailure(Component.literal("\u00a7c读取脚本文件失败: " + e.getMessage()));
        return 0;
    }

    CinematicScript script;
    try {
        script = ScriptParser.parse(json);
    } catch (ScriptParseException e) {
        source.sendFailure(Component.literal("\u00a7c脚本解析错误: " + e.getMessage()));
        return 0;
    }

    Collection<ServerPlayer> targets;
    try {
        targets = EntityArgument.getPlayers(context, "players");
    } catch (IllegalArgumentException e) {
        targets = server.getPlayerList().getPlayers();
    }

    for (ServerPlayer player : targets) {
        S2CPlayScriptPacket.send(player, json);
    }

    final int count = targets.size();
    source.sendSuccess(() -> Component.literal(
            "\u00a7a已向 " + count + " 名玩家推送脚本: \u00a7f" + script.getMeta().getName() +
            " \u00a77(总时长: " + String.format("%.1f", script.getTimeline().getTotalDuration()) + "s)"), false);
    return 1;
}
```

---

## 步骤 4: CinematicCommand.java — 重写 stopScript()

找到第 128-148 行的 `stopScript()` 方法。

**替换全部内容为**:

```java
private static int stopScript(CommandContext<CommandSourceStack> context) {
    CommandSourceStack source = context.getSource();
    MinecraftServer server = source.getServer();

    Collection<ServerPlayer> targets;
    try {
        targets = EntityArgument.getPlayers(context, "players");
    } catch (IllegalArgumentException e) {
        targets = server.getPlayerList().getPlayers();
    }

    for (ServerPlayer player : targets) {
        S2CStopScriptPacket.send(player, "");
    }

    final int count = targets.size();
    source.sendSuccess(() -> Component.literal(
            "\u00a7a已向 " + count + " 名玩家发送停止指令"), false);
    return 1;
}
```

---

## 步骤 5: CinematicCommand.java — check stopScript accessibility

找到第 59 行 `stopScript()` 的注册，确保不需要 `.requires()` 权限检查（玩家应该能自己 stop 自己的播放）。

当前已经是默认权限（任何人都能执行），不需要改。

---

## 完成检查清单

- [ ] `EntityArgument` import 已加
- [ ] play 命令注册加 `.then(Commands.argument("players", EntityArgument.players())...`
- [ ] stop 命令注册加 `.then(Commands.argument("players", EntityArgument.players())...`
- [ ] playScript() 方法完全替换，不使用 `Minecraft.getInstance()`
- [ ] stopScript() 方法完全替换，不使用 `Minecraft.getInstance()`
- [ ] 编译通过
- [ ] 测试单人: `/icinematics play test_script` 自己播放
- [ ] 测试: `/icinematics play test_script @a` 不报错
- [ ] 测试: Tab 补全 `@` 能弹出玩家选择器
- [ ] 未使用的 import 已删除（`CameraManager`/`ExitReason` 可能不再需要）
