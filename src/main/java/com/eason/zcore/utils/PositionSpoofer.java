package com.eason.zcore.utils;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 伪造坐标工具类。
 * <p>
 * Z Reach 与 Z TP Aura 都会向服务端发送伪造的位置包。26.3 的服务端规定同一个客户端 tick
 * 只能接收一个位置包，因此两个模块必须共享同一份状态，这里统一管理：
 * <ul>
 * <li>当前 tick 组是否已经发送过位置包；</li>
 * <li>是否还有伪造坐标需要在 tick 结束时还原。</li>
 * </ul>
 * 所有状态都是全局静态的，由各模块的事件处理器调用对应的处理方法。
 */
public final class PositionSpoofer {
    /** 伪造坐标与目标之间保留的最小距离，必须明显小于服务端允许的交互距离。 */
    private static final double MIN_OFFSET = 2.0;
    /** 寻找可用坐标时每次向外移动的步长。 */
    private static final double OFFSET_STEP = 0.5;
    /** 当前客户端 tick 组内是否已经发送过位置包。 */
    private static boolean positionSent;
    /** 是否存在需要在本 tick 结束后还原的伪造坐标。 */
    private static boolean pendingRestore;

    /**
     * 私有构造方法，禁止外部实例化该工具类。
     */
    private PositionSpoofer() {}

    /**
     * 返回当前 tick 组是否已经被位置包占用。
     *
     * @return 已发送过位置包时返回 true
     */
    public static boolean isLocked() {
        return positionSent;
    }

    /**
     * 处理发包事件：占用期间取消所有位置包。
     *
     * @param event 发包事件
     * @return 该事件是否为位置包
     */
    public static boolean handleSend(PacketEvent.Send event) {
        // 只处理真正携带坐标的移动包，纯旋转包不占用位置包名额
        if (event.packet instanceof ServerboundMovePlayerPacket packet && packet.hasPosition()) {
            // 本 tick 组已有位置包时，取消客户端自动发送的这个位置包
            if (positionSent) event.cancel();
            return true;
        }

        return false;
    }

    /**
     * 处理发包完成事件：在客户端 tick 结束包之后发送还原坐标。
     * <p>
     * 服务端收到 tick 结束包时会重置“每 tick 只能有一个位置包”的计数，
     * 因此还原包必须排在它之后，才能算作下一个 tick 组的第一个位置包。
     *
     * @param event 发包完成事件
     */
    public static void handleSent(PacketEvent.Sent event) {
        // 仅处理客户端 tick 结束包
        if (!(event.packet instanceof ServerboundClientTickEndPacket)) return;

        // 没有待还原的坐标时直接解锁，允许之后发送正常位置包
        if (!pendingRestore) {
            positionSent = false;
            return;
        }

        // 清除还原标记，避免重复还原
        pendingRestore = false;

        // 玩家或连接不存在时无法还原，直接解锁
        if (mc.player == null || mc.getConnection() == null) {
            positionSent = false;
            return;
        }

        // 静默发送真实坐标，该包属于下一个 tick 组
        event.sendSilently(new ServerboundMovePlayerPacket.Pos(mc.player.position(), mc.player.onGround(), mc.player.horizontalCollision));
        // 锁定下一个 tick 组，阻止客户端稍后自动发送的第二个位置包
        positionSent = true;
    }

    /**
     * 计算伪造的脚部坐标，让眼睛落在距目标碰撞箱一定距离的位置，并尽量避开方块。
     * <p>
     * 从距离目标最近的位置开始，沿玩家方向逐步向外尝试，返回第一个不会卡进方块的坐标，
     * 这样放置方块、破坏方块等需要玩家身体位置合法的动作才能顺利通过服务端校验。
     *
     * @param target    目标碰撞箱
     * @param maxOffset 与目标之间允许的最大距离
     * @return 伪造的脚部坐标，无法计算时返回 null
     */
    public static Vec3 feetNear(AABB target, double maxOffset) {
        // 玩家不存在时无法计算
        if (mc.player == null) return null;

        // 缓存玩家眼睛位置，作为计算基准
        Vec3 eye = mc.player.getEyePosition();

        // 计算目标碰撞箱上距离眼睛最近的点
        Vec3 closest = new Vec3(
            // X 轴方向取碰撞箱范围内的最近值
            Mth.clamp(eye.x, target.minX, target.maxX),
            // Y 轴方向取碰撞箱范围内的最近值
            Mth.clamp(eye.y, target.minY, target.maxY),
            // Z 轴方向取碰撞箱范围内的最近值
            Mth.clamp(eye.z, target.minZ, target.maxZ)
        );

        // 从最近点指向眼睛的方向向量
        Vec3 direction = eye.subtract(closest);
        // 眼睛已经在碰撞箱内时无需伪造
        if (direction.lengthSqr() < 1.0E-4) return null;

        // 归一化方向向量，方便按距离取点
        Vec3 normalized = direction.normalize();
        // 眼睛高度，用于把眼睛坐标换算成脚部坐标
        double eyeHeight = mc.player.getEyeY() - mc.player.getY();
        // 玩家原始碰撞箱，用于判断候选位置是否卡在方块里
        AABB playerBox = mc.player.getBoundingBox();

        // 从近到远逐步尝试，返回第一个不会卡进方块的位置
        for (double offset = MIN_OFFSET; offset <= maxOffset; offset += OFFSET_STEP) {
            // 沿玩家所在方向后退当前距离，得到伪造后的眼睛坐标
            Vec3 spoofedEye = closest.add(normalized.scale(offset));
            // 把眼睛坐标换算成脚部坐标，即减去眼睛高度
            Vec3 feet = spoofedEye.subtract(0.0, eyeHeight, 0.0);
            // 把玩家碰撞箱平移到候选位置
            AABB movedBox = playerBox.move(feet.subtract(mc.player.position()));
            // 世界不存在或该位置没有碰撞时直接采用
            if (mc.level == null || mc.level.noCollision(mc.player, movedBox)) return feet;
        }

        return null;
    }

    /**
     * 发送一个伪造位置包，并标记本 tick 组已被占用。
     *
     * @param feet 伪造的脚部坐标
     * @return 发送成功时返回 true；本 tick 组已被占用或参数无效时返回 false
     */
    public static boolean spoof(Vec3 feet) {
        // 本 tick 组已被占用、坐标为空或玩家与连接不存在时不发送
        if (positionSent || feet == null || mc.player == null || mc.getConnection() == null) return false;

        // 通过连接发送位置包，此时 positionSent 为 false，不会被取消逻辑拦截
        mc.getConnection().send(new ServerboundMovePlayerPacket.Pos(feet.x, feet.y, feet.z, mc.player.onGround(), mc.player.horizontalCollision));
        // 标记本 tick 组已发送过位置包
        markSpoofed();

        return true;
    }

    /**
     * 标记当前 tick 组已经发送过伪造位置包。
     * <p>
     * 供自行调用 {@code sendSilently} 发送位置包的模块使用，例如 Z Reach。
     */
    public static void markSpoofed() {
        // 锁定当前 tick 组，阻止客户端再发送位置包
        positionSent = true;
        // 记录需要在 tick 结束时还原坐标
        pendingRestore = true;
    }
}
