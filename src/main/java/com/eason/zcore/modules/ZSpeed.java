package com.eason.zcore.modules;

import com.eason.zcore.MeteorZCore;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.phys.Vec3;

/**
 * Z Speed 模块。
 * <p>
 * 参考 GCore 的 GSpeed：保持玩家已有的移动方向，把每 tick 的水平速度提升到配置值，
 * 可选自动跳跃。属于最基础的加速实现，不改变垂直速度。
 */
public class ZSpeed extends Module {
    /** 默认设置分组。 */
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    /** 目标水平速度（格/tick）。 */
    private final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        // 设置项名称
        .name("speed")
        // 设置项说明
        .description("The horizontal speed in blocks per tick.")
        // 默认值
        .defaultValue(0.6)
        // 允许输入的数值范围
        .range(0.1, 5.0)
        // 滑条显示的数值范围
        .sliderRange(0.2, 2.0)
        // 构建出设置对象
        .build()
    );

    /** 是否在移动时自动跳跃。 */
    private final Setting<Boolean> autoJump = sgGeneral.add(new BoolSetting.Builder()
        // 设置项名称
        .name("auto-jump")
        // 设置项说明
        .description("Jumps automatically while moving on the ground.")
        // 默认开启
        .defaultValue(true)
        // 构建出设置对象
        .build()
    );

    /**
     * 构造方法，完成模块注册信息。
     */
    public ZSpeed() {
        // 调用父类构造：传入分类、模块名与描述
        super(MeteorZCore.CATEGORY, "z-speed", "Increases your horizontal movement speed.");
    }

    /**
     * 每个客户端 tick 开始时调用，调整水平速度。
     *
     * @param event tick 前事件
     */
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        // 玩家或世界不存在时直接返回
        if (mc.player == null || mc.level == null) return;

        // 当前速度
        Vec3 velocity = mc.player.getDeltaMovement();
        // 水平方向长度
        double horizontal = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);

        // 没有水平移动时不处理
        if (horizontal < 0.001) return;

        // 按比例放大到目标速度
        double factor = speed.get() / horizontal;
        mc.player.setDeltaMovement(velocity.x * factor, velocity.y, velocity.z * factor);

        // 自动跳跃：在地面且有水平速度时起跳
        if (autoJump.get() && mc.player.onGround()) {
            mc.player.jumpFromGround();
        }
    }
}
