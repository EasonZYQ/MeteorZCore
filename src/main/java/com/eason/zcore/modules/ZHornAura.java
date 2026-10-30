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
 * Z Horn Aura 模块。
 * <p>
 * 参考 GCore 的 GHornAura：按配置的时间间隔自动切换到山羊角并吹响，
 * 常用于快速触发山羊角的音效/效果。切换物品后会切回原来的槽位。
 */
public class ZHornAura extends Module {
    /** 默认设置分组。 */
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    /** 两次吹角之间的间隔（毫秒）。 */
    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        // 设置项名称
        .name("delay")
        // 设置项说明
        .description("The delay between using the goat horn in milliseconds.")
        // 默认值
        .defaultValue(8000)
        // 允许输入的数值范围
        .range(1, 60000)
        // 滑条显示的数值范围
        .sliderRange(100, 30000)
        // 构建出设置对象
        .build()
    );

    /** 上次使用山羊角的时间戳。 */
    private long lastUse;

    /**
     * 构造方法，完成模块注册信息。
     */
    public ZHornAura() {
        // 调用父类构造：传入分类、模块名与描述
        super(MeteorZCore.CATEGORY, "z-horn-aura", "Automatically uses a goat horn at a configured interval.");
    }

    /**
     * 模块启用时调用，重置计时。
     */
    @Override
    public void onActivate() {
        // 重置上次使用时间
        lastUse = 0;
    }

    /**
     * 每个客户端 tick 开始时调用，按间隔使用山羊角。
     *
     * @param event tick 前事件
     */
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        // 玩家或世界不存在时直接返回
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;

        // 未到间隔时间时直接返回
        if (System.currentTimeMillis() - lastUse < delay.get()) return;

        // 查找快捷栏中的山羊角
        FindItemResult horn = InvUtils.findInHotbar(Items.GOAT_HORN);
        // 没有山羊角时不处理
        if (!horn.found()) return;

        // 切换到山羊角并保存原槽位
        InvUtils.swap(horn.slot(), true);
        // 右键使用山羊角
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        // 切回原槽位
        InvUtils.swapBack();
        // 更新上次使用时间
        lastUse = System.currentTimeMillis();
    }
}
