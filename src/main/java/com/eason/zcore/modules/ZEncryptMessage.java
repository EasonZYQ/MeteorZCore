package com.eason.zcore.modules;

import com.eason.zcore.MeteorZCore;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.game.SendMessageEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.chat.Component;

import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Z Encrypt Message 模块。
 * <p>
 * 参考 GCore 的 GEncryptMessage：发送聊天时用密钥做异或加密并用 `$...$` 包裹，
 * 收到带 `$...$` 的密文时自动解密显示，实现简单的聊天加密通信。
 */
public class ZEncryptMessage extends Module {
    /** 匹配 `$密文$` 的正则表达式。 */
    private static final Pattern ENCRYPTED = Pattern.compile("\\$([A-Za-z0-9+/=]{4,})\\$");

    /** 默认设置分组。 */
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    /** 加密使用的密钥。 */
    private final Setting<String> key = sgGeneral.add(new StringSetting.Builder()
        // 设置项名称
        .name("key")
        // 设置项说明
        .description("The key used to encrypt and decrypt messages.")
        // 默认值
        .defaultValue("zcore")
        // 构建出设置对象
        .build()
    );

    /** 是否在解密后只显示明文。 */
    private final Setting<Boolean> hideOriginal = sgGeneral.add(new BoolSetting.Builder()
        // 设置项名称
        .name("hide-original")
        // 设置项说明
        .description("Replaces the whole message with the decrypted text.")
        // 默认开启
        .defaultValue(true)
        // 构建出设置对象
        .build()
    );

    /**
     * 构造方法，完成模块注册信息。
     */
    public ZEncryptMessage() {
        // 调用父类构造：传入分类、模块名与描述
        super(MeteorZCore.CATEGORY, "z-encrypt-message", "Encrypts outgoing chat and decrypts $...$ messages with a shared key.");
    }

    /**
     * 监听发送聊天事件，把消息加密后替换。
     *
     * @param event 发送聊天事件
     */
    @EventHandler
    private void onSendMessage(SendMessageEvent event) {
        // 命令与空消息不加密
        if (event.message == null || event.message.isBlank() || event.message.startsWith("/")) return;

        // 已经是密文格式时不重复加密
        if (ENCRYPTED.matcher(event.message).matches()) return;

        // 加密消息并替换为 `$密文$` 形式
        event.message = "$" + encrypt(event.message) + "$";
    }

    /**
     * 监听接收聊天事件，把密文解密后替换显示。
     *
     * @param event 接收聊天事件
     */
    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        // 获取原始聊天文本
        String raw = event.getMessage().getString();
        // 不含密文时不处理
        if (!raw.contains("$")) return;

        // 依次解密所有密文片段
        Matcher matcher = ENCRYPTED.matcher(raw);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            // 解出明文并替换原片段
            String plain = decrypt(matcher.group(1));
            // 隐藏原文时只保留明文
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(hideOriginal.get() ? plain : plain));
        }
        matcher.appendTail(buffer);

        // 有内容变化时更新聊天显示
        String result = buffer.toString();
        if (!result.equals(raw)) {
            event.setMessage(Component.literal(result));
        }
    }

    /**
     * 用密钥做异或加密并编码为 Base64。
     *
     * @param text 明文
     * @return Base64 密文
     */
    private String encrypt(String text) {
        // 取出密钥字节
        byte[] keyBytes = key.get().getBytes();
        // 明文字节
        byte[] data = text.getBytes();
        // 逐字节异或
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (data[i] ^ keyBytes[i % keyBytes.length]);
        }

        // 编码为 Base64 字符串
        return Base64.getEncoder().encodeToString(data);
    }

    /**
     * 解码 Base64 并用密钥做异或解密。
     *
     * @param text Base64 密文
     * @return 明文，格式错误时返回原文本
     */
    private String decrypt(String text) {
        try {
            // 取出密钥字节
            byte[] keyBytes = key.get().getBytes();
            // 解码 Base64
            byte[] data = Base64.getDecoder().decode(text);
            // 逐字节异或还原
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) (data[i] ^ keyBytes[i % keyBytes.length]);
            }

            return new String(data);
        } catch (IllegalArgumentException exception) {
            // 不是合法 Base64 时保留原文本
            return "$" + text + "$";
        }
    }
}
