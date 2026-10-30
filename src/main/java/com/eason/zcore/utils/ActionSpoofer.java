package com.eason.zcore.utils;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 动作伪造工具类。
 * <p>
 * 统一拦截客户端的四类动作包（交互、放置、攻击、挖掘），当目标超出服务端允许的距离时，
 * 先发送一个伪造位置包再重新发送动作包。各模块通过实现 {@link Policy} 决定哪些动作需要伪造、
 * 以及伪造的最大距离。
 */
public final class ActionSpoofer {
    /** 动作类型。 */
    public enum Action {
        /** 右键交互（非放置类物品）。 */
        INTERACT,
        /** 放置方块或使用桶类物品。 */
        PLACE,
        /** 实体攻击。 */
        ATTACK,
        /** 方块破坏。 */
        MINE
    }

    /** 动作伪造策略，由各模块实现。 */
    public interface Policy {
        /** 判断某类动作是否允许伪造。 */
        boolean allow(Action action);

        /** 返回允许伪造的最大距离。 */
        double maxDistance();
    }

    /** 原版服务端允许的方块动作距离：4.5 格基础距离加 1.0 格缓冲。 */
    private static final double SERVER_BLOCK_RANGE = 5.5;
    /** 原版服务端允许的实体动作距离：3.0 格基础距离加 3.0 格缓冲。 */
    private static final double SERVER_ENTITY_RANGE = 6.0;

    /**
     * 私有构造方法，禁止实例化该工具类。
     */
    private ActionSpoofer() {}

    /**
     * 处理发包事件，按策略对超距离动作插入伪造位置包。
     *
     * @param event  发包事件
     * @param policy 伪造策略
     */
    public static void handleSend(PacketEvent.Send event, Policy policy) {
        // 先处理位置包本身，若是位置包则直接返回
        if (PositionSpoofer.handleSend(event)) return;
        // 传送引擎正在控制服务端位置时，不再做普通伪造
        if (TpMover.isBusy()) return;
        // 玩家或世界不存在、或本 tick 组已被位置包占用时不再处理
        if (mc.player == null || mc.level == null || PositionSpoofer.isLocked()) return;

        // 缓存玩家眼睛位置，用于计算距离
        Vec3 eye = mc.player.getEyePosition();
        // 目标碰撞箱，未匹配到动作包时保持为空
        AABB target = null;
        // 服务端允许的距离，实体类动作会换成实体阈值
        double serverRange = SERVER_BLOCK_RANGE;

        // 右键交互或放置
        if (event.packet instanceof ServerboundUseItemOnPacket packet) {
            // 根据手持物品判断属于放置还是交互
            Action action = isPlacingItem(mc.player.getItemInHand(packet.hand())) ? Action.PLACE : Action.INTERACT;
            // 策略不允许该动作时返回
            if (!policy.allow(action)) return;
            // 以被点击的方块作为目标
            target = new AABB(packet.hitResult().getBlockPos());
        }
        // 方块破坏动作
        else if (event.packet instanceof ServerboundPlayerActionPacket packet) {
            // 策略不允许挖掘或不属于破坏动作时返回
            if (!policy.allow(Action.MINE) || !isBreakAction(packet.getAction())) return;
            // 以被破坏的方块作为目标
            target = new AABB(packet.getPos());
        }
        // 实体右键交互
        else if (event.packet instanceof ServerboundInteractPacket packet) {
            // 策略不允许交互时返回
            if (!policy.allow(Action.INTERACT)) return;
            // 根据实体编号查找目标实体
            Entity entity = mc.level.getEntity(packet.entityId());
            if (entity == null) return;
            // 以实体碰撞箱作为目标，并使用实体距离阈值
            target = entity.getBoundingBox();
            serverRange = SERVER_ENTITY_RANGE;
        }
        // 实体攻击
        else if (event.packet instanceof ServerboundAttackPacket packet) {
            // 策略不允许攻击时返回
            if (!policy.allow(Action.ATTACK)) return;
            // 根据实体编号查找目标实体
            Entity entity = mc.level.getEntity(packet.entityId());
            if (entity == null) return;
            // 以实体碰撞箱作为目标，并使用实体距离阈值
            target = entity.getBoundingBox();
            serverRange = SERVER_ENTITY_RANGE;
        }
        // 其余包与距离无关，直接返回
        else return;

        // 执行伪造判断与发包
        spoof(event, target, eye, serverRange, policy.maxDistance());
    }

    /**
     * 判断手持物品是否属于放置类物品（方块或桶）。
     *
     * @param stack 手持物品
     * @return 属于放置类物品时返回 true
     */
    private static boolean isPlacingItem(ItemStack stack) {
        // 方块物品与桶类物品都走放置流程
        return stack.getItem() instanceof BlockItem || stack.getItem() instanceof BucketItem;
    }

    /**
     * 判断方块动作包是否属于破坏方块相关的动作。
     *
     * @param action 动作类型
     * @return 属于破坏方块动作时返回 true
     */
    private static boolean isBreakAction(ServerboundPlayerActionPacket.Action action) {
        // 只有破坏方块的四种动作需要伪造坐标
        return switch (action) {
            case START_DESTROY_BLOCK, STOP_DESTROY_BLOCK, ABORT_DESTROY_BLOCK, CHANGE_DESTROY_DIRECTION -> true;
            default -> false;
        };
    }

    /**
     * 在目标超出服务端允许距离时，插入一个伪造位置包并重新发送原动作包。
     *
     * @param event       发包事件
     * @param target      目标碰撞箱
     * @param eye         玩家眼睛位置
     * @param serverRange 服务端允许的最大距离
     * @param maxDistance 模块配置的最大伪造距离
     */
    private static void spoof(PacketEvent.Send event, AABB target, Vec3 eye, double serverRange, double maxDistance) {
        // 计算眼睛到目标碰撞箱的平方距离
        double distanceSq = target.distanceToSqr(eye);

        // 未超出服务端距离，服务端本来就会接受，保持原包不动
        if (distanceSq <= serverRange * serverRange) return;
        // 超出配置的最大距离，服务端无论如何都会拒绝，同样保持原包不动
        if (distanceSq > maxDistance * maxDistance) return;

        // 计算伪造的脚部坐标，避免落在方块里
        Vec3 feet = PositionSpoofer.feetNear(target, serverRange);
        // 找不到可用坐标时不处理
        if (feet == null) return;

        // 取消原始动作包，改为按顺序手动发送
        event.cancel();
        // 静默发送伪造位置包，服务端会先移动玩家
        event.sendSilently(new ServerboundMovePlayerPacket.Pos(feet.x, feet.y, feet.z, mc.player.onGround(), mc.player.horizontalCollision));
        // 静默重发原始动作包，此时服务端的距离校验可以通过
        event.sendSilently(event.packet);
        // 标记本 tick 组已被占用，并记录需要在 tick 结束后还原
        PositionSpoofer.markSpoofed();
    }
}
