package com.eason.zcore.mixin;

import com.eason.zcore.modules.ZReach;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PlayerMixin {
    @Inject(method = "blockInteractionRange", at = @At("RETURN"), cancellable = true)
    private void zcore$blockInteractionRange(CallbackInfoReturnable<Double> cir) {
        Modules modules = Modules.get();
        if (modules == null) return;

        ZReach zReach = modules.get(ZReach.class);
        if (zReach == null) return;

        double extra = zReach.extraBlockReach(cir.getReturnValueD());
        if (extra > 0) cir.setReturnValue(cir.getReturnValueD() + extra);
    }

    @Inject(method = "entityInteractionRange", at = @At("RETURN"), cancellable = true)
    private void zcore$entityInteractionRange(CallbackInfoReturnable<Double> cir) {
        Modules modules = Modules.get();
        if (modules == null) return;

        ZReach zReach = modules.get(ZReach.class);
        if (zReach == null) return;

        double extra = zReach.extraEntityReach(cir.getReturnValueD());
        if (extra > 0) cir.setReturnValue(cir.getReturnValueD() + extra);
    }
}
