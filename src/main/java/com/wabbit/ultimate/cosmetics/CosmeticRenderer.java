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
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.EntityPose;
import net.minecraft.util.Identifier;
import org.joml.Quaternionf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Draws the equipped cosmetics and the cape on the local player. Everything is built from coloured blocks
 * that are drawn with a plain white texture, so no model or texture registration is needed.
 */
public final class CosmeticRenderer {
    private static final Logger LOG = LoggerFactory.getLogger("WabbitUltimate");
    private CosmeticRenderer() {}

    private static final Identifier WHITE = Identifier.of("wabbitultimate", "textures/cosmetics/white.png");
    private static final float S = 1f / 16f;
    private static final float RAD = (float) (Math.PI / 180.0);
    private static final int FULL_BRIGHT = 0xF000F0;
    private static boolean reported;

    public static void reportOnce(Exception e) {
        if (!reported) {
            reported = true;
            LOG.error("Cosmetics failed to draw (this message is shown once)", e);
        }
    }

    public static void render(MatrixStack matrices, OrderedRenderCommandQueue queue,
                              PlayerEntityRenderState state, int light) {
        WabbitConfig cfg = ConfigManager.get();
        if (!cfg.cosmeticsOn) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity me = mc.player;
        if (me == null || state.invisible) return;

        // Only decorate the local player: the render state has no player id, so match by position.
        double dx = state.x - me.getX(), dy = state.y - me.getY(), dz = state.z - me.getZ();
        if (dx * dx + dy * dy + dz * dz > 4.0) return;
        if (!(state.isInPose(EntityPose.STANDING) || state.isInPose(EntityPose.CROUCHING))) return;

        List<Catalog.Placed> equipped = Catalog.equipped(cfg);
        boolean backBusy = false;
        for (Catalog.Placed p : equipped) if (p.item().slot() == Slot.WINGS || p.item().slot() == Slot.BACK) backBusy = true;
        CapeData cape = (cfg.capeOn && !backBusy) ? CapeLoader.current() : null;
        if (equipped.isEmpty() && cape == null) return;

        RenderLayer layer = RenderLayers.entityTranslucent(WHITE);
        float age = state.age;
        boolean sneak = state.sneaking;

        boolean anyHead = false, anyBody = cape != null;
        for (Catalog.Placed p : equipped) {
            if (p.item().slot().head) anyHead = true;
            else anyBody = true;
        }

        if (anyHead) {
            matrices.push();
            matrices.translate(0f, sneak ? 4.2f * S : 0f, 0f);
            matrices.multiply(new Quaternionf().rotationZYX(0f, state.relativeHeadYaw * RAD, state.pitch * RAD));
            for (Catalog.Placed p : equipped) if (p.item().slot().head) drawItem(matrices, queue, layer, p, age, light);
            matrices.pop();
        }

        if (anyBody) {
            matrices.push();
            if (sneak) {
                matrices.translate(0f, 3.2f * S, 0f);
                matrices.multiply(new Quaternionf().rotationX(0.5f));
            }
            for (Catalog.Placed p : equipped) if (!p.item().slot().head) drawItem(matrices, queue, layer, p, age, light);
            if (cape != null) drawCape(matrices, queue, layer, cape, cfg.capeFlip, state, sneak, light);
            matrices.pop();
        }
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

    private static void drawCape(MatrixStack m, OrderedRenderCommandQueue q, RenderLayer layer, CapeData cape,
                                 boolean flip, PlayerEntityRenderState state, boolean sneak, int light) {
        float amp = Math.min(1f, state.limbSwingAmplitude);
        float prog = state.limbSwingAnimationProgress;
        float angle = 5f + amp * 28f + (float) Math.sin(prog * 0.6662f) * amp * 8f + (sneak ? 18f : 0f);

        m.push();
        m.translate(0f, 0f, 2.2f * S);
        m.multiply(new Quaternionf().rotationX(angle * RAD));
        q.submitCustom(m, layer, (e, vc) -> {
            float cell = 0.5f * S;
            for (int gy = 0; gy < CapeData.H; gy++) {
                for (int gx = 0; gx < CapeData.W; gx++) {
                    int c = cape.at(gx, gy, flip);
                    if (((c >>> 24) & 0xFF) < 16) continue;
                    float x2 = (5f - gx * 0.5f) * S;
                    float x1 = x2 - cell;
                    float y1 = gy * cell;
                    float y2 = y1 + cell;
                    // outer face (seen from behind), inner face a little darker
                    quad(e, vc, c, light, 0, 0, 1, x2, y2, 0.5f * S, x1, y2, 0.5f * S, x1, y1, 0.5f * S, x2, y1, 0.5f * S);
                    int dark = (c & 0xFF000000)
                            | (((c >> 16 & 0xFF) * 7 / 10) << 16)
                            | (((c >> 8 & 0xFF) * 7 / 10) << 8)
                            | ((c & 0xFF) * 7 / 10);
                    quad(e, vc, dark, light, 0, 0, -1, x1, y2, 0f, x2, y2, 0f, x2, y1, 0f, x1, y1, 0f);
                }
            }
        });
        m.pop();
    }
}
