package com.eason.zcore.modules;

import com.eason.zcore.MeteorZCore;
import com.eason.zcore.utils.PositionSpoofer;
import com.eason.zcore.utils.TpMover;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.DamageUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.SwingMode;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Z Anti Explosion 模块。
 * <p>
 * 参考 GCore 的 GAntiExplosion：监听服务端的水晶生成包与方块更新包，
 * 当附近出现的末地水晶或重生锚可能对自己造成超过阈值的伤害时，
 * 由 {@link TpMover} 把服务端位置送到安全距离，抢先攻击水晶或拆掉重生锚，再回到原处。
 */
public class ZAntiExplosion extends Module {
    /** 传送到工作点时与目标碰撞箱保持的最大距离。 */
    private static final double TP_OFFSET = 5.0;

    /** 默认设置分组。 */
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    /** 允许自身受到的最大爆炸伤害，超过才会反制。 */
    private final Setting<Double> maxSelfDamage = sgGeneral.add(new DoubleSetting.Builder()
        // 设置项名称
        .name("max-self-damage")
        // 设置项说明
        .description("Only threats that would deal more damage than this are countered.")
        // 默认值
        .defaultValue(8.0)
        // 允许输入的数值范围
        .range(0.0, 36.0)
        // 滑条显示的数值范围
        .sliderRange(0.0, 20.0)
        // 构建出设置对象
        .build()
    );

    /** 是否禁止处理可能炸死自己的威胁。 */
    private final Setting<Boolean> antiSuicide = sgGeneral.add(new BoolSetting.Builder()
        // 设置项名称
        .name("anti-suicide")
        // 设置项说明
        .description("Skips threats that would kill you before they can be countered.")
        // 默认开启
        .defaultValue(true)
        // 构建出设置对象
        .build()
    );

    /** 允许反制的最大距离。 */
    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        // 设置项名称
        .name("range")
        // 设置项说明
        .description("The maximum distance at which threats are countered.")
        // 默认值
        .defaultValue(30.0)
        // 允许输入的数值范围
        .range(0.0, 1000.0)
        // 滑条显示的数值范围
        .sliderRange(5.0, 100.0)
        // 构建出设置对象
        .build()
    );

    /** 当前威胁位置。 */
    private BlockPos threatPos;
    /** 威胁是否为重生锚。 */
    private boolean threatAnchor;
    /** 威胁对应的水晶实体 id。 */
    private int threatEntityId = -1;
    /** 当前传送任务的目的地。 */
    private Vec3 travelTarget;
    /** 是否处于回程阶段。 */
    private boolean returning;

    /**
     * 构造方法，完成模块注册信息。
     */
    public ZAntiExplosion() {
        // 调用父类构造：传入分类、模块名与描述
        super(MeteorZCore.CATEGORY, "z-anti-explosion", "Counters crystals and anchors that would explode next to you.");
    }

    /**
     * 模块启用时调用，重置状态。
     */
    @Override
    public void onActivate() {
        // 清空威胁与传送状态
        clearThreat();
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
        clearThreat();
    }

    /**
     * 清空当前威胁。
     */
    private void clearThreat() {
        // 清空威胁字段与传送状态
        threatPos = null;
        threatAnchor = false;
        threatEntityId = -1;
        travelTarget = null;
        returning = false;
    }

    /**
     * 监听收包事件，识别可能伤害自己的爆炸物。
     *
     * @param event 收包事件
     */
    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        // 交给传送引擎判断是否收到位置校正
        TpMover.handleReceive(event);

        // 玩家或世界不存在时不处理
        if (mc.player == null || mc.level == null) return;
        // 已经有威胁时不重复处理
        if (threatPos != null) return;

        // 水晶生成包
        if (event.packet instanceof ClientboundAddEntityPacket packet && packet.getType() == EntityTypes.END_CRYSTAL) {
            Vec3 pos = new Vec3(packet.getX(), packet.getY(), packet.getZ());
            // 超出反制距离时忽略
            if (pos.distanceToSqr(mc.player.position()) > range.get() * range.get()) return;

            // 计算该水晶对自己的伤害
            float damage = DamageUtils.crystalDamage(mc.player, pos);
            // 伤害不够时忽略
            if (damage <= maxSelfDamage.get()) return;
            // 开启防自杀且会被炸死时忽略
            if (antiSuicide.get() && PlayerUtils.getTotalHealth() - damage <= 0) return;

            // 记录水晶威胁
            threatPos = BlockPos.containing(pos);
            threatAnchor = false;
            threatEntityId = packet.getId();
            return;
        }

        // 方块更新包，用于识别重生锚
        if (event.packet instanceof ClientboundBlockUpdatePacket packet && packet.getBlockState().is(Blocks.RESPAWN_ANCHOR)) {
            BlockPos pos = packet.getPos();
            // 超出反制距离时忽略
            if (Vec3.atCenterOf(pos).distanceToSqr(mc.player.position()) > range.get() * range.get()) return;

            // 计算该锚对自己的伤害
            float damage = DamageUtils.anchorDamage(mc.player, Vec3.atCenterOf(pos));
            // 伤害不够时忽略
            if (damage <= maxSelfDamage.get()) return;
            // 开启防自杀且会被炸死时忽略
            if (antiSuicide.get() && PlayerUtils.getTotalHealth() - damage <= 0) return;

            // 记录重生锚威胁
            threatPos = pos.immutable();
            threatAnchor = true;
            threatEntityId = -1;
        }
    }

    /**
     * 每个客户端 tick 开始时调用，传送到威胁附近并处理。
     *
     * @param event tick 前事件
     */
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        // 玩家或世界不存在时直接返回
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        // 没有威胁时直接返回
        if (threatPos == null) return;

        // 传送任务进行中
        if (TpMover.isBusy()) {
            // 不是本模块发起的传送，等待它结束
            if (travelTarget == null) return;

            // 推进传送，未到达时直接返回
            if (!TpMover.moveTo(travelTarget)) return;

            // 回程完成，结束本次反制
            if (returning) {
                clearThreat();
                TpMover.reset();
                return;
            }

            // 到达威胁附近，执行反制
            counter();

            // 开始回程
            returning = true;
            travelTarget = TpMover.origin();
            return;
        }

        // 计算威胁旁的工作点
        Vec3 feet = PositionSpoofer.feetNear(new AABB(threatPos), TP_OFFSET);
        // 找不到工作点时放弃
        if (feet == null) {
            clearThreat();
            return;
        }

        // 前往工作点
        travelTarget = feet;
        returning = false;
        TpMover.moveTo(feet);
    }

    /**
     * 在威胁旁执行反制动作。
     */
    private void counter() {
        // 重生锚：直接拆掉方块
        if (threatAnchor) {
            if (BlockUtils.canBreak(threatPos)) {
                BlockUtils.breakBlock(threatPos, true);
            }
            return;
        }

        // 水晶：优先按实体 id 攻击
        Entity entity = threatEntityId == -1 ? null : mc.level.getEntity(threatEntityId);
        if (entity instanceof EndCrystal crystal && crystal.isAlive()) {
            mc.gameMode.attack(mc.player, crystal);
            SwingMode.Both.attackSwing(InteractionHand.MAIN_HAND);
            return;
        }

        // 找不到原实体时在威胁位置附近搜索水晶
        for (EndCrystal crystal : mc.level.getEntitiesOfClass(EndCrystal.class, new AABB(threatPos).inflate(3.0))) {
            if (!crystal.isAlive()) continue;
            mc.gameMode.attack(mc.player, crystal);
            SwingMode.Both.attackSwing(InteractionHand.MAIN_HAND);
            return;
        }
    }
}
