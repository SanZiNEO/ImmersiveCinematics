package com.example.immersive_cinematics.handler;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.world.phys.Vec3;

public class CommandHandler {

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        // 设置摄像机参数指令
        dispatcher.register(Commands.literal("ic-run")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("set")
                        .then(Commands.literal("position")
                                .then(Commands.argument("pos", Vec3Argument.vec3())
                                        .executes(ctx -> executeSetPosition(ctx))
                                )
                        )
                        .then(Commands.literal("rotation")
                                .then(Commands.argument("yaw", DoubleArgumentType.doubleArg(-180, 180))
                                        .then(Commands.argument("pitch", DoubleArgumentType.doubleArg(-90, 90))
                                                .executes(ctx -> executeSetRotation(ctx))
                                        )
                                )
                        )
                        .then(Commands.literal("fov")
                                .then(Commands.argument("fov", DoubleArgumentType.doubleArg(5, 120))
                                        .executes(ctx -> executeSetFOV(ctx))
                                )
                        )
                        .then(Commands.literal("reset")
                                .executes(ctx -> executeResetSettings(ctx))
                        )
                )
        );

        // 直接朝向目标的直线运动指令
        dispatcher.register(Commands.literal("ic-run")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("direct")
                        .then(Commands.argument("start", Vec3Argument.vec3())
                                .then(Commands.argument("end", Vec3Argument.vec3())
                                        .then(Commands.argument("duration", DoubleArgumentType.doubleArg(0.1))
                                                .executes(ctx -> executeDirectLinearMovement(ctx))
                                                .then(Commands.literal("fov")
                                                        .then(Commands.argument("startFov", DoubleArgumentType.doubleArg(5, 120))
                                                                .then(Commands.argument("endFov", DoubleArgumentType.doubleArg(5, 120))
                                                                        .executes(ctx -> executeDirectLinearMovementWithFOV(ctx))
                                                                )
                                                        )
                                                )
                                                .then(Commands.literal("heading")
                                                        .then(Commands.argument("offset", DoubleArgumentType.doubleArg(-180, 180))
                                                                .executes(ctx -> executeDirectLinearMovementWithHeading(ctx))
                                                        )
                                                )
                                        )
                                )
                        )
                )
        );

        // 平滑曲线运动指令（带视角过渡）
        dispatcher.register(Commands.literal("ic-run")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("smooth")
                        .then(Commands.argument("start", Vec3Argument.vec3())
                                .then(Commands.argument("end", Vec3Argument.vec3())
                                        .then(Commands.argument("duration", DoubleArgumentType.doubleArg(0.1))
                                                .executes(ctx -> executeSmoothLinearMovement(ctx))
                                                .then(Commands.literal("fov")
                                                        .then(Commands.argument("startFov", DoubleArgumentType.doubleArg(5, 120))
                                                                .then(Commands.argument("endFov", DoubleArgumentType.doubleArg(5, 120))
                                                                        .executes(ctx -> executeSmoothLinearMovementWithFOV(ctx))
                                                                )
                                                        )
                                                )
                                                .then(Commands.literal("heading")
                                                        .then(Commands.argument("offset", DoubleArgumentType.doubleArg(-180, 180))
                                                                .executes(ctx -> executeSmoothLinearMovementWithHeading(ctx))
                                                        )
                                                )
                                        )
                                )
                        )
                )
        );

        // 环绕运动指令
        dispatcher.register(Commands.literal("ic-run")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("orbit")
                        .then(Commands.argument("center", Vec3Argument.vec3())
                                .then(Commands.argument("radius", DoubleArgumentType.doubleArg(0.1))
                                        .then(Commands.argument("speed", DoubleArgumentType.doubleArg(0.1))
                                                .then(Commands.argument("height", DoubleArgumentType.doubleArg(-100, 1000))
                                                        .then(Commands.argument("duration", DoubleArgumentType.doubleArg(0.1))
                                                                .executes(ctx -> executeOrbitMovement(ctx))
                                                                .then(Commands.literal("fov")
                                                                        .then(Commands.argument("startFov", DoubleArgumentType.doubleArg(5, 120))
                                                                                .then(Commands.argument("endFov", DoubleArgumentType.doubleArg(5, 120))
                                                                                        .executes(ctx -> executeOrbitMovementWithFOV(ctx))
                                                                                )
                                                                        )
                                                                )
                                                                .then(Commands.literal("heading")
                                                                        .then(Commands.argument("offset", DoubleArgumentType.doubleArg(-180, 180))
                                                                                .executes(ctx -> executeOrbitMovementWithHeading(ctx))
                                                                        )
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                )
        );

        // 贝塞尔曲线运动指令
        dispatcher.register(Commands.literal("ic-run")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("bezier")
                        .then(Commands.argument("start", Vec3Argument.vec3())
                                .then(Commands.argument("control", Vec3Argument.vec3())
                                        .then(Commands.argument("end", Vec3Argument.vec3())
                                                .then(Commands.argument("duration", DoubleArgumentType.doubleArg(0.1))
                                                        .executes(ctx -> executeBezierMovement(ctx))
                                                        .then(Commands.literal("fov")
                                                                .then(Commands.argument("startFov", DoubleArgumentType.doubleArg(5, 120))
                                                                        .then(Commands.argument("endFov", DoubleArgumentType.doubleArg(5, 120))
                                                                                .executes(ctx -> executeBezierMovementWithFOV(ctx))
                                                                        )
                                                                )
                                                        )
                                                        .then(Commands.literal("heading")
                                                                .then(Commands.argument("offset", DoubleArgumentType.doubleArg(-180, 180))
                                                                        .executes(ctx -> executeBezierMovementWithHeading(ctx))
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                )
        );

        // 螺旋运动指令
        dispatcher.register(Commands.literal("ic-run")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("spiral")
                        .then(Commands.argument("center", Vec3Argument.vec3())
                                .then(Commands.argument("radius", DoubleArgumentType.doubleArg(0.1))
                                        .then(Commands.argument("speed", DoubleArgumentType.doubleArg(0.1))
                                                .then(Commands.argument("height", DoubleArgumentType.doubleArg(-100, 1000))
                                                        .then(Commands.argument("duration", DoubleArgumentType.doubleArg(0.1))
                                                                .executes(ctx -> executeSpiralMovement(ctx))
                                                                .then(Commands.literal("fov")
                                                                        .then(Commands.argument("startFov", DoubleArgumentType.doubleArg(5, 120))
                                                                                .then(Commands.argument("endFov", DoubleArgumentType.doubleArg(5, 120))
                                                                                        .executes(ctx -> executeSpiralMovementWithFOV(ctx))
                                                                                )
                                                                        )
                                                                )
                                                                .then(Commands.literal("heading")
                                                                        .then(Commands.argument("offset", DoubleArgumentType.doubleArg(-180, 180))
                                                                                .executes(ctx -> executeSpiralMovementWithHeading(ctx))
                                                                        )
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                )
        );

        // 滑动变焦运动指令（标准版本）
        dispatcher.register(Commands.literal("ic-run")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("dolly")
                        .then(Commands.argument("start", Vec3Argument.vec3())
                                .then(Commands.argument("end", Vec3Argument.vec3())
                                        .then(Commands.argument("target", Vec3Argument.vec3())
                                                .then(Commands.argument("duration", DoubleArgumentType.doubleArg(0.1))
                                                        .executes(ctx -> executeDollyZoomMovement(ctx))
                                                        .then(Commands.literal("fov")
                                                                .then(Commands.argument("startFov", DoubleArgumentType.doubleArg(5, 120))
                                                                        .then(Commands.argument("endFov", DoubleArgumentType.doubleArg(5, 120))
                                                                                .executes(ctx -> executeDollyZoomMovementWithFOV(ctx))
                                                                        )
                                                                )
                                                        )
                                                        .then(Commands.literal("heading")
                                                                .then(Commands.argument("offset", DoubleArgumentType.doubleArg(-180, 180))
                                                                        .executes(ctx -> executeDollyZoomMovementWithHeading(ctx))
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                )
        );

        // 简化版滑动变焦运动指令（推荐，自动计算结束位置）
        dispatcher.register(Commands.literal("ic-run")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("dolly")
                        .then(Commands.literal("simple")
                                .then(Commands.argument("start", Vec3Argument.vec3())
                                        .then(Commands.argument("target", Vec3Argument.vec3())
                                                .then(Commands.argument("distance", DoubleArgumentType.doubleArg(0.1))
                                                        .then(Commands.argument("duration", DoubleArgumentType.doubleArg(0.1))
                                                                .executes(ctx -> executeSimpleDollyZoomMovement(ctx))
                                                                .then(Commands.literal("strength")
                                                                        .then(Commands.argument("strength", DoubleArgumentType.doubleArg(0.1, 2.0))
                                                                                .executes(ctx -> executeDollyZoomMovementWithStrength(ctx))
                                                                        )
                                                                )
                                                                .then(Commands.literal("fov")
                                                                        .then(Commands.argument("startFov", DoubleArgumentType.doubleArg(5, 120))
                                                                                .then(Commands.argument("endFov", DoubleArgumentType.doubleArg(5, 120))
                                                                                        .executes(ctx -> executeSimpleDollyZoomMovementWithFOV(ctx))
                                                                                )
                                                                        )
                                                                )
                                                                .then(Commands.literal("heading")
                                                                        .then(Commands.argument("offset", DoubleArgumentType.doubleArg(-180, 180))
                                                                                .executes(ctx -> executeSimpleDollyZoomMovementWithHeading(ctx))
                                                                        )
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                )
        );

        // 静态旋转/摇镜头运动指令
        dispatcher.register(Commands.literal("ic-run")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("pan")
                        .then(Commands.argument("position", Vec3Argument.vec3())
                                .then(Commands.argument("startPitch", DoubleArgumentType.doubleArg(-90, 90))
                                        .then(Commands.argument("startYaw", DoubleArgumentType.doubleArg(-180, 180))
                                                .then(Commands.argument("endPitch", DoubleArgumentType.doubleArg(-90, 90))
                                                        .then(Commands.argument("endYaw", DoubleArgumentType.doubleArg(-180, 180))
                                                                .then(Commands.argument("duration", DoubleArgumentType.doubleArg(0.1))
                                                                        .executes(ctx -> executeStationaryPanMovement(ctx))
                                                                        .then(Commands.literal("fov")
                                                                                .then(Commands.argument("startFov", DoubleArgumentType.doubleArg(5, 120))
                                                                                        .then(Commands.argument("endFov", DoubleArgumentType.doubleArg(5, 120))
                                                                                                .executes(ctx -> executeStationaryPanMovementWithFOV(ctx))
                                                                                        )
                                                                                )
                                                                        )
                                                                        .then(Commands.literal("heading")
                                                                                .then(Commands.argument("offset", DoubleArgumentType.doubleArg(-180, 180))
                                                                                        .executes(ctx -> executeStationaryPanMovementWithHeading(ctx))
                                                                                )
                                                                        )
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                )
        );
    }

    private static int executeDirectLinearMovement(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Vec3 startPos = Vec3Argument.getVec3(context, "start");
        Vec3 endPos = Vec3Argument.getVec3(context, "end");
        double duration = DoubleArgumentType.getDouble(context, "duration");

        CinematicManager.getInstance().startDirectLinearMovement(startPos, endPos, duration);
        return 1;
    }

    private static int executeDirectLinearMovementWithFOV(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Vec3 startPos = Vec3Argument.getVec3(context, "start");
        Vec3 endPos = Vec3Argument.getVec3(context, "end");
        double duration = DoubleArgumentType.getDouble(context, "duration");
        float startFov = (float) DoubleArgumentType.getDouble(context, "startFov");
        float endFov = (float) DoubleArgumentType.getDouble(context, "endFov");

        CinematicManager manager = CinematicManager.getInstance();
        manager.startDirectLinearMovement(startPos, endPos, duration);
        manager.setFOVRange(startFov, endFov);
        return 1;
    }

    private static int executeDirectLinearMovementWithHeading(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Vec3 startPos = Vec3Argument.getVec3(context, "start");
        Vec3 endPos = Vec3Argument.getVec3(context, "end");
        double duration = DoubleArgumentType.getDouble(context, "duration");
        double headingOffset = DoubleArgumentType.getDouble(context, "offset");

        CinematicManager manager = CinematicManager.getInstance();
        manager.startDirectLinearMovement(startPos, endPos, duration);
        manager.setHeadingOffset(headingOffset);
        return 1;
    }

    private static int executeSmoothLinearMovement(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Vec3 startPos = Vec3Argument.getVec3(context, "start");
        Vec3 endPos = Vec3Argument.getVec3(context, "end");
        double duration = DoubleArgumentType.getDouble(context, "duration");

        CinematicManager.getInstance().startSmoothLinearMovement(startPos, endPos, duration);
        return 1;
    }

    private static int executeSmoothLinearMovementWithFOV(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Vec3 startPos = Vec3Argument.getVec3(context, "start");
        Vec3 endPos = Vec3Argument.getVec3(context, "end");
        double duration = DoubleArgumentType.getDouble(context, "duration");
        float startFov = (float) DoubleArgumentType.getDouble(context, "startFov");
        float endFov = (float) DoubleArgumentType.getDouble(context, "endFov");

        CinematicManager manager = CinematicManager.getInstance();
        manager.startSmoothLinearMovement(startPos, endPos, duration);
        manager.setFOVRange(startFov, endFov);
        return 1;
    }

    private static int executeSmoothLinearMovementWithHeading(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Vec3 startPos = Vec3Argument.getVec3(context, "start");
        Vec3 endPos = Vec3Argument.getVec3(context, "end");
        double duration = DoubleArgumentType.getDouble(context, "duration");
        double headingOffset = DoubleArgumentType.getDouble(context, "offset");

        CinematicManager manager = CinematicManager.getInstance();
        manager.startSmoothLinearMovement(startPos, endPos, duration);
        manager.setHeadingOffset(headingOffset);
        return 1;
    }

    private static int executeOrbitMovement(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Vec3 centerPos = Vec3Argument.getVec3(context, "center");
        double radius = DoubleArgumentType.getDouble(context, "radius");
        double speed = DoubleArgumentType.getDouble(context, "speed");
        double height = DoubleArgumentType.getDouble(context, "height");
        double duration = DoubleArgumentType.getDouble(context, "duration");

        CinematicManager.getInstance().startOrbitMovement(centerPos, radius, speed, height, duration);
        return 1;
    }

    private static int executeOrbitMovementWithFOV(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Vec3 centerPos = Vec3Argument.getVec3(context, "center");
        double radius = DoubleArgumentType.getDouble(context, "radius");
        double speed = DoubleArgumentType.getDouble(context, "speed");
        double height = DoubleArgumentType.getDouble(context, "height");
        double duration = DoubleArgumentType.getDouble(context, "duration");
        float startFov = (float) DoubleArgumentType.getDouble(context, "startFov");
        float endFov = (float) DoubleArgumentType.getDouble(context, "endFov");

        CinematicManager manager = CinematicManager.getInstance();
        manager.startOrbitMovement(centerPos, radius, speed, height, duration);
        manager.setFOVRange(startFov, endFov);
        return 1;
    }

    private static int executeOrbitMovementWithHeading(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Vec3 centerPos = Vec3Argument.getVec3(context, "center");
        double radius = DoubleArgumentType.getDouble(context, "radius");
        double speed = DoubleArgumentType.getDouble(context, "speed");
        double height = DoubleArgumentType.getDouble(context, "height");
        double duration = DoubleArgumentType.getDouble(context, "duration");
        double headingOffset = DoubleArgumentType.getDouble(context, "offset");

        CinematicManager manager = CinematicManager.getInstance();
        manager.startOrbitMovement(centerPos, radius, speed, height, duration);
        manager.setHeadingOffset(headingOffset);
        return 1;
    }

    private static int executeBezierMovement(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Vec3 startPos = Vec3Argument.getVec3(context, "start");
        Vec3 controlPos = Vec3Argument.getVec3(context, "control");
        Vec3 endPos = Vec3Argument.getVec3(context, "end");
        double duration = DoubleArgumentType.getDouble(context, "duration");

        CinematicManager.getInstance().startBezierMovement(startPos, controlPos, endPos, duration);
        return 1;
    }

    private static int executeBezierMovementWithFOV(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Vec3 startPos = Vec3Argument.getVec3(context, "start");
        Vec3 controlPos = Vec3Argument.getVec3(context, "control");
        Vec3 endPos = Vec3Argument.getVec3(context, "end");
        double duration = DoubleArgumentType.getDouble(context, "duration");
        float startFov = (float) DoubleArgumentType.getDouble(context, "startFov");
        float endFov = (float) DoubleArgumentType.getDouble(context, "endFov");

        CinematicManager manager = CinematicManager.getInstance();
        manager.startBezierMovement(startPos, controlPos, endPos, duration);
        manager.setFOVRange(startFov, endFov);
        return 1;
    }

    private static int executeBezierMovementWithHeading(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Vec3 startPos = Vec3Argument.getVec3(context, "start");
        Vec3 controlPos = Vec3Argument.getVec3(context, "control");
        Vec3 endPos = Vec3Argument.getVec3(context, "end");
        double duration = DoubleArgumentType.getDouble(context, "duration");
        double headingOffset = DoubleArgumentType.getDouble(context, "offset");

        CinematicManager manager = CinematicManager.getInstance();
        manager.startBezierMovement(startPos, controlPos, endPos, duration);
        manager.setHeadingOffset(headingOffset);
        return 1;
    }

    private static int executeSpiralMovement(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Vec3 centerPos = Vec3Argument.getVec3(context, "center");
        double radius = DoubleArgumentType.getDouble(context, "radius");
        double speed = DoubleArgumentType.getDouble(context, "speed");
        double height = DoubleArgumentType.getDouble(context, "height");
        double duration = DoubleArgumentType.getDouble(context, "duration");

        CinematicManager.getInstance().startSpiralMovement(centerPos, radius, speed, height, duration);
        return 1;
    }

    private static int executeSpiralMovementWithFOV(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Vec3 centerPos = Vec3Argument.getVec3(context, "center");
        double radius = DoubleArgumentType.getDouble(context, "radius");
        double speed = DoubleArgumentType.getDouble(context, "speed");
        double height = DoubleArgumentType.getDouble(context, "height");
        double duration = DoubleArgumentType.getDouble(context, "duration");
        float startFov = (float) DoubleArgumentType.getDouble(context, "startFov");
        float endFov = (float) DoubleArgumentType.getDouble(context, "endFov");

        CinematicManager manager = CinematicManager.getInstance();
        manager.startSpiralMovement(centerPos, radius, speed, height, duration);
        manager.setFOVRange(startFov, endFov);
        return 1;
    }

    private static int executeSpiralMovementWithHeading(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Vec3 centerPos = Vec3Argument.getVec3(context, "center");
        double radius = DoubleArgumentType.getDouble(context, "radius");
        double speed = DoubleArgumentType.getDouble(context, "speed");
        double height = DoubleArgumentType.getDouble(context, "height");
        double duration = DoubleArgumentType.getDouble(context, "duration");
        double headingOffset = DoubleArgumentType.getDouble(context, "offset");

        CinematicManager manager = CinematicManager.getInstance();
        manager.startSpiralMovement(centerPos, radius, speed, height, duration);
        manager.setHeadingOffset(headingOffset);
        return 1;
    }

    private static int executeDollyZoomMovement(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Vec3 startPos = Vec3Argument.getVec3(context, "start");
        Vec3 endPos = Vec3Argument.getVec3(context, "end");
        Vec3 targetPoint = Vec3Argument.getVec3(context, "target");
        double duration = DoubleArgumentType.getDouble(context, "duration");

        CinematicManager.getInstance().startDollyZoomMovement(startPos, endPos, targetPoint, duration);
        return 1;
    }

    private static int executeDollyZoomMovementWithFOV(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Vec3 startPos = Vec3Argument.getVec3(context, "start");
        Vec3 endPos = Vec3Argument.getVec3(context, "end");
        Vec3 targetPoint = Vec3Argument.getVec3(context, "target");
        double duration = DoubleArgumentType.getDouble(context, "duration");
        float startFov = (float) DoubleArgumentType.getDouble(context, "startFov");
        float endFov = (float) DoubleArgumentType.getDouble(context, "endFov");

        CinematicManager manager = CinematicManager.getInstance();
        manager.startDollyZoomMovement(startPos, endPos, targetPoint, duration);
        manager.setFOVRange(startFov, endFov);
        return 1;
    }

    private static int executeDollyZoomMovementWithHeading(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Vec3 startPos = Vec3Argument.getVec3(context, "start");
        Vec3 endPos = Vec3Argument.getVec3(context, "end");
        Vec3 targetPoint = Vec3Argument.getVec3(context, "target");
        double duration = DoubleArgumentType.getDouble(context, "duration");
        double headingOffset = DoubleArgumentType.getDouble(context, "offset");

        CinematicManager manager = CinematicManager.getInstance();
        manager.startDollyZoomMovement(startPos, endPos, targetPoint, duration);
        manager.setHeadingOffset(headingOffset);
        return 1;
    }

    private static int executeStationaryPanMovement(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Vec3 fixedPosition = Vec3Argument.getVec3(context, "position");
        double startPitch = DoubleArgumentType.getDouble(context, "startPitch");
        double startYaw = DoubleArgumentType.getDouble(context, "startYaw");
        double endPitch = DoubleArgumentType.getDouble(context, "endPitch");
        double endYaw = DoubleArgumentType.getDouble(context, "endYaw");
        double duration = DoubleArgumentType.getDouble(context, "duration");

        Vec3 startRotation = new Vec3(Math.toRadians(startPitch), Math.toRadians(startYaw), 0);
        Vec3 endRotation = new Vec3(Math.toRadians(endPitch), Math.toRadians(endYaw), 0);

        CinematicManager.getInstance().startStationaryPanMovement(fixedPosition, startRotation, endRotation, duration);
        return 1;
    }

    private static int executeStationaryPanMovementWithFOV(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Vec3 fixedPosition = Vec3Argument.getVec3(context, "position");
        double startPitch = DoubleArgumentType.getDouble(context, "startPitch");
        double startYaw = DoubleArgumentType.getDouble(context, "startYaw");
        double endPitch = DoubleArgumentType.getDouble(context, "endPitch");
        double endYaw = DoubleArgumentType.getDouble(context, "endYaw");
        double duration = DoubleArgumentType.getDouble(context, "duration");
        float startFov = (float) DoubleArgumentType.getDouble(context, "startFov");
        float endFov = (float) DoubleArgumentType.getDouble(context, "endFov");

        Vec3 startRotation = new Vec3(Math.toRadians(startPitch), Math.toRadians(startYaw), 0);
        Vec3 endRotation = new Vec3(Math.toRadians(endPitch), Math.toRadians(endYaw), 0);

        CinematicManager manager = CinematicManager.getInstance();
        manager.startStationaryPanMovement(fixedPosition, startRotation, endRotation, duration);
        manager.setFOVRange(startFov, endFov);
        return 1;
    }

    private static int executeStationaryPanMovementWithHeading(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Vec3 fixedPosition = Vec3Argument.getVec3(context, "position");
        double startPitch = DoubleArgumentType.getDouble(context, "startPitch");
        double startYaw = DoubleArgumentType.getDouble(context, "startYaw");
        double endPitch = DoubleArgumentType.getDouble(context, "endPitch");
        double endYaw = DoubleArgumentType.getDouble(context, "endYaw");
        double duration = DoubleArgumentType.getDouble(context, "duration");
        double headingOffset = DoubleArgumentType.getDouble(context, "offset");

        Vec3 startRotation = new Vec3(Math.toRadians(startPitch), Math.toRadians(startYaw), 0);
        Vec3 endRotation = new Vec3(Math.toRadians(endPitch), Math.toRadians(endYaw), 0);

        CinematicManager manager = CinematicManager.getInstance();
        manager.startStationaryPanMovement(fixedPosition, startRotation, endRotation, duration);
        manager.setHeadingOffset(headingOffset);
        return 1;
    }

    // 执行设置摄像机位置
    private static int executeSetPosition(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Vec3 pos = Vec3Argument.getVec3(context, "pos");
        CinematicManager.getInstance().setCustomCameraPosition(pos);
        return 1;
    }

    // 执行设置摄像机旋转角度
    private static int executeSetRotation(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        double yaw = DoubleArgumentType.getDouble(context, "yaw");
        double pitch = DoubleArgumentType.getDouble(context, "pitch");
        CinematicManager.getInstance().setCustomCameraRotation((float) yaw, (float) pitch);
        return 1;
    }

    // 执行设置摄像机FOV
    private static int executeSetFOV(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        double fov = DoubleArgumentType.getDouble(context, "fov");
        CinematicManager.getInstance().setCustomCameraFOV(fov);
        return 1;
    }

    // 执行简化版滑动变焦运动
    private static int executeSimpleDollyZoomMovement(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Vec3 startPos = Vec3Argument.getVec3(context, "start");
        Vec3 targetPoint = Vec3Argument.getVec3(context, "target");
        double distance = DoubleArgumentType.getDouble(context, "distance");
        double duration = DoubleArgumentType.getDouble(context, "duration");

        CinematicManager.getInstance().startSimpleDollyZoomMovement(startPos, targetPoint, distance, duration);
        return 1;
    }

    // 执行带强度参数的滑动变焦运动
    private static int executeDollyZoomMovementWithStrength(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Vec3 startPos = Vec3Argument.getVec3(context, "start");
        Vec3 targetPoint = Vec3Argument.getVec3(context, "target");
        double distance = DoubleArgumentType.getDouble(context, "distance");
        double duration = DoubleArgumentType.getDouble(context, "duration");
        double strength = DoubleArgumentType.getDouble(context, "strength");

        CinematicManager.getInstance().startDollyZoomMovementWithStrength(startPos, targetPoint, distance, duration, strength);
        return 1;
    }

    // 执行带FOV参数的简化版滑动变焦运动
    private static int executeSimpleDollyZoomMovementWithFOV(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Vec3 startPos = Vec3Argument.getVec3(context, "start");
        Vec3 targetPoint = Vec3Argument.getVec3(context, "target");
        double distance = DoubleArgumentType.getDouble(context, "distance");
        double duration = DoubleArgumentType.getDouble(context, "duration");
        float startFov = (float) DoubleArgumentType.getDouble(context, "startFov");
        float endFov = (float) DoubleArgumentType.getDouble(context, "endFov");

        CinematicManager manager = CinematicManager.getInstance();
        manager.startSimpleDollyZoomMovement(startPos, targetPoint, distance, duration);
        manager.setFOVRange(startFov, endFov);
        return 1;
    }

    // 执行带朝向偏移参数的简化版滑动变焦运动
    private static int executeSimpleDollyZoomMovementWithHeading(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Vec3 startPos = Vec3Argument.getVec3(context, "start");
        Vec3 targetPoint = Vec3Argument.getVec3(context, "target");
        double distance = DoubleArgumentType.getDouble(context, "distance");
        double duration = DoubleArgumentType.getDouble(context, "duration");
        double headingOffset = DoubleArgumentType.getDouble(context, "offset");

        CinematicManager manager = CinematicManager.getInstance();
        manager.startSimpleDollyZoomMovement(startPos, targetPoint, distance, duration);
        manager.setHeadingOffset(headingOffset);
        return 1;
    }

    // 执行重置摄像机设置
    private static int executeResetSettings(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CinematicManager.getInstance().resetCustomCameraSettings();
        return 1;
    }
}

