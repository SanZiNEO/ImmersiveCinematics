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
        // 直接朝向目标的直线运动指令
        dispatcher.register(Commands.literal("ic-run")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("direct")
                        .then(Commands.argument("start", Vec3Argument.vec3())
                                .then(Commands.argument("end", Vec3Argument.vec3())
                                        .then(Commands.argument("duration", DoubleArgumentType.doubleArg(0.1))
                                                .executes(ctx -> executeDirectLinearMovement(ctx))
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
                                        )
                                )
                        )
                )
        );

        // 保持向后兼容的线性运动指令
        dispatcher.register(Commands.literal("ic-run")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("linear")
                        .then(Commands.argument("start", Vec3Argument.vec3())
                                .then(Commands.argument("end", Vec3Argument.vec3())
                                        .then(Commands.argument("duration", DoubleArgumentType.doubleArg(0.1))
                                                .executes(ctx -> executeLinearMovement(ctx))
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
                                                        )
                                                )
                                        )
                                )
                        )
                )
        );

        // 滑动变焦运动指令
        dispatcher.register(Commands.literal("ic-run")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("dolly")
                        .then(Commands.argument("start", Vec3Argument.vec3())
                                .then(Commands.argument("end", Vec3Argument.vec3())
                                        .then(Commands.argument("target", Vec3Argument.vec3())
                                                .then(Commands.argument("duration", DoubleArgumentType.doubleArg(0.1))
                                                        .executes(ctx -> executeDollyZoomMovement(ctx))
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

    private static int executeSmoothLinearMovement(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Vec3 startPos = Vec3Argument.getVec3(context, "start");
        Vec3 endPos = Vec3Argument.getVec3(context, "end");
        double duration = DoubleArgumentType.getDouble(context, "duration");

        CinematicManager.getInstance().startSmoothLinearMovement(startPos, endPos, duration);
        return 1;
    }

    private static int executeLinearMovement(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Vec3 startPos = Vec3Argument.getVec3(context, "start");
        Vec3 endPos = Vec3Argument.getVec3(context, "end");
        double duration = DoubleArgumentType.getDouble(context, "duration");

        CinematicManager.getInstance().startLinearMovement(startPos, endPos, duration);
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

    private static int executeBezierMovement(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Vec3 startPos = Vec3Argument.getVec3(context, "start");
        Vec3 controlPos = Vec3Argument.getVec3(context, "control");
        Vec3 endPos = Vec3Argument.getVec3(context, "end");
        double duration = DoubleArgumentType.getDouble(context, "duration");

        CinematicManager.getInstance().startBezierMovement(startPos, controlPos, endPos, duration);
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

    private static int executeDollyZoomMovement(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Vec3 startPos = Vec3Argument.getVec3(context, "start");
        Vec3 endPos = Vec3Argument.getVec3(context, "end");
        Vec3 targetPoint = Vec3Argument.getVec3(context, "target");
        double duration = DoubleArgumentType.getDouble(context, "duration");

        CinematicManager.getInstance().startDollyZoomMovement(startPos, endPos, targetPoint, duration);
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
}
