package com.nicertp;

import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public final class RTPCommand implements CommandExecutor, TabCompleter {

    private final NiceRTP plugin;

    public RTPCommand(NiceRTP plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            plugin.messages().send(sender, "players-only");
            return true;
        }
        if (!p.hasPermission("nicertp.use")) {
            plugin.messages().send(p, "no-permission");
            return true;
        }

        RTPManager m = plugin.manager();

        if (args.length == 0) {
            if (plugin.getConfig().getBoolean("rtp.use-gui", false)) plugin.gui().openRtp(p);
            else m.rtp(p, m.defaultWorldFor(p), false);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "queue", "q" -> m.toggleQueue(p);
            case "back" -> m.back(p);
            case "cancel" -> {
                boolean a = m.cancel(p, "cancelled-manual");
                boolean b = m.leaveQueue(p);
                if (!a && !b) plugin.messages().send(p, "nothing-to-cancel");
            }
            default -> {
                World w = m.worldFor(args[0]);
                if (w == null) plugin.messages().send(p, "unknown-world", "input", args[0]);
                else m.rtp(p, w, false);
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String in = args[0].toLowerCase();
            for (String s : List.of("overworld", "nether", "end", "queue", "back", "cancel")) {
                if (s.startsWith(in)) out.add(s);
            }
        }
        return out;
    }
}
