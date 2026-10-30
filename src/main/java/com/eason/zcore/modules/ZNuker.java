package com.eason.zcore.modules;

import com.eason.zcore.MeteorZCore;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;

/**
 * Z Nuker 模块。
 * <p>
 * 参考 GCore 的 GNuker：以玩家为中心扫描一定范围内的方块，
 * 按距离从近到远逐个破坏可破坏方块。每进行 `break-delay` 个 tick 处理一次，
 * 可通过 `blocks-per-tick` 控制每次破坏的数量。
 */
public class ZNuker extends Module {
    /** 默认设置分组。 */
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    /** 扫描半径（水平与垂直方向相同）。 */
    private final Setting<Integer> scanBorder = sgGeneral.add(new IntSetting.Builder()
        // 设置项名称
        .name("scan-border")
        // 设置项说明
        .description("The radius around you in which blocks are searched.")
        // 默认值
        .defaultValue(4)
        // 允许输入的数值范围
        .range(1, 10)
        // 滑条显示的数值范围
        .sliderRange(1, 6)
        // 构建出设置对象
        .build()
    );

    /** 允许破坏的最大距离。 */
    private final Setting<Double> breakRange = sgGeneral.add(new DoubleSetting.Builder()
        // 设置项名称
        .name("break-range")
        // 设置项说明
        .description("The maximum distance at which blocks can be broken.")
        // 默认值
        .defaultValue(5.0)
        // 允许输入的数值范围
        .range(0.0, 1000.0)
        // 滑条显示的数值范围
        .sliderRange(2.0, 10.0)
        // 构建出设置对象
        .build()
    );

    /** 两次挖掘之间的 tick 延迟。 */
    private final Setting<Integer> breakDelay = sgGeneral.add(new IntSetting.Builder()
        // 设置项名称
        .name("break-delay")
        // 设置项说明
        .description("The tick delay between nuking cycles.")
        // 默认值
        .defaultValue(1)
        // 允许输入的数值范围
        .range(0, 10)
        // 构建出设置对象
        .build()
    );

    /** 每次循环最多破坏的方块数量。 */
    private final Setting<Integer> blocksPerTick = sgGeneral.add(new IntSetting.Builder()
        // 设置项名称
        .name("blocks-per-tick")
        // 设置项说明
        .description("How many blocks to break per cycle.")
        // 默认值
        .defaultValue(1)
        // 允许输入的数值范围
        .range(1, 10)
        // 构建出设置对象
        .build()
    );

    /** 剩余延迟 tick。 */
    private int delayLeft;

    /**
     * 构造方法，完成模块注册信息。
     */
    public ZNuker() {
        // 调用父类构造：传入分类、模块名与描述
        super(MeteorZCore.CATEGORY, "z-nuker", "Breaks blocks around you from the closest to the farthest.");
    }

    /**
     * 模块启用时调用，重置延迟。
     */
    @Override
    public void onActivate() {
        // 重置剩余延迟
        delayLeft = 0;
    }

    /**
     * 每个客户端 tick 开始时调用，扫描并破坏方块。
     *
     * @param event tick 前事件
     */
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        // 玩家或世界不存在时直接返回
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;

        // 延迟未结束时先等待
        if (delayLeft > 0) {
            delayLeft--;
            return;
        }

        // 以玩家脚下方块为中心扫描
        BlockPos center = mc.player.blockPosition();
        int radius = scanBorder.get();
        int broken = 0;

        // 按距离从近到远遍历扫描范围内的方块
        for (int distance = 0; distance <= radius && broken < blocksPerTick.get(); distance++) {
            for (int dx = -distance; dx <= distance && broken < blocksPerTick.get(); dx++) {
                for (int dz = -distance; dz <= distance && broken < blocksPerTick.get(); dz++) {
                    for (int dy = -radius; dy <= radius && broken < blocksPerTick.get(); dy++) {
                        // 跳过不在当前扫描层的位置
                        if (Math.abs(dx) + Math.abs(dz) != distance) continue;

                        BlockPos pos = center.offset(dx, dy, dz);
                        // 空气方块跳过
                        if (mc.level.getBlockState(pos).isAir()) continue;
                        // 超出破坏距离时跳过
                        double distanceSq = mc.player.position().distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                        if (distanceSq > breakRange.get() * breakRange.get()) continue;
                        // 不可破坏的方块跳过
                        if (!BlockUtils.canBreak(pos)) continue;

                        // 破坏方块
                        BlockUtils.breakBlock(pos, true);
                        broken++;
                    }
                }
            }
        }

        // 本次循环没有破坏任何方块时也重置延迟，避免空转过快
        delayLeft = breakDelay.get();
    }
}
