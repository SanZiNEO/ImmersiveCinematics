# 跟踪镜头系统 (look_at + follow)

**版本**: 0.4.x  
**类型**: 新功能  
**依赖**: `camera-chunk-preload.md` — 外场目标需要区块已加载

---

## 设计

新增 clip 级 `tracking` 配置块，三属性独立可控：

```json
"tracking": {
  "look_at": "none",    // none | coordinate | entity | structure
  "follow": "none",     // none | entity | structure
  "look_target": {      // 仅 look_at 或 follow 使用
    "type": "coordinate",           // coordinate | entity | structure
    "coordinate": [100.0, 64.0, 200.0],
    "entity_uuid": "550e8400-...",
    "structure_id": "minecraft:end_city"
  }
}
```

| mode | 效果 |
|------|------|
| `look_at: none, follow: none` | 默认，走关键帧（当前行为） |
| `look_at` only | 位置走关键帧，朝向跟踪目标（相机飞过时始终对准目标） |
| `follow` only | 朝向走关键帧，位置跟随目标+关键帧 dx/dy/dz 偏移 |
| `look_at + follow` | 第三人称跟拍 |

### look_at 实现

`CameraTrackPlayer.writeAttributes()` 中，如果 `look_at != "none"`：

```java
Vec3 target = resolveTrackingTarget(tracking, level, entityManager);
Vec3 camPos = activePath.getPosition();

double dx = target.x - camPos.x;
double dy = target.y - camPos.y;
double dz = target.z - camPos.z;

float lookYaw   = (float) Math.toDegrees(Math.atan2(-dx, dz));
double horizDist = Math.sqrt(dx * dx + dz * dz);
float lookPitch = (float) -Math.toDegrees(Math.atan2(dy, horizDist));

// 替代关键帧插值的 yaw/pitch
yaw = lookYaw;
pitch = lookPitch;
```

不依赖关键帧的 yaw/pitch——`look_at` 覆盖原插值值。

### follow 实现

`CameraTrackPlayer` 中，如果 `follow != "none"`：

```java
Vec3 target = resolveTrackingTarget(tracking, level, entityManager);
CameraKeyframe from = ...; // still from keyframe interpolation

Vec3 resolvedPos = target.add(
    from.position.dx,
    from.position.dy,
    from.position.dz
);

activePath.setPosition(resolvedPos);
```

关键帧的 dx/dy/dz 从世界坐标变成"目标中心 → 相机"的相对偏移。

---

## 目标解析

`resolveTrackingTarget()` 在 `ScriptParser` 中执行，脚本加载时一次性解析：

```java
switch (lookTarget.type) {
    case "coordinate":
        return new Vec3(lookTarget.x, lookTarget.y, lookTarget.z);
    case "entity":
        Entity e = serverLevel.getEntity(lookTarget.entityUuid);
        if (e == null) throw new ScriptParseException("Entity not found: " + lookTarget.entityUuid);
        lookTarget.resolvedPos = e.position();  // 缓存，加载时解析
        return lookTarget.resolvedPos;
    case "structure":
        StructureStart start = serverLevel.structureManager().getStructureStartAt(
            lookTarget.structurePos, BuiltInRegistries.STRUCTURE_TYPE.get(ResourceLocation(lookTarget.structureId))
        );
        if (start == null || !start.isValid()) throw new ScriptParseException("Structure not found");
        BoundingBox box = start.getBoundingBox();
        lookTarget.resolvedPos = new Vec3(box.getCenter().getX(), box.getCenter().getY(), box.getCenter().getZ());
        return lookTarget.resolvedPos;
}
```

外场结构（`structure_id: "minecraft:end_city"`）依赖区块预加载——如果末地城半路跨 300 格，没在服务端加载则解析失败。预加载 chunx 阶段已覆盖这一点。

---

## 改动

| 文件 | 改动 |
|------|------|
| `script/CameraClip.java` | 新增 TrackingConfig(trackingType, targetConfig) |
| `script/ScriptParser.java` | 解析 `tracking` 块，resolveTrackingTarget() |
| `script/CameraTrackPlayer.java` | writeAttributes 中 look_at 覆盖 yaw/pitch；writePosition 中 follow 覆盖 position |
