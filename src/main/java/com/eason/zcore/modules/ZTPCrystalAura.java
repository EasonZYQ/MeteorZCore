package com.eason.zcore.modules;

import com.eason.zcore.MeteorZCore;
import com.eason.zcore.utils.ActionSpoofer;
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
import meteordevelopment.meteorclient.utils.entity.DamageUtils;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.SwingMode;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Z TP Crystal Aura 模块。
 * <p>
 * 模仿 Meteor 的 CrystalAura：自动在目标附近放置水晶并引爆。
 * 与普通版本的区别是：目标超出服务端允许距离时，会先由 {@link TpMover} 把服务端位置送到
 * 水晶旁（自动在直传与分段传送之间选择），完成放置/引爆后再回到原处。
 * <p>
 * 选择位置时以“对目标造成的伤害最大”为标准，并按传送后的站位计算自身伤害，
 * 保证既不会炸到自己，也能在必要时自动补放黑曜石、挖开挡路方块。
 */
public class ZTPCrystalAura extends Module implements ActionSpoofer.Policy {
    /** 扫描目标周围方块时使用的半径（水平方向）。 */
    private static final int HORIZONTAL_SCAN = 2;
    /** 扫描目标周围方块时使用的垂直范围。 */
    private static final int VERTICAL_SCAN = 1;
    /** 搜索已有水晶时使用的范围。 */
    private static final double CRYSTAL_SEARCH = 6.0;
    /** 传送到工作点时与目标方块保持的最大距离。 */
    private static final double TP_OFFSET = 5.0;

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
        .sliderRange(5.0, 100.0)
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
        .description("The maximum damage to inflict on yourself, measured at the teleport position.")
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
        .description("Will not place or detonate crystals if they would kill you.")
        // 默认开启
        .defaultValue(true)
        // 构建出设置对象
        .build()
    );

    /** 是否自动补放黑曜石底座。 */
    private final Setting<Boolean> autoObsidian = sgGeneral.add(new BoolSetting.Builder()
        // 设置项名称
        .name("auto-obsidian")
        // 设置项说明
        .description("Places obsidian first when the crystal has no valid base.")
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
        .description("Mines blocks that are in the way of the crystal or its base.")
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
        .description("The tick delay between placing crystals.")
        // 默认值
        .defaultValue(2)
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
        .description("The tick delay between detonating crystals.")
        // 默认值
        .defaultValue(2)
        // 允许输入的数值范围
        .range(0, 10)
        // 构建出设置对象
        .build()
    );

    /** 当前目标玩家。 */
    private Player target;
    /** 最佳水晶底座位置。 */
    private final BlockPos.MutableBlockPos bestBasePos = new BlockPos.MutableBlockPos();
    /** 最佳放置点对应的传送位置。 */
    private Vec3 bestPlaceFeet;
    /** 最佳放置位置预计造成的伤害。 */
    private double bestPlaceDamage;
    /** 最佳引爆水晶。 */
    private EndCrystal bestCrystal;
    /** 最佳引爆水晶对应的传送位置。 */
    private Vec3 bestCrystalFeet;
    /** 最佳引爆位置预计造成的伤害。 */
    private double bestBreakDamage;
    /** 放置倒计时。 */
    private int placeDelayLeft;
    /** 引爆倒计时。 */
    private int breakDelayLeft;

    /** 当前传送任务的目的地。 */
    private Vec3 travelTarget;
    /** 是否处于回程阶段。 */
    private boolean returning;
    /** 是否正在执行一个工作任务。 */
    private boolean workActive;
    /** 当前工作任务的底座位置。 */
    private BlockPos workBase;
    /** 当前工作任务的引爆水晶。 */
    private EndCrystal workCrystal;

    /**
     * 构造方法，完成模块注册信息。
     */
    public ZTPCrystalAura() {
        // 调用父类构造：传入分类、模块名与描述
        super(MeteorZCore.CATEGORY, "z-tp-crystal-aura", "Places and detonates end crystals around your target from any distance.");
    }

    /**
     * 模块启用时调用，重置传送引擎状态。
     */
    @Override
    public void onActivate() {
        // 清空任务与传送状态
        clearWork();
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
        clearWork();
        target = null;
    }

    /**
     * 清空当前工作任务。
     */
    private void clearWork() {
        // 清空传送与工作字段
        travelTarget = null;
        returning = false;
        workActive = false;
        workBase = null;
        workCrystal = null;
        // 清空倒计时
        placeDelayLeft = 0;
        breakDelayLeft = 0;
    }

    /**
     * 每个客户端 tick 开始时调用，负责移动、搜索目标并执行水晶操作。
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
                clearWork();
                TpMover.reset();
                return;
            }

            // 到达工作点，执行一步操作
            boolean done = doWork();
            // 任务完成时开始回程
            if (done) {
                returning = true;
                travelTarget = TpMover.origin();
            }
            return;
        }

        // 目标失效时重新搜索血量最低的玩家目标
        if (TargetUtils.isBadTarget(target, range.get())) {
            target = TargetUtils.getPlayerTarget(range.get(), SortPriority.LowestHealth);
            // 仍然没有合适目标时直接返回
            if (TargetUtils.isBadTarget(target, range.get())) return;
        }

        // 扫描目标附近的水晶位置与已有水晶
        scanPositions();

        // 优先处理已有水晶的引爆
        if (bestBreakDamage > 0 && bestCrystal != null && bestCrystalFeet != null) {
            workCrystal = bestCrystal;
            workBase = null;
            workActive = true;
            travelTarget = bestCrystalFeet;
            returning = false;
            TpMover.moveTo(travelTarget);
            return;
        }

        // 没有可引爆水晶时处理放置
        if (bestPlaceDamage > 0 && bestPlaceFeet != null) {
            workBase = bestBasePos.immutable();
            workCrystal = null;
            workActive = true;
            travelTarget = bestPlaceFeet;
            returning = false;
            TpMover.moveTo(travelTarget);
        }
    }

    /**
     * 扫描目标周围的位置，找出伤害最高且安全的放置点或引爆目标。
     */
    private void scanPositions() {
        // 重置本轮的最佳结果
        bestPlaceDamage = 0;
        bestPlaceFeet = null;
        bestBreakDamage = 0;
        bestCrystal = null;
        bestCrystalFeet = null;

        // 以目标脚下方块为基准遍历周围的位置
        BlockPos targetPos = target.blockPosition();

        for (int dx = -HORIZONTAL_SCAN; dx <= HORIZONTAL_SCAN; dx++) {
            for (int dz = -HORIZONTAL_SCAN; dz <= HORIZONTAL_SCAN; dz++) {
                for (int dy = -VERTICAL_SCAN; dy <= VERTICAL_SCAN; dy++) {
                    // 当前候选底座位置
                    BlockPos base = targetPos.offset(dx, dy, dz);
                    // 水晶放在底座上方一格
                    BlockPos crystalPos = base.above();

                    // 底座方块状态
                    BlockState baseState = mc.level.getBlockState(base);
                    // 水晶位置的方块状态
                    BlockState crystalState = mc.level.getBlockState(crystalPos);

                    // 判断底座是否已经可以放水晶
                    boolean baseReady = baseState.is(Blocks.OBSIDIAN) || baseState.is(Blocks.BEDROCK);

                    // 底座未就绪时需要补放黑曜石
                    if (!baseReady) {
                        // 关闭自动补放时跳过
                        if (!autoObsidian.get()) continue;

                        if (baseState.canBeReplaced()) {
                            // 位置可替换时必须能正常放置
                            if (!BlockUtils.canPlace(base)) continue;
                        } else if (!autoMine.get() || !BlockUtils.canBreak(base)) {
                            // 位置被占据时需要能够挖开
                            continue;
                        }
                    }

                    // 水晶位置必须可替换，或者能够挖开
                    if (!crystalState.canBeReplaced() && !(autoMine.get() && BlockUtils.canBreak(crystalPos))) continue;

                    // 计算工作点：站到该点后既能放水晶又不至于离得太远
                    Vec3 feet = PositionSpoofer.feetNear(new AABB(base), TP_OFFSET);
                    // 找不到站立位置时跳过
                    if (feet == null) continue;

                    // 计算水晶爆炸对目标造成的伤害
                    Vec3 center = Vec3.atCenterOf(crystalPos);
                    float targetDamage = DamageUtils.crystalDamage(target, center);
                    // 达不到最低伤害要求时跳过
                    if (targetDamage < minDamage.get()) continue;
                    // 计算在该工作点站位时对自己的伤害
                    float selfDamage = selfDamageAt(feet, center);
                    // 自身伤害不安全时跳过
                    if (!isSafe(selfDamage)) continue;

                    // 记录伤害最高的底座位置
                    if (targetDamage > bestPlaceDamage) {
                        bestPlaceDamage = targetDamage;
                        bestBasePos.set(base);
                        bestPlaceFeet = feet;
                    }
                }
            }
        }

        // 搜索目标附近已经存在的水晶，作为引爆候选
        for (EndCrystal crystal : mc.level.getEntitiesOfClass(EndCrystal.class, new AABB(targetPos).inflate(CRYSTAL_SEARCH))) {
            // 已经移除的水晶跳过
            if (!crystal.isAlive()) continue;

            // 计算该水晶的工作点
            Vec3 feet = PositionSpoofer.feetNear(crystal.getBoundingBox(), TP_OFFSET);
            // 找不到站立位置时跳过
            if (feet == null) continue;

            // 计算该水晶爆炸对目标造成的伤害
            float targetDamage = DamageUtils.crystalDamage(target, crystal.position());
            // 达不到最低伤害要求时跳过
            if (targetDamage < minDamage.get()) continue;
            // 计算在该工作点站位时对自己的伤害
            float selfDamage = selfDamageAt(feet, crystal.position());
            // 自身伤害不安全时跳过
            if (!isSafe(selfDamage)) continue;

            // 记录伤害最高的水晶
            if (targetDamage > bestBreakDamage) {
                bestBreakDamage = targetDamage;
                bestCrystal = crystal;
                bestCrystalFeet = feet;
            }
        }
    }

    /**
     * 计算玩家站在指定脚部坐标时受到的爆炸伤害。
     *
     * @param feet       脚部坐标
     * @param explosion  爆炸位置
     * @return 预计伤害
     */
    private float selfDamageAt(Vec3 feet, Vec3 explosion) {
        // 计算眼睛高度
        double eyeHeight = mc.player.getEyeY() - mc.player.getY();
        // 把眼睛位置换算到工作点
        Vec3 eye = feet.add(0.0, eyeHeight, 0.0);
        // 把玩家碰撞箱平移到工作点
        AABB box = mc.player.getBoundingBox().move(feet.subtract(mc.player.position()));
        // 用工作点的眼睛与碰撞箱计算水晶爆炸伤害
        return DamageUtils.crystalDamage(mc.player, eye, box, explosion, DamageUtils.HIT_FACTORY);
    }

    /**
     * 判断自身伤害是否在允许范围内。
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
     * 在到达工作点后执行一步水晶操作。
     *
     * @return 该工作任务完成时返回 true
     */
    private boolean doWork() {
        // 没有工作任务时直接结束
        if (!workActive) return true;

        // 引爆任务：攻击目标水晶
        if (workCrystal != null) {
            // 倒计时未结束时先等待
            if (breakDelayLeft > 0) {
                breakDelayLeft--;
                return false;
            }

            // 水晶仍然存在时发送攻击包
            if (workCrystal.isAlive()) {
                mc.gameMode.attack(mc.player, workCrystal);
                SwingMode.Both.attackSwing(InteractionHand.MAIN_HAND);
            }

            // 重置倒计时并结束任务
            breakDelayLeft = breakDelay.get();
            workCrystal = null;
            workActive = false;
            return true;
        }

        // 放置任务：处理底座与水晶体位置
        if (workBase == null) {
            workActive = false;
            return true;
        }

        BlockPos base = workBase;
        BlockPos crystalPos = base.above();

        // 水晶位置被占据时先挖开
        if (!mc.level.getBlockState(crystalPos).canBeReplaced()) {
            if (autoMine.get() && BlockUtils.canBreak(crystalPos)) {
                BlockUtils.breakBlock(crystalPos, true);
                return false;
            }

            workBase = null;
            workActive = false;
            return true;
        }

        // 缓存底座方块状态
        BlockState baseState = mc.level.getBlockState(base);

        // 底座不是黑曜石或基岩时需要先补放黑曜石
        if (!baseState.is(Blocks.OBSIDIAN) && !baseState.is(Blocks.BEDROCK)) {
            // 放置倒计时未结束时先等待
            if (placeDelayLeft > 0) {
                placeDelayLeft--;
                return false;
            }

            // 底座被占据时先挖开
            if (!baseState.canBeReplaced()) {
                if (autoMine.get() && BlockUtils.canBreak(base)) {
                    BlockUtils.breakBlock(base, true);
                    return false;
                }

                workBase = null;
                workActive = false;
                return true;
            }

            // 关闭自动补放或没有黑曜石时放弃
            if (!autoObsidian.get()) {
                workBase = null;
                workActive = false;
                return true;
            }

            FindItemResult obsidian = InvUtils.findInHotbar(Items.OBSIDIAN);
            if (!obsidian.found()) {
                workBase = null;
                workActive = false;
                return true;
            }

            // 放置黑曜石，下一 tick 再放水晶
            BlockUtils.place(base, obsidian, false, 50, true, false, true);
            placeDelayLeft = placeDelay.get();
            return false;
        }

        // 放置倒计时未结束时先等待
        if (placeDelayLeft > 0) {
            placeDelayLeft--;
            return false;
        }

        // 查找快捷栏中的末地水晶
        FindItemResult crystal = InvUtils.findInHotbar(Items.END_CRYSTAL);
        if (!crystal.found()) {
            workBase = null;
            workActive = false;
            return true;
        }

        // 在底座上方放置末地水晶
        BlockUtils.place(crystalPos, crystal, false, 50, true, false, true);
        placeDelayLeft = placeDelay.get();
        workBase = null;
        workActive = false;
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
     * 判断某类动作是否允许伪造，供 {@link ActionSpoofer} 调用。
     *
     * @param action 动作类型
     * @return 水晶的流转需要放置、交互、挖掘与攻击，全部允许
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
