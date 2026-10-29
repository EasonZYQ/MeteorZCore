package com.eason.zcore;

import com.eason.zcore.hud.HudExample;
import com.eason.zcore.modules.ZReach;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

/**
 * MeteorZCore 插件入口。
 * <p>
 * 该类由 fabric.mod.json 中的 {@code meteor} entrypoint 加载，
 * 负责注册模块、HUD 元素以及模块分类。
 */
public class MeteorZCore extends MeteorAddon {
    /** 插件日志输出器。 */
    public static final Logger LOG = LogUtils.getLogger();
    /** 插件专属模块分类，界面上显示为 “Z Core”。 */
    public static final Category CATEGORY = new Category("Z Core");
    /** 插件专属 HUD 分组，界面上显示为 “Z Core”。 */
    public static final HudGroup HUD_GROUP = new HudGroup("Z Core");

    /**
     * 插件初始化回调，由 Meteor 在加载插件时调用。
     */
    @Override
    public void onInitialize() {
        // 输出初始化日志
        LOG.info("Initializing MeteorZCore");

        // 将 Z Reach 模块注册到 Meteor 的模块系统
        Modules.get().add(new ZReach());

        // 注册 HUD 元素，传入其静态注册信息
        Hud.get().register(HudExample.INFO);
    }

    /**
     * 注册模块分类的回调。
     * <p>
     * Meteor 只允许在该回调中注册分类，提前注册会抛出异常。
     */
    @Override
    public void onRegisterCategories() {
        // 注册插件自己的模块分类
        Modules.registerCategory(CATEGORY);
    }

    /**
     * 返回插件所在的包名。
     * <p>
     * Meteor 会使用该包名注册事件总线的 lambda 工厂，
     * 因此必须覆盖所有包含事件处理方法的类所在包。
     *
     * @return 插件根包名
     */
    @Override
    public String getPackage() {
        return "com.eason.zcore";
    }

    /**
     * 返回插件的 GitHub 仓库信息，用于界面上的来源链接。
     *
     * @return 仓库信息
     */
    @Override
    public GithubRepo getRepo() {
        // 构造 GitHub 仓库对象，依次传入仓库所有者和仓库名称
        return new GithubRepo("EasonZYQ", "MeteorZCore");
    }
}
