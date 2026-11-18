package com.nicertp;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public final class NiceRTPCommand implements CommandExecutor, TabCompleter {

    private final NiceRTP plugin;

    public NiceRTPCommand(NiceRTP plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        Messages msg = plugin.messages();

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            msg.sendList(sender, "help");
            return true;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("reload")) {
            if (!sender.hasPermission("nicertp.admin")) {
                msg.send(sender, "no-permission");
                return true;
            }
            plugin.reloadAll();
            msg.send(sender, "reloaded");
            return true;
        }

        if (sub.equals("config")) {
            if (!(sender instanceof Player p)) {
                msg.send(sender, "players-only");
                return true;
            }
            if (!p.hasPermission("nicertp.admin")) {
                msg.send(p, "no-permission");
                return true;
            }
            plugin.gui().openConfig(p);
            return true;
        }

        if (sub.equals("--forcertp") || sub.equals("forcertp")) {
            if (!sender.hasPermission("nicertp.force")) {
                msg.send(sender, "no-permission");
                return true;
            }
            if (args.length < 2) {
                msg.sendList(sender, "help");
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                msg.send(sender, "player-not-found");
                return true;
            }
            World w = args.length >= 3 ? plugin.manager().worldFor(args[2]) : plugin.manager().defaultWorldFor(target);
            if (w == null) {
                msg.send(sender, "unknown-world", "input", args.length >= 3 ? args[2] : "?");
                return true;
            }
            plugin.manager().rtp(target, w, true);
            msg.send(sender, "force-sender", "player", target.getName());
            if (plugin.getConfig().getBoolean("force.notify-target", true)) {
                msg.send(target, "force-target");
            }
            return true;
        }

        msg.sendList(sender, "help");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String in = args[0].toLowerCase();
            for (String s : List.of("reload", "config", "--forcertp", "help")) {
                if (s.startsWith(in)) out.add(s);
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("--forcertp") || args[0].equalsIgnoreCase("forcertp"))) {
            String in = args[1].toLowerCase();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(in)) out.add(p.getName());
            }
        } else if (args.length == 3 && (args[0].equalsIgnoreCase("--forcertp") || args[0].equalsIgnoreCase("forcertp"))) {
            String in = args[2].toLowerCase();
            for (String s : List.of("overworld", "nether", "end")) {
                if (s.startsWith(in)) out.add(s);
            }
        }
        return out;
    }
}
