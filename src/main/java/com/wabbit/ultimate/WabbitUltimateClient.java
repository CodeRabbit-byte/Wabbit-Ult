package com.wabbit.ultimate;

import com.wabbit.ultimate.config.ConfigManager;
import com.wabbit.ultimate.cosmetics.CapeLoader;
import com.wabbit.ultimate.cosmetics.CosmeticFeature;
import com.wabbit.ultimate.gui.WabbitScreen;
import com.wabbit.ultimate.gui.DonationsScreen;
import com.wabbit.ultimate.hud.DonationAlerts;
import com.wabbit.ultimate.hud.PerfMonitor;
import com.wabbit.ultimate.hud.PerformanceHud;
import com.wabbit.ultimate.hud.SpotifyHud;
import com.wabbit.ultimate.spotify.SpotifyService;
import com.wabbit.ultimate.mods.ModManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WabbitUltimateClient implements ClientModInitializer {
    public static final String MOD_ID = "wabbitultimate";
    public static final Logger LOG = LoggerFactory.getLogger("WabbitUltimate");
    private static KeyBinding openMenu;

    @Override
    public void onInitializeClient() {
        ConfigManager.load();
        try {
            ModManager.init();
        } catch (Exception e) {
            LOG.error("Mod manager failed to start; the rest of the mod still works", e);
        }

        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));
        openMenu = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.wabbitultimate.open_menu",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_SHIFT,
                category));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openMenu.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new WabbitScreen());
                }
            }
        });

        HudElementRegistry.attachElementAfter(
                VanillaHudElements.MISC_OVERLAYS,
                Identifier.of(MOD_ID, "performance"),
                (ctx, tickCounter) -> {
                    try {
                        PerformanceHud.render(ctx);
                    } catch (Exception e) {
                        LOG.error("HUD render failed", e);
                    }
                });

        HudElementRegistry.attachElementAfter(
                Identifier.of(MOD_ID, "performance"),
                Identifier.of(MOD_ID, "donations"),
                (ctx, tickCounter) -> {
                    try {
                        MinecraftClient mc = MinecraftClient.getInstance();
                        // the Donation Alerts menu draws its own live preview
                        if (mc.options.hudHidden || mc.currentScreen instanceof DonationsScreen) return;
                        DonationAlerts.render(ctx, ctx.getScaledWindowWidth(), ctx.getScaledWindowHeight());
                    } catch (Exception e) {
                        LOG.error("Donation alert render failed", e);
                    }
                });

        HudElementRegistry.attachElementAfter(
                Identifier.of(MOD_ID, "donations"),
                Identifier.of(MOD_ID, "spotify"),
                (ctx, tickCounter) -> {
                    try {
                        SpotifyHud.render(ctx, ctx.getScaledWindowWidth(), ctx.getScaledWindowHeight());
                    } catch (Exception e) {
                        LOG.error("Spotify HUD render failed", e);
                    }
                });
        try {
            PerfMonitor.init();
        } catch (Exception e) {
            LOG.error("Performance monitor failed to start; the rest of the mod still works", e);
        }
        try {
            SpotifyService.start();
        } catch (Exception e) {
            LOG.error("Spotify service failed to start; the rest of the mod still works", e);
        }

        try {
            CapeLoader.reload();
            LivingEntityFeatureRendererRegistrationCallback.EVENT.register((entityType, entityRenderer, helper, context) -> {
                if (entityRenderer instanceof PlayerEntityRenderer playerRenderer) {
                    helper.register(new CosmeticFeature(playerRenderer));
                }
            });
        } catch (Exception e) {
            LOG.error("Cosmetics failed to start; the rest of the mod still works", e);
        }

        LOG.info("Wabbit Ultimate loaded");
    }
}
