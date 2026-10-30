package com.eason.zcore.modules;

import com.eason.zcore.MeteorZCore;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

import java.util.Random;

/**
 * Z Random Move 模块。
 * <p>
 * 参考 GCore 的 GRandomMove：每隔一段时间随机选择水平方向给玩家一个速度脉冲，
 * 可选自动跳跃，让角色像随机走动一样，用于避免长时间静止。
 */
public class ZRandomMove extends Module {
    /** 默认设置分组。 */
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    /** 两次随机移动之间的 tick 间隔。 */
    private final Setting<Integer> interval = sgGeneral.add(new IntSetting.Builder()
        // 设置项名称
        .name("interval")
        // 设置项说明
        .description("The tick interval between random movement impulses.")
        // 默认值
        .defaultValue(20)
        // 允许输入的数值范围
        .range(1, 200)
        // 滑条显示的数值范围
        .sliderRange(5, 60)
        // 构建出设置对象
        .build()
    );

    /** 每次脉冲的水平速度。 */
    private final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        // 设置项名称
        .name("speed")
        // 设置项说明
        .description("The horizontal speed of each random impulse.")
        // 默认值
        .defaultValue(0.35)
        // 允许输入的数值范围
        .range(0.05, 2.0)
        // 滑条显示的数值范围
        .sliderRange(0.1, 1.0)
        // 构建出设置对象
        .build()
    );

    /** 是否在移动时起跳。 */
    private final Setting<Boolean> jump = sgGeneral.add(new BoolSetting.Builder()
        // 设置项名称
        .name("jump")
        // 设置项说明
        .description("Jumps when a random impulse is applied on the ground.")
        // 默认开启
        .defaultValue(true)
        // 构建出设置对象
        .build()
    );

    /** 随机数生成器。 */
    private final Random random = new Random();
    /** 剩余间隔 tick。 */
    private int delayLeft;

    /**
     * 构造方法，完成模块注册信息。
     */
    public ZRandomMove() {
        // 调用父类构造：传入分类、模块名与描述
        super(MeteorZCore.CATEGORY, "z-random-move", "Moves you in random directions at a configured interval.");
    }

    /**
     * 模块启用时调用，重置间隔。
     */
    @Override
    public void onActivate() {
        // 重置剩余间隔
        delayLeft = 0;
    }

    /**
     * 每个客户端 tick 开始时调用，按间隔施加随机速度。
     *
     * @param event tick 前事件
     */
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        // 玩家或世界不存在时直接返回
        if (mc.player == null || mc.level == null) return;

        // 间隔未到时先等待
        if (delayLeft > 0) {
            delayLeft--;
            return;
        }

        // 随机生成一个水平方向
        double angle = random.nextDouble() * Math.PI * 2.0;
        double vx = Math.cos(angle) * speed.get();
        double vz = Math.sin(angle) * speed.get();

        // 保留垂直速度，替换水平速度
        mc.player.setDeltaMovement(vx, mc.player.getDeltaMovement().y, vz);

        // 在地面时按配置起跳
        if (jump.get() && mc.player.onGround()) {
            mc.player.jumpFromGround();
        }

        // 重置间隔
        delayLeft = interval.get();
    }
}
