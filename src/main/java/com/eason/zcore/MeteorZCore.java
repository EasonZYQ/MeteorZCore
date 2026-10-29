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

public class MeteorZCore extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Z Core");
    public static final HudGroup HUD_GROUP = new HudGroup("Z Core");

    @Override
    public void onInitialize() {
        LOG.info("Initializing MeteorZCore");

        // Modules
        Modules.get().add(new ZReach());

        // HUD
        Hud.get().register(HudExample.INFO);
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.eason.zcore";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("EasonZYQ", "MeteorZCore");
    }
}
