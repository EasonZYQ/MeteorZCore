package com.eason.zcore.modules;

import com.eason.zcore.MeteorZCore;
import com.eason.zcore.utils.ActionSpoofer;
import com.eason.zcore.utils.PositionSpoofer;
import com.eason.zcore.utils.TpMover;
import meteordevelopment.meteorclient.events.entity.player.StartBreakingBlockEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Z Packet Mine 模块。
 * <p>
 * 参考 GgBoy 的 GrimPactetMine 与 lotus 的 PacketMinePlus：
 * 拦截玩家正常的开始破坏事件，改为只发送动作包来模拟挖掘进度（客户端不产生破坏动画）。
 * 每个 tick 发送一组 START / ABORT / STOP 动作包。
 * <p>
 * 方块在服务端允许的距离内时直接发包挖掘；距离更远时由 {@link TpMover} 把服务端位置
 * 送到方块旁，站在工作点持续挖掘，挖完后再回到原处。
 */
public class ZPacketMine extends Module implements ActionSpoofer.Policy {
    /** 原版服务端允许的方块动作距离：4.5 格基础距离加 1.0 格缓冲。 */
    private static final double SERVER_BLOCK_RANGE = 5.5;
    /** 传送到工作点时与目标方块保持的最大距离。 */
    private static final double TP_OFFSET = 5.0;

    /** 默认设置分组。 */
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    /** 允许发包挖掘的最大距离。 */
    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        // 设置项名称
        .name("range")
        // 设置项说明
        .description("The maximum distance at which blocks can be packet mined.")
        // 默认值
        .defaultValue(10.0)
        // 允许输入的数值范围
        .range(0.0, 1000.0)
        // 滑条显示的数值范围
        .sliderRange(4.5, 50.0)
        // 构建出设置对象
        .build()
    );

    /** 是否在方块已经破坏后继续补发包。 */
    private final Setting<Boolean> rebreak = sgGeneral.add(new BoolSetting.Builder()
        // 设置项名称
        .name("rebreak")
        // 设置项说明
        .description("Keeps sending abort packets after the block is broken to hide the break.")
        // 默认关闭
        .defaultValue(false)
        // 构建出设置对象
        .build()
    );

    /** 两次动作序列之间的 tick 延迟。 */
    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        // 设置项名称
        .name("delay")
        // 设置项说明
        .description("The tick delay between action sequences.")
        // 默认值
        .defaultValue(1)
        // 允许输入的数值范围
        .range(0, 10)
        // 构建出设置对象
        .build()
    );

    /** 待挖掘方块队列。 */
    private final List<MineTarget> targets = new ArrayList<>();

    /** 当前传送任务的目的地。 */
    private Vec3 travelTarget;
    /** 是否处于回程阶段。 */
    private boolean returning;
    /** 是否正在远距离工作任务中。 */
    private boolean workActive;

    /**
     * 构造方法，完成模块注册信息。
     */
    public ZPacketMine() {
        // 调用父类构造：传入分类、模块名与描述
        super(MeteorZCore.CATEGORY, "z-packet-mine", "Mines blocks by sending action packets only, without the vanilla breaking animation.");
    }

    /**
     * 模块启用时调用，重置传送引擎状态。
     */
    @Override
    public void onActivate() {
        // 清空传送状态并重置引擎
        travelTarget = null;
        returning = false;
        workActive = false;
        TpMover.reset();
        TpMover.resetDetection();
    }

    /**
     * 模块关闭时调用，中止传送并清空队列。
     */
    @Override
    public void onDeactivate() {
        // 中止正在进行的传送
        TpMover.abort();
        // 清空所有待挖掘方块
        targets.clear();
        travelTarget = null;
        returning = false;
        workActive = false;
    }

    /**
     * 拦截玩家开始破坏方块的事件，改为发包挖掘。
     *
     * @param event 开始破坏方块事件
     */
    @EventHandler
    private void onStartBreakingBlock(StartBreakingBlockEvent event) {
        // 不可破坏的方块不处理
        if (!BlockUtils.canBreak(event.blockPos)) return;

        // 取消原版破坏行为，避免客户端与服务端重复发动作包
        event.cancel();

        // 距离超出配置或已经在队列中时忽略
        if (Vec3.atCenterOf(event.blockPos).distanceToSqr(mc.player.position()) > range.get() * range.get()) return;
        if (isQueued(event.blockPos)) return;

        // 加入挖掘队列
        targets.add(new MineTarget(event.blockPos.immutable(), event.direction, mc.level.getBlockState(event.blockPos)));
    }

    /**
     * 判断某个位置是否已经在挖掘队列中。
     *
     * @param pos 方块位置
     * @return 已在队列中时返回 true
     */
    private boolean isQueued(BlockPos pos) {
        // 遍历队列比较位置
        for (MineTarget target : targets) {
            if (target.pos.equals(pos)) return true;
        }

        return false;
    }

    /**
     * 每个客户端 tick 开始时调用，推进队列中的方块挖掘。
     *
     * @param event tick 前事件
     */
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        // 玩家、世界或游戏模式不存在时直接返回
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;

        // 传送任务进行中
        if (TpMover.isBusy()) {
            // 不是本模块发起的传送，等待它结束
            if (travelTarget == null) return;

            // 推进传送，未到达时直接返回
            if (!TpMover.moveTo(travelTarget)) return;

            // 回程完成，结束本次任务
            if (returning) {
                travelTarget = null;
                returning = false;
                workActive = false;
                TpMover.reset();
                return;
            }

            // 队列已空或任务失效时直接回程
            if (!workActive || targets.isEmpty()) {
                returning = true;
                travelTarget = TpMover.origin();
                return;
            }

            // 在工作点继续发包挖掘
            if (mineStep(targets.getFirst())) {
                targets.removeFirst();
                workActive = false;
                returning = true;
                travelTarget = TpMover.origin();
            }
            return;
        }

        // 队列为空时无需处理
        if (targets.isEmpty()) return;

        MineTarget target = targets.getFirst();

        // 方块已经变成空气时处理收尾
        if (mc.level.getBlockState(target.pos).isAir()) {
            handleBroken(target);
            return;
        }

        // 判断方块是否在服务端允许的距离内
        Vec3 eye = mc.player.getEyePosition();
        double distanceSq = new AABB(target.pos).distanceToSqr(eye);

        // 近距离直接发包挖掘，不需要传送
        if (distanceSq <= SERVER_BLOCK_RANGE * SERVER_BLOCK_RANGE) {
            if (mineStep(target)) handleBroken(target);
            return;
        }

        // 远距离：计算方块旁的工作点
        Vec3 feet = PositionSpoofer.feetNear(new AABB(target.pos), TP_OFFSET);
        // 找不到可用位置时丢弃该目标
        if (feet == null) {
            targets.removeFirst();
            return;
        }

        // 前往工作点，到达后开始挖掘
        workActive = true;
        travelTarget = feet;
        returning = false;
        TpMover.moveTo(feet);
    }

    /**
     * 处理已经破坏的方块。
     *
     * @param target 已破坏的目标
     */
    private void handleBroken(MineTarget target) {
        // 开启补发包时先发送一次 ABORT 与 STOP 混淆破坏记录
        if (rebreak.get() && !target.rebroken) {
            sendAction(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, target);
            sendAction(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, target);
            target.rebroken = true;
            return;
        }

        // 移除已完成的方块
        targets.remove(target);
    }

    /**
     * 发送一次挖掘动作序列。
     *
     * @param target 目标方块
     * @return 方块已经被破坏时返回 true
     */
    private boolean mineStep(MineTarget target) {
        // 缓存当前方块状态
        BlockState state = mc.level.getBlockState(target.pos);

        // 方块已经是空气时视为完成
        if (state.isAir()) return true;

        // 方块状态发生变化（例如被别人放置了新方块）时重新开始
        if (state.getBlock() != target.state.getBlock()) {
            target.state = state;
            target.started = false;
        }

        // 延迟未结束时先等待
        if (target.delayLeft > 0) {
            target.delayLeft--;
            return false;
        }

        // 未开始挖掘时发送完整的启动序列
        if (!target.started) {
            sendAction(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, target);
            sendAction(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, target);
            sendAction(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, target);
            sendAction(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, target);
            sendAction(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, target);
            target.started = true;
        } else {
            // 已开始挖掘时循环发送 STOP / START / ABORT 维持进度
            sendAction(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, target);
            sendAction(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, target);
            sendAction(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, target);
        }

        // 重置延迟
        target.delayLeft = delay.get();

        return false;
    }

    /**
     * 发送一个动作包。
     *
     * @param action 动作类型
     * @param target 目标方块
     */
    private void sendAction(ServerboundPlayerActionPacket.Action action, MineTarget target) {
        // 直接构造动作包发送，序列号使用默认值 0
        mc.getConnection().send(new ServerboundPlayerActionPacket(action, target.pos, target.direction));
    }

    /**
     * 判断某类动作是否允许伪造，供 {@link ActionSpoofer} 调用。
     *
     * @param action 动作类型
     * @return 只有挖掘动作返回 true
     */
    @Override
    public boolean allow(ActionSpoofer.Action action) {
        return action == ActionSpoofer.Action.MINE;
    }

    /**
     * 返回允许伪造的最大距离，供 {@link ActionSpoofer} 调用。
     *
     * @return 最大距离
     */
    @Override
    public double maxDistance() {
        return range.get();
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
     * 监听发包事件，在动作执行前插入伪造坐标。
     *
     * @param event 发包事件
     */
    @EventHandler
    private void onSendPacket(PacketEvent.Send event) {
        // 交给共享工具类按本模块的策略处理
        ActionSpoofer.handleSend(event, this);
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
     * 单个待挖掘方块的数据。
     */
    private static final class MineTarget {
        /** 方块位置。 */
        private final BlockPos pos;
        /** 破坏方向。 */
        private final Direction direction;
        /** 开始挖掘时的方块状态。 */
        private BlockState state;
        /** 是否已经发送过启动序列。 */
        private boolean started;
        /** 是否已经补发过破坏包。 */
        private boolean rebroken;
        /** 剩余延迟 tick。 */
        private int delayLeft;

        /**
         * 构造方法。
         *
         * @param pos       方块位置
         * @param direction 破坏方向
         * @param state     方块状态
         */
        private MineTarget(BlockPos pos, Direction direction, BlockState state) {
            this.pos = pos;
            this.direction = direction;
            this.state = state;
        }
    }
}
