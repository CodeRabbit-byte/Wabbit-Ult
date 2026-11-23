package com.wabbit.ultimate.mixin;

import com.wabbit.ultimate.cosmetics.CosmeticRenderer;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.entity.PlayerLikeEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hides the player's own cape while a Wabbit cape is shown, so the two never draw on top of each other. */
@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerEntityRendererMixin {
    @Inject(method = "updateRenderState(Lnet/minecraft/entity/PlayerLikeEntity;Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;F)V",
            at = @At("TAIL"))
    private void wabbitultimate$hideVanillaCape(PlayerLikeEntity entity, PlayerEntityRenderState state,
                                                float tickProgress, CallbackInfo ci) {
        try {
            if (CosmeticRenderer.replacesVanillaCape(entity)) state.capeVisible = false;
        } catch (RuntimeException ignored) {
            // never break player rendering over a cosmetic
        }
    }
}
