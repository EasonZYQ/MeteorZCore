package com.eason.zcore.mixin;

import com.eason.zcore.MeteorZCore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.main.GameConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin 示例类。
 * <p>
 * 演示如何在 Minecraft 类的方法中注入代码。更多资料可参考：
 * <ul>
 * <li><a href="https://fabricmc.net/wiki/tutorial:mixin_introduction">FabricMC 官方教程</a></li>
 * <li><a href="https://github.com/SpongePowered/Mixin/wiki">Mixin 官方 Wiki</a></li>
 * <li><a href="https://github.com/LlamaLad7/MixinExtras/wiki">MixinExtras Wiki</a></li>
 * <li><a href="https://jenkins.liteloader.com/view/Other/job/Mixin/javadoc/allclasses-noframe.html">Mixin Javadoc</a></li>
 * <li><a href="https://github.com/2xsaiko/mixin-cheatsheet">Mixin 速查表</a></li>
 * </ul>
 */
@Mixin(Minecraft.class)
public abstract class ExampleMixin {
    /**
     * 注入到 Minecraft 构造方法（{@code <init>}）末尾（{@code TAIL}）的示例方法。
     *
     * @param gameConfig 游戏启动配置
     * @param ci         注入回调信息
     */
    @Inject(method = "<init>", at = @At("TAIL"))
    private void onGameLoaded(GameConfig gameConfig, CallbackInfo ci) {
        // 在游戏客户端构造完成时输出一条日志
        MeteorZCore.LOG.info("Hello from ExampleMixin!");
    }
}
