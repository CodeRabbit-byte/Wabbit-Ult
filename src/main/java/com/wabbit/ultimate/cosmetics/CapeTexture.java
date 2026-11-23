package com.wabbit.ultimate.cosmetics;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

/** Uploads the chosen cape picture as a game texture. Call only on the render thread. */
public final class CapeTexture {
    private CapeTexture() {}

    private static final Identifier ID = Identifier.of("wabbitultimate", "dynamic/cape");
    private static CapeData uploaded;
    private static boolean uploadedFlip;

    /** The texture for this cape, uploading it first if the cape or the flip setting changed. */
    public static Identifier get(CapeData cape, boolean flip) {
        if (cape != uploaded || flip != uploadedFlip) {
            NativeImage img = new NativeImage(cape.texW, cape.texH, false);
            try {
                for (int y = 0; y < cape.texH; y++) {
                    for (int x = 0; x < cape.texW; x++) img.setColorArgb(x, y, cape.texel(x, y, flip));
                }
            } catch (RuntimeException e) {
                img.close();
                throw e;
            }
            // registering under the same id closes the previous texture
            MinecraftClient.getInstance().getTextureManager()
                    .registerTexture(ID, new NativeImageBackedTexture(() -> "Wabbit Ultimate cape", img));
            uploaded = cape;
            uploadedFlip = flip;
        }
        return ID;
    }
}
