package com.wabbit.ultimate.hud;

import com.wabbit.ultimate.config.ConfigManager;
import com.wabbit.ultimate.config.WabbitConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;

/** Shows a toast when the game runs badly. Only watches while you are in a world with no menu open. */
public final class PerfMonitor {
    private PerfMonitor() {}

    private static final PerfWatch WATCH = new PerfWatch();

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(PerfMonitor::tick);
    }

    private static void tick(MinecraftClient mc) {
        WabbitConfig cfg = ConfigManager.get();
        if (!cfg.perfAlerts || mc.world == null || mc.currentScreen != null) {
            WATCH.reset();
            return;
        }
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) / 1048576L;
        long max = rt.maxMemory() / 1048576L;
        PerfWatch.Note n = WATCH.check(System.currentTimeMillis(), mc.getCurrentFps(), used, max,
                cfg.perfLowFps, cfg.perfHighMemPct);
        if (n != null) DonationAlerts.notice(n.title(), n.text(), n.color());
    }
}
