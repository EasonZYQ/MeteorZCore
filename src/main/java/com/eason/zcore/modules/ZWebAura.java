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
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Z Web Aura 模块。
 * <p>
 * 参考 GCore 的 GWebAura：在目标脚下与头部位置快速放置蜘蛛网限制其移动，
 * 可选先在目标脚下补放黑曜石。远距离目标由 {@link TpMover} 送到目标旁后操作，完成再回程。
 */
public class ZWebAura extends Module {
    /** 传送到工作点时与目标碰撞箱保持的最大距离。 */
    private static final double TP_OFFSET = 5.0;

    /** 默认设置分组。 */
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    /** 目标搜索与动作实施的最大距离。 */
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
        .sliderRange(5.0, 100.0)
        // 构建出设置对象
        .build()
    );

    /** 两次放置之间的 tick 延迟。 */
    private final Setting<Integer> placeDelay = sgGeneral.add(new IntSetting.Builder()
        // 设置项名称
        .name("place-delay")
        // 设置项说明
        .description("The tick delay between placements.")
        // 默认值
        .defaultValue(2)
        // 允许输入的数值范围
        .range(0, 10)
        // 构建出设置对象
        .build()
    );

    /** 放置蜘蛛网的数量（脚下与头部）。 */
    private final Setting<Integer> webAmount = sgGeneral.add(new IntSetting.Builder()
        // 设置项名称
        .name("web-amount")
        // 设置项说明
        .description("How many cobwebs to place on the target (feet and head).")
        // 默认值
        .defaultValue(2)
        // 允许输入的数值范围
        .range(1, 2)
        // 构建出设置对象
        .build()
    );

    /** 是否先在目标脚下补放黑曜石。 */
    private final Setting<Boolean> obsidian = sgGeneral.add(new BoolSetting.Builder()
        // 设置项名称
        .name("obsidian")
        // 设置项说明
        .description("Places obsidian below the target first.")
        // 默认开启
        .defaultValue(true)
        // 构建出设置对象
        .build()
    );

    /** 当前目标玩家。 */
    private Player target;
    /** 待放置位置与对应物品类型（true 表示黑曜石）。 */
    private final List<BlockPos> plan = new ArrayList<>();
    /** 每个计划位置对应的物品是否为黑曜石。 */
    private final List<Boolean> planObsidian = new ArrayList<>();
    /** 当前传送任务的目的地。 */
    private Vec3 travelTarget;
    /** 是否处于回程阶段。 */
    private boolean returning;
    /** 放置倒计时。 */
    private int placeDelayLeft;

    /**
     * 构造方法，完成模块注册信息。
     */
    public ZWebAura() {
        // 调用父类构造：传入分类、模块名与描述
        super(MeteorZCore.CATEGORY, "z-web-aura", "Places cobwebs on your target from any distance.");
    }

    /**
     * 模块启用时调用，重置状态。
     */
    @Override
    public void onActivate() {
        // 清空任务与传送状态
        clearTask();
        TpMover.reset();
        TpMover.resetDetection();
    }

    /**
     * 模块关闭时调用，中止传送并清空任务。
     */
    @Override
    public void onDeactivate() {
        // 中止正在进行的传送
        TpMover.abort();
        clearTask();
        target = null;
    }

    /**
     * 清空当前任务。
     */
    private void clearTask() {
        // 清空计划、传送与延迟
        plan.clear();
        planObsidian.clear();
        travelTarget = null;
        returning = false;
        placeDelayLeft = 0;
    }

    /**
     * 每个客户端 tick 开始时调用，负责移动与放置。
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

            // 回程完成，结束任务
            if (returning) {
                clearTask();
                TpMover.reset();
                return;
            }

            // 到达工作点后执行放置
            boolean done = doPlace();
            // 全部放完后开始回程
            if (done) {
                returning = true;
                travelTarget = TpMover.origin();
            }
            return;
        }

        // 目标失效时重新搜索血量最低的玩家
        if (TargetUtils.isBadTarget(target, range.get())) {
            target = TargetUtils.getPlayerTarget(range.get(), SortPriority.LowestHealth);
            // 没有合适目标时直接返回
            if (TargetUtils.isBadTarget(target, range.get())) return;
        }

        // 生成目标周围需要放置的位置
        buildPlan();
        // 没有可放置的位置时直接返回
        if (plan.isEmpty()) return;

        // 计算目标旁的工作点
        Vec3 feet = PositionSpoofer.feetNear(target.getBoundingBox(), TP_OFFSET);
        // 找不到工作点时放弃
        if (feet == null) {
            clearTask();
            return;
        }

        // 前往工作点
        travelTarget = feet;
        returning = false;
        TpMover.moveTo(feet);
    }

    /**
     * 生成目标周围需要放置的位置。
     */
    private void buildPlan() {
        // 清空上一轮的计划
        plan.clear();
        planObsidian.clear();

        BlockPos feet = target.blockPosition();

        // 先在目标脚下补放黑曜石
        if (obsidian.get()) {
            BlockPos below = feet.below();
            if (needsPlace(below)) {
                plan.add(below);
                planObsidian.add(true);
            }
        }

        // 目标脚下放蜘蛛网
        if (needsPlace(feet)) {
            plan.add(feet);
            planObsidian.add(false);
        }

        // 配置数量大于 1 时在头部位置再放一个蜘蛛网
        if (webAmount.get() > 1) {
            BlockPos head = feet.above();
            if (needsPlace(head)) {
                plan.add(head);
                planObsidian.add(false);
            }
        }
    }

    /**
     * 判断某个位置是否需要且可以放置。
     *
     * @param pos 候选位置
     * @return 需要放置时返回 true
     */
    private boolean needsPlace(BlockPos pos) {
        // 方块状态
        var state = mc.level.getBlockState(pos);
        // 只有可替换且能正常放置的位置才处理
        return state.canBeReplaced() && BlockUtils.canPlace(pos);
    }

    /**
     * 在工作点执行一次放置。
     *
     * @return 计划完成时返回 true
     */
    private boolean doPlace() {
        // 清理已经不需要处理的位置
        for (int i = plan.size() - 1; i >= 0; i--) {
            if (!needsPlace(plan.get(i))) {
                plan.remove(i);
                planObsidian.remove(i);
            }
        }

        // 计划为空时视为完成
        if (plan.isEmpty()) return true;

        // 延迟未结束时先等待
        if (placeDelayLeft > 0) {
            placeDelayLeft--;
            return false;
        }

        // 逐项放置计划中的方块
        while (!plan.isEmpty()) {
            BlockPos pos = plan.removeFirst();
            boolean useObsidian = planObsidian.removeFirst();
            // 位置不再需要时跳过
            if (!needsPlace(pos)) continue;

            // 根据类型查找物品
            FindItemResult item = InvUtils.findInHotbar(useObsidian ? Items.OBSIDIAN : Items.COBWEB);
            // 没有对应物品时结束任务
            if (!item.found()) return true;

            // 放置方块
            BlockUtils.place(pos, item, false, 50, true, false, true);
            // 重置延迟并等待下一轮
            placeDelayLeft = placeDelay.get();
            return plan.isEmpty();
        }

        return true;
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
