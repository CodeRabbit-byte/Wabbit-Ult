package com.wabbit.ultimate.cosmetics;

import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.LoadedEntityModels;
import net.minecraft.client.render.entity.model.PlayerCapeModel;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;

/** Hooks the cosmetics into the player renderer. A failure here must never crash the game. */
public class CosmeticFeature extends FeatureRenderer<PlayerEntityRenderState, PlayerEntityModel> {
    private final PlayerCapeModel capeModel;

    public CosmeticFeature(FeatureRendererContext<PlayerEntityRenderState, PlayerEntityModel> context,
                           LoadedEntityModels models) {
        super(context);
        this.capeModel = new PlayerCapeModel(models.getModelPart(EntityModelLayers.PLAYER_CAPE));
    }

    @Override
    public void render(MatrixStack matrices, OrderedRenderCommandQueue queue, int light,
                       PlayerEntityRenderState state, float limbAngle, float limbDistance) {
        try {
            CosmeticRenderer.render(matrices, queue, state, light, getContextModel(), capeModel);
        } catch (Exception e) {
            CosmeticRenderer.reportOnce(e);
        }
    }
}
