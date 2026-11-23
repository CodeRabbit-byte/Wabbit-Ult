package com.wabbit.ultimate.gui;

import com.wabbit.ultimate.config.ConfigManager;
import com.wabbit.ultimate.config.WabbitConfig;
import com.wabbit.ultimate.hud.LayoutCodec;
import com.wabbit.ultimate.hud.PerformanceHud;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/** Drag the HUD box with the left mouse button. Opacity buttons and 3 layout slots below. */
public class HudEditorScreen extends Screen {
    private final Screen parent;
    private boolean dragging, wasDown;
    private int offX, offY;
    private String status = "";

    public HudEditorScreen(Screen parent) {
        super(Text.literal("HUD Editor"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        WabbitConfig cfg = ConfigManager.get();

        int row1W = 4 * 90 + 3 * 4;
        int x = (width - row1W) / 2;
        int y1 = height - 52;
        addDrawableChild(ButtonWidget.builder(Text.literal("Opacity -"), b -> {
            cfg.hudOpacity = Math.max(0, cfg.hudOpacity - 10);
            ConfigManager.save();
            status = "Opacity " + cfg.hudOpacity + "%";
        }).dimensions(x, y1, 90, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Opacity +"), b -> {
            cfg.hudOpacity = Math.min(100, cfg.hudOpacity + 10);
            ConfigManager.save();
            status = "Opacity " + cfg.hudOpacity + "%";
        }).dimensions(x + 94, y1, 90, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Reset"), b -> {
            cfg.resetHud();
            ConfigManager.save();
            status = "HUD reset";
        }).dimensions(x + 188, y1, 90, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
                .dimensions(x + 282, y1, 90, 20).build());

        int y0 = height - 76;
        addDrawableChild(ButtonWidget.builder(Text.literal("Copy layout"), b -> {
            MinecraftClient.getInstance().keyboard.setClipboard(LayoutCodec.encode(new LayoutCodec.Layout(
                    cfg.hudX, cfg.hudY, cfg.hudOpacity, cfg.fpsHud, cfg.memoryHud, cfg.hudBackground)));
            status = "Layout copied. Paste it on another PC.";
        }).dimensions(x, y0, 90, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Paste layout"), b -> {
            try {
                LayoutCodec.Layout l = LayoutCodec.decode(MinecraftClient.getInstance().keyboard.getClipboard());
                cfg.hudX = Math.min(l.x(), Math.max(3, width - 20));
                cfg.hudY = Math.min(l.y(), Math.max(3, height - 20));
                cfg.hudOpacity = l.opacity();
                cfg.fpsHud = l.fps();
                cfg.memoryHud = l.memory();
                cfg.hudBackground = l.background();
                ConfigManager.save();
                status = "Layout pasted.";
            } catch (IllegalArgumentException e) {
                status = e.getMessage();
            }
        }).dimensions(x + 94, y0, 90, 20).build());

        int row2W = 6 * 56 + 5 * 4;
        int x2 = (width - row2W) / 2;
        int y2 = height - 28;
        for (int i = 0; i < 3; i++) {
            final int slot = i;
            addDrawableChild(ButtonWidget.builder(Text.literal("Save " + (i + 1)), b -> {
                cfg.layoutSlots()[slot] = cfg.captureLayout();
                ConfigManager.save();
                status = "Saved layout " + (slot + 1);
            }).dimensions(x2 + i * 60, y2, 56, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Load " + (i + 1)), b -> {
                WabbitConfig.HudLayout l = cfg.layoutSlots()[slot];
                if (l == null) {
                    status = "Layout " + (slot + 1) + " is empty";
                } else {
                    cfg.applyLayout(l);
                    ConfigManager.save();
                    status = "Loaded layout " + (slot + 1);
                }
            }).dimensions(x2 + 180 + i * 60, y2, 56, 20).build());
        }
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        WabbitConfig cfg = ConfigManager.get();

        PerformanceHud.draw(ctx, true);

        // Drag handling by polling the left mouse button (uses the already-scaled mouseX/mouseY).
        long window = MinecraftClient.getInstance().getWindow().getHandle();
        boolean down = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        int[] b = PerformanceHud.bounds(true);

        if (down && !wasDown && mouseX >= b[0] && mouseX <= b[2] && mouseY >= b[1] && mouseY <= b[3]) {
            dragging = true;
            offX = mouseX - cfg.hudX;
            offY = mouseY - cfg.hudY;
        }
        if (dragging && down) {
            int boxW = b[2] - b[0];
            int boxH = b[3] - b[1];
            cfg.hudX = Math.max(3, Math.min(width - boxW + 3, mouseX - offX));
            cfg.hudY = Math.max(3, Math.min(height - boxH + 3, mouseY - offY));
        }
        if (dragging && !down) {
            dragging = false;
            ConfigManager.save();
            status = "Position saved";
        }
        wasDown = down;

        ctx.drawCenteredTextWithShadow(textRenderer, "Drag the HUD box. Changes save automatically.",
                width / 2, 10, 0xFFFFFFFF);
        if (!status.isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer, status, width / 2, height - 90, cfg.accent());
        }
    }

    @Override
    public void close() {
        MinecraftClient.getInstance().setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
