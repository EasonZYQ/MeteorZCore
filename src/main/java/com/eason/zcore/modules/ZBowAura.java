package com.eason.zcore.modules;

import com.eason.zcore.MeteorZCore;
import com.eason.zcore.utils.PositionSpoofer;
import com.eason.zcore.utils.TpMover;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.Target;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Z Bow Aura 模块。
 * <p>
 * 合并 GCore 的 GBowGod 与 GTpBowAura：
 * 自动搜索目标、转向、拉满弓后射击（满蓄力保证弓箭伤害最大），
 * 可选先由 {@link TpMover} 把服务端位置送到目标附近，让远距离目标也能稳定命中；
 * 目标失效后回到原处。
 */
public class ZBowAura extends Module {
    /** 默认设置分组。 */
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    /** 目标搜索的最大距离。 */
    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        // 设置项名称
        .name("range")
        // 设置项说明
        .description("The maximum distance at which targets are searched.")
        // 默认值
        .defaultValue(100.0)
        // 允许输入的数值范围
        .range(0.0, 1000.0)
        // 滑条显示的数值范围
        .sliderRange(5.0, 200.0)
        // 构建出设置对象
        .build()
    );

    /** 是否在射击前传送到目标附近。 */
    private final Setting<Boolean> tp = sgGeneral.add(new BoolSetting.Builder()
        // 设置项名称
        .name("tp")
        // 设置项说明
        .description("Teleports close to the target before shooting so arrows land reliably.")
        // 默认开启
        .defaultValue(true)
        // 构建出设置对象
        .build()
    );

    /** 传送到目标旁射击时保持的距离。 */
    private final Setting<Double> tpDistance = sgGeneral.add(new DoubleSetting.Builder()
        // 设置项名称
        .name("tp-distance")
        // 设置项说明
        .description("The distance from the target kept after teleporting.")
        // 默认值
        .defaultValue(8.0)
        // 允许输入的数值范围
        .range(3.0, 30.0)
        // 滑条显示的数值范围
        .sliderRange(4.0, 15.0)
        // 只有开启传送时可见
        .visible(tp::get)
        // 构建出设置对象
        .build()
    );

    /** 蓄力完成所需的 tick 数，20 tick 为满蓄力最大伤害。 */
    private final Setting<Integer> chargeTicks = sgGeneral.add(new IntSetting.Builder()
        // 设置项名称
        .name("charge-ticks")
        // 设置项说明
        .description("How many ticks to draw the bow; 20 ticks is a full charge for maximum damage.")
        // 默认值
        .defaultValue(20)
        // 允许输入的数值范围
        .range(3, 30)
        // 构建出设置对象
        .build()
    );

    /** 射击后的延迟（tick）。 */
    private final Setting<Integer> shootDelay = sgGeneral.add(new IntSetting.Builder()
        // 设置项名称
        .name("shoot-delay")
        // 设置项说明
        .description("The tick delay after a shot before drawing the bow again.")
        // 默认值
        .defaultValue(5)
        // 允许输入的数值范围
        .range(0, 40)
        // 构建出设置对象
        .build()
    );

    /** 是否始终转向目标。 */
    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        // 设置项名称
        .name("rotate")
        // 设置项说明
        .description("Always rotates towards the target while drawing the bow.")
        // 默认开启
        .defaultValue(true)
        // 构建出设置对象
        .build()
    );

    /** 是否自动切换到弓箭。 */
    private final Setting<Boolean> autoSwitch = sgGeneral.add(new BoolSetting.Builder()
        // 设置项名称
        .name("auto-switch")
        // 设置项说明
        .description("Switches to a bow automatically before shooting.")
        // 默认开启
        .defaultValue(true)
        // 构建出设置对象
        .build()
    );

    /** 当前目标玩家。 */
    private Player target;
    /** 当前传送任务的目的地。 */
    private Vec3 travelTarget;
    /** 是否处于回程阶段。 */
    private boolean returning;
    /** 射击后的剩余延迟。 */
    private int shootDelayLeft;
    /** 自动切换前保存的槽位。 */
    private int previousSlot = -1;

    /**
     * 构造方法，完成模块注册信息。
     */
    public ZBowAura() {
        // 调用父类构造：传入分类、模块名与描述
        super(MeteorZCore.CATEGORY, "z-bow-aura", "Aims and fully charges a bow for maximum damage; optionally teleports close so shots land.");
    }

    /**
     * 模块启用时调用，重置状态。
     */
    @Override
    public void onActivate() {
        // 清空任务与传送状态
        target = null;
        travelTarget = null;
        returning = false;
        shootDelayLeft = 0;
        previousSlot = -1;
        TpMover.reset();
        TpMover.resetDetection();
    }

    /**
     * 模块关闭时调用，停止拉弓、切回槽位并中止传送。
     */
    @Override
    public void onDeactivate() {
        // 正在使用物品时松开弓
        if (mc.player != null && mc.gameMode != null && mc.player.isUsingItem()) {
            mc.gameMode.releaseUsingItem(mc.player);
        }

        // 切回原来的槽位
        if (previousSlot != -1) {
            InvUtils.swap(previousSlot, false);
            previousSlot = -1;
        }

        // 中止正在进行的传送
        TpMover.abort();
        travelTarget = null;
        returning = false;
    }

    /**
     * 每个客户端 tick 开始时调用，负责移动、瞄准与射击。
     *
     * @param event tick 前事件
     */
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        // 玩家或世界不存在时直接返回
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
                TpMover.reset();
                return;
            }

            // 目标失效时开始回程
            if (TargetUtils.isBadTarget(target, range.get())) {
                returning = true;
                travelTarget = TpMover.origin();
                return;
            }

            // 已在目标附近，直接射击
            shootStep();
            return;
        }

        // 目标失效时重新搜索血量最低的玩家
        if (TargetUtils.isBadTarget(target, range.get())) {
            target = TargetUtils.getPlayerTarget(range.get(), SortPriority.LowestHealth);
            // 没有可用目标时直接返回
            if (TargetUtils.isBadTarget(target, range.get())) return;
        }

        // 未开启传送时直接在当前位置射击
        if (!tp.get()) {
            shootStep();
            return;
        }

        // 计算目标旁近距离射击的工作点
        Vec3 workPoint = PositionSpoofer.feetNear(target.getBoundingBox(), tpDistance.get());
        // 找不到工作点时直接返回
        if (workPoint == null) return;

        // 前往工作点，之后一直在此处射击
        travelTarget = workPoint;
        returning = false;
        TpMover.moveTo(workPoint);
    }

    /**
     * 执行一次射击流程：切弓、转向、拉满弓、放箭。
     */
    private void shootStep() {
        // 射击后的延迟未结束时先等待
        if (shootDelayLeft > 0) {
            shootDelayLeft--;
            return;
        }

        // 没有箭矢时不处理
        if (!InvUtils.findInHotbar(Items.ARROW).found()) return;

        // 自动切换到弓箭
        if (!mc.player.getMainHandItem().is(Items.BOW)) {
            // 关闭自动切换时直接返回
            if (!autoSwitch.get()) return;

            // 查找快捷栏中的弓
            FindItemResult bow = InvUtils.findInHotbar(Items.BOW);
            if (!bow.found()) return;

            // 记录原槽位并切换
            previousSlot = mc.player.getInventory().getSelectedSlot();
            InvUtils.swap(bow.slot(), false);
            return;
        }

        // 按配置转向目标
        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(target), Rotations.getPitch(target, Target.Body));
        }

        // 尚未开始拉弓时开始使用弓箭
        if (!mc.player.isUsingItem()) {
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            return;
        }

        // 蓄力完成后松开弓射击，满蓄力对应最大伤害
        if (mc.player.getTicksUsingItem() >= chargeTicks.get()) {
            mc.gameMode.releaseUsingItem(mc.player);
            // 切回原来的槽位
            if (previousSlot != -1) {
                InvUtils.swap(previousSlot, false);
                previousSlot = -1;
            }
            // 重置射击后的延迟
            shootDelayLeft = shootDelay.get();
        }
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
     * 返回当前目标名称，用于模块信息栏显示。
     *
     * @return 目标名称，没有目标时返回 null
     */
    @Override
    public String getInfoString() {
        return EntityUtils.getName(target);
    }
}
