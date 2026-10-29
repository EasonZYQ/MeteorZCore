package com.eason.zcore.modules;

import com.eason.zcore.MeteorZCore;
import com.eason.zcore.utils.PositionSpoofer;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EntityTypeListSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.Target;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.player.SwingMode;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Z TP Aura 模块。
 * <p>
 * 模仿 Meteor 自带的 KillAura：自动搜索周围符合条件的目标、转向并攻击。
 * 区别在于当目标超出服务端允许的攻击距离时，会先发送一个伪造位置包，
 * 让服务端误以为玩家已经站到目标旁边，攻击完成后再在 tick 结束时还原坐标。
 * <p>
 * 位置包的发送与还原统一交给 {@link PositionSpoofer} 管理，
 * 避免与 Z Reach 同时开启时出现一个 tick 内两个位置包的情况。
 */
public class ZTPAura extends Module {
    /** 原版服务端允许的实体攻击距离：3.0 格基础距离加 3.0 格缓冲。 */
    private static final double SERVER_ATTACK_RANGE = 6.0;
    /** 原版攻击冷却的取值参数，用于判断攻击是否已经蓄力完成。 */
    private static final float ATTACK_COOLDOWN_ADJUST = 0.5F;

    /** 默认设置分组。 */
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    /** 目标筛选设置分组。 */
    private final SettingGroup sgTargeting = settings.createGroup("Targeting");

    /** 攻击目标的最大距离。 */
    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        // 设置项名称
        .name("range")
        // 设置项说明
        .description("The maximum distance at which an entity can be attacked.")
        // 默认值
        .defaultValue(10.0)
        // 允许输入的数值范围
        .range(0.0, 20.0)
        // 滑条显示的数值范围
        .sliderRange(3.0, 14.0)
        // 构建出设置对象
        .build()
    );

    /** 转向目标的时机。 */
    private final Setting<RotationMode> rotation = sgGeneral.add(new EnumSetting.Builder<RotationMode>()
        // 设置项名称
        .name("rotate")
        // 设置项说明
        .description("Determines when you should rotate towards the target.")
        // 默认始终转向
        .defaultValue(RotationMode.Always)
        // 构建出设置对象
        .build()
    );

    /** 是否只在按住左键时攻击。 */
    private final Setting<Boolean> onlyOnClick = sgGeneral.add(new BoolSetting.Builder()
        // 设置项名称
        .name("only-on-click")
        // 设置项说明
        .description("Only attacks when holding left click.")
        // 默认关闭
        .defaultValue(false)
        // 构建出设置对象
        .build()
    );

    /** 允许攻击的实体类型。 */
    private final Setting<Set<EntityType<?>>> entities = sgTargeting.add(new EntityTypeListSetting.Builder()
        // 设置项名称
        .name("entities")
        // 设置项说明
        .description("Entities to attack.")
        // 只列出可以攻击的实体
        .onlyAttackable()
        // 默认攻击玩家
        .defaultValue(EntityTypes.PLAYER)
        // 构建出设置对象
        .build()
    );

    /** 多个目标时的排序方式。 */
    private final Setting<SortPriority> priority = sgTargeting.add(new EnumSetting.Builder<SortPriority>()
        // 设置项名称
        .name("priority")
        // 设置项说明
        .description("How to filter targets within range.")
        // 默认按角度最近排序
        .defaultValue(SortPriority.ClosestAngle)
        // 构建出设置对象
        .build()
    );

    /** 每次最多攻击的实体数量。 */
    private final Setting<Integer> maxTargets = sgTargeting.add(new IntSetting.Builder()
        // 设置项名称
        .name("max-targets")
        // 设置项说明
        .description("How many entities to target at once.")
        // 默认只攻击一个目标
        .defaultValue(1)
        // 最小值为 1
        .min(1)
        // 滑条显示的数值范围
        .sliderRange(1, 5)
        // 构建出设置对象
        .build()
    );

    /** 是否忽略拥有自定义名称的实体。 */
    private final Setting<Boolean> ignoreNamed = sgTargeting.add(new BoolSetting.Builder()
        // 设置项名称
        .name("ignore-named")
        // 设置项说明
        .description("Whether or not to attack mobs with a name.")
        // 默认关闭
        .defaultValue(false)
        // 构建出设置对象
        .build()
    );

    /** 是否忽略自己驯服的实体。 */
    private final Setting<Boolean> ignoreTamed = sgTargeting.add(new BoolSetting.Builder()
        // 设置项名称
        .name("ignore-tamed")
        // 设置项说明
        .description("Will avoid attacking mobs you tamed.")
        // 默认关闭
        .defaultValue(false)
        // 构建出设置对象
        .build()
    );

    /** 本 tick 筛选出的目标列表。 */
    private final List<Entity> targets = new ArrayList<>();

    /**
     * 构造方法，完成模块注册信息。
     */
    public ZTPAura() {
        // 调用父类构造：传入分类、模块名与描述
        super(MeteorZCore.CATEGORY, "z-tp-aura", "Attacks entities around you like KillAura and teleports next to distant targets using movement packets.");
    }

    /**
     * 模块关闭时清空目标列表。
     */
    @Override
    public void onDeactivate() {
        // 清空本 tick 缓存的目标
        targets.clear();
    }

    /**
     * 每个客户端 tick 开始时调用，负责搜索目标、转向并执行攻击。
     *
     * @param event tick 前事件
     */
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        // 玩家或世界不存在、或游戏模式无效时直接返回
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        // 玩家已死亡或处于旁观模式时不执行攻击
        if (!mc.player.isAlive() || PlayerUtils.getGameMode() == GameType.SPECTATOR) return;
        // 开启“仅按住左键”时，未按住左键则直接返回
        if (onlyOnClick.get() && !mc.options.keyAttack.isDown()) return;

        // 清空上一 tick 的目标列表
        targets.clear();
        // 按筛选条件、排序方式和最大数量收集目标
        TargetUtils.getList(targets, this::entityCheck, priority.get(), maxTargets.get());
        // 没有目标时直接返回
        if (targets.isEmpty()) return;

        // 取排序后的第一个目标作为主要目标
        Entity primary = targets.getFirst();

        // 设置为始终转向时，先把视角转向主要目标
        if (rotation.get() == RotationMode.Always) {
            // 调用旋转工具，传入水平角和俯仰角
            Rotations.rotate(Rotations.getYaw(primary), Rotations.getPitch(primary, Target.Body));
        }

        // 攻击尚未蓄力完成时先不攻击，避免浪费一次伪造坐标
        if (mc.player.getAttackStrengthScale(ATTACK_COOLDOWN_ADJUST) < 1.0F) return;

        // 缓存玩家眼睛位置，用于计算到目标的距离
        Vec3 eye = mc.player.getEyePosition();
        // 计算眼睛到主要目标碰撞箱的平方距离
        double distanceSq = primary.getBoundingBox().distanceToSqr(eye);

        // 目标超出服务端允许的攻击距离时，需要先伪造坐标
        if (distanceSq > SERVER_ATTACK_RANGE * SERVER_ATTACK_RANGE) {
            // 本 tick 组已被位置包占用时，等待下一个可用的 tick
            if (PositionSpoofer.isLocked()) return;

            // 计算伪造的脚部坐标
            Vec3 feet = PositionSpoofer.feetNear(primary.getBoundingBox());
            // 无法计算坐标或发送失败时放弃本 tick 的攻击
            if (feet == null || !PositionSpoofer.spoof(feet)) return;
        }

        // 依次攻击所有筛选出的目标
        for (Entity target : targets) attack(target);
    }

    /**
     * 监听发包事件，把位置包的管理交给共享工具类。
     *
     * @param event 发包事件
     */
    @EventHandler
    private void onSendPacket(PacketEvent.Send event) {
        // 处理位置包：占用期间取消客户端自动发送的位置包
        PositionSpoofer.handleSend(event);
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
     * 对单个目标执行攻击。
     *
     * @param target 攻击目标
     */
    private void attack(Entity target) {
        // 设置为命中时转向时，在攻击瞬间把视角转向该目标
        if (rotation.get() == RotationMode.OnHit) {
            // 调用旋转工具，传入水平角和俯仰角
            Rotations.rotate(Rotations.getYaw(target), Rotations.getPitch(target, Target.Body));
        }

        // 通过游戏模式对象发送攻击包并处理客户端预测
        mc.gameMode.attack(mc.player, target);
        // 同时播放客户端与服务端的挥手动画
        SwingMode.Both.attackSwing(InteractionHand.MAIN_HAND);
    }

    /**
     * 判断某个实体是否可以作为攻击目标。
     *
     * @param entity 待判断的实体
     * @return 可以作为目标时返回 true
     */
    private boolean entityCheck(Entity entity) {
        // 排除自己和摄像机附着的实体
        if (entity.equals(mc.player) || entity.equals(mc.getCameraEntity())) return false;
        // 排除已经死亡或正在死亡的实体
        if (entity instanceof LivingEntity living && living.isDeadOrDying()) return false;
        // 排除所有非存活实体
        if (!entity.isAlive()) return false;

        // 缓存实体碰撞箱，用于计算距离
        AABB box = entity.getBoundingBox();
        // 按距离设置过滤：取碰撞箱上距离玩家最近的点判断是否在范围内
        if (!PlayerUtils.isWithin(
            // X 轴方向取碰撞箱范围内的最近值
            Mth.clamp(mc.player.getX(), box.minX, box.maxX),
            // Y 轴方向取碰撞箱范围内的最近值
            Mth.clamp(mc.player.getY(), box.minY, box.maxY),
            // Z 轴方向取碰撞箱范围内的最近值
            Mth.clamp(mc.player.getZ(), box.minZ, box.maxZ),
            // 允许的最大距离
            range.get()
        )) return false;

        // 排除不在实体类型列表中的实体
        if (!entities.get().contains(entity.getType())) return false;
        // 开启“忽略命名实体”时，排除拥有自定义名称的实体
        if (ignoreNamed.get() && entity.hasCustomName()) return false;
        // 开启“忽略驯服实体”时，排除由自己驯服的实体
        if (ignoreTamed.get() && entity instanceof OwnableEntity ownable && ownable.getOwner() == mc.player) return false;
        // 目标是玩家时，再做额外判断
        if (entity instanceof Player player) {
            // 排除创造模式玩家
            if (player.isCreative()) return false;
            // 排除好友列表中的玩家
            if (!Friends.get().shouldAttack(player)) return false;
        }

        return true;
    }

    /**
     * 返回当前主要目标的名称，用于模块信息栏显示。
     *
     * @return 目标名称，没有目标时返回 null
     */
    @Override
    public String getInfoString() {
        // 目标列表不为空时取第一个目标的名字
        return targets.isEmpty() ? null : EntityUtils.getName(targets.getFirst());
    }

    /**
     * 转向时机枚举。
     */
    public enum RotationMode {
        /** 每个 tick 都转向目标。 */
        Always,
        /** 只在攻击命中时转向目标。 */
        OnHit,
        /** 从不转向目标。 */
        None
    }
}
