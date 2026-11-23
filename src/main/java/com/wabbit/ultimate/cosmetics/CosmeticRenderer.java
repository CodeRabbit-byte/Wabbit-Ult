package com.wabbit.ultimate.cosmetics;

import com.wabbit.ultimate.config.ConfigManager;
import com.wabbit.ultimate.config.WabbitConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.model.PlayerCapeModel;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.PlayerLikeEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import org.joml.Quaternionf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Draws the equipped cosmetics and the cape on the local player. Items are built from coloured blocks drawn with
 * a plain white texture; they are attached to the real head and body model parts, so they follow every pose and
 * animation (sneaking, swimming, crawling, flying, riding, sleeping). The cape is drawn on the vanilla cape model
 * with the picture as its texture, so it swings exactly like a Minecraft cape.
 */
public final class CosmeticRenderer {
    private static final Logger LOG = LoggerFactory.getLogger("WabbitUltimate");
    private CosmeticRenderer() {}

    private static final Identifier WHITE = Identifier.of("wabbitultimate", "textures/cosmetics/white.png");
    private static final float S = 1f / 16f;
    private static final float RAD = (float) (Math.PI / 180.0);
    private static final int FULL_BRIGHT = 0xF000F0;
    /** Head items scale about the centre of the head so they sit on top of the hat layer or a helmet. */
    private static final float HAT_LAYER_SCALE = 9f / 8f, HELMET_SCALE = 10f / 8f;
    private static boolean reported;

    public static void reportOnce(Exception e) {
        if (!reported) {
            reported = true;
            LOG.error("Cosmetics failed to draw (this message is shown once)", e);
        }
    }

    /** True when the custom cape replaces the player's own (Mojang / launcher) cape. Used by the renderer mixin. */
    public static boolean replacesVanillaCape(PlayerLikeEntity entity) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || entity != mc.player) return false;
        WabbitConfig cfg = ConfigManager.get();
        return cfg.cosmeticsOn && cfg.capeOn && CapeLoader.current() != null && !backBusy(Catalog.equipped(cfg));
    }

    public static void render(MatrixStack m, OrderedRenderCommandQueue q, PlayerEntityRenderState state, int light,
                              PlayerEntityModel model, PlayerCapeModel capeModel) {
        WabbitConfig cfg = ConfigManager.get();
        if (!cfg.cosmeticsOn || state.invisible) return;
        ClientPlayerEntity me = MinecraftClient.getInstance().player;
        if (me == null || state.id != me.getId()) return; // only ever decorate the local player

        List<Catalog.Placed> equipped = Catalog.equipped(cfg);
        boolean elytra = has(state.equippedChestStack, DataComponentTypes.GLIDER);
        CapeData cape = (cfg.capeOn && !elytra && !backBusy(equipped)) ? CapeLoader.current() : null;
        if (equipped.isEmpty() && cape == null) return;

        RenderLayer layer = RenderLayers.entityTranslucent(WHITE);
        float age = state.age;
        boolean chestArmor = hasChestArmor(state.equippedChestStack);

        boolean anyHead = false, anyBody = false;
        for (Catalog.Placed p : equipped) {
            if (p.item().slot().head) anyHead = true;
            else if (shown(p, elytra)) anyBody = true;
        }

        if (anyHead) {
            m.push();
            model.getRootPart().applyTransform(m);
            model.head.applyTransform(m);
            ItemStack helmet = state.equippedHeadStack;
            float k = helmet != null && !helmet.isEmpty() ? HELMET_SCALE : (state.hatVisible ? HAT_LAYER_SCALE : 1f);
            if (k != 1f) {
                m.translate(0f, -4f * S, 0f);
                m.scale(k, k, k);
                m.translate(0f, 4f * S, 0f);
            }
            for (Catalog.Placed p : equipped) if (p.item().slot().head) drawItem(m, q, layer, p, age, light);
            m.pop();
        }

        if (anyBody) {
            m.push();
            model.getRootPart().applyTransform(m);
            model.body.applyTransform(m);
            if (chestArmor) m.translate(0f, 0f, 1f * S); // sit on the chestplate, not inside it
            for (Catalog.Placed p : equipped) {
                if (!p.item().slot().head && shown(p, elytra)) drawItem(m, q, layer, p, age, light);
            }
            m.pop();
        }

        if (cape != null) {
            Identifier tex = CapeTexture.get(cape, cfg.capeFlip);
            m.push();
            if (chestArmor) m.translate(0f, -0.053125f, 0.06875f); // same offset the vanilla cape uses
            q.submitModel(capeModel, state, m, RenderLayers.entityTranslucent(tex), light,
                    OverlayTexture.DEFAULT_UV, state.outlineColor, null);
            m.pop();
        }
    }

    /** Wings are hidden while an elytra is worn; it sits in the same place. */
    private static boolean shown(Catalog.Placed p, boolean elytra) {
        return !(elytra && p.item().slot() == Slot.WINGS);
    }

    private static boolean backBusy(List<Catalog.Placed> equipped) {
        for (Catalog.Placed p : equipped) if (p.item().slot() == Slot.WINGS || p.item().slot() == Slot.BACK) return true;
        return false;
    }

    private static boolean has(ItemStack stack, net.minecraft.component.ComponentType<?> type) {
        return stack != null && !stack.isEmpty() && stack.contains(type);
    }

    private static boolean hasChestArmor(ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.contains(DataComponentTypes.GLIDER)) return false;
        EquippableComponent e = stack.get(DataComponentTypes.EQUIPPABLE);
        return e != null && e.slot() == EquipmentSlot.CHEST && e.assetId().isPresent();
    }

    private static void drawItem(MatrixStack m, OrderedRenderCommandQueue q, RenderLayer layer,
                                 Catalog.Placed p, float age, int light) {
        Cosmetic c = p.item();
        float bob = c.bob() ? (float) Math.sin(age * 0.08f) * 0.6f * S : 0f;
        m.push();
        m.translate(0f, bob, 0f);

        List<Box> fixed = c.group(0);
        if (!fixed.isEmpty()) submit(m, q, layer, fixed, p.tintRgb(), light);

        for (int g = 1; g <= 2; g++) {
            List<Box> wing = c.group(g);
            if (wing.isEmpty()) continue;
            float px = (g == 1 ? Catalog.WING_PIVOT[0] : -Catalog.WING_PIVOT[0]) * S;
            float py = Catalog.WING_PIVOT[1] * S;
            float pz = Catalog.WING_PIVOT[2] * S;
            m.push();
            m.translate(px, py, pz);
            m.multiply(new Quaternionf().rotationY(Catalog.wingRotation(g, age) * RAD));
            m.translate(-px, -py, -pz);
            submit(m, q, layer, wing, p.tintRgb(), light);
            m.pop();
        }
        m.pop();
    }

    private static void submit(MatrixStack m, OrderedRenderCommandQueue q, RenderLayer layer,
                               List<Box> boxes, int tintRgb, int light) {
        q.submitCustom(m, layer, (entry, vc) -> {
            for (Box b : boxes) emit(entry, vc, b, tintRgb, light);
        });
    }

    private static void emit(MatrixStack.Entry e, VertexConsumer vc, Box b, int tintRgb, int light) {
        int c = b.color(tintRgb);
        int l = b.glow() ? FULL_BRIGHT : light;
        float x1 = b.x1() * S, y1 = b.y1() * S, z1 = b.z1() * S;
        float x2 = b.x2() * S, y2 = b.y2() * S, z2 = b.z2() * S;
        quad(e, vc, c, l, 0, 0, -1, x1, y2, z1, x2, y2, z1, x2, y1, z1, x1, y1, z1); // front
        quad(e, vc, c, l, 0, 0, 1, x2, y2, z2, x1, y2, z2, x1, y1, z2, x2, y1, z2);  // back
        quad(e, vc, c, l, -1, 0, 0, x1, y2, z2, x1, y2, z1, x1, y1, z1, x1, y1, z2); // -x side
        quad(e, vc, c, l, 1, 0, 0, x2, y2, z1, x2, y2, z2, x2, y1, z2, x2, y1, z1);  // +x side
        quad(e, vc, c, l, 0, -1, 0, x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2); // top (model -y is up)
        quad(e, vc, c, l, 0, 1, 0, x1, y2, z2, x2, y2, z2, x2, y2, z1, x1, y2, z1);  // bottom
    }

    private static void quad(MatrixStack.Entry e, VertexConsumer vc, int color, int light,
                             float nx, float ny, float nz,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz) {
        vtx(e, vc, color, light, nx, ny, nz, ax, ay, az, 0f, 1f);
        vtx(e, vc, color, light, nx, ny, nz, bx, by, bz, 1f, 1f);
        vtx(e, vc, color, light, nx, ny, nz, cx, cy, cz, 1f, 0f);
        vtx(e, vc, color, light, nx, ny, nz, dx, dy, dz, 0f, 0f);
    }

    private static void vtx(MatrixStack.Entry e, VertexConsumer vc, int color, int light,
                            float nx, float ny, float nz, float x, float y, float z, float u, float v) {
        vc.vertex(e, x, y, z)
                .color(color)
                .texture(u, v)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(light)
                .normal(e, nx, ny, nz);
    }
}
