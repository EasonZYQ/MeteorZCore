package com.eason.zcore.hud;

import com.eason.zcore.MeteorZCore;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;

/**
 * HUD 元素示例。
 * <p>
 * 用于演示 Meteor 的 HUD 元素注册与绘制流程，界面分组为 “Z Core”。
 */
public class HudExample extends HudElement {
    /**
     * HUD 元素的注册信息，包含所属分组、元素名称、描述与创建工厂。
     * <p>
     * 名称参数需要使用 kebab-case 命名法。
     */
    public static final HudElementInfo<HudExample> INFO = new HudElementInfo<>(MeteorZCore.HUD_GROUP, "example", "HUD element example.", HudExample::new);

    /**
     * 构造方法，把注册信息交给父类保存。
     */
    public HudExample() {
        // 调用父类构造方法，绑定该元素的注册信息
        super(INFO);
    }

    /**
     * HUD 每帧绘制回调。
     *
     * @param renderer HUD 渲染器，提供文字与矩形绘制能力
     */
    @Override
    public void render(HudRenderer renderer) {
        // 根据文字的实际尺寸设置元素大小，供布局与对齐使用
        setSize(renderer.textWidth("Example element", true), renderer.textHeight(true));

        // 绘制背景矩形，颜色为浅灰色
        renderer.quad(x, y, getWidth(), getHeight(), Color.LIGHT_GRAY);

        // 绘制白色文字，坐标为元素左上角
        renderer.text("Example element", x, y, Color.WHITE, true);
    }
}
