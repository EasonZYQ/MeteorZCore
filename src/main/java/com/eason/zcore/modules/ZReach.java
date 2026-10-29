package com.eason.zcore.modules;

import com.eason.zcore.MeteorZCore;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Extends the reach for interactions, attacks and block breaking. The client-side reach is
 * increased so targets can be picked at range, then a single spoofed position packet is sent
 * right before the action so the server believes the player is next to the target.
 */
public class ZReach extends Module {
    /** Vanilla server-side block limit: 4.5 blocks of reach plus a 1.0 block buffer. */
    private static final double SERVER_BLOCK_RANGE = 5.5;
    /** Vanilla server-side entity limit: 3.0 blocks of reach plus a 3.0 block buffer. */
    private static final double SERVER_ENTITY_RANGE = 6.0;
    /** How far the spoofed position is kept from the target, well inside the server-side limits. */
    private static final double SPOOF_OFFSET = 3.0;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> maxDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-distance")
        .description("Maximum distance at which actions are attempted. Values above 12 may be rejected by server movement checks.")
        .defaultValue(10.0)
        .range(0.0, 20.0)
        .sliderRange(4.5, 14.0)
        .build()
    );

    private final Setting<Boolean> interaction = sgGeneral.add(new BoolSetting.Builder()
        .name("interaction")
        .description("Extend the distance at which blocks and entities can be right-clicked.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> attack = sgGeneral.add(new BoolSetting.Builder()
        .name("attack")
        .description("Extend the distance at which entities can be attacked.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> mining = sgGeneral.add(new BoolSetting.Builder()
        .name("mining")
        .description("Extend the distance at which blocks can be broken.")
        .defaultValue(true)
        .build()
    );

    /** Whether a position packet was already sent in the current client tick. */
    private boolean positionSent;
    /** Whether a spoofed position still has to be reverted once the current client tick ends. */
    private boolean pendingRestore;

    public ZReach() {
        super(MeteorZCore.CATEGORY, "z-reach", "Increases your reach for interaction, attack and mining by making the server think you are next to the target.");
    }

    @Override
    public void onActivate() {
        positionSent = false;
        pendingRestore = false;
    }

    @Override
    public void onDeactivate() {
        positionSent = false;
        pendingRestore = false;
    }

    /**
     * Extra block reach added on top of the vanilla value, used by the {@code Player} mixin.
     */
    public double extraBlockReach(double vanillaRange) {
        return isActive() && (interaction.get() || mining.get()) ? Math.max(0, maxDistance.get() - vanillaRange) : 0;
    }

    /**
     * Extra entity reach added on top of the vanilla value, used by the {@code Player} mixin.
     */
    public double extraEntityReach(double vanillaRange) {
        return isActive() && (interaction.get() || attack.get()) ? Math.max(0, maxDistance.get() - vanillaRange) : 0;
    }

    @EventHandler
    private void onPacketSent(PacketEvent.Sent event) {
        if (!(event.packet instanceof ServerboundClientTickEndPacket)) return;

        // The server resets its one-position-packet-per-tick check when it receives this packet,
        // so the restore packet has to be sent after it to avoid a second position in the same tick.
        if (!pendingRestore) {
            positionSent = false;
            return;
        }

        pendingRestore = false;

        if (mc.player == null || mc.getConnection() == null) {
            positionSent = false;
            return;
        }

        event.sendSilently(new ServerboundMovePlayerPacket.Pos(mc.player.position(), mc.player.onGround(), mc.player.horizontalCollision));
        positionSent = true;
    }

    @EventHandler
    private void onSendPacket(PacketEvent.Send event) {
        if (event.packet instanceof ServerboundMovePlayerPacket packet && packet.hasPosition()) {
            if (positionSent) event.cancel();
            return;
        }

        if (mc.player == null || mc.level == null || positionSent) return;

        Vec3 eye = mc.player.getEyePosition();

        if (event.packet instanceof ServerboundUseItemOnPacket packet) {
            if (interaction.get()) spoof(event, new AABB(packet.hitResult().getBlockPos()), eye, SERVER_BLOCK_RANGE);
        } else if (event.packet instanceof ServerboundPlayerActionPacket packet) {
            if (mining.get() && isBreakAction(packet.getAction())) spoof(event, new AABB(packet.getPos()), eye, SERVER_BLOCK_RANGE);
        } else if (event.packet instanceof ServerboundInteractPacket packet) {
            if (!interaction.get()) return;

            Entity entity = mc.level.getEntity(packet.entityId());
            if (entity != null) spoof(event, entity.getBoundingBox(), eye, SERVER_ENTITY_RANGE);
        } else if (event.packet instanceof ServerboundAttackPacket packet) {
            if (!attack.get()) return;

            Entity entity = mc.level.getEntity(packet.entityId());
            if (entity != null) spoof(event, entity.getBoundingBox(), eye, SERVER_ENTITY_RANGE);
        }
    }

    private static boolean isBreakAction(ServerboundPlayerActionPacket.Action action) {
        return switch (action) {
            case START_DESTROY_BLOCK, STOP_DESTROY_BLOCK, ABORT_DESTROY_BLOCK, CHANGE_DESTROY_DIRECTION -> true;
            default -> false;
        };
    }

    private void spoof(PacketEvent.Send event, AABB target, Vec3 eye, double serverRange) {
        double distanceSq = target.distanceToSqr(eye);

        // The server would accept this action anyway, so leave it untouched.
        if (distanceSq <= serverRange * serverRange) return;
        // Out of the configured range, the server will reject it either way.
        if (distanceSq > maxDistance.get() * maxDistance.get()) return;

        Vec3 closest = new Vec3(
            Mth.clamp(eye.x, target.minX, target.maxX),
            Mth.clamp(eye.y, target.minY, target.maxY),
            Mth.clamp(eye.z, target.minZ, target.maxZ)
        );

        Vec3 direction = eye.subtract(closest);
        if (direction.lengthSqr() < 1.0E-4) return;

        Vec3 spoofedEye = closest.add(direction.normalize().scale(SPOOF_OFFSET));
        Vec3 spoofedFeet = spoofedEye.subtract(0.0, mc.player.getEyeY() - mc.player.getY(), 0.0);

        event.cancel();
        event.sendSilently(new ServerboundMovePlayerPacket.Pos(spoofedFeet.x, spoofedFeet.y, spoofedFeet.z, mc.player.onGround(), mc.player.horizontalCollision));
        event.sendSilently(event.packet);

        positionSent = true;
        pendingRestore = true;
    }
}
