package com.eason.zcore.modules;

import com.eason.zcore.MeteorZCore;
import com.eason.zcore.utils.ActionSpoofer;
import com.eason.zcore.utils.PositionSpoofer;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.DamageUtils;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Z TP Anchor Aura 模块。
 * <p>
 * 模仿 Meteor 的 AnchorAura：自动在目标附近放置、充能并引爆重生锚。
 * 区别在于所有动作都可以在远距离完成——动作包会由 {@link ActionSpoofer} 插入伪造坐标，
 * 让服务端误以为玩家就在锚旁边。
 * <p>
 * 选择位置时以“对目标造成的伤害最大”为标准，并限制自身受到的伤害；
 * 需要时还会自动挖开挡路的方块，为放置锚腾出空间。
 */
public class ZTPAnchorAura extends Module implements ActionSpoofer.Policy {
    /** 扫描目标周围方块时使用的半径（水平方向）。 */
    private static final int HORIZONTAL_SCAN = 2;
    /** 扫描目标周围方块时使用的垂直范围。 */
    private static final int VERTICAL_SCAN = 1;

    /** 默认设置分组。 */
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    /** 目标搜索与动作实施的统一距离。 */
    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        // 设置项名称
        .name("range")
        // 设置项说明
        .description("The maximum distance at which targets are searched and actions are performed.")
        // 默认值
        .defaultValue(100.0)
        // 允许输入的数值范围
        .range(0.0, 1000.0)
        // 滑条显示的数值范围
        .sliderRange(5.0, 50.0)
        // 构建出设置对象
        .build()
    );

    /** 对目标造成的最低伤害。 */
    private final Setting<Double> minDamage = sgGeneral.add(new DoubleSetting.Builder()
        // 设置项名称
        .name("min-damage")
        // 设置项说明
        .description("The minimum damage to inflict on the target.")
        // 默认值
        .defaultValue(6.0)
        // 允许输入的数值范围
        .range(0.0, 36.0)
        // 滑条显示的数值范围
        .sliderRange(1.0, 20.0)
        // 构建出设置对象
        .build()
    );

    /** 允许自身受到的最大伤害。 */
    private final Setting<Double> maxSelfDamage = sgGeneral.add(new DoubleSetting.Builder()
        // 设置项名称
        .name("max-self-damage")
        // 设置项说明
        .description("The maximum damage to inflict on yourself.")
        // 默认值
        .defaultValue(6.0)
        // 允许输入的数值范围
        .range(0.0, 36.0)
        // 滑条显示的数值范围
        .sliderRange(0.0, 20.0)
        // 构建出设置对象
        .build()
    );

    /** 是否禁止可能炸死自己的操作。 */
    private final Setting<Boolean> antiSuicide = sgGeneral.add(new BoolSetting.Builder()
        // 设置项名称
        .name("anti-suicide")
        // 设置项说明
        .description("Will not place or detonate anchors if they would kill you.")
        // 默认开启
        .defaultValue(true)
        // 构建出设置对象
        .build()
    );

    /** 是否自动挖开挡路的方块。 */
    private final Setting<Boolean> autoMine = sgGeneral.add(new BoolSetting.Builder()
        // 设置项名称
        .name("auto-mine")
        // 设置项说明
        .description("Mines blocks that are in the way of the anchor.")
        // 默认开启
        .defaultValue(true)
        // 构建出设置对象
        .build()
    );

    /** 两次放置之间的 tick 延迟。 */
    private final Setting<Integer> placeDelay = sgGeneral.add(new IntSetting.Builder()
        // 设置项名称
        .name("place-delay")
        // 设置项说明
        .description("The tick delay between placing anchors.")
        // 默认值
        .defaultValue(3)
        // 允许输入的数值范围
        .range(0, 10)
        // 构建出设置对象
        .build()
    );

    /** 两次引爆之间的 tick 延迟。 */
    private final Setting<Integer> breakDelay = sgGeneral.add(new IntSetting.Builder()
        // 设置项名称
        .name("break-delay")
        // 设置项说明
        .description("The tick delay between detonating anchors.")
        // 默认值
        .defaultValue(1)
        // 允许输入的数值范围
        .range(0, 10)
        // 构建出设置对象
        .build()
    );

    /** 当前目标玩家。 */
    private Player target;
    /** 最佳放置位置。 */
    private final BlockPos.MutableBlockPos bestPlacePos = new BlockPos.MutableBlockPos();
    /** 最佳放置位置预计造成的伤害。 */
    private double bestPlaceDamage;
    /** 最佳引爆位置。 */
    private final BlockPos.MutableBlockPos bestAnchorPos = new BlockPos.MutableBlockPos();
    /** 最佳引爆位置预计造成的伤害。 */
    private double bestBreakDamage;
    /** 放置倒计时。 */
    private int placeDelayLeft;
    /** 引爆倒计时。 */
    private int breakDelayLeft;

    /**
     * 构造方法，完成模块注册信息。
     */
    public ZTPAnchorAura() {
        // 调用父类构造：传入分类、模块名与描述
        super(MeteorZCore.CATEGORY, "z-tp-anchor-aura", "Places and detonates respawn anchors around your target from any distance.");
    }

    /**
     * 模块关闭时清空目标与计时。
     */
    @Override
    public void onDeactivate() {
        // 清空目标
        target = null;
        // 清空倒计时
        placeDelayLeft = 0;
        breakDelayLeft = 0;
    }

    /**
     * 每个客户端 tick 开始时调用，负责搜索目标并执行锚的操作。
     *
     * @param event tick 前事件
     */
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        // 玩家、世界或游戏模式不存在时直接返回
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;

        // 下界中重生锚不会爆炸，直接报错并关闭模块
        if (mc.level.dimension() == Level.NETHER) {
            // 提示玩家该维度不可用
            error("You can't detonate respawn anchors in the Nether, disabling.");
            // 关闭模块
            toggle();
            return;
        }

        // 目标失效时重新搜索最近的玩家目标
        if (TargetUtils.isBadTarget(target, range.get())) {
            target = TargetUtils.getPlayerTarget(range.get(), SortPriority.LowestHealth);
            // 仍然没有合适目标时直接返回
            if (TargetUtils.isBadTarget(target, range.get())) return;
        }

        // 扫描目标附近的锚位置
        scanPositions();
        // 执行选出的操作
        execute();
    }

    /**
     * 扫描目标周围的位置，找出伤害最高且安全的放置点或引爆点。
     */
    private void scanPositions() {
        // 重置本轮的最佳结果
        bestPlaceDamage = 0;
        bestBreakDamage = 0;

        // 以目标脚下方块为基准遍历周围的位置
        BlockPos targetPos = target.blockPosition();

        for (int dx = -HORIZONTAL_SCAN; dx <= HORIZONTAL_SCAN; dx++) {
            for (int dz = -HORIZONTAL_SCAN; dz <= HORIZONTAL_SCAN; dz++) {
                for (int dy = -VERTICAL_SCAN; dy <= VERTICAL_SCAN; dy++) {
                    // 当前候选位置
                    BlockPos pos = targetPos.offset(dx, dy, dz);
                    // 缓存该位置的方块状态
                    BlockState state = mc.level.getBlockState(pos);
                    // 判断该位置是否已经有重生锚
                    boolean isAnchor = state.getBlock() == Blocks.RESPAWN_ANCHOR;

                    // 有锚时评估引爆伤害，没有锚时评估放置后的引爆伤害
                    float targetDamage = DamageUtils.anchorDamage(target, Vec3.atCenterOf(pos));
                    // 达不到最低伤害要求时跳过
                    if (targetDamage < minDamage.get()) continue;
                    // 计算对自己的伤害
                    float selfDamage = DamageUtils.anchorDamage(mc.player, Vec3.atCenterOf(pos));
                    // 自身伤害不安全时跳过
                    if (!isSafe(selfDamage)) continue;

                    if (isAnchor) {
                        // 已存在的锚只需要引爆
                        if (targetDamage > bestBreakDamage) {
                            bestBreakDamage = targetDamage;
                            bestAnchorPos.set(pos);
                        }
                    } else {
                        // 新锚需要可放置或可挖开
                        if (!canPlaceOrMine(pos, state)) continue;
                        if (targetDamage > bestPlaceDamage) {
                            bestPlaceDamage = targetDamage;
                            bestPlacePos.set(pos);
                        }
                    }
                }
            }
        }
    }

    /**
     * 判断某个位置是否可以对目标造成安全且足够的伤害。
     *
     * @param selfDamage 自身受到的伤害
     * @return 安全时返回 true
     */
    private boolean isSafe(float selfDamage) {
        // 自身伤害超过上限时视为不安全
        if (selfDamage > maxSelfDamage.get()) return false;
        // 开启防自杀时，剩余血量必须足够承受这次伤害
        return !antiSuicide.get() || PlayerUtils.getTotalHealth() - selfDamage > 0;
    }

    /**
     * 判断某个位置能否放置锚，必要时允许先挖开。
     *
     * @param pos   候选位置
     * @param state 该位置的方块状态
     * @return 可以放置时返回 true
     */
    private boolean canPlaceOrMine(BlockPos pos, BlockState state) {
        // 位置可以替换时，只要求能正常放置
        if (state.canBeReplaced()) return BlockUtils.canPlace(pos);
        // 位置被占据且开启自动挖掘时，允许先挖开再放置
        return autoMine.get() && BlockUtils.canBreak(pos);
    }

    /**
     * 根据扫描结果执行放置、充能或引爆。
     */
    private void execute() {
        // 查找快捷栏中的重生锚与萤石
        FindItemResult anchor = InvUtils.findInHotbar(Items.RESPAWN_ANCHOR);
        FindItemResult glowStone = InvUtils.findInHotbar(Items.GLOWSTONE);

        // 优先处理可以引爆的锚
        if (bestBreakDamage > 0) {
            // 倒计时未结束时先等待
            if (breakDelayLeft > 0) {
                breakDelayLeft--;
                return;
            }

            // 复制出一个不可变坐标，避免后续状态变化
            BlockPos pos = bestAnchorPos.immutable();
            // 缓存该位置的方块状态
            BlockState state = mc.level.getBlockState(pos);
            // 方块已经不是锚时放弃本次操作
            if (state.getBlock() != Blocks.RESPAWN_ANCHOR) return;

            // 判断操作是否超出距离且本 tick 无法伪造坐标
            if (!canAct(pos)) return;

            // 读取当前充能数量
            int charges = state.getValue(BlockStateProperties.RESPAWN_ANCHOR_CHARGES);

            // 未充能时先用萤石充能
            if (charges == 0) {
                // 快捷栏没有萤石时放弃
                if (!glowStone.found()) return;

                // 切换到萤石并保存原槽位
                InvUtils.swap(glowStone.slot(), true);
                // 右键锚进行充能
                BlockUtils.interact(new BlockHitResult(Vec3.atCenterOf(pos), BlockUtils.getDirection(pos), pos, true), InteractionHand.MAIN_HAND, true);
                // 切回原槽位
                InvUtils.swapBack();
                return;
            }

            // 已充能时用任意非萤石物品右键引爆
            FindItemResult detonator = InvUtils.findInHotbar(item -> !item.getItem().equals(Items.GLOWSTONE));
            // 没有可用物品时放弃
            if (!detonator.found()) return;

            // 切换到引爆物品并保存原槽位
            InvUtils.swap(detonator.slot(), true);
            // 右键锚进行引爆
            BlockUtils.interact(new BlockHitResult(Vec3.atCenterOf(pos), BlockUtils.getDirection(pos), pos, true), InteractionHand.MAIN_HAND, true);
            // 切回原槽位
            InvUtils.swapBack();
            // 客户端立即把锚设为空气，避免后续重复操作
            mc.level.setBlock(pos, mc.level.getFluidState(pos).createLegacyBlock(), 0);
            // 重置引爆倒计时
            breakDelayLeft = breakDelay.get();
            return;
        }

        // 没有可引爆的锚时处理放置
        if (bestPlaceDamage > 0 && anchor.found()) {
            // 倒计时未结束时先等待
            if (placeDelayLeft > 0) {
                placeDelayLeft--;
                return;
            }

            // 复制出一个不可变坐标
            BlockPos pos = bestPlacePos.immutable();
            // 判断操作是否超出距离且本 tick 无法伪造坐标
            if (!canAct(pos)) return;

            // 缓存该位置的方块状态
            BlockState state = mc.level.getBlockState(pos);

            // 位置被占据时先挖开
            if (!state.canBeReplaced()) {
                // 开启自动挖掘时破坏该方块
                if (autoMine.get()) BlockUtils.breakBlock(pos, true);
                return;
            }

            // 放置重生锚
            BlockUtils.place(pos, anchor, false, 50, true, false, true);
            // 重置放置倒计时
            placeDelayLeft = placeDelay.get();
        }
    }

    /**
     * 判断当前 tick 是否可以对指定位置执行动作。
     *
     * @param pos 目标位置
     * @return 距离在服务端范围内，或本 tick 仍可伪造坐标时返回 true
     */
    private boolean canAct(BlockPos pos) {
        // 计算到目标位置的平方距离
        double distanceSq = Vec3.atCenterOf(pos).distanceToSqr(mc.player.getEyePosition());
        // 服务端允许的方块动作距离为 5.5 格，这里取 6 格留出余量
        return distanceSq <= 36.0 || !PositionSpoofer.isLocked();
    }

    /**
     * 判断某类动作是否允许伪造，供 {@link ActionSpoofer} 调用。
     *
     * @param action 动作类型
     * @return 锚的流转需要放置、交互、挖掘与攻击，全部允许
     */
    @Override
    public boolean allow(ActionSpoofer.Action action) {
        return true;
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
     * 返回当前目标名称，用于模块信息栏显示。
     *
     * @return 目标名称，没有目标时返回 null
     */
    @Override
    public String getInfoString() {
        return EntityUtils.getName(target);
    }
}
