package dev.obsidian.core.integration;

import dev.obsidian.core.ObsidianPlugin;
import dev.obsidian.core.tracker.PlayerData;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Optional corroboration bridge to TotemGuard, the companion anticheat that
 * handles autototem and inventory while Obsidian handles combat and aim. The two
 * coexist with zero configuration — both are passive PacketEvents listeners with
 * separate concerns — and this bridge is the extra, optional touch: when
 * TotemGuard flags a player, Obsidian adds a small, capped confidence bonus to
 * the same player. Corroboration, never conviction; the cap keeps an external
 * anticheat from ever flagging someone on Obsidian's behalf.
 *
 * <p>No compile-time dependency on TotemGuard. It hooks TotemGuard 3.0's API
 * event bus reflectively and disables itself cleanly if the API is absent or a
 * different shape, exactly like the GrimAC bridge. It subscribes with a standard
 * {@link Consumer}, so there is no fragile proxy — only method lookups, resolved
 * once at startup.</p>
 */
public final class TotemGuardBridge {

    private static final String API_CLASS = "com.deathmotion.totemguard.api.TotemGuardAPI";
    private static final String FLAG_EVENT_CLASS =
            "com.deathmotion.totemguard.api.event.events.TGUserFlagEvent";

    private final ObsidianPlugin plugin;

    public TotemGuardBridge(ObsidianPlugin plugin) {
        this.plugin = plugin;
    }

    /** Returns true if the bridge attached. */
    public boolean tryAttach() {
        if (!plugin.configs().totemGuardEnabled()
                || plugin.getServer().getPluginManager().getPlugin("TotemGuard") == null) {
            return false;
        }
        try {
            Object api = resolveApi();
            if (api == null) {
                plugin.getLogger().info("TotemGuard found but its API is not available; bridge disabled.");
                return false;
            }
            Object eventBus = api.getClass().getMethod("getEventBus").invoke(api);
            Class<?> flagEvent = Class.forName(FLAG_EVENT_CLASS);
            Object channel = eventBus.getClass().getMethod("get", Class.class).invoke(eventBus, flagEvent);

            Consumer<Object> handler = this::onFlag;
            Method subscribe = channel.getClass().getMethod("subscribe", Object.class, Consumer.class);
            subscribe.invoke(channel, plugin, handler);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().info("TotemGuard present but its flag API is unavailable ("
                    + e.getMessage() + "); bridge disabled.");
            return false;
        }
    }

    private Object resolveApi() throws ReflectiveOperationException {
        Class<?> apiClass = Class.forName(API_CLASS);
        // Preferred: the plugin registered its API as a Bukkit service.
        Object service = plugin.getServer().getServicesManager().load(apiClass);
        if (service != null) {
            return service;
        }
        // Fallback: a static accessor on the TotemGuard main class.
        for (String accessor : new String[]{"getAPI", "getApi", "get", "getInstance"}) {
            try {
                Method m = Class.forName("com.deathmotion.totemguard.api.TotemGuard").getMethod(accessor);
                Object api = m.invoke(null);
                if (api != null && apiClass.isInstance(api)) {
                    return api;
                }
            } catch (ReflectiveOperationException ignored) {
                // try the next candidate accessor
            }
        }
        return null;
    }

    private void onFlag(Object event) {
        try {
            UUID uuid = extractUuid(event);
            if (uuid == null) {
                return;
            }
            PlayerData data = plugin.players().get(uuid);
            if (data == null) {
                return;
            }
            double cap = plugin.configs().totemGuardMaxTotalBonus();
            data.externalBonus = Math.min(cap, data.externalBonus + plugin.configs().totemGuardBonusPerFlag());
        } catch (Throwable t) {
            // Never let a bridge error escape into TotemGuard's dispatch.
            plugin.getLogger().warning("TotemGuard bridge error: " + t);
        }
    }

    private static UUID extractUuid(Object event) throws ReflectiveOperationException {
        Object holder = invokeFirst(event, "getUser", "getPlayer");
        Object source = holder != null ? holder : event;
        Object id = invokeFirst(source, "getUniqueId", "getUUID", "getUniqueID", "uuid");
        return id instanceof UUID u ? u : null;
    }

    private static Object invokeFirst(Object target, String... methodNames)
            throws ReflectiveOperationException {
        if (target == null) {
            return null;
        }
        for (String name : methodNames) {
            try {
                Method m = target.getClass().getMethod(name);
                return m.invoke(target);
            } catch (NoSuchMethodException ignored) {
                // try the next candidate
            }
        }
        return null;
    }
}
