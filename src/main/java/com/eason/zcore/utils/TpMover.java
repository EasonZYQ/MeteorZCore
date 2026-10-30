package com.eason.zcore.utils;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.Vec3;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 多 tick 传送引擎。
 * <p>
 * 26.3 的服务端规定一个客户端 tick 只能收到一个位置包，因此无法像旧版本那样靠同 tick 连发多个包
 * 完成超远距离传送。本引擎的做法是：
 * <ol>
 * <li>先尝试一次“直接传送”：发送一个目标坐标的位置包，并等待两个 tick 观察服务端是否回传校正包；</li>
 * <li>如果服务端没有校正，说明该服务器允许直接超远传送，之后一直使用直传；</li>
 * <li>如果收到校正包，则判定直传不可用，改为每个 tick 前进 {@link #STEP_DISTANCE} 格，分多个 tick 走到目标。</li>
 * </ol>
 * 移动期间客户端位置保持不变，只伪造服务端视角的位置；所有位置包仍由 {@link PositionSpoofer} 统一管理。
 */
public final class TpMover {
    /** 分段传送时每个 tick 的最大前进距离，略小于原版 10 格的位移限制。 */
    private static final double STEP_DISTANCE = 9.5;
    /** 判定已经到达目标的距离容差。 */
    private static final double ARRIVE_DISTANCE = 0.5;
    /** 直传探测的等待 tick 数。 */
    private static final int VERIFY_TICKS = 2;

    /** 是否正在执行一次传送任务。 */
    private static boolean busy;
    /** 任务开始时客户端所在位置，也是回程目标。 */
    private static Vec3 origin;
    /** 本次任务的目标位置。 */
    private static Vec3 destination;
    /** 对服务端当前所在位置的估计。 */
    private static Vec3 current;
    /** 是否已经确认该服务器不支持直传。 */
    private static boolean directDenied;
    /** 是否已经确认该服务器支持直传。 */
    private static boolean directVerified;
    /** 正在等待验证的直传目标，为空表示没有待验证的直传。 */
    private static Vec3 verifyTarget;
    /** 直传验证剩余等待 tick 数。 */
    private static int verifyWait;
    /** 是否强制在位置包里声明玩家处于空中。 */
    private static boolean forceAirborne;

    /**
     * 私有构造方法，禁止实例化该工具类。
     */
    private TpMover() {}

    /**
     * 返回是否正在执行传送任务。
     *
     * @return 传送期间返回 true
     */
    public static boolean isBusy() {
        return busy;
    }

    /**
     * 返回任务开始时记录的客户端位置，即回程目标。
     *
     * @return 回程坐标，没有任务时返回 null
     */
    public static Vec3 origin() {
        return origin;
    }

    /**
     * 返回当前传送任务的目的地。
     *
     * @return 目的坐标，没有任务时返回 null
     */
    public static Vec3 destination() {
        return destination;
    }

    /**
     * 设置是否强制在位置包里声明玩家处于空中。
     *
     * @param value 为 true 时所有位置包的 onGround 都为 false
     */
    public static void setForceAirborne(boolean value) {
        forceAirborne = value;
    }

    /**
     * 重新开始直传探测，切换服务器或维度后调用。
     */
    public static void resetDetection() {
        directDenied = false;
        directVerified = false;
        verifyTarget = null;
        verifyWait = 0;
    }

    /**
     * 清空传送任务状态，模块关闭时调用。
     */
    public static void reset() {
        busy = false;
        verifyTarget = null;
        verifyWait = 0;
        forceAirborne = false;
        PositionSpoofer.setHolding(false);
    }

    /**
     * 中止传送并尽量把服务端位置还原到客户端位置。
     */
    public static void abort() {
        if (busy && origin != null && !PositionSpoofer.isLocked() && mc.player != null && mc.getConnection() != null) {
            // 尽量发送一个回到客户端真实位置的位置包
            sendPosition(origin);
        }

        reset();
    }

    /**
     * 每个 tick 调用一次，向目标推进。
     *
     * @param target 目标坐标
     * @return 服务端位置已经到达目标时返回 true，本 tick 可以执行动作
     */
    public static boolean moveTo(Vec3 target) {
        // 玩家或连接不存在时无法传送
        if (mc.player == null || mc.getConnection() == null || target == null) return false;

        // 没有任务，或目标发生变化时重新开始一个任务
        if (!busy || destination.distanceTo(target) > 0.5) start(target);

        return advance();
    }

    /**
     * 处理接收到的位置包，用于判断直传是否被服务端拒绝。
     *
     * @param event 收包事件
     */
    public static void handleReceive(PacketEvent.Receive event) {
        // 只关心服务端下发的玩家位置校正包
        if (!(event.packet instanceof ClientboundPlayerPositionPacket packet)) return;
        // 相对坐标包无法直接比较，忽略
        if (!packet.relatives().isEmpty()) return;

        Vec3 pos = packet.change().position();

        // 正在等待直传验证，且服务端给的位置与目标明显不同，说明直传被拒绝
        if (verifyTarget != null && pos.distanceTo(verifyTarget) > 1.0) {
            directDenied = true;
            directVerified = false;
            verifyTarget = null;
            verifyWait = 0;
            current = pos;
            origin = pos;
        }
    }

    /**
     * 开始一个传送任务。
     *
     * @param target 目标坐标
     */
    private static void start(Vec3 target) {
        // 首次开始时以客户端当前位置为起点与回程目标
        if (!busy) {
            origin = mc.player.position();
            current = origin;
        }

        busy = true;
        destination = target;
        verifyTarget = null;
        verifyWait = 0;
        // 开启保持模式，tick 结束时不再自动还原坐标
        PositionSpoofer.setHolding(true);
    }

    /**
     * 推进一次任务，决定本 tick 是直传、分段前进还是已经到达。
     *
     * @return 已到达目标时返回 true
     */
    private static boolean advance() {
        // 已经到达目标
        if (current.distanceTo(destination) <= ARRIVE_DISTANCE) return true;

        // 还有直传探测在等待结果
        if (verifyTarget != null) {
            if (--verifyWait <= 0) {
                directVerified = true;
                verifyTarget = null;
            }
            return false;
        }

        double distance = current.distanceTo(destination);

        // 距离超过单 tick 上限时才需要考虑直传
        if (!directDenied && distance > STEP_DISTANCE) {
            // 已经验证过直传可用，直接一次跳到目标
            if (directVerified) {
                if (!sendPosition(destination)) return false;
                current = destination;
                return true;
            }

            // 尚未验证：先探测一次直传，等待服务端是否拉回
            if (!sendPosition(destination)) return false;
            current = destination;
            verifyTarget = destination;
            verifyWait = VERIFY_TICKS;
            return false;
        }

        // 分段推进：每个 tick 最多前进 STEP_DISTANCE 格
        double step = Math.min(STEP_DISTANCE, distance);
        Vec3 next = current.add(destination.subtract(current).normalize().scale(step));
        if (!sendPosition(next)) return false;
        current = next;

        return current.distanceTo(destination) <= ARRIVE_DISTANCE;
    }

    /**
     * 发送一个伪造位置包，并占用当前 tick 组。
     *
     * @param pos 要发送的位置
     * @return 发送成功时返回 true；本 tick 组已被占用时返回 false
     */
    private static boolean sendPosition(Vec3 pos) {
        // 本 tick 组已经有位置包，等待下一个 tick
        if (PositionSpoofer.isLocked()) return false;

        // 发送位置包，此时尚未占用 tick 组，不会被取消逻辑拦截
        mc.getConnection().send(new ServerboundMovePlayerPacket.Pos(pos.x, pos.y, pos.z, !forceAirborne && mc.player.onGround(), mc.player.horizontalCollision));
        // 发送完成后占用本 tick 组
        PositionSpoofer.lock();

        return true;
    }
}
