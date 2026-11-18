package com.nicertp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;

public final class Messages {

    private final NiceRTP plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public Messages(NiceRTP plugin) {
        this.plugin = plugin;
    }

    public String raw(String key) {
        String s = plugin.getConfig().getString("messages." + key);
        if (s == null) return "<red>Missing message: " + key;
        return s.replace("%prefix%", plugin.getConfig().getString("messages.prefix", ""));
    }

    public Component component(String key, String... pairs) {
        return mm.deserialize(apply(raw(key), pairs));
    }

    public Component parse(String text, String... pairs) {
        return mm.deserialize(apply(text, pairs));
    }

    public void send(CommandSender to, String key, String... pairs) {
        String r = raw(key);
        if (r.isBlank()) return;
        to.sendMessage(mm.deserialize(apply(r, pairs)));
    }

    public void sendList(CommandSender to, String key) {
        String prefix = plugin.getConfig().getString("messages.prefix", "");
        for (String line : plugin.getConfig().getStringList("messages." + key)) {
            to.sendMessage(mm.deserialize(line.replace("%prefix%", prefix)));
        }
    }

    private static String apply(String s, String... pairs) {
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            s = s.replace("%" + pairs[i] + "%", pairs[i + 1]);
        }
        return s;
    }
}
