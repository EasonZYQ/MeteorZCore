package com.eason.zcore.modules;

import com.eason.zcore.MeteorZCore;
import com.eason.zcore.utils.PositionSpoofer;
import com.eason.zcore.utils.TpMover;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Z Click Tp 模块。
 * <p>
 * 右键点击准星指向的方块或实体时，直接传送到目标位置：
 * <ul>
 * <li>方块：传送到方块顶部的可站立位置；</li>
 * <li>实体：传送到实体脚下。</li>
 * </ul>
 * 传送由 {@link TpMover} 完成：先探测服务器是否支持超远直传，
 * 不支持时自动改为每 tick 前进一段距离的分段传送，到达后同步客户端位置。
 */
public class ZClickTp extends Module {
    /** 传送落点最多向上尝试的格数，用于寻找可站立位置。 */
    private static final int MAX_STAND_TRIES = 3;

    /** 默认设置分组。 */
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    /** 允许点击传送的最大距离。 */
    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        // 设置项名称
        .name("range")
        // 设置项说明
        .description("The maximum distance at which a click teleports you.")
        // 默认值
        .defaultValue(100.0)
        // 允许输入的数值范围
        .range(0.0, 1000.0)
        // 滑条显示的数值范围
        .sliderRange(5.0, 200.0)
        // 构建出设置对象
        .build()
    );

    /** 当前传送目标位置。 */
    private Vec3 destination;
    /** 是否存在待执行的点击传送任务。 */
    private boolean taskActive;

    /**
     * 构造方法，完成模块注册信息。
     */
    public ZClickTp() {
        // 调用父类构造：传入分类、模块名与描述
        super(MeteorZCore.CATEGORY, "z-click-tp", "Right click a block or entity to teleport to it.");
    }

    /**
     * 模块启用时调用，重置传送状态。
     */
    @Override
    public void onActivate() {
        // 清空任务并重置传送引擎
        destination = null;
        taskActive = false;
        TpMover.reset();
        TpMover.resetDetection();
    }

    /**
     * 模块关闭时调用，中止传送。
     */
    @Override
    public void onDeactivate() {
        // 中止正在进行的传送
        TpMover.abort();
        destination = null;
        taskActive = false;
    }

    /**
     * 监听发包事件，在玩家右键方块或实体时记录传送目标。
     *
     * @param event 发包事件
     */
    @EventHandler
    private void onSendPacket(PacketEvent.Send event) {
        // 玩家或世界不存在时不处理
        if (mc.player == null || mc.level == null) return;
        // 已经有任务在身，或传送引擎被其他模块占用时不处理
        if (taskActive || TpMover.isBusy()) return;

        Vec3 target = null;

        // 右键方块：传送到方块顶部的可站立位置
        if (event.packet instanceof ServerboundUseItemOnPacket packet) {
            target = standOn(packet.hitResult().getBlockPos());
        }
        // 右键实体：传送到实体脚下
        else if (event.packet instanceof ServerboundInteractPacket packet) {
            Entity entity = mc.level.getEntity(packet.entityId());
            if (entity == null) return;
            target = entity.position();
        }
        // 其他包与点击传送无关
        else return;

        // 找不到合适的落点时不处理
        if (target == null) return;
        // 超出配置距离时不处理
        if (target.distanceToSqr(mc.player.position()) > range.get() * range.get()) return;

        // 取消原始交互，避免在旧位置执行动作
        event.cancel();

        // 记录目标，下一个 tick 开始传送
        destination = target;
        taskActive = true;
    }

    /**
     * 计算方块顶部的可站立位置。
     *
     * @param pos 被点击的方块位置
     * @return 可站立位置，找不到时返回 null
     */
    private Vec3 standOn(BlockPos pos) {
        // 从方块上方开始逐格向上尝试
        for (int dy = 1; dy <= MAX_STAND_TRIES; dy++) {
            Vec3 feet = Vec3.atBottomCenterOf(pos.above(dy));
            // 把玩家碰撞箱平移到候选位置
            AABB box = mc.player.getBoundingBox().move(feet.subtract(mc.player.position()));
            // 该位置没有碰撞时可以直接站立
            if (mc.level.noCollision(mc.player, box)) return feet;
        }

        return null;
    }

    /**
     * 每个客户端 tick 开始时调用，推进点击传送任务。
     *
     * @param event tick 前事件
     */
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        // 没有任务时直接返回
        if (!taskActive || destination == null) return;
        // 玩家或世界不存在时取消任务
        if (mc.player == null || mc.level == null) {
            taskActive = false;
            return;
        }

        // 传送引擎正被其他模块占用时等待
        if (TpMover.isBusy() && !isOurTask()) {
            taskActive = false;
            destination = null;
            return;
        }

        // 推进传送，未到达时直接返回
        if (!TpMover.moveTo(destination)) return;

        // 到达目标后把客户端位置同步过去
        mc.player.setPos(destination);
        // 清除残余速度，避免落地后被继续推动
        mc.player.setDeltaMovement(Vec3.ZERO);
        // 结束任务并解除传送状态
        TpMover.reset();
        taskActive = false;
        destination = null;
    }

    /**
     * 判断当前的传送任务是否由本模块发起。
     *
     * @return 引擎正前往本模块的目标时返回 true
     */
    private boolean isOurTask() {
        Vec3 engineTarget = TpMover.destination();
        return engineTarget != null && destination != null && engineTarget.distanceTo(destination) <= 0.5;
    }

    /**
     * 监听收包事件，让传送引擎判断直传是否被服务端拒绝。
     *
     * @param event 收包事件
     */
    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        // 交给传送引擎判断是否收到位置校正
        TpMover.handleReceive(event);
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
}
