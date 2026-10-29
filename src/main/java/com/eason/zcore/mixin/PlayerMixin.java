package com.eason.zcore.mixin;

import com.eason.zcore.modules.ZReach;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 玩家类的 Mixin。
 * <p>
 * 用于在客户端放大玩家自身的交互距离，使准星能够在更远的位置选中方块或实体。
 */
@Mixin(Player.class)
public abstract class PlayerMixin {
    /**
     * 修改方块交互距离，在原版数值上叠加 Z Reach 提供的额外距离。
     *
     * @param cir 方法返回值回调
     */
    @Inject(method = "blockInteractionRange", at = @At("RETURN"), cancellable = true)
    private void zcore$blockInteractionRange(CallbackInfoReturnable<Double> cir) {
        // 获取 Meteor 的模块系统，启动早期可能为空
        Modules modules = Modules.get();
        // 模块系统为空时直接返回，保持原版数值
        if (modules == null) return;

        // 获取 Z Reach 模块实例
        ZReach zReach = modules.get(ZReach.class);
        // 模块尚未注册时直接返回，保持原版数值
        if (zReach == null) return;

        // 计算需要额外增加的方块交互距离
        double extra = zReach.extraBlockReach(cir.getReturnValueD());
        // 额外距离大于 0 时才改写返回值
        if (extra > 0) cir.setReturnValue(cir.getReturnValueD() + extra);
    }

    /**
     * 修改实体交互距离，在原版数值上叠加 Z Reach 提供的额外距离。
     *
     * @param cir 方法返回值回调
     */
    @Inject(method = "entityInteractionRange", at = @At("RETURN"), cancellable = true)
    private void zcore$entityInteractionRange(CallbackInfoReturnable<Double> cir) {
        // 获取 Meteor 的模块系统，启动早期可能为空
        Modules modules = Modules.get();
        // 模块系统为空时直接返回，保持原版数值
        if (modules == null) return;

        // 获取 Z Reach 模块实例
        ZReach zReach = modules.get(ZReach.class);
        // 模块尚未注册时直接返回，保持原版数值
        if (zReach == null) return;

        // 计算需要额外增加的实体交互距离
        double extra = zReach.extraEntityReach(cir.getReturnValueD());
        // 额外距离大于 0 时才改写返回值
        if (extra > 0) cir.setReturnValue(cir.getReturnValueD() + extra);
    }
}
