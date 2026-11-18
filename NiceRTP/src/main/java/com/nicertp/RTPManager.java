package com.nicertp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class RTPManager {

    static final class Pending {
        Location start;
        final boolean group;
        BukkitTask task;
        boolean cancelled;

        Pending(Location start, boolean group) {
            this.start = start;
            this.group = group;
        }
    }

    private final NiceRTP plugin;
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, Location> backLocations = new HashMap<>();
    private final Set<UUID> protectedPlayers = new HashSet<>();
    private final LinkedHashMap<UUID, Long> queue = new LinkedHashMap<>();
    private BukkitTask queueTask;

    public RTPManager(NiceRTP plugin) {
        this.plugin = plugin;
    }

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    private Messages msg() {
        return plugin.messages();
    }

    // ------------------------------------------------------------------ worlds

    public World worldFor(String key) {
        if (key == null) return null;
        String k = key.toLowerCase();
        World.Environment env = switch (k) {
            case "overworld" -> World.Environment.NORMAL;
            case "nether" -> World.Environment.NETHER;
            case "end" -> World.Environment.THE_END;
            default -> null;
        };
        if (env != null) {
            String configured = cfg().getString("rtp.worlds." + k, "auto");
            if (configured != null && !configured.equalsIgnoreCase("auto")) {
                World w = Bukkit.getWorld(configured);
                if (w != null) return w;
            }
            for (World w : Bukkit.getWorlds()) {
                if (w.getEnvironment() == env) return w;
            }
            return null;
        }
        return Bukkit.getWorld(key);
    }

    public World defaultWorldFor(Player p) {
        if (worldAllowed(p.getWorld())) return p.getWorld();
        return worldFor(cfg().getString("rtp.default-world", "overworld"));
    }

    public boolean worldAllowed(World w) {
        for (String name : cfg().getStringList("rtp.disabled-worlds")) {
            if (name.equalsIgnoreCase(w.getName())) return false;
        }
        return cfg().getBoolean("locations." + LocationFinder.envKey(w) + ".enabled", true);
    }

    // ---------------------------------------------------------------- cooldown

    public long cooldownLeft(Player p) {
        if (p.hasPermission("nicertp.bypass.cooldown")) return 0;
        Long last = cooldowns.get(p.getUniqueId());
        if (last == null) return 0;
        long total = cfg().getLong("cooldown.seconds", 30);
        long left = total - (System.currentTimeMillis() - last) / 1000;
        return Math.max(0, left);
    }

    // --------------------------------------------------------------------- RTP

    public void rtp(Player p, World w, boolean force) {
        if (w == null) {
            msg().send(p, "unknown-world", "input", "?");
            return;
        }
        UUID id = p.getUniqueId();

        if (!force) {
            if (!worldAllowed(w)) {
                msg().send(p, "world-disabled", "world", w.getName());
                return;
            }
            if (cfg().getBoolean("rtp.per-world-permissions", false)
                    && !p.hasPermission("nicertp.world." + LocationFinder.envKey(w))) {
                msg().send(p, "no-permission-world");
                return;
            }
            if (pending.containsKey(id)) {
                msg().send(p, "already-teleporting");
                return;
            }
            long left = cooldownLeft(p);
            if (left > 0) {
                msg().send(p, "cooldown", "seconds", String.valueOf(left));
                return;
            }
        } else {
            cancel(p, null);
        }

        Pending pd = new Pending(p.getLocation(), false);
        pending.put(id, pd);
        if (!force) msg().send(p, "searching");

        plugin.finder().find(w).thenAccept(loc -> {
            if (pending.get(id) != pd || pd.cancelled) return;
            if (!p.isOnline()) {
                pending.remove(id);
                return;
            }
            if (loc == null) {
                pending.remove(id);
                msg().send(p, "no-safe-location");
                return;
            }
            int delay = (force || p.hasPermission("nicertp.bypass.delay")) ? 0 : cfg().getInt("countdown.seconds", 3);
            startCountdown(p, pd, loc, delay, force);
        });
    }

    private void startCountdown(Player p, Pending pd, Location dest, int seconds, boolean force) {
        if (seconds <= 0) {
            teleport(p, dest, force);
            return;
        }
        pd.start = p.getLocation();
        applyCountdownEffects(p, seconds);
        UUID id = p.getUniqueId();

        pd.task = new BukkitRunnable() {
            int left = seconds;

            @Override
            public void run() {
                if (!p.isOnline() || pending.get(id) != pd) {
                    cancel();
                    return;
                }
                if (left <= 0) {
                    cancel();
                    teleport(p, dest, force);
                    return;
                }
                tick(p, left);
                left--;
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    private void tick(Player p, int left) {
        String sec = String.valueOf(left);
        if (cfg().getBoolean("countdown.show-title", true)) {
            Component t = msg().component("countdown-title", "seconds", sec);
            Component st = msg().component("countdown-subtitle", "seconds", sec);
            p.showTitle(Title.title(t, st, Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ofMillis(200))));
        }
        if (cfg().getBoolean("countdown.show-actionbar", true)) {
            p.sendActionBar(msg().component("countdown-actionbar", "seconds", sec));
        }
        play(p, "countdown-tick");
    }

    private void teleport(Player p, Location dest, boolean force) {
        UUID id = p.getUniqueId();
        pending.remove(id);
        if (!p.isOnline()) return;

        clearCountdownEffects(p);
        Location from = p.getLocation();
        if (cfg().getBoolean("qol.keep-yaw-pitch", true)) {
            dest.setYaw(from.getYaw());
            dest.setPitch(from.getPitch());
        }

        p.teleportAsync(dest).thenAccept(ok -> plugin.sync(() -> {
            if (!ok) {
                msg().send(p, "teleport-failed");
                return;
            }
            particles(from);
            play(p, from, "teleport-out");
            particles(p.getLocation());
            play(p, "teleport-in");

            if (!force && !p.hasPermission("nicertp.bypass.cooldown")) {
                cooldowns.put(id, System.currentTimeMillis());
            }
            backLocations.put(id, from);

            int ticks = cfg().getInt("safety.invulnerable-ticks", 60);
            if (ticks > 0) {
                protectedPlayers.add(id);
                Bukkit.getScheduler().runTaskLater(plugin, () -> protectedPlayers.remove(id), ticks);
            }
            p.setFallDistance(0);

            String x = String.valueOf(dest.getBlockX());
            String y = String.valueOf(dest.getBlockY());
            String z = String.valueOf(dest.getBlockZ());
            String world = dest.getWorld().getName();

            msg().send(p, "teleported", "x", x, "y", y, "z", z, "world", world);
            if (cfg().getBoolean("effects.arrival-title", true)) {
                p.showTitle(Title.title(
                        msg().component("arrival-title", "x", x, "y", y, "z", z, "world", world),
                        msg().component("arrival-subtitle", "x", x, "y", y, "z", z, "world", world)));
            }
            if (cfg().getBoolean("logging.log-teleports", false)) {
                plugin.getLogger().info(p.getName() + " RTP'd to " + world + " " + x + ", " + y + ", " + z);
            }
        }));
    }

    // ------------------------------------------------------------------ cancel

    public boolean cancel(Player p, String reasonKey) {
        Pending pd = pending.remove(p.getUniqueId());
        if (pd == null) return false;
        pd.cancelled = true;
        if (pd.task != null) pd.task.cancel();
        clearCountdownEffects(p);
        p.clearTitle();
        if (reasonKey != null) {
            msg().send(p, reasonKey);
            play(p, "cancel");
        }
        return true;
    }

    Pending getPending(Player p) {
        return pending.get(p.getUniqueId());
    }

    public boolean isProtected(UUID id) {
        return protectedPlayers.contains(id);
    }

    public void handleQuit(Player p) {
        cancel(p, null);
        queue.remove(p.getUniqueId());
        protectedPlayers.remove(p.getUniqueId());
        backLocations.remove(p.getUniqueId());
    }

    // -------------------------------------------------------------------- back

    public void back(Player p) {
        if (!cfg().getBoolean("back.enabled", true)) {
            msg().send(p, "back-disabled");
            return;
        }
        if (!p.hasPermission("nicertp.back")) {
            msg().send(p, "no-permission");
            return;
        }
        Location l = backLocations.get(p.getUniqueId());
        if (l == null) {
            msg().send(p, "no-back");
            return;
        }
        p.teleportAsync(l).thenAccept(ok -> plugin.sync(() -> {
            if (ok) {
                msg().send(p, "back-teleported");
                play(p, "teleport-in");
            } else {
                msg().send(p, "teleport-failed");
            }
        }));
    }

    // ------------------------------------------------------------------- queue

    private World queueWorld() {
        return worldFor(cfg().getString("rtpq.world", "overworld"));
    }

    public int queueSize() {
        return queue.size();
    }

    public int queueRequired() {
        return Math.max(2, cfg().getInt("rtpq.players-required", 2));
    }

    public boolean leaveQueue(Player p) {
        if (queue.remove(p.getUniqueId()) != null) {
            msg().send(p, "queue-left");
            return true;
        }
        return false;
    }

    public void toggleQueue(Player p) {
        if (!cfg().getBoolean("rtpq.enabled", false)) {
            msg().send(p, "rtpq-disabled");
            return;
        }
        if (!p.hasPermission("nicertp.queue")) {
            msg().send(p, "no-permission");
            return;
        }
        UUID id = p.getUniqueId();
        if (leaveQueue(p)) return;

        if (pending.containsKey(id)) {
            msg().send(p, "already-teleporting");
            return;
        }
        long left = cooldownLeft(p);
        if (left > 0) {
            msg().send(p, "cooldown", "seconds", String.valueOf(left));
            return;
        }
        World w = queueWorld();
        if (w == null) {
            msg().send(p, "unknown-world", "input", cfg().getString("rtpq.world", "?"));
            return;
        }
        if (!worldAllowed(w)) {
            msg().send(p, "world-disabled", "world", w.getName());
            return;
        }

        queue.put(id, System.currentTimeMillis());
        msg().send(p, "queue-joined", "size", String.valueOf(queue.size()), "required", String.valueOf(queueRequired()));
        play(p, "queue-join");
        tryMatch();
    }

    private void tryMatch() {
        queue.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
        int required = queueRequired();
        if (queue.size() < required) return;
        World w = queueWorld();
        if (w == null) return;

        List<Player> group = new ArrayList<>();
        Iterator<UUID> it = queue.keySet().iterator();
        while (it.hasNext() && group.size() < required) {
            Player pl = Bukkit.getPlayer(it.next());
            it.remove();
            if (pl != null) group.add(pl);
        }
        if (group.size() < required) {
            for (Player pl : group) queue.put(pl.getUniqueId(), System.currentTimeMillis());
            return;
        }

        Map<UUID, Pending> pds = new HashMap<>();
        for (Player pl : group) {
            Pending pd = new Pending(pl.getLocation(), true);
            pending.put(pl.getUniqueId(), pd);
            pds.put(pl.getUniqueId(), pd);
        }
        String names = group.stream().map(Player::getName).collect(Collectors.joining(", "));
        for (Player pl : group) {
            msg().send(pl, "queue-matched", "players", names);
            play(pl, "queue-match");
        }

        plugin.finder().find(w).thenAccept(loc -> {
            if (loc == null) {
                for (Player pl : group) {
                    UUID id = pl.getUniqueId();
                    if (pending.get(id) == pds.get(id)) pending.remove(id);
                    msg().send(pl, "no-safe-location");
                }
                return;
            }
            runGroupCountdown(group, pds, loc);
        });
    }

    private void runGroupCountdown(List<Player> group, Map<UUID, Pending> pds, Location loc) {
        int seconds = Math.max(0, cfg().getInt("rtpq.countdown-seconds", 3));
        new BukkitRunnable() {
            int left = seconds;

            @Override
            public void run() {
                List<Player> active = new ArrayList<>();
                for (Player pl : group) {
                    UUID id = pl.getUniqueId();
                    if (pl.isOnline() && pending.get(id) == pds.get(id)) active.add(pl);
                }
                if (active.size() < 2) {
                    for (Player pl : active) {
                        pending.remove(pl.getUniqueId());
                        msg().send(pl, "queue-partner-left");
                    }
                    cancel();
                    return;
                }
                for (Player pl : active) {
                    if (left <= 0) teleport(pl, loc.clone(), false);
                    else tick(pl, left);
                }
                if (left <= 0) {
                    cancel();
                    return;
                }
                left--;
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    // ------------------------------------------------------------------- tasks

    public void startTasks() {
        queueTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long now = System.currentTimeMillis();
            long timeout = cfg().getLong("rtpq.timeout-seconds", 60) * 1000L;

            Iterator<Map.Entry<UUID, Long>> it = queue.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, Long> e = it.next();
                Player pl = Bukkit.getPlayer(e.getKey());
                if (pl == null) {
                    it.remove();
                    continue;
                }
                if (timeout > 0 && now - e.getValue() > timeout) {
                    it.remove();
                    msg().send(pl, "queue-timeout");
                    continue;
                }
                if (cfg().getBoolean("rtpq.show-waiting-actionbar", true)) {
                    pl.sendActionBar(msg().component("queue-waiting",
                            "size", String.valueOf(queue.size()), "required", String.valueOf(queueRequired())));
                }
            }

            long cdMs = cfg().getLong("cooldown.seconds", 30) * 1000L;
            cooldowns.values().removeIf(t -> now - t > cdMs);
        }, 20L, 20L);
    }

    public void shutdown() {
        if (queueTask != null) queueTask.cancel();
        for (Pending pd : pending.values()) {
            if (pd.task != null) pd.task.cancel();
        }
        pending.clear();
    }

    // ----------------------------------------------------------------- effects

    private void applyCountdownEffects(Player p, int seconds) {
        int ticks = (seconds + 2) * 20;
        if (cfg().getBoolean("effects.countdown-nausea", false)) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, ticks, 0, false, false, false));
        }
        if (cfg().getBoolean("effects.countdown-blindness", false)) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, ticks, 0, false, false, false));
        }
    }

    private void clearCountdownEffects(Player p) {
        if (cfg().getBoolean("effects.countdown-nausea", false)) p.removePotionEffect(PotionEffectType.NAUSEA);
        if (cfg().getBoolean("effects.countdown-blindness", false)) p.removePotionEffect(PotionEffectType.BLINDNESS);
    }

    public void play(Player p, String key) {
        play(p, p.getLocation(), key);
    }

    private void play(Player p, Location at, String key) {
        if (!cfg().getBoolean("effects.sounds", true)) return;
        String b = "effects.sound-list." + key;
        String name = cfg().getString(b + ".name");
        if (name == null || name.isBlank()) return;
        p.playSound(at, name, (float) cfg().getDouble(b + ".volume", 1.0), (float) cfg().getDouble(b + ".pitch", 1.0));
    }

    private void particles(Location at) {
        if (!cfg().getBoolean("effects.particles", true) || at.getWorld() == null) return;
        try {
            NamespacedKey key = NamespacedKey.fromString(cfg().getString("effects.particle-type", "portal").toLowerCase());
            if (key == null) return;
            Particle type = Registry.PARTICLE_TYPE.get(key);
            if (type == null) return;
            at.getWorld().spawnParticle(type, at.clone().add(0, 1, 0),
                    cfg().getInt("effects.particle-count", 60), 0.4, 0.8, 0.4, 0.1);
        } catch (Exception ignored) {
        }
    }
}
