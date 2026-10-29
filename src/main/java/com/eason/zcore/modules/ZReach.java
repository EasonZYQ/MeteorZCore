package com.eason.zcore.modules;

import com.eason.zcore.MeteorZCore;
import com.eason.zcore.utils.PositionSpoofer;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Z Reach 模块。
 * <p>
 * 实现分为两部分：
 * 1. 客户端部分：通过 Mixin 放大玩家自身的交互距离（见 {@link com.eason.zcore.mixin.PlayerMixin}），
 *    让准星可以在更远处选中方块或实体并触发对应动作。
 * 2. 服务端部分：在动作发包前先发送一个伪造的移动包，让服务端误以为玩家已经站在目标旁边，
 *    动作执行完后再在客户端 tick 结束时发送还原坐标。
 * <p>
 * 位置包的发送与还原统一交给 {@link PositionSpoofer} 管理，
 * 避免与 Z TP Aura 同时开启时出现一个 tick 内两个位置包的情况。
 */
public class ZReach extends Module {
    /** 原版服务端允许的方块交互距离：4.5 格基础距离加 1.0 格缓冲。 */
    private static final double SERVER_BLOCK_RANGE = 5.5;
    /** 原版服务端允许的实体交互距离：3.0 格基础距离加 3.0 格缓冲。 */
    private static final double SERVER_ENTITY_RANGE = 6.0;

    /** 默认设置分组。 */
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    /** 允许动作发生的最大距离。 */
    private final Setting<Double> maxDistance = sgGeneral.add(new DoubleSetting.Builder()
        // 设置项名称，界面标题由它自动生成
        .name("max-distance")
        // 设置项说明
        .description("Maximum distance at which actions are attempted. Values above 12 may be rejected by server movement checks.")
        // 默认值
        .defaultValue(10.0)
        // 允许输入的数值范围
        .range(0.0, 20.0)
        // 滑条显示的数值范围
        .sliderRange(4.5, 14.0)
        // 构建出设置对象
        .build()
    );

    /** 是否扩展右键交互（方块与实体）的距离。 */
    private final Setting<Boolean> interaction = sgGeneral.add(new BoolSetting.Builder()
        // 设置项名称
        .name("interaction")
        // 设置项说明
        .description("Extend the distance at which blocks and entities can be right-clicked.")
        // 默认开启
        .defaultValue(true)
        // 构建出设置对象
        .build()
    );

    /** 是否扩展实体攻击的距离。 */
    private final Setting<Boolean> attack = sgGeneral.add(new BoolSetting.Builder()
        // 设置项名称
        .name("attack")
        // 设置项说明
        .description("Extend the distance at which entities can be attacked.")
        // 默认开启
        .defaultValue(true)
        // 构建出设置对象
        .build()
    );

    /** 是否扩展方块挖掘的距离。 */
    private final Setting<Boolean> mining = sgGeneral.add(new BoolSetting.Builder()
        // 设置项名称
        .name("mining")
        // 设置项说明
        .description("Extend the distance at which blocks can be broken.")
        // 默认开启
        .defaultValue(true)
        // 构建出设置对象
        .build()
    );

    /**
     * 构造方法，完成模块注册信息。
     */
    public ZReach() {
        // 调用父类构造：传入分类、模块名与描述
        super(MeteorZCore.CATEGORY, "z-reach", "Increases your reach for interaction, attack and mining by making the server think you are next to the target.");
    }

    /**
     * 计算方块交互距离需要额外增加的值，供 {@link com.eason.zcore.mixin.PlayerMixin} 调用。
     *
     * @param vanillaRange 原版交互距离
     * @return 需要额外增加的距离，未启用或已关闭相关开关时返回 0
     */
    public double extraBlockReach(double vanillaRange) {
        // 模块启用且交互或挖掘开关打开时，把距离补足到 max-distance
        return isActive() && (interaction.get() || mining.get()) ? Math.max(0, maxDistance.get() - vanillaRange) : 0;
    }

    /**
     * 计算实体交互距离需要额外增加的值，供 {@link com.eason.zcore.mixin.PlayerMixin} 调用。
     *
     * @param vanillaRange 原版交互距离
     * @return 需要额外增加的距离，未启用或已关闭相关开关时返回 0
     */
    public double extraEntityReach(double vanillaRange) {
        // 模块启用且交互或攻击开关打开时，把距离补足到 max-distance
        return isActive() && (interaction.get() || attack.get()) ? Math.max(0, maxDistance.get() - vanillaRange) : 0;
    }

    /**
     * 监听发包完成事件，把伪造坐标的还原工作交给共享工具类。
     *
     * @param event 发包完成事件
     */
    @EventHandler
    private void onPacketSent(PacketEvent.Sent event) {
        // 交给共享工具类处理 tick 结束包与坐标还原
        PositionSpoofer.handleSent(event);
    }

    /**
     * 监听发包事件，在动作执行前插入伪造坐标。
     *
     * @param event 发包事件
     */
    @EventHandler
    private void onSendPacket(PacketEvent.Send event) {
        // 先交给共享工具类处理位置包，若该包是位置包则不再继续处理
        if (PositionSpoofer.handleSend(event)) return;

        // 玩家或世界不存在、或本 tick 组已被位置包占用时，不再尝试伪造坐标
        if (mc.player == null || mc.level == null || PositionSpoofer.isLocked()) return;

        // 缓存玩家眼睛位置，供下面计算距离使用
        Vec3 eye = mc.player.getEyePosition();

        // 方块右键交互包
        if (event.packet instanceof ServerboundUseItemOnPacket packet) {
            // 交互开关打开时，按方块碰撞箱尝试伪造坐标
            if (interaction.get()) spoof(event, new AABB(packet.hitResult().getBlockPos()), eye, SERVER_BLOCK_RANGE);
        }
        // 方块破坏动作包
        else if (event.packet instanceof ServerboundPlayerActionPacket packet) {
            // 挖掘开关打开且动作属于破坏方块时，按方块碰撞箱尝试伪造坐标
            if (mining.get() && isBreakAction(packet.getAction())) spoof(event, new AABB(packet.getPos()), eye, SERVER_BLOCK_RANGE);
        }
        // 实体右键交互包
        else if (event.packet instanceof ServerboundInteractPacket packet) {
            // 交互开关关闭时直接返回
            if (!interaction.get()) return;

            // 根据实体编号查找目标实体
            Entity entity = mc.level.getEntity(packet.entityId());
            // 找到实体时按其碰撞箱尝试伪造坐标
            if (entity != null) spoof(event, entity.getBoundingBox(), eye, SERVER_ENTITY_RANGE);
        }
        // 实体攻击包
        else if (event.packet instanceof ServerboundAttackPacket packet) {
            // 攻击开关关闭时直接返回
            if (!attack.get()) return;

            // 根据实体编号查找目标实体
            Entity entity = mc.level.getEntity(packet.entityId());
            // 找到实体时按其碰撞箱尝试伪造坐标
            if (entity != null) spoof(event, entity.getBoundingBox(), eye, SERVER_ENTITY_RANGE);
        }
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
     */
    private void spoof(PacketEvent.Send event, AABB target, Vec3 eye, double serverRange) {
        // 计算眼睛到目标碰撞箱的平方距离
        double distanceSq = target.distanceToSqr(eye);

        // 未超出服务端距离，服务端本来就会接受，保持原包不动
        if (distanceSq <= serverRange * serverRange) return;
        // 超出配置的最大距离，服务端无论如何都会拒绝，同样保持原包不动
        if (distanceSq > maxDistance.get() * maxDistance.get()) return;

        // 计算伪造的脚部坐标，眼睛已经在目标里时返回空
        Vec3 feet = PositionSpoofer.feetNear(target);
        // 无法计算坐标时不处理
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
