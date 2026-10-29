package com.eason.zcore.modules;

import com.eason.zcore.MeteorZCore;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.util.Mth;
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
 * 由于 26.3 的服务端限制同一个客户端 tick 只能收到一个位置包，
 * 本模块用 {@code positionSent} 与 {@code pendingRestore} 两个标记保证每个 tick 组只占用一个位置包。
 */
public class ZReach extends Module {
    /** 原版服务端允许的方块交互距离：4.5 格基础距离加 1.0 格缓冲。 */
    private static final double SERVER_BLOCK_RANGE = 5.5;
    /** 原版服务端允许的实体交互距离：3.0 格基础距离加 3.0 格缓冲。 */
    private static final double SERVER_ENTITY_RANGE = 6.0;
    /** 伪造坐标与目标之间保留的距离，必须明显小于服务端允许的距离。 */
    private static final double SPOOF_OFFSET = 3.0;

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

    /** 当前客户端 tick 组内是否已经发送过位置包。 */
    private boolean positionSent;
    /** 是否存在需要在本 tick 结束后还原的伪造坐标。 */
    private boolean pendingRestore;

    /**
     * 构造方法，完成模块注册信息。
     */
    public ZReach() {
        // 调用父类构造：传入分类、模块名与描述
        super(MeteorZCore.CATEGORY, "z-reach", "Increases your reach for interaction, attack and mining by making the server think you are next to the target.");
    }

    /**
     * 模块启用时调用，重置内部状态。
     */
    @Override
    public void onActivate() {
        // 清除上一轮可能残留的位置包标记
        positionSent = false;
        // 清除上一轮可能残留的还原标记
        pendingRestore = false;
    }

    /**
     * 模块关闭时调用，重置内部状态。
     */
    @Override
    public void onDeactivate() {
        // 清除位置包标记
        positionSent = false;
        // 清除还原标记
        pendingRestore = false;
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
     * 监听发包完成事件，在客户端 tick 结束时把伪造坐标还原成真实坐标。
     *
     * @param event 发包完成事件
     */
    @EventHandler
    private void onPacketSent(PacketEvent.Sent event) {
        // 仅处理客户端 tick 结束包，它代表当前 tick 组的位置包已经发送完毕
        if (!(event.packet instanceof ServerboundClientTickEndPacket)) return;

        // 没有待还原的坐标时直接解锁，允许之后发送正常位置包
        if (!pendingRestore) {
            positionSent = false;
            return;
        }

        // 清除还原标记，避免重复还原
        pendingRestore = false;

        // 玩家或连接不存在时无法还原，直接解锁
        if (mc.player == null || mc.getConnection() == null) {
            positionSent = false;
            return;
        }

        // 静默发送真实坐标：该包排在 tick 结束包之后，属于下一个 tick 组，服务端会重新计数
        event.sendSilently(new ServerboundMovePlayerPacket.Pos(mc.player.position(), mc.player.onGround(), mc.player.horizontalCollision));
        // 锁定下一个 tick 组，阻止客户端稍后自动发送的第二个位置包
        positionSent = true;
    }

    /**
     * 监听发包事件，在动作执行前插入伪造坐标。
     *
     * @param event 发包事件
     */
    @EventHandler
    private void onSendPacket(PacketEvent.Send event) {
        // 处理位置包：本 tick 已发送过位置包时取消客户端自动发送的位置包
        if (event.packet instanceof ServerboundMovePlayerPacket packet && packet.hasPosition()) {
            // 该 tick 组已被占用，取消这个多余的位置包
            if (positionSent) event.cancel();
            return;
        }

        // 玩家或世界不存在、或本 tick 组已被占用时，不再尝试伪造坐标
        if (mc.player == null || mc.level == null || positionSent) return;

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

        // 计算目标碰撞箱上距离眼睛最近的点
        Vec3 closest = new Vec3(
            // X 轴方向取碰撞箱范围内的最近值
            Mth.clamp(eye.x, target.minX, target.maxX),
            // Y 轴方向取碰撞箱范围内的最近值
            Mth.clamp(eye.y, target.minY, target.maxY),
            // Z 轴方向取碰撞箱范围内的最近值
            Mth.clamp(eye.z, target.minZ, target.maxZ)
        );

        // 从最近点指向眼睛的单位方向向量
        Vec3 direction = eye.subtract(closest);
        // 距离过近时无需伪造
        if (direction.lengthSqr() < 1.0E-4) return;

        // 沿着玩家所在方向后退固定距离，得到伪造后的眼睛坐标
        Vec3 spoofedEye = closest.add(direction.normalize().scale(SPOOF_OFFSET));
        // 把眼睛坐标换算成脚部坐标，即减去眼睛高度
        Vec3 spoofedFeet = spoofedEye.subtract(0.0, mc.player.getEyeY() - mc.player.getY(), 0.0);

        // 取消原始动作包，改为按顺序手动发送
        event.cancel();
        // 静默发送伪造位置包，服务端会先移动玩家
        event.sendSilently(new ServerboundMovePlayerPacket.Pos(spoofedFeet.x, spoofedFeet.y, spoofedFeet.z, mc.player.onGround(), mc.player.horizontalCollision));
        // 静默重发原始动作包，此时服务端的距离校验可以通过
        event.sendSilently(event.packet);

        // 标记当前 tick 组已经发送过位置包
        positionSent = true;
        // 标记需要在 tick 结束后还原坐标
        pendingRestore = true;
    }
}
