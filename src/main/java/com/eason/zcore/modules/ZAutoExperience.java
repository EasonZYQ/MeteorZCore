package com.eason.zcore.modules;

import com.eason.zcore.MeteorZCore;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;

/**
 * Z Auto Experience 模块。
 * <p>
 * 参考 GCore 的 GAutoExperience：按配置的 tick 间隔自动扔经验瓶，
 * 达到配置的经验等级上限后停止，避免继续浪费经验瓶。
 */
public class ZAutoExperience extends Module {
    /** 默认设置分组。 */
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    /** 两次投掷之间的 tick 延迟。 */
    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        // 设置项名称
        .name("delay")
        // 设置项说明
        .description("The tick delay between throwing experience bottles.")
        // 默认值
        .defaultValue(2)
        // 允许输入的数值范围
        .range(0, 40)
        // 滑条显示的数值范围
        .sliderRange(0, 20)
        // 构建出设置对象
        .build()
    );

    /** 达到该经验等级后停止投掷。 */
    private final Setting<Integer> maxLevel = sgGeneral.add(new IntSetting.Builder()
        // 设置项名称
        .name("max-level")
        // 设置项说明
        .description("Stops throwing bottles when your experience level reaches this value.")
        // 默认值
        .defaultValue(30)
        // 允许输入的数值范围
        .range(0, 1000)
        // 滑条显示的数值范围
        .sliderRange(0, 100)
        // 构建出设置对象
        .build()
    );

    /** 剩余延迟 tick。 */
    private int delayLeft;

    /**
     * 构造方法，完成模块注册信息。
     */
    public ZAutoExperience() {
        // 调用父类构造：传入分类、模块名与描述
        super(MeteorZCore.CATEGORY, "z-auto-experience", "Automatically throws experience bottles until the configured level is reached.");
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
     * 每个客户端 tick 开始时调用，按间隔扔经验瓶。
     *
     * @param event tick 前事件
     */
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        // 玩家或世界不存在时直接返回
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        // 达到等级上限时停止
        if (mc.player.experienceLevel >= maxLevel.get()) return;

        // 延迟未结束时先等待
        if (delayLeft > 0) {
            delayLeft--;
            return;
        }

        // 查找快捷栏中的经验瓶
        FindItemResult bottle = InvUtils.findInHotbar(Items.EXPERIENCE_BOTTLE);
        // 没有经验瓶时不处理
        if (!bottle.found()) return;

        // 切换到经验瓶并保存原槽位
        InvUtils.swap(bottle.slot(), true);
        // 右键投掷经验瓶
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        // 切回原槽位
        InvUtils.swapBack();
        // 重置延迟
        delayLeft = delay.get();
    }
}
