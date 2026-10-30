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
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.player.SwingMode;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Z Piston Aura 模块。
 * <p>
 * 参考 GCore 的 GTpPistonAura：在目标附近放置“活塞 + 黑曜石 + 末地水晶 + 红石块”，
 * 利用活塞把水晶向前推一格，让爆炸点更贴近目标（或避开遮挡），然后引爆。
 * <p>
 * 动作同样支持远距离：由 {@link TpMover} 把服务端位置送到工作点，
 * 完成整条活塞链后再回到原处。活塞朝向通过临时发送旋转包控制，
 * 保证服务端放置出的活塞方向与预期一致。
 */
public class ZPistonAura extends Module implements ActionSpoofer.Policy {
    /** 扫描目标周围方块时使用的半径（水平方向）。 */
    private static final int HORIZONTAL_SCAN = 2;
    /** 扫描目标周围方块时使用的垂直范围。 */
    private static final int VERTICAL_SCAN = 1;
    /** 传送到工作点时与目标方块保持的最大距离。 */
    private static final double TP_OFFSET = 5.0;
    /** 寻找被推动后的水晶时使用的搜索半径。 */
    private static final double CRYSTAL_SEARCH = 3.0;
    /** 红石块放下后等待活塞伸出的 tick 数。 */
    private static final int PUSH_WAIT_TICKS = 2;

    /** 工作阶段：准备黑曜石底座。 */
    private static final int STAGE_BASE = 0;
    /** 工作阶段：放置活塞。 */
    private static final int STAGE_PISTON = 1;
    /** 工作阶段：放置末地水晶。 */
    private static final int STAGE_CRYSTAL = 2;
    /** 工作阶段：放置红石块给活塞供能。 */
    private static final int STAGE_REDSTONE = 3;
    /** 工作阶段：等待活塞把水晶推开。 */
    private static final int STAGE_PUSH = 4;
    /** 工作阶段：引爆被推动后的水晶。 */
    private static final int STAGE_ATTACK = 5;

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
        .description("Will not use the piston setup if it would kill you.")
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
        .description("Mines blocks that are in the way of the setup.")
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
        .description("The tick delay between placements.")
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
        .description("The tick delay before detonating the pushed crystal.")
        // 默认值
        .defaultValue(1)
        // 允许输入的数值范围
        .range(0, 10)
        // 构建出设置对象
        .build()
    );

    /** 当前目标玩家。 */
    private Player target;
    /** 当前最优的活塞方案。 */
    private PistonPlan bestPlan;
    /** 当前最优方案预计造成的伤害。 */
    private double bestDamage;

    /** 当前传送任务的目的地。 */
    private Vec3 travelTarget;
    /** 是否处于回程阶段。 */
    private boolean returning;
    /** 是否正在执行一个工作任务。 */
    private boolean workActive;
    /** 当前执行的活塞方案。 */
    private PistonPlan plan;
    /** 当前工作阶段。 */
    private int stage;
    /** 活塞是否已经放置。 */
    private boolean pistonPlaced;
    /** 活塞推开后的等待计数。 */
    private int pushWaitLeft;
    /** 放置倒计时。 */
    private int placeDelayLeft;
    /** 引爆倒计时。 */
    private int breakDelayLeft;

    /**
     * 构造方法，完成模块注册信息。
     */
    public ZPistonAura() {
        // 调用父类构造：传入分类、模块名与描述
        super(MeteorZCore.CATEGORY, "z-piston-aura", "Places a piston setup around your target and pushes a crystal into it from any distance.");
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
        plan = null;
        stage = STAGE_BASE;
        pistonPlaced = false;
        pushWaitLeft = 0;
        placeDelayLeft = 0;
        breakDelayLeft = 0;
    }

    /**
     * 每个客户端 tick 开始时调用，负责移动、搜索目标并执行活塞链。
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

        // 扫描目标附近的活塞方案
        scanPlans();

        // 没有合适方案时直接返回
        if (bestPlan == null) return;

        // 开始执行最优方案
        plan = bestPlan;
        stage = STAGE_BASE;
        pistonPlaced = false;
        pushWaitLeft = 0;
        workActive = true;
        travelTarget = plan.workFeet;
        returning = false;
        TpMover.moveTo(travelTarget);
    }

    /**
     * 扫描目标周围，寻找能造成最高伤害的活塞推水晶方案。
     */
    private void scanPlans() {
        // 重置本轮的最佳结果
        bestPlan = null;
        bestDamage = 0;

        // 以目标脚下方块为基准遍历周围的位置
        BlockPos targetPos = target.blockPosition();

        for (int dx = -HORIZONTAL_SCAN; dx <= HORIZONTAL_SCAN; dx++) {
            for (int dz = -HORIZONTAL_SCAN; dz <= HORIZONTAL_SCAN; dz++) {
                for (int dy = -VERTICAL_SCAN; dy <= VERTICAL_SCAN; dy++) {
                    // 水晶初始位置
                    BlockPos crystalPos = targetPos.offset(dx, dy, dz);

                    // 水晶位置必须为空或可替换
                    if (!mc.level.getBlockState(crystalPos).canBeReplaced()) continue;

                    // 底座位置
                    BlockPos basePos = crystalPos.below();
                    BlockState baseState = mc.level.getBlockState(basePos);
                    // 底座必须是黑曜石或基岩，否则需要能够补放黑曜石
                    boolean baseReady = baseState.is(Blocks.OBSIDIAN) || baseState.is(Blocks.BEDROCK);
                    if (!baseReady && !(baseState.canBeReplaced() && BlockUtils.canPlace(basePos))) continue;

                    // 计算工作点
                    Vec3 feet = PositionSpoofer.feetNear(new AABB(crystalPos), TP_OFFSET);
                    if (feet == null) continue;

                    // 遍历四个水平方向作为活塞朝向
                    for (Direction facing : Direction.Plane.HORIZONTAL) {
                        // 被推动后的水晶位置
                        BlockPos pushPos = crystalPos.relative(facing);
                        // 推动目标位置必须为空或可替换
                        if (!mc.level.getBlockState(pushPos).canBeReplaced()) continue;

                        // 活塞位置：水晶的反方向
                        BlockPos pistonPos = crystalPos.relative(facing.getOpposite());
                        // 活塞位置必须可以放置
                        if (!BlockUtils.canPlace(pistonPos)) continue;

                        // 寻找可以给活塞供能的位置
                        BlockPos redstonePos = findRedstonePos(pistonPos, facing);
                        if (redstonePos == null) continue;

                        // 计算爆炸伤害：水晶被推到 pushPos 后爆炸
                        Vec3 explosion = Vec3.atCenterOf(pushPos);
                        float targetDamage = DamageUtils.crystalDamage(target, explosion);
                        // 达不到最低伤害要求时跳过
                        if (targetDamage < minDamage.get()) continue;
                        // 计算在该工作点站位时对自己的伤害
                        float selfDamage = selfDamageAt(feet, explosion);
                        // 自身伤害不安全时跳过
                        if (!isSafe(selfDamage)) continue;

                        // 记录伤害最高的方案
                        if (targetDamage > bestDamage) {
                            bestDamage = targetDamage;
                            bestPlan = new PistonPlan(crystalPos, pushPos, pistonPos, redstonePos, basePos, facing, feet);
                        }
                    }
                }
            }
        }
    }

    /**
     * 在活塞四周寻找一个可以放置红石块的位置。
     *
     * @param pistonPos 活塞位置
     * @param facing    活塞朝向，正前方的位置被水晶占用
     * @return 可放置红石块的位置，没有时返回 null
     */
    private BlockPos findRedstonePos(BlockPos pistonPos, Direction facing) {
        // 优先尝试水平方向，其次上下方向
        for (Direction side : Direction.values()) {
            // 跳过活塞正前方，那里的位置留给水晶
            if (side == facing) continue;

            BlockPos pos = pistonPos.relative(side);
            // 该位置必须可以放置红石块
            if (BlockUtils.canPlace(pos)) return pos;
        }

        return null;
    }

    /**
     * 计算玩家站在指定脚部坐标时受到的爆炸伤害。
     *
     * @param feet      脚部坐标
     * @param explosion 爆炸位置
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
     * 在到达工作点后按阶段执行活塞链。
     *
     * @return 整个方案完成时返回 true
     */
    private boolean doWork() {
        // 没有方案时直接结束
        if (!workActive || plan == null) return true;

        switch (stage) {
            case STAGE_BASE:
                return doBase();
            case STAGE_PISTON:
                return doPiston();
            case STAGE_CRYSTAL:
                return doCrystal();
            case STAGE_REDSTONE:
                return doRedstone();
            case STAGE_PUSH:
                return doPush();
            case STAGE_ATTACK:
                return doAttack();
            default:
                return true;
        }
    }

    /**
     * 准备黑曜石底座。
     *
     * @return 整个方案完成时返回 true
     */
    private boolean doBase() {
        // 底座已经是黑曜石或基岩，直接进入下一步
        if (mc.level.getBlockState(plan.basePos).is(Blocks.OBSIDIAN) || mc.level.getBlockState(plan.basePos).is(Blocks.BEDROCK)) {
            stage = STAGE_PISTON;
            return false;
        }

        // 底座被占据时先挖开
        if (!mc.level.getBlockState(plan.basePos).canBeReplaced()) {
            if (autoMine.get() && BlockUtils.canBreak(plan.basePos)) {
                BlockUtils.breakBlock(plan.basePos, true);
                return false;
            }

            return true;
        }

        // 放置倒计时未结束时先等待
        if (placeDelayLeft > 0) {
            placeDelayLeft--;
            return false;
        }

        // 查找快捷栏中的黑曜石
        FindItemResult obsidian = InvUtils.findInHotbar(Items.OBSIDIAN);
        if (!obsidian.found()) return true;

        // 放置黑曜石
        BlockUtils.place(plan.basePos, obsidian, false, 50, true, false, true);
        placeDelayLeft = placeDelay.get();
        return false;
    }

    /**
     * 放置活塞，并保证朝向正确。
     *
     * @return 整个方案完成时返回 true
     */
    private boolean doPiston() {
        // 活塞已放置，进入下一步
        if (pistonPlaced) {
            stage = STAGE_CRYSTAL;
            return false;
        }

        // 放置倒计时未结束时先等待
        if (placeDelayLeft > 0) {
            placeDelayLeft--;
            return false;
        }

        // 查找快捷栏中的活塞
        FindItemResult piston = InvUtils.findInHotbar(Items.PISTON);
        if (!piston.found()) return true;

        // 放置活塞，并临时转向让活塞朝向我们计算的方向
        placeWithRotation(plan.pistonPos, piston, plan.facing.getOpposite());
        pistonPlaced = true;
        placeDelayLeft = placeDelay.get();
        return false;
    }

    /**
     * 放置末地水晶。
     *
     * @return 整个方案完成时返回 true
     */
    private boolean doCrystal() {
        // 水晶位置不可替换时放弃方案
        if (!mc.level.getBlockState(plan.crystalPos).canBeReplaced()) return true;

        // 放置倒计时未结束时先等待
        if (placeDelayLeft > 0) {
            placeDelayLeft--;
            return false;
        }

        // 查找快捷栏中的末地水晶
        FindItemResult crystal = InvUtils.findInHotbar(Items.END_CRYSTAL);
        if (!crystal.found()) return true;

        // 在底座上方放置水晶
        BlockUtils.place(plan.crystalPos, crystal, false, 50, true, false, true);
        placeDelayLeft = placeDelay.get();
        stage = STAGE_REDSTONE;
        return false;
    }

    /**
     * 放置红石块给活塞供能。
     *
     * @return 整个方案完成时返回 true
     */
    private boolean doRedstone() {
        // 放置倒计时未结束时先等待
        if (placeDelayLeft > 0) {
            placeDelayLeft--;
            return false;
        }

        // 查找快捷栏中的红石块
        FindItemResult redstone = InvUtils.findInHotbar(Items.REDSTONE_BLOCK);
        if (!redstone.found()) return true;

        // 放置红石块，活塞会在下一 tick 伸出并推动水晶
        BlockUtils.place(plan.redstonePos, redstone, false, 50, true, false, true);
        placeDelayLeft = placeDelay.get();
        pushWaitLeft = PUSH_WAIT_TICKS;
        stage = STAGE_PUSH;
        return false;
    }

    /**
     * 等待活塞把水晶推开。
     *
     * @return 整个方案完成时返回 true
     */
    private boolean doPush() {
        // 等待计数未结束时先等待
        if (pushWaitLeft > 0) {
            pushWaitLeft--;
            return false;
        }

        // 进入引爆阶段
        stage = STAGE_ATTACK;
        return false;
    }

    /**
     * 引爆被推动后的水晶。
     *
     * @return 整个方案完成时返回 true
     */
    private boolean doAttack() {
        // 引爆倒计时未结束时先等待
        if (breakDelayLeft > 0) {
            breakDelayLeft--;
            return false;
        }

        // 在推动后的位置附近寻找水晶实体
        EndCrystal crystal = findCrystal(plan.pushPos);
        // 没有找到时结束方案
        if (crystal == null) return true;

        // 发送攻击包引爆水晶
        mc.gameMode.attack(mc.player, crystal);
        // 播放客户端与服务端的挥手动画
        SwingMode.Both.attackSwing(InteractionHand.MAIN_HAND);

        // 重置引爆倒计时并结束方案
        breakDelayLeft = breakDelay.get();
        return true;
    }

    /**
     * 在指定位置附近寻找水晶实体。
     *
     * @param pos 目标方块位置
     * @return 找到的水晶，没有时返回 null
     */
    private EndCrystal findCrystal(BlockPos pos) {
        // 搜索位置附近的末地水晶
        for (EndCrystal crystal : mc.level.getEntitiesOfClass(EndCrystal.class, new AABB(pos).inflate(CRYSTAL_SEARCH))) {
            // 只返回仍然存活的水晶
            if (crystal.isAlive()) return crystal;
        }

        return null;
    }

    /**
     * 先临时转向再放置方块，保证活塞朝向正确。
     *
     * @param pos      放置位置
     * @param item     物品
     * @param lookDir  需要看向的方向（决定活塞朝向）
     */
    private void placeWithRotation(BlockPos pos, FindItemResult item, Direction lookDir) {
        // 保存玩家原来的视角
        float oldYaw = mc.player.getYRot();
        float oldPitch = mc.player.getXRot();

        // 计算看向目标方向时的水平角
        Vec3 eye = mc.player.getEyePosition();
        Vec3 lookPoint = eye.add(lookDir.getStepX(), 0.0, lookDir.getStepZ());
        float yaw = (float) Rotations.getYaw(lookPoint);

        // 临时设置客户端视角
        mc.player.setYRot(yaw);
        mc.player.setXRot(0.0F);
        // 立即发送旋转包，服务端会先更新朝向再处理放置
        mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(yaw, 0.0F, mc.player.onGround(), mc.player.horizontalCollision));

        // 放置方块
        BlockUtils.place(pos, item, false, 50, true, false, true);

        // 还原客户端视角，避免影响玩家操作
        mc.player.setYRot(oldYaw);
        mc.player.setXRot(oldPitch);
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
     * @return 活塞链需要放置、交互、挖掘与攻击，全部允许
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

    /**
     * 一个完整的活塞推水晶方案。
     */
    private static final class PistonPlan {
        /** 水晶初始位置。 */
        private final BlockPos crystalPos;
        /** 水晶被推动后的位置。 */
        private final BlockPos pushPos;
        /** 活塞位置。 */
        private final BlockPos pistonPos;
        /** 红石块位置。 */
        private final BlockPos redstonePos;
        /** 黑曜石底座位置。 */
        private final BlockPos basePos;
        /** 活塞朝向。 */
        private final Direction facing;
        /** 传送工作点。 */
        private final Vec3 workFeet;

        /**
         * 构造方法。
         *
         * @param crystalPos  水晶初始位置
         * @param pushPos     水晶被推动后的位置
         * @param pistonPos   活塞位置
         * @param redstonePos 红石块位置
         * @param basePos     黑曜石底座位置
         * @param facing      活塞朝向
         * @param workFeet    传送工作点
         */
        private PistonPlan(BlockPos crystalPos, BlockPos pushPos, BlockPos pistonPos, BlockPos redstonePos, BlockPos basePos, Direction facing, Vec3 workFeet) {
            this.crystalPos = crystalPos;
            this.pushPos = pushPos;
            this.pistonPos = pistonPos;
            this.redstonePos = redstonePos;
            this.basePos = basePos;
            this.facing = facing;
            this.workFeet = workFeet;
        }
    }
}
