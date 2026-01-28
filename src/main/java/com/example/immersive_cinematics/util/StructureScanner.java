package com.example.immersive_cinematics.util;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import net.minecraft.Util;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModList;
import org.apache.commons.lang3.text.WordUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 结构扫描工具类
 * 参考ExplorersCompass项目的StructureUtils
 * 提供按模组分类的结构扫描功能
 */
public class StructureScanner {

    /**
     * 获取所有结构并按模组分类
     */
    public static ListMultimap<String, ResourceLocation> getStructuresByMod(ServerLevel level) {
        ListMultimap<String, ResourceLocation> structuresByMod = ArrayListMultimap.create();
        for (ResourceLocation structureKey : getAllowedStructureKeys(level)) {
            String modid = structureKey.getNamespace();
            structuresByMod.put(getModDisplayName(modid), structureKey);
        }
        return structuresByMod;
    }

    /**
     * 获取所有允许的结构键
     */
    public static List<ResourceLocation> getAllowedStructureKeys(ServerLevel level) {
        final List<ResourceLocation> structures = new ArrayList<>();
        Registry<Structure> structureRegistry = getStructureRegistry(level);
        
        for (Structure structure : structureRegistry) {
            ResourceLocation key = structureRegistry.getKey(structure);
            if (structure != null && key != null && !isStructureBlacklisted(key)) {
                structures.add(key);
            }
        }
        
        return structures;
    }

    /**
     * 检查结构是否在黑名单中
     */
    private static boolean isStructureBlacklisted(ResourceLocation key) {
        // 可以根据需要添加黑名单逻辑
        return false;
    }

    /**
     * 获取结构的本地化名称
     */
    @OnlyIn(Dist.CLIENT)
    public static String getStructureDisplayName(ResourceLocation key) {
        String name = key.toString();
        String translatedName = I18n.get(Util.makeDescriptionId("structure", key));
        
        if (!translatedName.equals(Util.makeDescriptionId("structure", key))) {
            return translatedName;
        }
        
        // 如果没有翻译，使用默认格式
        name = key.getPath();
        return WordUtils.capitalize(name.replace('_', ' '));
    }

    /**
     * 获取模组的显示名称
     */
    @OnlyIn(Dist.CLIENT)
    public static String getModDisplayName(String modid) {
        if (modid.equals("minecraft")) {
            return "Minecraft";
        }
        
        Optional<? extends ModContainer> modContainer = ModList.get().getModContainerById(modid);
        if (modContainer.isPresent()) {
            return modContainer.get().getModInfo().getDisplayName();
        }
        
        return modid;
    }

    /**
     * 获取结构的模组来源
     */
    @OnlyIn(Dist.CLIENT)
    public static String getStructureSource(ResourceLocation key) {
        return getModDisplayName(key.getNamespace());
    }

    /**
     * 获取结构生成的维度
     */
    public static List<ResourceLocation> getGeneratingDimensions(ServerLevel serverLevel, Structure structure) {
        final List<ResourceLocation> dimensions = new ArrayList<>();
        for (ServerLevel level : serverLevel.getServer().getAllLevels()) {
            ChunkGenerator chunkGenerator = level.getChunkSource().getGenerator();
            var biomeSet = chunkGenerator.getBiomeSource().possibleBiomes();
            
            if (!structure.biomes().stream().noneMatch(biomeSet::contains)) {
                dimensions.add(level.dimension().location());
            }
        }
        
        // 修复要塞的维度问题（要塞在所有维度都可能生成）
        if (structure == StructureType.STRONGHOLD && dimensions.isEmpty()) {
            dimensions.add(ResourceLocation.parse("minecraft:overworld"));
        }
        
        return dimensions;
    }

    /**
     * 获取结构的类型
     */
    public static ResourceLocation getStructureType(ServerLevel level, Structure structure) {
        Registry<StructureSet> structureSetRegistry = getStructureSetRegistry(level);
        for (StructureSet set : structureSetRegistry) {
            for (var entry : set.structures()) {
                if (entry.structure().get().equals(structure)) {
                    return structureSetRegistry.getKey(set);
                }
            }
        }
        return ResourceLocation.fromNamespaceAndPath("immersive_cinematics", "none");
    }

    /**
     * 获取结构注册表
     */
    private static Registry<Structure> getStructureRegistry(ServerLevel level) {
        // 修复类型推断问题
        @SuppressWarnings("unchecked")
        Registry<Structure> registry = (Registry<Structure>) level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        return registry;
    }

    /**
     * 获取结构集注册表
     */
    private static Registry<StructureSet> getStructureSetRegistry(ServerLevel level) {
        // 修复类型推断问题
        @SuppressWarnings("unchecked")
        Registry<StructureSet> registry = (Registry<StructureSet>) level.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET);
        return registry;
    }

    /**
     * 根据搜索词过滤结构
     */
    @OnlyIn(Dist.CLIENT)
    public static List<ResourceLocation> searchStructures(List<ResourceLocation> allStructures, String searchTerm) {
        if (searchTerm.isEmpty()) {
            return new ArrayList<>(allStructures);
        }
        
        String lowerCaseSearch = searchTerm.toLowerCase();
        final String finalSearch = lowerCaseSearch;
        
        return allStructures.stream()
                .filter(key -> {
                    if (finalSearch.startsWith("@")) {
                        String modSearch = finalSearch.substring(1);
                        return getStructureSource(key).toLowerCase().contains(modSearch);
                    } else {
                        return getStructureDisplayName(key).toLowerCase().contains(finalSearch) ||
                               key.toString().toLowerCase().contains(finalSearch);
                    }
                })
                .collect(Collectors.toList());
    }

    /**
     * 获取结构信息字符串
     */
    @OnlyIn(Dist.CLIENT)
    public static String getStructureInfo(ResourceLocation key) {
        return String.format("结构: %s (%s)", getStructureDisplayName(key), getStructureSource(key));
    }
}