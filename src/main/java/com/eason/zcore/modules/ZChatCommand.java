package com.eason.zcore.modules;

import com.eason.zcore.MeteorZCore;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;

/**
 * Z Chat Command 模块。
 * <p>
 * 参考 GCore 的 GChatCommand：监听收到的聊天内容，当文本包含指定触发词时
 * 自动发送一条配置好的命令（例如自动回城、自动购买等），并带有防重复发送的冷却。
 */
public class ZChatCommand extends Module {
    /** 默认设置分组。 */
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    /** 触发执行命令的聊天关键词。 */
    private final Setting<String> trigger = sgGeneral.add(new StringSetting.Builder()
        // 设置项名称
        .name("trigger")
        // 设置项说明
        .description("The text that must appear in a received chat message.")
        // 默认值
        .defaultValue("teleport")
        // 构建出设置对象
        .build()
    );

    /** 触发后发送的命令。 */
    private final Setting<String> command = sgGeneral.add(new StringSetting.Builder()
        // 设置项名称
        .name("command")
        // 设置项说明
        .description("The command to send when the trigger is found.")
        // 默认值
        .defaultValue("/spawn")
        // 构建出设置对象
        .build()
    );

    /** 两次执行之间的冷却（毫秒）。 */
    private final Setting<Integer> cooldown = sgGeneral.add(new IntSetting.Builder()
        // 设置项名称
        .name("cooldown")
        // 设置项说明
        .description("The cooldown between two command executions in milliseconds.")
        // 默认值
        .defaultValue(5000)
        // 允许输入的数值范围
        .range(0, 60000)
        // 滑条显示的数值范围
        .sliderRange(1000, 30000)
        // 构建出设置对象
        .build()
    );

    /** 上次执行命令的时间戳。 */
    private long lastRun;

    /**
     * 构造方法，完成模块注册信息。
     */
    public ZChatCommand() {
        // 调用父类构造：传入分类、模块名与描述
        super(MeteorZCore.CATEGORY, "z-chat-command", "Runs a configured command when a chat message contains the trigger text.");
    }

    /**
     * 模块启用时调用，重置冷却时间。
     */
    @Override
    public void onActivate() {
        // 重置上次执行时间
        lastRun = 0;
    }

    /**
     * 监听接收聊天事件，匹配触发词后发送命令。
     *
     * @param event 接收聊天事件
     */
    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        // 触发词为空时不处理
        if (trigger.get().isBlank()) return;

        // 获取聊天文本
        String raw = event.getMessage().getString();
        // 不包含触发词时不处理
        if (!raw.contains(trigger.get())) return;
        // 冷却未结束时直接返回
        if (System.currentTimeMillis() - lastRun < cooldown.get()) return;

        // 发送配置好的命令
        ChatUtils.sendPlayerMsg(command.get(), false);
        // 更新上次执行时间
        lastRun = System.currentTimeMillis();
    }
}
