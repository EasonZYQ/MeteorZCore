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
import meteordevelopment.meteorclient.utils.player.SwingMode;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Z Mace Tp 模块。
 * <p>
 * 重锤（Mace）的砸击伤害取决于服务端的摔落距离：摔落越久，加成伤害越高。
 * 本模块拦截使用重锤时的攻击包，先用 {@link TpMover} 把服务端位置送到目标上方的指定高度，
 * 再分段向下移动制造摔落距离，最后在目标范围内重新发送攻击包，让服务端按高摔落距离结算伤害。
 * <p>
 * 整个过程客户端位置与视角都不变，只伪造服务端视角的运动轨迹；重锤命中后服务端会自动清零摔落距离，
 * 因此不会产生摔落伤害。
 */
public class ZMaceTp extends Module {
    /** 阶段：空闲。 */
    private static final int STAGE_IDLE = 0;
    /** 阶段：上升到起始高度。 */
    private static final int STAGE_UP = 1;
    /** 阶段：分段下落到攻击点。 */
    private static final int STAGE_DOWN = 2;
    /** 阶段：从攻击点向上抬升，用于清零摔落距离。 */
    private static final int STAGE_LAND = 3;
    /** 阶段：回到原位置。 */
    private static final int STAGE_RETURN = 4;
    /** 单次流程允许的最大 tick 数，超过后自动中止。 */
    private static final int MAX_TICKS = 400;
    /** 攻击点相对玩家脚部抬高的高度。 */
    private static final double ATTACK_OFFSET = 1.0;
    /** 清零摔落距离时抬升的高度。 */
    private static final double RESET_OFFSET = 2.0;

    /** 默认设置分组。 */
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    /** 开始下落时距离攻击点的高度。 */
    private final Setting<Double> fallHeight = sgGeneral.add(new DoubleSetting.Builder()
        // 设置项名称
        .name("fall-height")
        // 设置项说明
        .description("The height you fall from before the mace hit is re-sent.")
        // 默认值
        .defaultValue(30.0)
        // 允许输入的数值范围
        .range(1.0, 1000.0)
        // 滑条显示的数值范围
        .sliderRange(3.0, 100.0)
        // 构建出设置对象
        .build()
    );

    /** 当前阶段。 */
    private int stage = STAGE_IDLE;
    /** 攻击目标实体 id。 */
    private int targetId = -1;
    /** 玩家原始位置，也是最终回程目标。 */
    private Vec3 origin;
    /** 攻击时服务端应到达的位置。 */
    private Vec3 attackPoint;
    /** 摔落起始位置。 */
    private Vec3 upPoint;
    /** 用于清零摔落距离的位置。 */
    private Vec3 landPoint;
    /** 当前流程已经经过的 tick 数。 */
    private int ticks;
    /** 是否正在发送本模块自己的攻击包，用于避免再次被拦截。 */
    private boolean bypass;

    /**
     * 构造方法，完成模块注册信息。
     */
    public ZMaceTp() {
        // 调用父类构造：传入分类、模块名与描述
        super(MeteorZCore.CATEGORY, "z-mace-tp", "Teleports you up before a mace hit so the smash attack uses your configured fall height.");
    }

    /**
     * 模块启用时调用，重置传送引擎状态。
     */
    @Override
    public void onActivate() {
        // 清空流程并重置传送引擎
        resetTask();
        TpMover.reset();
        TpMover.resetDetection();
    }

    /**
     * 模块关闭时调用，中止传送并清空流程。
     */
    @Override
    public void onDeactivate() {
        // 中止正在进行的传送
        TpMover.abort();
        resetTask();
    }

    /**
     * 清空当前流程状态。
     */
    private void resetTask() {
        // 回到空闲阶段并清空目标与坐标
        stage = STAGE_IDLE;
        targetId = -1;
        origin = null;
        attackPoint = null;
        upPoint = null;
        landPoint = null;
        ticks = 0;
        bypass = false;
    }

    /**
     * 监听发包事件，拦截使用重锤时的攻击包并改为远距离下落攻击。
     *
     * @param event 发包事件
     */
    @EventHandler
    private void onSendPacket(PacketEvent.Send event) {
        // 正在发送本模块自己的攻击包时不重复处理
        if (bypass) return;
        // 已经有流程在进行时不重复处理
        if (stage != STAGE_IDLE) return;
        // 玩家或世界不存在时不处理
        if (mc.player == null || mc.level == null) return;
        // 只拦截攻击包
        if (!(event.packet instanceof ServerboundAttackPacket packet)) return;
        // 只有主手持重锤时才处理
        if (!mc.player.getMainHandItem().is(Items.MACE)) return;
        // 传送引擎被其他模块占用时不处理
        if (TpMover.isBusy()) return;

        // 校验目标实体
        Entity target = mc.level.getEntity(packet.entityId());
        if (target == null || !target.isAlive() || target == mc.player) return;

        // 取消原始攻击包，稍后在目标上方重新发送
        event.cancel();

        // 记录本次流程的坐标
        origin = mc.player.position();
        attackPoint = origin.add(0.0, ATTACK_OFFSET, 0.0);
        upPoint = attackPoint.add(0.0, fallHeight.get(), 0.0);
        landPoint = origin.add(0.0, RESET_OFFSET, 0.0);
        targetId = packet.entityId();
        ticks = 0;
        stage = STAGE_UP;
    }

    /**
     * 每个客户端 tick 开始时调用，推进下落攻击流程。
     *
     * @param event tick 前事件
     */
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        // 空闲时直接返回
        if (stage == STAGE_IDLE) return;
        // 玩家或世界不存在时中止流程
        if (mc.player == null || mc.level == null) {
            abortTask();
            return;
        }
        // 流程耗时过长时中止，避免卡在异常状态
        if (++ticks > MAX_TICKS) {
            abortTask();
            return;
        }

        switch (stage) {
            case STAGE_UP:
                stepUp();
                break;
            case STAGE_DOWN:
                stepDown();
                break;
            case STAGE_LAND:
                stepLand();
                break;
            case STAGE_RETURN:
                stepReturn();
                break;
            default:
                abortTask();
                break;
        }
    }

    /**
     * 上升到摔落起点。
     */
    private void stepUp() {
        // 未到达起点时继续上升
        if (!TpMover.moveTo(upPoint)) return;

        // 起点处的上升位移会让服务端清零摔落距离，随后开始下落
        TpMover.setForceAirborne(true);
        stage = STAGE_DOWN;
    }

    /**
     * 分段下落到攻击点，并在到达的那一 tick 发送攻击包。
     */
    private void stepDown() {
        // 未到达攻击点时继续下落
        if (!TpMover.moveTo(attackPoint)) return;

        // 此时服务端记录的摔落距离约为配置高度
        if (targetId != -1) {
            // 标记为自发送，避免再次触发拦截
            bypass = true;
            // 直接发送攻击包，服务端会用当前摔落距离结算重锤加成
            mc.getConnection().send(new ServerboundAttackPacket(targetId));
            // 取消标记
            bypass = false;
            // 播放客户端与服务端的挥手动画
            SwingMode.Both.attackSwing(InteractionHand.MAIN_HAND);
        }

        // 进入清零摔落距离阶段
        stage = STAGE_LAND;
    }

    /**
     * 向上抬升，让服务端清零摔落距离。
     */
    private void stepLand() {
        // 未到达抬升点时继续移动
        if (!TpMover.moveTo(landPoint)) return;

        // 抬升完成后恢复正常的地面状态
        TpMover.setForceAirborne(false);
        stage = STAGE_RETURN;
    }

    /**
     * 回到玩家原始位置。
     */
    private void stepReturn() {
        // 未回到原点时继续移动
        if (!TpMover.moveTo(origin)) return;

        // 结束流程并解除传送状态
        TpMover.reset();
        resetTask();
    }

    /**
     * 中止当前流程并恢复传送状态。
     */
    private void abortTask() {
        // 让服务端位置尽量回到原点
        TpMover.abort();
        resetTask();
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
