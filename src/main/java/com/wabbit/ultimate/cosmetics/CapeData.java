package com.wabbit.ultimate.cosmetics;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A cape picture turned into two things:
 * <ul>
 *   <li>a full-resolution texture in the vanilla cape layout (64x32, or an integer multiple of it), which the
 *       game draws on the real cape model so it moves exactly like a Minecraft cape, and</li>
 *   <li>a small 20 x 32 grid of colours of the outer side, used by the menu preview.</li>
 * </ul>
 * Standard cape files (64x32, 128x64... or the old 22x17) are used as they are; any other picture is stretched
 * over the outside of the cape, mirrored on the inside, and its average colour is used for the edges.
 */
public final class CapeData {
    public static final int W = 20, H = 32;
    /** Largest scale kept for the texture (16 = 1024x512). Bigger files are sampled down. */
    private static final int MAX_SCALE = 16;

    public final int[] argb = new int[W * H];
    final int texW, texH;
    final int[] tex;
    /** Texture pixels per cape-layout pixel. */
    private final int s;

    private CapeData(int scale) {
        this.s = scale;
        this.texW = 64 * scale;
        this.texH = 32 * scale;
        this.tex = new int[texW * texH];
    }

    public static CapeData fromFile(Path file) throws IOException {
        if (Files.size(file) > 8_000_000) throw new IOException("That image is too big (over 8 MB).");
        BufferedImage img = ImageIO.read(file.toFile());
        if (img == null) throw new IOException("That file is not a readable PNG image.");
        return fromImage(img);
    }

    public static CapeData fromImage(BufferedImage img) throws IOException {
        int w = img.getWidth(), h = img.getHeight();
        if (w < 4 || h < 4 || w > 4096 || h > 4096) throw new IOException("Image size must be between 4 and 4096 pixels.");

        CapeData d;
        if (w == 22 && h == 17) {                         // old minimal cape layout
            d = new CapeData(1);
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) d.tex[y * d.texW + x] = img.getRGB(x, y);
        } else if (w == h * 2 && w % 64 == 0) {           // standard cape layout (any scale)
            d = new CapeData(Math.min(w / 64, MAX_SCALE));
            for (int y = 0; y < d.texH; y++) {
                for (int x = 0; x < d.texW; x++) {
                    d.tex[y * d.texW + x] = img.getRGB(x * w / d.texW, y * h / d.texH);
                }
            }
        } else {                                          // any picture: stretch it over the cape
            int scale = Math.max(1, Math.min(MAX_SCALE, (int) Math.ceil(Math.max(w / 10.0, h / 16.0))));
            d = new CapeData(scale);
            d.paintPicture(img);
        }

        // menu preview grid: the outer side of the cape
        for (int gy = 0; gy < H; gy++) {
            for (int gx = 0; gx < W; gx++) {
                int tx = d.s + Math.min(10 * d.s - 1, (int) ((gx + 0.5) / W * 10 * d.s));
                int ty = d.s + Math.min(16 * d.s - 1, (int) ((gy + 0.5) / H * 16 * d.s));
                d.argb[gy * W + gx] = d.tex[ty * d.texW + tx];
            }
        }
        return d;
    }

    private void paintPicture(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int ow = 10 * s, oh = 16 * s;
        long r = 0, g = 0, b = 0, n = 0;
        for (int y = 0; y < oh; y++) {
            for (int x = 0; x < ow; x++) {
                int c = img.getRGB(Math.min(w - 1, x * w / ow), Math.min(h - 1, y * h / oh));
                tex[(s + y) * texW + (s + x)] = c;                         // outer side
                tex[(s + y) * texW + (12 * s + (ow - 1 - x))] = darker(c); // inner side, mirrored
                if ((c >>> 24) >= 128) {
                    r += (c >> 16) & 0xFF;
                    g += (c >> 8) & 0xFF;
                    b += c & 0xFF;
                    n++;
                }
            }
        }
        int edge = n == 0 ? 0 : darker(0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n));
        fill(s, 0, 10 * s, s, edge);        // top
        fill(11 * s, 0, 10 * s, s, edge);   // bottom
        fill(0, s, s, 16 * s, edge);        // side
        fill(11 * s, s, s, 16 * s, edge);   // other side
    }

    private void fill(int x0, int y0, int fw, int fh, int c) {
        for (int y = y0; y < y0 + fh; y++) for (int x = x0; x < x0 + fw; x++) tex[y * texW + x] = c;
    }

    private static int darker(int c) {
        return (c & 0xFF000000)
                | (((c >> 16 & 0xFF) * 8 / 10) << 16)
                | (((c >> 8 & 0xFF) * 8 / 10) << 8)
                | ((c & 0xFF) * 8 / 10);
    }

    /** Menu preview colour; {@code flip} mirrors the picture left-right. */
    public int at(int gx, int gy, boolean flip) {
        return argb[gy * W + (flip ? W - 1 - gx : gx)];
    }

    /** Texture pixel; {@code flip} mirrors the outer and inner faces left-right (edges stay put). */
    int texel(int x, int y, boolean flip) {
        if (flip && y >= s && y < 17 * s) {
            if (x >= s && x < 11 * s) x = s + (11 * s - 1 - x);
            else if (x >= 12 * s && x < 22 * s) x = 12 * s + (22 * s - 1 - x);
        }
        return tex[y * texW + x];
    }
}
