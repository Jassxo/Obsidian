package dev.obsidian.api;

/**
 * Static holder for the {@link ObsidianApi} instance. Populated by the core plugin
 * during {@code onEnable} and cleared on {@code onDisable}.
 */
public final class ObsidianApiProvider {

    private static volatile ObsidianApi instance;

    private ObsidianApiProvider() {
    }

    /**
     * @return the live API instance
     * @throws IllegalStateException if Obsidian is not enabled
     */
    public static ObsidianApi get() {
        ObsidianApi api = instance;
        if (api == null) {
            throw new IllegalStateException("Obsidian is not enabled");
        }
        return api;
    }

    /**
     * @return true once Obsidian has enabled and the API is usable
     */
    public static boolean isAvailable() {
        return instance != null;
    }

    /**
     * Internal use only.
     */
    public static void register(ObsidianApi api) {
        instance = api;
    }

    /**
     * Internal use only.
     */
    public static void unregister() {
        instance = null;
    }
}
