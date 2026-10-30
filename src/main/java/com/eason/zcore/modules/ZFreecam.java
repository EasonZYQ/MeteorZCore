package com.eason.zcore.modules;

import com.eason.zcore.MeteorZCore;
import com.eason.zcore.utils.ActionSpoofer;
import com.eason.zcore.utils.PositionSpoofer;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.Freecam;
import meteordevelopment.orbit.EventHandler;

/**
 * Z Freecam 模块。
 * <p>
 * 直接复用 Meteor 自带的 Freecam 实现（相机移动、准星拾取等行为完全一致），
 * 并在 Freecam 启用期间为交互、放置、攻击、挖掘四类动作插入伪造位置包，
 * 使玩家在相机远离身体时依然可以对远处的方块和实体执行操作。
 * <p>
 * 位置包的发送与还原统一交给 {@link PositionSpoofer} 管理。
 */
public class ZFreecam extends Module implements ActionSpoofer.Policy {
    /** 默认设置分组。 */
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    /** 允许动作发生的最大距离。 */
    private final Setting<Double> maxDistance = sgGeneral.add(new DoubleSetting.Builder()
        // 设置项名称
        .name("max-distance")
        // 设置项说明
        .description("Maximum distance at which actions are attempted while in freecam.")
        // 默认值
        .defaultValue(100.0)
        // 允许输入的数值范围
        .range(0.0, 1000.0)
        // 滑条显示的数值范围
        .sliderRange(5.0, 200.0)
        // 构建出设置对象
        .build()
    );

    /** Meteor 自带的 Freecam 模块实例。 */
    private Freecam freecam;
    /** 记录 Freecam 是否由本模块开启，用于关闭时恢复原状态。 */
    private boolean enabledByUs;

    /**
     * 构造方法，完成模块注册信息。
     */
    public ZFreecam() {
        // 调用父类构造：传入分类、模块名与描述
        super(MeteorZCore.CATEGORY, "z-freecam", "Freecam with long range interaction, placing, attack and mining.");
    }

    /**
     * 模块启用时调用，尝试开启 Meteor 自带的 Freecam。
     */
    @Override
    public void onActivate() {
        // 获取 Meteor 的 Freecam 模块实例
        freecam = Modules.get().get(Freecam.class);
        // 重置标记，避免误关别人开启的 Freecam
        enabledByUs = false;

        // 只有在世界中且 Freecam 未启用时才由本模块开启
        if (freecam != null && !freecam.isActive() && mc.player != null && mc.level != null) {
            // 开启 Freecam，其自身的相机逻辑会立即生效
            freecam.enable();
            // 记录是本次开启
            enabledByUs = true;
        }
    }

    /**
     * 模块关闭时调用，恢复 Freecam 的原始状态。
     */
    @Override
    public void onDeactivate() {
        // 仅关闭由本模块开启的 Freecam
        if (enabledByUs && freecam != null && freecam.isActive()) {
            // 关闭 Freecam
            freecam.disable();
        }

        enabledByUs = false;
    }

    /**
     * 每个客户端 tick 结束时调用，确保 Freecam 始终处于启用状态。
     *
     * @param event tick 后事件
     */
    @EventHandler
    private void onTick(TickEvent.Post event) {
        // 玩家或世界不存在时不处理
        if (freecam == null || mc.player == null || mc.level == null) return;

        // Freecam 被外部关闭时重新开启，保持本模块的相机效果
        if (!freecam.isActive()) {
            // 重新开启 Freecam
            freecam.enable();
            enabledByUs = true;
        }
    }

    /**
     * 判断某类动作是否允许伪造，供 {@link ActionSpoofer} 调用。
     *
     * @param action 动作类型
     * @return 自由视角下所有动作都允许
     */
    @Override
    public boolean allow(ActionSpoofer.Action action) {
        return true;
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
     * 监听发包事件，在动作执行前插入伪造坐标。
     *
     * @param event 发包事件
     */
    @EventHandler
    private void onSendPacket(PacketEvent.Send event) {
        // 交给共享工具类按本模块的策略处理
        ActionSpoofer.handleSend(event, this);
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
}
