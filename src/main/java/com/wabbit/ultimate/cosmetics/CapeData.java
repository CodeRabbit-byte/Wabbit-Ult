package com.wabbit.ultimate.cosmetics;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A cape as a 20 x 32 grid of colours. Standard Minecraft cape files (64x32, 128x64... or the old 22x17)
 * use their cape area; any other picture is stretched over the whole cape.
 */
public final class CapeData {
    public static final int W = 20, H = 32;
    public final int[] argb = new int[W * H];

    private CapeData() {}

    public static CapeData fromFile(Path file) throws IOException {
        if (Files.size(file) > 8_000_000) throw new IOException("That image is too big (over 8 MB).");
        BufferedImage img = ImageIO.read(file.toFile());
        if (img == null) throw new IOException("That file is not a readable PNG image.");
        return fromImage(img);
    }

    public static CapeData fromImage(BufferedImage img) throws IOException {
        int w = img.getWidth(), h = img.getHeight();
        if (w < 4 || h < 4 || w > 4096 || h > 4096) throw new IOException("Image size must be between 4 and 4096 pixels.");
        int x0 = 0, y0 = 0, rw = w, rh = h;
        if (w == 22 && h == 17) {                       // old minimal cape layout
            x0 = 1; y0 = 1; rw = 10; rh = 16;
        } else if (w == h * 2 && w % 64 == 0) {        // standard cape layout (any scale)
            int s = w / 64;
            x0 = s; y0 = s; rw = 10 * s; rh = 16 * s;
        }
        CapeData d = new CapeData();
        for (int gy = 0; gy < H; gy++) {
            for (int gx = 0; gx < W; gx++) {
                int sx = x0 + Math.min(rw - 1, (int) ((gx + 0.5) / W * rw));
                int sy = y0 + Math.min(rh - 1, (int) ((gy + 0.5) / H * rh));
                d.argb[gy * W + gx] = img.getRGB(sx, sy);
            }
        }
        return d;
    }

    public int at(int gx, int gy, boolean flip) {
        return argb[gy * W + (flip ? W - 1 - gx : gx)];
    }
}
