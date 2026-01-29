package com.example.immersive_cinematics.trigger;

import com.example.immersive_cinematics.ImmersiveCinematics;
import com.example.immersive_cinematics.director.CameraScript;
import com.example.immersive_cinematics.director.CameraScript.TriggerType;
import com.example.immersive_cinematics.director.CameraScriptStorage;
import com.example.immersive_cinematics.director.TriggerBinding;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Mod.EventBusSubscriber(
    modid = ImmersiveCinematics.MODID,
    bus = Mod.EventBusSubscriber.Bus.FORGE,  // 必须显式指定 Forge 总线
    value = {Dist.DEDICATED_SERVER, Dist.CLIENT}  // 服务器和客户端都需要
)
public class WorldEventDetector {

    private static final Logger LOGGER = LogManager.getLogger();
    private static WorldEventDetector instance;

    private static final int STRUCTURE_SCAN_INTERVAL = 40;
    private final Map<UUID, List<ScheduledTask>> scheduledTasks;
    private final Map<UUID, PlayerData> playerDataMap;

    private static class PlayerData {
        public ResourceLocation lastDimension;
        public Set<ResourceLocation> visitedDimensions;
        public ResourceLocation currentStructure;
        public int scanTimer;
        public long lastTriggerTimestamp;
        public long lastTimeOfDay;

        public PlayerData() {
            this.visitedDimensions = new HashSet<>();
            this.scanTimer = 0;
            this.lastTriggerTimestamp = 0;
            this.lastTimeOfDay = 0;
        }
    }

    private static class ScheduledTask {
        public final String scriptName;
        public final int delay;
        public int remainingTicks;

        public ScheduledTask(String scriptName, int delay) {
            this.scriptName = scriptName;
            this.delay = delay;
            this.remainingTicks = delay;
        }
    }

    private WorldEventDetector() {
        this.scheduledTasks = new HashMap<>();
        this.playerDataMap = new HashMap<>();
    }

    public static WorldEventDetector getInstance() {
        if (instance == null) {
            instance = new WorldEventDetector();
        }
        return instance;
    }

    @SubscribeEvent
    public void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer)) {
            return;
        }

        ServerPlayer player = (ServerPlayer) event.getEntity();
        ResourceKey<Level> oldDimension = event.getFrom();
        ResourceKey<Level> newDimension = event.getTo();

        LOGGER.info("Player {} changed dimension from {} to {}",
                player.getName().getString(),
                oldDimension.location(),
                newDimension.location());

        PlayerData playerData = initializePlayerData(player);
        playerData.lastDimension = newDimension.location();

        // 扫描所有已加载的脚本，匹配维度参数
        Map<String, CameraScript> allScripts = CameraScriptStorage.getInstance().getCameraScripts();
        LOGGER.debug("Found {} camera scripts loaded", allScripts.size());
        
        for (Map.Entry<String, CameraScript> entry : allScripts.entrySet()) {
            String scriptName = entry.getKey();
            CameraScript script = entry.getValue();
            
            LOGGER.debug("Checking script: {} with {} shot rules", scriptName, script.getShotRules().size());
            
            for (CameraScript.ShotRule shotRule : script.getShotRules()) {
                LOGGER.debug("Checking shot rule: {} with {} trigger conditions", 
                        shotRule.getRuleName(), shotRule.getTriggerConditions().size());
                
                for (CameraScript.TriggerCondition condition : shotRule.getTriggerConditions()) {
                    LOGGER.debug("Checking trigger condition: type={}, parameter={}, value={}",
                            condition.getTriggerType(), condition.getParameter(), condition.getValue());
                    
                    if (condition.getTriggerType() == TriggerType.DIMENSION_CHANGE &&
                            matchesPattern(condition.getParameter(), newDimension.location().toString())) {
                        LOGGER.info("Triggering camera script {} (rule {}) for player {} changing dimension to {}",
                                scriptName, shotRule.getRuleName(),
                                player.getName().getString(),
                                newDimension.location());

                        // 将秒转换为刻（1秒 = 20刻）
                        int delayTicks = (int) (shotRule.getDelay() * 20);
                        scheduleScriptTrigger(player, scriptName, delayTicks);
                    }
                }
            }
        }
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer)) {
            return;
        }

        ServerPlayer player = (ServerPlayer) event.getEntity();
        ResourceLocation dimension = player.level().dimension().location();

        LOGGER.info("Player {} joined dimension {}",
                player.getName().getString(),
                dimension);

        PlayerData playerData = initializePlayerData(player);
        playerData.lastDimension = dimension;
        playerData.visitedDimensions.add(dimension);

        // 触发玩家登录事件
        List<TriggerBinding> bindings = CameraScriptStorage.getInstance().getBindings();
        for (TriggerBinding binding : bindings) {
            if (binding.getTriggerType() == TriggerType.PLAYER_LOGIN) {
                LOGGER.info("Triggering camera script {} for player {} logging in",
                        binding.getScriptName(),
                        player.getName().getString());

                scheduleScriptTrigger(player, binding.getScriptName(), binding.getDelay());
            }
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        processScheduledTasks();

        for (Map.Entry<UUID, PlayerData> entry : playerDataMap.entrySet()) {
            UUID playerId = entry.getKey();
            PlayerData playerData = entry.getValue();
            
            playerData.scanTimer++;
            if (playerData.scanTimer >= STRUCTURE_SCAN_INTERVAL) {
                Optional<ServerPlayer> optionalPlayer = getServerPlayer(playerId);
                optionalPlayer.ifPresent(player -> {
                    scanForStructures(player);
                    checkTimeOfDay(player);
                });
                playerData.scanTimer = 0;
            }
        }
    }

    private void scanForStructures(ServerPlayer player) {
        PlayerData playerData = playerDataMap.get(player.getUUID());
        if (playerData == null) {
            playerData = initializePlayerData(player);
        }

        BlockPos playerPos = player.blockPosition();
        Level level = player.level();

        Optional<ResourceLocation> foundStructure = findStructureAtPosition(level, playerPos);

        if (foundStructure.isPresent()) {
            ResourceLocation structureId = foundStructure.get();
            if (playerData.currentStructure == null || 
                !playerData.currentStructure.equals(structureId)) {
                triggerStructureEntry(player, structureId);
                playerData.currentStructure = structureId;
            }
        } else {
            if (playerData.currentStructure != null) {
                triggerStructureExit(player, playerData.currentStructure);
                playerData.currentStructure = null;
            }
        }
    }

    private void checkTimeOfDay(ServerPlayer player) {
        PlayerData playerData = playerDataMap.get(player.getUUID());
        if (playerData == null) {
            return;
        }

        long currentTime = player.level().getDayTime() % 24000;
        if (Math.abs(currentTime - playerData.lastTimeOfDay) > 1000) {
            playerData.lastTimeOfDay = currentTime;
            
            List<TriggerBinding> bindings = CameraScriptStorage.getInstance().getBindings();
            for (TriggerBinding binding : bindings) {
            if (binding.getTriggerType() == TriggerType.TIME_OF_DAY) {
                    String timePattern = binding.getId();
                    if (isTimeMatch(timePattern, currentTime)) {
                        LOGGER.info("Triggering camera script {} for player {} at time {}",
                                binding.getScriptName(),
                                player.getName().getString(),
                                currentTime);

                        scheduleScriptTrigger(player, binding.getScriptName(), binding.getDelay());
                    }
                }
            }
        }
    }

    private boolean isTimeMatch(String timePattern, long currentTime) {
        // 支持多种时间匹配模式："0-1000" 表示范围，"6000" 表示精确时间，"18000-0" 表示跨越午夜的范围
        if (timePattern.contains("-")) {
            String[] parts = timePattern.split("-");
            if (parts.length == 2) {
                try {
                    long start = Long.parseLong(parts[0].trim());
                    long end = Long.parseLong(parts[1].trim());

                    if (start <= end) {
                        return currentTime >= start && currentTime <= end;
                    } else {
                        return currentTime >= start || currentTime <= end;
                    }
                } catch (NumberFormatException e) {
                    LOGGER.error("Invalid time range pattern: {}", timePattern);
                }
            }
        } else {
            try {
                long targetTime = Long.parseLong(timePattern.trim());
                // 允许500刻的误差范围
                return Math.abs(currentTime - targetTime) <= 250;
            } catch (NumberFormatException e) {
                LOGGER.error("Invalid time pattern: {}", timePattern);
            }
        }
        return false;
    }

    private Optional<ResourceLocation> findStructureAtPosition(Level level, BlockPos pos) {
        try {
            if (level instanceof net.minecraft.server.level.ServerLevel) {
                net.minecraft.server.level.ServerLevel serverLevel = (net.minecraft.server.level.ServerLevel) level;
                
                net.minecraft.core.Registry<net.minecraft.world.level.levelgen.structure.Structure> structureRegistry = 
                        level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
                
                for (net.minecraft.resources.ResourceLocation structureId : structureRegistry.keySet()) {
                    net.minecraft.world.level.levelgen.structure.Structure structure = structureRegistry.get(structureId);
                    if (structure != null) {
                        net.minecraft.world.level.levelgen.structure.StructureStart structureStart = 
                                serverLevel.structureManager().getStructureWithPieceAt(pos, structure);
                        
                        if (structureStart != null && structureStart.isValid()) {
                            return Optional.ofNullable(structureRegistry.getKey(structure));
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error finding structure at position {}", pos, e);
        }
        
        return Optional.empty();
    }

    private void triggerStructureEntry(ServerPlayer player, ResourceLocation structureId) {
        List<TriggerBinding> bindings = CameraScriptStorage.getInstance().getBindings();
        for (TriggerBinding binding : bindings) {
            if (binding.getTriggerType() == TriggerType.STRUCTURE_ENTER &&
                    matchesPattern(binding.getId(), structureId.toString())) {
                LOGGER.info("Triggering camera script {} for player {} entering structure {}",
                        binding.getScriptName(),
                        player.getName().getString(),
                        structureId);

                com.example.immersive_cinematics.network.NetworkHandler.sendToPlayer(
                        new com.example.immersive_cinematics.network.TriggerCameraScriptPacket(binding.getScriptName()),
                        player);
            }
        }
    }

    private void triggerStructureExit(ServerPlayer player, ResourceLocation structureId) {
        List<TriggerBinding> bindings = CameraScriptStorage.getInstance().getBindings();
        for (TriggerBinding binding : bindings) {
            if (binding.getTriggerType() == TriggerType.STRUCTURE_EXIT &&
                    matchesPattern(binding.getId(), structureId.toString())) {
                LOGGER.info("Triggering camera script {} for player {} exiting structure {}",
                        binding.getScriptName(),
                        player.getName().getString(),
                        structureId);

                com.example.immersive_cinematics.network.NetworkHandler.sendToPlayer(
                        new com.example.immersive_cinematics.network.TriggerCameraScriptPacket(binding.getScriptName()),
                        player);
            }
        }
    }

    private boolean isFirstTimeInDimension(ServerPlayer player, ResourceLocation dimension) {
        CompoundTag persistentData = player.getPersistentData();
        String dimensionKey = "ic_visited_" + dimension.toString().replace(':', '_');

        if (persistentData.contains(dimensionKey, Tag.TAG_BYTE)) {
            LOGGER.debug("Player {} has already visited dimension {}",
                    player.getName().getString(), dimension);
            return false;
        } else {
            persistentData.putBoolean(dimensionKey, true);
            LOGGER.debug("Player {} visiting dimension {} for the first time",
                    player.getName().getString(), dimension);
            return true;
        }
    }

    private PlayerData initializePlayerData(Player player) {
        UUID playerId = player.getUUID();
        if (!playerDataMap.containsKey(playerId)) {
            playerDataMap.put(playerId, new PlayerData());
        }
        return playerDataMap.get(playerId);
    }

    private Optional<ServerPlayer> getServerPlayer(UUID playerId) {
        try {
            net.minecraft.server.MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
                    Player player = level.getPlayerByUUID(playerId);
                    if (player instanceof ServerPlayer) {
                        return Optional.of((ServerPlayer) player);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to get server instance", e);
        }
        return Optional.empty();
    }

    private void scheduleScriptTrigger(ServerPlayer player, String scriptName, int delay) {
        if (delay <= 0) {
            com.example.immersive_cinematics.network.NetworkHandler.sendToPlayer(
                    new com.example.immersive_cinematics.network.TriggerCameraScriptPacket(scriptName),
                    player);
        } else {
            UUID playerId = player.getUUID();
            if (!scheduledTasks.containsKey(playerId)) {
                scheduledTasks.put(playerId, new ArrayList<>());
            }
            scheduledTasks.get(playerId).add(new ScheduledTask(scriptName, delay));
            LOGGER.info("Scheduled camera script {} for player {} to run in {} ticks",
                    scriptName, player.getName().getString(), delay);
        }
    }

    private void processScheduledTasks() {
        Iterator<Map.Entry<UUID, List<ScheduledTask>>> iterator = scheduledTasks.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, List<ScheduledTask>> entry = iterator.next();
            UUID playerId = entry.getKey();
            List<ScheduledTask> tasks = entry.getValue();

            Iterator<ScheduledTask> taskIterator = tasks.iterator();
            while (taskIterator.hasNext()) {
                ScheduledTask task = taskIterator.next();
                task.remainingTicks--;

                if (task.remainingTicks <= 0) {
                    Optional<ServerPlayer> optionalPlayer = getServerPlayer(playerId);
                    optionalPlayer.ifPresent(player -> {
                        LOGGER.info("Executing scheduled camera script {} for player {}",
                                task.scriptName, player.getName().getString());
                        com.example.immersive_cinematics.network.NetworkHandler.sendToPlayer(
                                new com.example.immersive_cinematics.network.TriggerCameraScriptPacket(task.scriptName),
                                player);
                    });
                    taskIterator.remove();
                }
            }

            if (tasks.isEmpty()) {
                iterator.remove();
            }
        }
    }

    private boolean matchesPattern(String pattern, String target) {
        if (pattern == null || target == null) {
            return false;
        }

        String regexPattern = escapeRegExp(pattern)
                .replace("*", ".*")
                .replace("?", ".");

        try {
            Pattern compiledPattern = Pattern.compile(regexPattern, Pattern.CASE_INSENSITIVE);
            return compiledPattern.matcher(target).matches();
        } catch (PatternSyntaxException e) {
            LOGGER.error("Invalid pattern: {}", pattern, e);
            return false;
        }
    }

    private String escapeRegExp(String str) {
        return str.replaceAll("([\\\\\\[\\]\\{\\}\\(\\)\\*\\+\\?\\.\\^\\$\\|])", "\\\\$1");
    }

    public Vec3 getStructureCenter(Level level, BlockPos pos) {
        return Vec3.atCenterOf(pos);
    }

    public BoundingBox getCurrentStructureBounds(ServerPlayer player) {
        PlayerData playerData = playerDataMap.get(player.getUUID());
        if (playerData == null || playerData.currentStructure == null) {
            return null;
        }

        BlockPos playerPos = player.blockPosition();
        return new BoundingBox(
                playerPos.getX() - 10, playerPos.getY() - 10, playerPos.getZ() - 10,
                playerPos.getX() + 10, playerPos.getY() + 10, playerPos.getZ() + 10
        );
    }

    public boolean isPlayerInStructure(ServerPlayer player, String structurePattern) {
        PlayerData playerData = playerDataMap.get(player.getUUID());
        if (playerData == null || playerData.currentStructure == null) {
            return false;
        }

        return matchesPattern(structurePattern, playerData.currentStructure.toString());
    }

    public List<ResourceLocation> getPlayerStructures(ServerPlayer player) {
        List<ResourceLocation> structures = new ArrayList<>();
        PlayerData playerData = playerDataMap.get(player.getUUID());
        if (playerData != null && playerData.currentStructure != null) {
            structures.add(playerData.currentStructure);
        }
        return structures;
    }
}