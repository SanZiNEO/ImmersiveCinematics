package com.example.immersive_cinematics.item;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import com.example.immersive_cinematics.gui.CinematicMovementScreen;
import com.example.immersive_cinematics.gui.DirectMovementScreen;
import com.example.immersive_cinematics.gui.SmoothMovementScreen;
import com.example.immersive_cinematics.gui.BezierMovementScreen;
import com.example.immersive_cinematics.gui.OrbitMovementScreen;
import com.example.immersive_cinematics.gui.PanMovementScreen;
import com.example.immersive_cinematics.gui.DollyMovementScreen;
import com.example.immersive_cinematics.gui.SpiralMovementScreen;
import com.example.immersive_cinematics.gui.StaticMovementScreen;
import com.example.immersive_cinematics.script.IMovementPath;
import com.example.immersive_cinematics.script.DirectLinearPath;
import com.example.immersive_cinematics.script.SmoothLinearPath;
import com.example.immersive_cinematics.script.BezierPath;
import com.example.immersive_cinematics.script.DollyZoomPath;
import com.example.immersive_cinematics.script.OrbitPath;
import com.example.immersive_cinematics.script.SpiralPath;
import com.example.immersive_cinematics.script.StationaryPanPath;
import com.example.immersive_cinematics.script.StaticPath;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.HashMap;
import java.util.List;

public class CinematicMovementItem extends Item {
    private static final Map<String, Class<? extends IMovementPath>> PATH_TYPE_MAP = new HashMap<>();
    
    static {
        PATH_TYPE_MAP.put("direct", DirectLinearPath.class);
        PATH_TYPE_MAP.put("smooth", SmoothLinearPath.class);
        PATH_TYPE_MAP.put("bezier", BezierPath.class);
        PATH_TYPE_MAP.put("dolly", DollyZoomPath.class);
        PATH_TYPE_MAP.put("orbit", OrbitPath.class);
        PATH_TYPE_MAP.put("spiral", SpiralPath.class);
        PATH_TYPE_MAP.put("pan", StationaryPanPath.class);
        PATH_TYPE_MAP.put("static", StaticPath.class);
    }
    
    private final String pathType;
    
    public CinematicMovementItem(Properties properties) {
        this(properties, "unknown");
    }
    
    public CinematicMovementItem(Properties properties, String pathType) {
        super(properties);
        this.pathType = pathType;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level world, Player player, InteractionHand hand) {
        if (world.isClientSide) {
            openSpecificScreen();
        }
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }
    
    @Override
    public void appendHoverText(ItemStack stack, Level world, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, world, tooltip, flag);
        
        // 从物品NBT加载路径数据
        IMovementPath path = loadPathFromItem(stack);
        if (path == null) {
            tooltip.add(Component.literal("§7未配置路径"));
            return;
        }
        
        // 根据路径类型显示信息
        String pathType = "未知";
        if (path instanceof DirectLinearPath) {
            pathType = "直线路径";
        } else if (path instanceof SmoothLinearPath) {
            pathType = "平滑路径";
        } else if (path instanceof BezierPath) {
            pathType = "贝塞尔曲线";
        } else if (path instanceof OrbitPath) {
            pathType = "轨道环绕";
        } else if (path instanceof StationaryPanPath) {
            pathType = "摇摄路径";
        } else if (path instanceof DollyZoomPath) {
            pathType = "推拉镜头";
        } else if (path instanceof SpiralPath) {
            pathType = "螺旋路径";
        } else if (path instanceof StaticPath) {
            pathType = "静态镜头";
        }
        
        tooltip.add(Component.literal("§a路径类型: §f" + pathType));
        tooltip.add(Component.literal("§a持续时间: §f" + String.format("%.1f", path.getDuration()) + " 秒"));
        
        // 显示起始和结束位置（如果可用）
        if (path instanceof DirectLinearPath) {
            DirectLinearPath directPath = (DirectLinearPath) path;
            Vec3 start = directPath.getStartPosition();
            Vec3 end = directPath.getEndPosition();
            tooltip.add(Component.literal("§a起始点: §f" + String.format("(%.1f, %.1f, %.1f)", start.x, start.y, start.z)));
            tooltip.add(Component.literal("§a结束点: §f" + String.format("(%.1f, %.1f, %.1f)", end.x, end.y, end.z)));
        }
        // 可以添加其他路径类型的显示
    }
    
    private void openSpecificScreen() {
        switch (pathType) {
            case "direct":
                net.minecraft.client.Minecraft.getInstance().setScreen(new DirectMovementScreen());
                break;
            case "smooth":
                net.minecraft.client.Minecraft.getInstance().setScreen(new SmoothMovementScreen());
                break;
            case "bezier":
                net.minecraft.client.Minecraft.getInstance().setScreen(new BezierMovementScreen());
                break;
            case "orbit":
                net.minecraft.client.Minecraft.getInstance().setScreen(new OrbitMovementScreen());
                break;
            case "pan":
                net.minecraft.client.Minecraft.getInstance().setScreen(new PanMovementScreen());
                break;
            case "dolly":
                net.minecraft.client.Minecraft.getInstance().setScreen(new DollyMovementScreen());
                break;
            case "spiral":
                net.minecraft.client.Minecraft.getInstance().setScreen(new SpiralMovementScreen());
                break;
            case "static":
                net.minecraft.client.Minecraft.getInstance().setScreen(new StaticMovementScreen());
                break;
            default:
                // 默认打开通用选择界面（保持向后兼容）
                CinematicMovementScreen.openScreen();
                break;
        }
    }
    
    // NBT数据存储方法
    public static void savePathToItem(ItemStack stack, IMovementPath path, String pathType) {
        CompoundTag nbt = stack.getOrCreateTag();
        nbt.putString("pathType", pathType);
        
        CompoundTag pathData = new CompoundTag();
        
        // 通用属性
        pathData.putDouble("duration", path.getDuration());
        
        // 根据路径类型保存特定属性
        if (path instanceof DirectLinearPath) {
            saveDirectLinearPath(pathData, (DirectLinearPath) path);
        } else if (path instanceof SmoothLinearPath) {
            saveSmoothLinearPath(pathData, (SmoothLinearPath) path);
        } else if (path instanceof BezierPath) {
            saveBezierPath(pathData, (BezierPath) path);
        } else if (path instanceof DollyZoomPath) {
            saveDollyZoomPath(pathData, (DollyZoomPath) path);
        } else if (path instanceof OrbitPath) {
            saveOrbitPath(pathData, (OrbitPath) path);
        } else if (path instanceof SpiralPath) {
            saveSpiralPath(pathData, (SpiralPath) path);
        } else if (path instanceof StationaryPanPath) {
            saveStationaryPanPath(pathData, (StationaryPanPath) path);
        } else if (path instanceof StaticPath) {
            saveStaticPath(pathData, (StaticPath) path);
        }
        
        nbt.put("pathData", pathData);
    }
    
    public static IMovementPath loadPathFromItem(ItemStack stack) {
        CompoundTag nbt = stack.getTag();
        if (nbt == null || !nbt.contains("pathType") || !nbt.contains("pathData")) {
            return null;
        }
        
        String pathType = nbt.getString("pathType");
        CompoundTag pathData = nbt.getCompound("pathData");
        
        Class<? extends IMovementPath> pathClass = PATH_TYPE_MAP.get(pathType);
        if (pathClass == null) {
            return null;
        }
        
        try {
            IMovementPath path = pathClass.getDeclaredConstructor().newInstance();
            
            // 加载通用属性
            if (pathData.contains("duration")) {
                // 使用反射设置持续时间
                try {
                    java.lang.reflect.Method setDurationMethod = pathClass.getMethod("setDuration", double.class);
                    setDurationMethod.invoke(path, pathData.getDouble("duration"));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            
            // 根据路径类型加载特定属性
            if (path instanceof DirectLinearPath) {
                loadDirectLinearPath(pathData, (DirectLinearPath) path);
            } else if (path instanceof SmoothLinearPath) {
                loadSmoothLinearPath(pathData, (SmoothLinearPath) path);
            } else if (path instanceof BezierPath) {
                loadBezierPath(pathData, (BezierPath) path);
            } else if (path instanceof DollyZoomPath) {
                loadDollyZoomPath(pathData, (DollyZoomPath) path);
            } else if (path instanceof OrbitPath) {
                loadOrbitPath(pathData, (OrbitPath) path);
            } else if (path instanceof SpiralPath) {
                loadSpiralPath(pathData, (SpiralPath) path);
            } else if (path instanceof StationaryPanPath) {
                loadStationaryPanPath(pathData, (StationaryPanPath) path);
            } else if (path instanceof StaticPath) {
                loadStaticPath(pathData, (StaticPath) path);
            }
            
            return path;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
    
    private static void saveDirectLinearPath(CompoundTag tag, DirectLinearPath path) {
        saveVec3(tag, "start", path.getStartPosition());
        saveVec3(tag, "end", path.getEndPosition());
        tag.putDouble("speed", path.getSpeed());
        tag.putDouble("distance", path.getDistance());
    }
    
    private static void loadDirectLinearPath(CompoundTag tag, DirectLinearPath path) {
        if (tag.contains("start")) {
            Vec3 start = loadVec3(tag, "start");
            path.setStartPoint(start);
        }
        if (tag.contains("end")) {
            Vec3 end = loadVec3(tag, "end");
            path.setEndPoint(end);
        }
        if (tag.contains("speed")) {
            path.setSpeed((float) tag.getDouble("speed"));
        }
        if (tag.contains("distance")) {
            path.setDistance((float) tag.getDouble("distance"));
        }
    }
    
    private static void saveSmoothLinearPath(CompoundTag tag, SmoothLinearPath path) {
        saveVec3(tag, "start", path.getStartPosition());
        saveVec3(tag, "end", path.getEndPosition());
            saveVec3(tag, "startRotation", path.getStartRotation());
            saveVec3(tag, "endRotation", path.getEndRotation());
        tag.putDouble("speed", path.getSpeed());
        tag.putDouble("distance", path.getDistance());
        tag.putDouble("smoothness", path.getSmoothness());
    }
    
    private static void loadSmoothLinearPath(CompoundTag tag, SmoothLinearPath path) {
        if (tag.contains("start")) {
            Vec3 start = loadVec3(tag, "start");
            path.setStartPoint(start);
        }
        if (tag.contains("end")) {
            Vec3 end = loadVec3(tag, "end");
            path.setEndPoint(end);
        }
        if (tag.contains("speed")) {
            path.setSpeed(tag.getDouble("speed"));
        }
        if (tag.contains("distance")) {
            path.setDistance((float) tag.getDouble("distance"));
        }
        if (tag.contains("smoothness")) {
            path.setSmoothness(tag.getDouble("smoothness"));
        }
        // 新增：加载旋转属性
        if (tag.contains("startRotation")) {
            Vec3 startRotation = loadVec3(tag, "startRotation");
            path.setStartRotation(startRotation);
        }
        if (tag.contains("endRotation")) {
            Vec3 endRotation = loadVec3(tag, "endRotation");
            path.setEndRotation(endRotation);
        }
    }
    
    private static void saveBezierPath(CompoundTag tag, BezierPath path) {
        saveVec3(tag, "start", path.getStartPosition());
        saveVec3(tag, "control", path.getControlPosition());
        saveVec3(tag, "end", path.getEndPosition());
        saveVec3(tag, "startRotation", path.getStartRotation());
        saveVec3(tag, "endRotation", path.getEndRotation());
        tag.putDouble("speed", path.getSpeed());
    }
    
    private static void loadBezierPath(CompoundTag tag, BezierPath path) {
        if (tag.contains("start")) {
            Vec3 start = loadVec3(tag, "start");
            path.setStart(start.x, start.y, start.z);
        }
        if (tag.contains("control")) {
            Vec3 control = loadVec3(tag, "control");
            path.setControl(control.x, control.y, control.z);
        }
        if (tag.contains("end")) {
            Vec3 end = loadVec3(tag, "end");
            path.setEnd(end.x, end.y, end.z);
        }
        if (tag.contains("speed")) {
            path.setSpeed((float) tag.getDouble("speed"));
        }
        // 新增：加载旋转属性
        if (tag.contains("startRotation")) {
            Vec3 startRotation = loadVec3(tag, "startRotation");
            path.setStartRotation(startRotation);
        }
        if (tag.contains("endRotation")) {
            Vec3 endRotation = loadVec3(tag, "endRotation");
            path.setEndRotation(endRotation);
        }
    }
    
    private static void saveDollyZoomPath(CompoundTag tag, DollyZoomPath path) {
        saveVec3(tag, "start", path.getStartPosition());
        saveVec3(tag, "end", path.getEndPosition());
        saveVec3(tag, "target", path.getTargetPosition());
        saveVec3(tag, "startRotation", path.getStartRotation());
        saveVec3(tag, "endRotation", path.getEndRotation());
        tag.putDouble("speed", path.getSpeed());
        tag.putDouble("distance", path.getDistance());
        tag.putFloat("fovStart", path.getFovStart());
        tag.putFloat("fovEnd", path.getFovEnd());
    }
    
    private static void loadDollyZoomPath(CompoundTag tag, DollyZoomPath path) {
        if (tag.contains("start")) {
            Vec3 start = loadVec3(tag, "start");
            path.setStart(start.x, start.y, start.z);
        }
        if (tag.contains("end")) {
            Vec3 end = loadVec3(tag, "end");
            path.setEnd(end.x, end.y, end.z);
        }
        if (tag.contains("target")) {
            Vec3 target = loadVec3(tag, "target");
            path.setTargetPosition(target);
        }
        if (tag.contains("speed")) {
            path.setSpeed((float) tag.getDouble("speed"));
        }
        if (tag.contains("distance")) {
            path.setDistance((float) tag.getDouble("distance"));
        }
        if (tag.contains("fovStart")) {
            path.setFovStart(tag.getFloat("fovStart"));
        }
        if (tag.contains("fovEnd")) {
            path.setFovEnd(tag.getFloat("fovEnd"));
        }
    }
    
    private static void saveOrbitPath(CompoundTag tag, OrbitPath path) {
        saveVec3(tag, "center", path.getCenterPosition());
        tag.putDouble("radius", path.getRadius());
        tag.putDouble("speed", path.getSpeed());
        tag.putDouble("height", path.getHeight());
        tag.putFloat("pitch", path.getPitch());
        tag.putFloat("yaw", path.getYaw());
    }
    
    private static void loadOrbitPath(CompoundTag tag, OrbitPath path) {
        if (tag.contains("center")) {
            Vec3 center = loadVec3(tag, "center");
            path.setCenterPosition(center);
        }
        if (tag.contains("radius")) {
            path.setRadius(tag.getDouble("radius"));
        }
        if (tag.contains("speed")) {
            path.setSpeed(tag.getDouble("speed"));
        }
        if (tag.contains("height")) {
            path.setHeight(tag.getDouble("height"));
        }
        if (tag.contains("pitch")) {
            path.setPitch(tag.getFloat("pitch"));
        }
        if (tag.contains("yaw")) {
            path.setYaw(tag.getFloat("yaw"));
        }
    }
    
    private static void saveSpiralPath(CompoundTag tag, SpiralPath path) {
        saveVec3(tag, "center", path.getCenterPosition());
        tag.putDouble("radius", path.getRadius());
        tag.putDouble("speed", path.getSpeed());
        tag.putDouble("height", path.getHeight());
        tag.putDouble("turns", path.getTurns());
    }
    
    private static void loadSpiralPath(CompoundTag tag, SpiralPath path) {
        if (tag.contains("center")) {
            Vec3 center = loadVec3(tag, "center");
            path.setCenterPosition(center);
        }
        if (tag.contains("radius")) {
            path.setRadius((float) tag.getDouble("radius"));
        }
        if (tag.contains("speed")) {
            path.setSpeed((float) tag.getDouble("speed"));
        }
        if (tag.contains("height")) {
            path.setHeight((float) tag.getDouble("height"));
        }
        if (tag.contains("turns")) {
            path.setTurns((float) tag.getDouble("turns"));
        }
    }
    
    private static void saveStationaryPanPath(CompoundTag tag, StationaryPanPath path) {
        saveVec3(tag, "center", path.getCenterPosition());
        tag.putDouble("panSpeed", path.getPanSpeed());
        tag.putDouble("tiltSpeed", path.getTiltSpeed());
        tag.putDouble("panDistance", path.getPanDistance());
        tag.putDouble("tiltDistance", path.getTiltDistance());
    }
    
    private static void loadStationaryPanPath(CompoundTag tag, StationaryPanPath path) {
        if (tag.contains("center")) {
            Vec3 center = loadVec3(tag, "center");
            path.setCenterPosition(center);
        }
        if (tag.contains("panSpeed")) {
            path.setPanSpeed(tag.getDouble("panSpeed"));
        }
        if (tag.contains("tiltSpeed")) {
            path.setTiltSpeed(tag.getDouble("tiltSpeed"));
        }
        if (tag.contains("panDistance")) {
            path.setPanDistance(tag.getDouble("panDistance"));
        }
        if (tag.contains("tiltDistance")) {
            path.setTiltDistance(tag.getDouble("tiltDistance"));
        }
    }
    
    private static void saveStaticPath(CompoundTag tag, StaticPath path) {
        saveVec3(tag, "position", path.getPosition());
        saveVec3(tag, "startRotation", path.getStartRotation());
        saveVec3(tag, "endRotation", path.getEndRotation());
    }
    
    private static void loadStaticPath(CompoundTag tag, StaticPath path) {
        if (tag.contains("position")) {
            Vec3 position = loadVec3(tag, "position");
            path.setPosition(position);
        }
        if (tag.contains("startRotation")) {
            Vec3 rotation = loadVec3(tag, "startRotation");
            path.setStartRotation(rotation);
        }
        if (tag.contains("endRotation")) {
            Vec3 rotation = loadVec3(tag, "endRotation");
            path.setEndRotation(rotation);
        }
    }
    
    private static void saveVec3(CompoundTag tag, String key, Vec3 vec) {
        CompoundTag vecTag = new CompoundTag();
        vecTag.putDouble("x", vec.x);
        vecTag.putDouble("y", vec.y);
        vecTag.putDouble("z", vec.z);
        tag.put(key, vecTag);
    }
    
    private static Vec3 loadVec3(CompoundTag tag, String key) {
        CompoundTag vecTag = tag.getCompound(key);
        double x = vecTag.getDouble("x");
        double y = vecTag.getDouble("y");
        double z = vecTag.getDouble("z");
        return new Vec3(x, y, z);
    }
}