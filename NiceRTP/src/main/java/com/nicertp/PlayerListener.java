package com.nicertp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PlayerListener implements Listener {

    private final NiceRTP plugin;

    public PlayerListener(NiceRTP plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!e.hasChangedBlock()) return;
        Player p = e.getPlayer();
        RTPManager.Pending pd = plugin.manager().getPending(p);
        if (pd == null || pd.group || pd.task == null) return;
        if (!plugin.getConfig().getBoolean("countdown.cancel-on-move", true)) return;

        Location to = e.getTo();
        double tol = plugin.getConfig().getDouble("countdown.move-tolerance", 0.5);
        if (!to.getWorld().equals(pd.start.getWorld()) || to.distanceSquared(pd.start) > tol * tol) {
            plugin.manager().cancel(p, "cancelled-moved");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;

        if (plugin.manager().isProtected(p.getUniqueId())) {
            e.setCancelled(true);
            return;
        }
        RTPManager.Pending pd = plugin.manager().getPending(p);
        if (pd == null || pd.group) return;
        if (plugin.getConfig().getBoolean("countdown.cancel-on-damage", true)) {
            plugin.manager().cancel(p, "cancelled-damage");
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!plugin.getConfig().getBoolean("first-join.enabled", false) || p.hasPlayedBefore()) return;

        long delay = plugin.getConfig().getLong("first-join.delay-ticks", 20);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            World w = plugin.manager().worldFor(plugin.getConfig().getString("first-join.world", "overworld"));
            plugin.manager().rtp(p, w, true);
        }, delay);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.manager().handleQuit(e.getPlayer());
    }
}
