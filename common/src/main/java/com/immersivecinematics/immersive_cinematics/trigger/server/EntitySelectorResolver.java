package com.immersivecinematics.immersive_cinematics.trigger.server;

import com.immersivecinematics.immersive_cinematics.util.SuccessOnlySource;
import com.mojang.brigadier.StringReader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import net.minecraft.commands.arguments.selector.options.EntitySelectorOptions;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 服务端实体选择器解析器。
 * <p>
 * 只负责用原版 {@link EntitySelectorParser} 解析 selector，并在服务端求值。
 * 不引用任何客户端类；必须在服务端主线程调用。
 * <p>
 * 支持原版选择器的完整选项，包括 {@code nbt=} / {@code tag=} / {@code type=} /
 * {@code name=} / {@code distance=} / {@code sort=} / {@code limit=} 等。
 */
public final class EntitySelectorResolver {

    private static final int MAX_SELECTOR_LENGTH = 512;
    private static final int MAX_RESULTS = 32;

    private static boolean bootstrapped;

    private EntitySelectorResolver() {
    }

    /**
     * 解析并求值 selector。
     *
     * @param player   请求玩家，用于构造权限 4、带实体上下文的 CommandSourceStack
     * @param selector 原版选择器字符串，例如
     *                 {@code @e[nbt={ForgeData:{'BetterEvE:FactionID':'red'}},limit=1]}
     * @param x        selector 的 origin X（供 sort=nearest / distance 使用）
     * @param y        origin Y
     * @param z        origin Z
     * @return 匹配到的实体 UUID 列表（按原版 sort/limit 结果顺序，最多 {@link #MAX_RESULTS} 个）
     * @throws Exception 参数非法、语法错误、权限异常等
     */
    public static List<UUID> resolve(ServerPlayer player, String selector, double x, double y, double z) throws Exception {
        if (selector == null || selector.isBlank()) {
            throw new IllegalArgumentException("selector is empty");
        }
        if (selector.length() > MAX_SELECTOR_LENGTH) {
            throw new IllegalArgumentException("selector too long: " + selector.length());
        }
        if (player == null) {
            throw new IllegalArgumentException("player is null");
        }

        ensureBootstrapped();

        // 原版解析：nbt= 会通过 NbtPredicate.getEntityTagToCompare(entity) 做部分 NBT 匹配。
        EntitySelectorParser parser = new EntitySelectorParser(new StringReader(selector), true);
        EntitySelector entitySelector = parser.parse();

        // 权限 4 的来源：避免 @e 选择器被权限检查拦掉；origin 设成相机位置，让 sort=nearest 正确。
        CommandSourceStack source = new SuccessOnlySource(player)
                .withPosition(new Vec3(x, y, z))
                .withPermission(4);

        List<? extends Entity> entities = entitySelector.findEntities(source);
        List<UUID> result = new ArrayList<>();
        for (Entity entity : entities) {
            if (entity == null || entity.isRemoved()) {
                continue;
            }
            result.add(entity.getUUID());
            if (result.size() >= MAX_RESULTS) {
                break;
            }
        }
        return result;
    }

    private static void ensureBootstrapped() {
        if (!bootstrapped) {
            // 原版 EntityArgument 静态初始化时也会调用；这里显式保证自定义解析入口可用。
            EntitySelectorOptions.bootStrap();
            bootstrapped = true;
        }
    }
}
