package com.wabbit.ultimate.donut;

import com.wabbit.ultimate.config.WabbitConfig;

import java.util.List;

/** Tier selection and message templates (no Minecraft code, so it is easy to test). */
public final class DonationLogic {
    private DonationLogic() {}

    /** The tier with the highest minimum that the amount reaches, or null if below all tiers. */
    public static WabbitConfig.DonationTier pickTier(List<WabbitConfig.DonationTier> tiers, long amount) {
        WabbitConfig.DonationTier best = null;
        if (tiers == null) return null;
        for (WabbitConfig.DonationTier t : tiers) {
            if (t == null || t.min > amount) continue;
            if (best == null || t.min > best.min) best = t;
        }
        return best;
    }

    public static String message(String template, String name, String amount, String tier) {
        String t = (template == null || template.isBlank()) ? "{name} donated {amount}!" : template;
        return t.replace("{name}", name).replace("{amount}", amount).replace("{tier}", tier == null ? "" : tier);
    }
}
