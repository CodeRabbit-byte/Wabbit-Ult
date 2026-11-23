package com.wabbit.ultimate.cosmetics;

import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;

/** Hooks the cosmetics into the player renderer. A failure here must never crash the game. */
public class CosmeticFeature extends FeatureRenderer<PlayerEntityRenderState, PlayerEntityModel> {
    public CosmeticFeature(FeatureRendererContext<PlayerEntityRenderState, PlayerEntityModel> context) {
        super(context);
    }

    @Override
    public void render(MatrixStack matrices, OrderedRenderCommandQueue queue, int light,
                       PlayerEntityRenderState state, float limbAngle, float limbDistance) {
        try {
            CosmeticRenderer.render(matrices, queue, state, light);
        } catch (Exception e) {
            CosmeticRenderer.reportOnce(e);
        }
    }
}
