package com.eason.zcore.modules;

import com.eason.zcore.MeteorZCore;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Z Auto Spammer 模块。
 * <p>
 * 参考 GCore 的 GAutoSpammer：按配置的间隔自动在聊天栏发送消息，
 * 支持 `{randomPlayer}` 占位符（随机替换成在线玩家名），用于刷屏或提醒。
 */
public class ZAutoSpammer extends Module {
    /** 默认设置分组。 */
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    /** 发送的聊天内容。 */
    private final Setting<String> message = sgGeneral.add(new StringSetting.Builder()
        // 设置项名称
        .name("message")
        // 设置项说明
        .description("The message to send. Supports {randomPlayer}.")
        // 默认内容
        .defaultValue("Hello {randomPlayer}")
        // 构建出设置对象
        .build()
    );

    /** 两次发送之间的 tick 延迟。 */
    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        // 设置项名称
        .name("delay")
        // 设置项说明
        .description("The tick delay between messages.")
        // 默认值
        .defaultValue(100)
        // 允许输入的数值范围
        .range(1, 2000)
        // 滑条显示的数值范围
        .sliderRange(10, 500)
        // 构建出设置对象
        .build()
    );

    /** 是否把 {randomPlayer} 替换为随机在线玩家名。 */
    private final Setting<Boolean> randomPlayer = sgGeneral.add(new BoolSetting.Builder()
        // 设置项名称
        .name("random-player")
        // 设置项说明
        .description("Replaces {randomPlayer} with a random online player name.")
        // 默认开启
        .defaultValue(true)
        // 构建出设置对象
        .build()
    );

    /** 剩余延迟 tick。 */
    private int delayLeft;
    /** 随机数生成器。 */
    private final Random random = new Random();

    /**
     * 构造方法，完成模块注册信息。
     */
    public ZAutoSpammer() {
        // 调用父类构造：传入分类、模块名与描述
        super(MeteorZCore.CATEGORY, "z-auto-spammer", "Automatically sends chat messages at a configured interval.");
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
     * 每个客户端 tick 开始时调用，按间隔发送聊天消息。
     *
     * @param event tick 前事件
     */
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        // 玩家或世界不存在时直接返回
        if (mc.player == null || mc.level == null) return;

        // 延迟未结束时先等待
        if (delayLeft > 0) {
            delayLeft--;
            return;
        }

        // 取出配置的文本
        String text = message.get();
        // 开启随机玩家替换时，把占位符换成在线玩家名
        if (randomPlayer.get() && text.contains("{randomPlayer}")) {
            text = text.replace("{randomPlayer}", pickRandomPlayer());
        }

        // 文本为空时不发送
        if (text.isBlank()) return;

        // 通过聊天工具发送消息，且不加入历史记录
        ChatUtils.sendPlayerMsg(text, false);
        // 重置延迟
        delayLeft = delay.get();
    }

    /**
     * 随机选择一个在线玩家名。
     *
     * @return 玩家名，没有其他玩家时返回 "player"
     */
    private String pickRandomPlayer() {
        // 收集除自己以外的在线玩家
        List<String> names = new ArrayList<>();
        for (Player player : mc.level.players()) {
            if (player != mc.player) names.add(player.getGameProfile().name());
        }

        // 没有其他玩家时使用默认文本
        if (names.isEmpty()) return "player";

        // 随机返回一个名字
        return names.get(random.nextInt(names.size()));
    }
}
