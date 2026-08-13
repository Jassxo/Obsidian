package dev.obsidian.core.engine;

import java.util.Map;

/**
 * Maps a check's internal category to the cheat name staff actually recognize, so
 * a flag reads "Killaura" or "Reach" instead of a generic label. The mapping is
 * intentionally coarse: several checks in the same family all point at one name.
 */
public final class CheatNames {

    private static final Map<String, String> DISPLAY = Map.ofEntries(
            Map.entry("autocrystal", "AutoCrystal"),
            Map.entry("autoanchor", "AutoAnchor"),
            Map.entry("aim", "Aimbot / Aim Assist"),
            Map.entry("killaura", "Killaura"),
            Map.entry("reach", "Reach"),
            Map.entry("autoclicker", "AutoClicker"),
            Map.entry("mace", "Auto Mace"),
            Map.entry("badpackets", "Bad Packets"),
            Map.entry("criticals", "Criticals"),
            Map.entry("timer", "Timer"),
            Map.entry("ml", "Anomaly (ML)")
    );

    private CheatNames() {
    }

    /** Human cheat name for a category, or a safe fallback. */
    public static String displayFor(String category) {
        if (category == null) {
            return "Suspicious activity";
        }
        return DISPLAY.getOrDefault(category, "Suspicious activity");
    }

    /** True for categories that are corroboration only and should not stand as sole evidence. */
    public static boolean isCorroborationOnly(String category) {
        return "ml".equals(category);
    }
}
