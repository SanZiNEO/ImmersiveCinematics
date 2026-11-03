package com.immersivecinematics.immersive_cinematics.trigger.server.fact;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * 单条事实的 SNBT 编解码（v2 文件里的 {@code facts} 列表元素）。
 *
 * <p>只写非空字段：字符串维度为空不写、{@link FactResult#NONE} 不写、{@code scene} 为 null 不写；
 * {@code type} 与 {@code tick} 总是写。{@link #decode(CompoundTag)} 对未知类型返回 {@code null}，
 * 由调用方跳过（坏条目不应拖垮整份文件）。
 */
public final class FactCodec {

    private FactCodec() {}

    public static CompoundTag encode(Fact fact) {
        CompoundTag tag = new CompoundTag();
        tag.putString("type", fact.type().id());
        if (!fact.script().isEmpty()) {
            tag.putString("script", fact.script());
        }
        if (!fact.object().isEmpty()) {
            tag.putString("object", fact.object());
        }
        if (!fact.detail().isEmpty()) {
            tag.putString("detail", fact.detail());
        }
        if (!fact.instance().isEmpty()) {
            tag.putString("instance", fact.instance());
        }
        tag.putInt("tick", fact.tick());
        if (fact.result() != FactResult.NONE) {
            tag.putString("result", fact.result().id());
        }
        FactScene scene = fact.scene();
        if (scene != null) {
            CompoundTag sceneTag = new CompoundTag();
            if (!scene.dimension().isEmpty()) {
                sceneTag.putString("dimension", scene.dimension());
            }
            if (!scene.biome().isEmpty()) {
                sceneTag.putString("biome", scene.biome());
            }
            sceneTag.putDouble("x", scene.x());
            sceneTag.putDouble("y", scene.y());
            sceneTag.putDouble("z", scene.z());
            tag.put("scene", sceneTag);
        }
        return tag;
    }

    /** 解码一条事实；未知 {@code type} 返回 {@code null}。缺失字段按空串 / 0 / NONE 处理。 */
    public static Fact decode(CompoundTag tag) {
        FactType type = FactType.byId(tag.getString("type"));
        if (type == null) {
            return null;
        }
        FactScene scene = null;
        if (tag.contains("scene", Tag.TAG_COMPOUND)) {
            CompoundTag sceneTag = tag.getCompound("scene");
            scene = new FactScene(
                    sceneTag.getString("dimension"),
                    sceneTag.getString("biome"),
                    sceneTag.getDouble("x"),
                    sceneTag.getDouble("y"),
                    sceneTag.getDouble("z"));
        }
        return new Fact(
                type,
                tag.getString("script"),
                tag.getString("object"),
                tag.getString("detail"),
                tag.getString("instance"),
                tag.getInt("tick"),
                FactResult.byId(tag.getString("result")),
                scene);
    }
}
