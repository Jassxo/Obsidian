package dev.obsidian.core.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * MiniMessage rendering with the gradient prefix wired in as a {@code <prefix>} tag.
 */
public final class Messages {

    private final ConfigManager config;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public Messages(ConfigManager config) {
        this.config = config;
    }

    public Component render(String path, TagResolver... resolvers) {
        String raw = config.message(path);
        TagResolver[] all = new TagResolver[resolvers.length + 1];
        System.arraycopy(resolvers, 0, all, 0, resolvers.length);
        all[resolvers.length] = Placeholder.parsed("prefix", config.message("prefix"));
        return mm.deserialize(raw, all);
    }

    public Component raw(String miniMessage, TagResolver... resolvers) {
        return mm.deserialize(miniMessage, resolvers);
    }
}
