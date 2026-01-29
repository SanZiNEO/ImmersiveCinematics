package com.example.immersive_cinematics.director;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.util.INBTSerializable;

/**
 * 事件触发绑定
 * 支持多种触发类型和规则配置
 */
public class TriggerBinding implements INBTSerializable<CompoundTag> {

    private String id;
    private String scriptName;
    private CameraScript.TriggerType triggerType;
    private int delay;

    public TriggerBinding() {
        this.id = "";
        this.scriptName = "";
        this.triggerType = CameraScript.TriggerType.DIMENSION_CHANGE;
        this.delay = 0;
    }

    public TriggerBinding(String id, String scriptName, CameraScript.TriggerType triggerType, int delay) {
        this.id = id;
        this.scriptName = scriptName;
        this.triggerType = triggerType;
        this.delay = delay;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getScriptName() {
        return scriptName;
    }

    public void setScriptName(String scriptName) {
        this.scriptName = scriptName;
    }

    public CameraScript.TriggerType getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(CameraScript.TriggerType triggerType) {
        this.triggerType = triggerType;
    }

    public int getDelay() {
        return delay;
    }

    public void setDelay(int delay) {
        this.delay = delay;
    }

    @Override
    public CompoundTag serializeNBT() {
        CompoundTag nbt = new CompoundTag();
        nbt.putString("id", id);
        nbt.putString("scriptName", scriptName);
        nbt.putString("triggerType", triggerType.name());
        nbt.putInt("delay", delay);
        return nbt;
    }

    @Override
    public void deserializeNBT(CompoundTag nbt) {
        this.id = nbt.getString("id");
        this.scriptName = nbt.getString("scriptName");
        String typeName = nbt.getString("triggerType");
        
        try {
            // 处理向后兼容性，将旧类型映射到新类型
            if (typeName.equals("DIMENSION_ENTRY") || typeName.equals("DIMENSION_EXIT")) {
                this.triggerType = CameraScript.TriggerType.DIMENSION_CHANGE;
            } else {
                this.triggerType = CameraScript.TriggerType.valueOf(typeName);
            }
        } catch (Exception e) {
            this.triggerType = CameraScript.TriggerType.DIMENSION_CHANGE;
        }
        
        this.delay = nbt.getInt("delay");
    }

    @Override
    public String toString() {
        return "TriggerBinding{" +
                "id='" + id + '\'' +
                ", scriptName='" + scriptName + '\'' +
                ", triggerType=" + triggerType +
                ", delay=" + delay +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        TriggerBinding that = (TriggerBinding) o;

        if (delay != that.delay) return false;
        if (!id.equals(that.id)) return false;
        if (!scriptName.equals(that.scriptName)) return false;
        return triggerType == that.triggerType;
    }

    @Override
    public int hashCode() {
        int result = id.hashCode();
        result = 31 * result + scriptName.hashCode();
        result = 31 * result + triggerType.hashCode();
        result = 31 * result + delay;
        return result;
    }
}