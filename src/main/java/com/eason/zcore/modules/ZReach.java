package com.eason.zcore.modules;

import com.eason.zcore.MeteorZCore;
import com.eason.zcore.utils.ActionSpoofer;
import com.eason.zcore.utils.PositionSpoofer;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

/**
 * Z Reach 模块。
 * <p>
 * 实现分为两部分：
 * 1. 客户端部分：通过 Mixin 放大玩家自身的交互距离（见 {@link com.eason.zcore.mixin.PlayerMixin}），
 *    让准星可以在更远处选中方块或实体并触发对应动作。
 * 2. 服务端部分：在动作发包前先发送一个伪造的移动包，让服务端误以为玩家已经站在目标旁边，
 *    动作执行完后再在客户端 tick 结束时发送还原坐标。
 * <p>
 * 动作的判定与发包统一交给 {@link ActionSpoofer}，
 * 位置包的发送与还原统一交给 {@link PositionSpoofer}。
 */
public class ZReach extends Module implements ActionSpoofer.Policy {
    /** 默认设置分组。 */
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    /** 允许动作发生的最大距离。 */
    private final Setting<Double> maxDistance = sgGeneral.add(new DoubleSetting.Builder()
        // 设置项名称，界面标题由它自动生成
        .name("max-distance")
        // 设置项说明
        .description("Maximum distance at which actions are attempted.")
        // 默认值
        .defaultValue(10.0)
        // 允许输入的数值范围
        .range(0.0, 1000.0)
        // 滑条显示的数值范围
        .sliderRange(4.5, 50.0)
        // 构建出设置对象
        .build()
    );

    /** 是否扩展右键交互（方块与实体）的距离。 */
    private final Setting<Boolean> interaction = sgGeneral.add(new BoolSetting.Builder()
        // 设置项名称
        .name("interaction")
        // 设置项说明
        .description("Extend the distance at which blocks and entities can be right-clicked.")
        // 默认开启
        .defaultValue(true)
        // 构建出设置对象
        .build()
    );

    /** 是否扩展方块放置的距离。 */
    private final Setting<Boolean> placing = sgGeneral.add(new BoolSetting.Builder()
        // 设置项名称
        .name("placing")
        // 设置项说明
        .description("Extend the distance at which blocks can be placed.")
        // 默认开启
        .defaultValue(true)
        // 构建出设置对象
        .build()
    );

    /** 是否扩展实体攻击的距离。 */
    private final Setting<Boolean> attack = sgGeneral.add(new BoolSetting.Builder()
        // 设置项名称
        .name("attack")
        // 设置项说明
        .description("Extend the distance at which entities can be attacked.")
        // 默认开启
        .defaultValue(true)
        // 构建出设置对象
        .build()
    );

    /** 是否扩展方块挖掘的距离。 */
    private final Setting<Boolean> mining = sgGeneral.add(new BoolSetting.Builder()
        // 设置项名称
        .name("mining")
        // 设置项说明
        .description("Extend the distance at which blocks can be broken.")
        // 默认开启
        .defaultValue(true)
        // 构建出设置对象
        .build()
    );

    /**
     * 构造方法，完成模块注册信息。
     */
    public ZReach() {
        // 调用父类构造：传入分类、模块名与描述
        super(MeteorZCore.CATEGORY, "z-reach", "Increases your reach for interaction, placing, attack and mining by making the server think you are next to the target.");
    }

    /**
     * 计算方块交互距离需要额外增加的值，供 {@link com.eason.zcore.mixin.PlayerMixin} 调用。
     *
     * @param vanillaRange 原版交互距离
     * @return 需要额外增加的距离，未启用或已关闭相关开关时返回 0
     */
    public double extraBlockReach(double vanillaRange) {
        // 模块启用且交互、放置或挖掘开关打开时，把距离补足到 max-distance
        return isActive() && (interaction.get() || placing.get() || mining.get()) ? Math.max(0, maxDistance.get() - vanillaRange) : 0;
    }

    /**
     * 计算实体交互距离需要额外增加的值，供 {@link com.eason.zcore.mixin.PlayerMixin} 调用。
     *
     * @param vanillaRange 原版交互距离
     * @return 需要额外增加的距离，未启用或已关闭相关开关时返回 0
     */
    public double extraEntityReach(double vanillaRange) {
        // 模块启用且交互或攻击开关打开时，把距离补足到 max-distance
        return isActive() && (interaction.get() || attack.get()) ? Math.max(0, maxDistance.get() - vanillaRange) : 0;
    }

    /**
     * 判断某类动作是否允许伪造，供 {@link ActionSpoofer} 调用。
     *
     * @param action 动作类型
     * @return 对应的开关打开时返回 true
     */
    @Override
    public boolean allow(ActionSpoofer.Action action) {
        // 根据动作类型返回对应的设置项
        return switch (action) {
            case INTERACT -> interaction.get();
            case PLACE -> placing.get();
            case ATTACK -> attack.get();
            case MINE -> mining.get();
        };
    }

    /**
     * 返回允许伪造的最大距离，供 {@link ActionSpoofer} 调用。
     *
     * @return 最大距离
     */
    @Override
    public double maxDistance() {
        return maxDistance.get();
    }

    /**
     * 监听发包完成事件，把伪造坐标的还原工作交给共享工具类。
     *
     * @param event 发包完成事件
     */
    @EventHandler
    private void onPacketSent(PacketEvent.Sent event) {
        // 交给共享工具类处理 tick 结束包与坐标还原
        PositionSpoofer.handleSent(event);
    }

    /**
     * 监听发包事件，在动作执行前插入伪造坐标。
     *
     * @param event 发包事件
     */
    @EventHandler
    private void onSendPacket(PacketEvent.Send event) {
        // 交给共享工具类按本模块的策略处理
        ActionSpoofer.handleSend(event, this);
    }
}
