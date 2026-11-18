package com.nicertp;

import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

public final class LocationFinder {

    private final NiceRTP plugin;

    public LocationFinder(NiceRTP plugin) {
        this.plugin = plugin;
    }

    public static String envKey(World w) {
        return switch (w.getEnvironment()) {
            case NETHER -> "nether";
            case THE_END -> "end";
            default -> "overworld";
        };
    }

    /** Completes (on the main thread) with a safe location, or null if none was found. */
    public CompletableFuture<Location> find(World world) {
        CompletableFuture<Location> result = new CompletableFuture<>();
        Search s = new Search(world);
        attempt(s, 0, result);
        return result;
    }

    private void attempt(Search s, int n, CompletableFuture<Location> result) {
        if (n >= s.attempts) {
            result.complete(null);
            return;
        }
        int[] p = s.randomXZ();
        s.world.getChunkAtAsync(p[0] >> 4, p[1] >> 4, true).whenComplete((chunk, ex) -> plugin.sync(() -> {
            if (ex != null || chunk == null) {
                attempt(s, n + 1, result);
                return;
            }
            Location loc;
            try {
                loc = evaluate(s, p[0], p[1]);
            } catch (Exception e) {
                loc = null;
            }
            if (loc != null) result.complete(loc);
            else attempt(s, n + 1, result);
        }));
    }

    private Location evaluate(Search s, int x, int z) {
        World w = s.world;
        int groundY;

        if (w.getEnvironment() == World.Environment.NETHER) {
            int top = Math.min(s.maxY, w.getMaxHeight() - 3);
            int bottom = Math.max(s.minY, w.getMinHeight() + 1);
            if (top <= bottom) return null;
            groundY = Integer.MIN_VALUE;
            int start = ThreadLocalRandom.current().nextInt(bottom, top + 1);
            for (int y = start; y >= bottom; y--) {
                if (safeSpot(s, x, y, z)) {
                    groundY = y;
                    break;
                }
            }
            if (groundY == Integer.MIN_VALUE) return null;
        } else {
            groundY = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            if (groundY <= w.getMinHeight()) return null;
            if (w.getBlockAt(x, groundY, z).getType() == Material.SNOW) groundY--;
            if (!safeSpot(s, x, groundY, z)) return null;
        }

        Location loc = new Location(w, x + 0.5, groundY + 1, z + 0.5);
        if (s.border && !w.getWorldBorder().isInside(loc)) return null;

        if (!s.badBiomes.isEmpty()) {
            String biome = w.getBiome(x, groundY, z).getKey().getKey();
            if (s.badBiomes.contains(biome)) return null;
        }
        return loc;
    }

    private boolean safeSpot(Search s, int x, int y, int z) {
        World w = s.world;
        Block ground = w.getBlockAt(x, y, z);
        if (!ground.getType().isSolid()) return false;

        Block feet = ground.getRelative(BlockFace.UP);
        Block head = feet.getRelative(BlockFace.UP);

        if (s.bad.contains(ground.getType()) || s.bad.contains(feet.getType()) || s.bad.contains(head.getType())) {
            return false;
        }
        if (!feet.isPassable() || feet.isLiquid() || !head.isPassable() || head.isLiquid()) return false;

        BlockFace[] sides = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
        for (BlockFace f : sides) {
            if (s.bad.contains(ground.getRelative(f).getType()) || s.bad.contains(feet.getRelative(f).getType())) {
                return false;
            }
        }
        return true;
    }

    /** Snapshot of the config for one search. */
    private final class Search {
        final World world;
        final int min, max, attempts, minY, maxY;
        final String shape;
        final double cx, cz;
        final boolean border;
        final Set<Material> bad = new HashSet<>();
        final Set<String> badBiomes = new HashSet<>();

        Search(World world) {
            FileConfiguration c = plugin.getConfig();
            this.world = world;
            String b = "locations." + envKey(world) + ".";

            int mn = Math.max(0, c.getInt(b + "min-radius", 0));
            int mx = Math.max(mn + 1, c.getInt(b + "max-radius", 1000));
            this.border = c.getBoolean("safety.respect-world-border", true);

            double x = 0, z = 0;
            String mode = c.getString(b + "center-mode", "ZERO").toUpperCase();
            switch (mode) {
                case "SPAWN" -> {
                    Location sp = world.getSpawnLocation();
                    x = sp.getX();
                    z = sp.getZ();
                }
                case "BORDER" -> {
                    Location bc = world.getWorldBorder().getCenter();
                    x = bc.getX();
                    z = bc.getZ();
                }
                case "CUSTOM" -> {
                    x = c.getDouble(b + "center-x", 0);
                    z = c.getDouble(b + "center-z", 0);
                }
                default -> {
                }
            }
            this.cx = x;
            this.cz = z;

            if (border) {
                int half = (int) (world.getWorldBorder().getSize() / 2) - 16;
                mx = Math.min(mx, Math.max(half, 2));
                mn = Math.max(0, Math.min(mn, mx - 1));
            }
            this.min = mn;
            this.max = mx;
            this.shape = c.getString(b + "shape", "CIRCLE").toUpperCase();
            this.attempts = Math.max(1, c.getInt(b + "max-attempts", 30));
            this.minY = c.getInt(b + "min-y", 32);
            this.maxY = c.getInt(b + "max-y", 120);

            bad.add(Material.LAVA);
            bad.add(Material.FIRE);
            for (String n : c.getStringList("safety.blocked-blocks")) {
                Material m = Material.matchMaterial(n);
                if (m != null) bad.add(m);
            }
            for (String n : c.getStringList("safety.blocked-biomes")) {
                badBiomes.add(n.toLowerCase().replace("minecraft:", ""));
            }
        }

        int[] randomXZ() {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            double dx, dz;
            if (shape.equals("SQUARE")) {
                do {
                    dx = r.nextInt(-max, max + 1);
                    dz = r.nextInt(-max, max + 1);
                } while (Math.max(Math.abs(dx), Math.abs(dz)) < min);
            } else {
                double angle = r.nextDouble() * Math.PI * 2;
                double radius = Math.sqrt(r.nextDouble() * ((double) max * max - (double) min * min) + (double) min * min);
                dx = Math.cos(angle) * radius;
                dz = Math.sin(angle) * radius;
            }
            return new int[]{(int) Math.floor(cx + dx), (int) Math.floor(cz + dz)};
        }
    }
}
