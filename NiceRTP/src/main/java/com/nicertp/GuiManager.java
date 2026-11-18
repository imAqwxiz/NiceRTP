package com.nicertp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public final class GuiManager implements Listener {

    private static final String[] KEYS = {"overworld", "nether", "end", "rtpq"};

    private record Toggle(String path, Material icon, String name, String desc) {
    }

    private static final List<Toggle> TOGGLES = List.of(
            new Toggle("rtp.use-gui", Material.CHEST, "Use GUI for /rtp", "/rtp opens the chest menu"),
            new Toggle("rtpq.enabled", Material.ENDER_PEARL, "RTPQ (queue)", "Enable the RTP queue (slot 22)"),
            new Toggle("countdown.cancel-on-move", Material.LEATHER_BOOTS, "Cancel on move", "Moving cancels the RTP"),
            new Toggle("countdown.cancel-on-damage", Material.IRON_SWORD, "Cancel on damage", "Damage cancels the RTP"),
            new Toggle("countdown.show-title", Material.OAK_SIGN, "Countdown title", "Show the countdown as a title"),
            new Toggle("countdown.show-actionbar", Material.PAPER, "Countdown actionbar", "Show the countdown in the actionbar"),
            new Toggle("effects.sounds", Material.NOTE_BLOCK, "Sounds", "Play RTP sounds"),
            new Toggle("effects.particles", Material.BLAZE_POWDER, "Particles", "Spawn teleport particles"),
            new Toggle("effects.countdown-nausea", Material.FERMENTED_SPIDER_EYE, "Countdown nausea", "Nausea during countdown"),
            new Toggle("safety.respect-world-border", Material.BARRIER, "Respect world border", "Never RTP outside the border"),
            new Toggle("first-join.enabled", Material.OAK_DOOR, "RTP on first join", "RTP brand-new players"),
            new Toggle("back.enabled", Material.COMPASS, "/rtp back", "Allow returning to the previous spot"),
            new Toggle("logging.log-teleports", Material.WRITABLE_BOOK, "Log teleports", "Log every RTP to console")
    );

    private static final class Holder implements InventoryHolder {
        final String type;
        Inventory inv;

        Holder(String type) {
            this.type = type;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final NiceRTP plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public GuiManager(NiceRTP plugin) {
        this.plugin = plugin;
    }

    // ----------------------------------------------------------------- RTP GUI

    public void openRtp(Player p) {
        FileConfiguration c = plugin.getConfig();
        int rows = Math.max(3, Math.min(6, c.getInt("gui.rows", 3)));
        Holder h = new Holder("rtp");
        Inventory inv = Bukkit.createInventory(h, rows * 9, mm.deserialize(c.getString("gui.title", "NiceRTP")));
        h.inv = inv;

        if (c.getBoolean("gui.filler.enabled", true)) {
            ItemStack filler = item(c.getString("gui.filler.material", "GRAY_STAINED_GLASS_PANE"), " ", List.of(), false);
            for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
        }

        for (String key : KEYS) {
            if (key.equals("rtpq") && !c.getBoolean("rtpq.enabled", false)) continue;
            String b = "gui.items." + key + ".";
            if (!c.getBoolean(b + "enabled", true)) continue;
            int slot = c.getInt(b + "slot", -1);
            if (slot < 0 || slot >= inv.getSize()) continue;

            List<String> lore = new ArrayList<>();
            for (String line : c.getStringList(b + "lore")) lore.add(fill(line, p));
            inv.setItem(slot, item(c.getString(b + "material", "STONE"),
                    fill(c.getString(b + "name", key), p), lore, c.getBoolean(b + "glow", false)));
        }
        p.openInventory(inv);
    }

    private String fill(String s, Player p) {
        long cd = plugin.manager().cooldownLeft(p);
        return s.replace("%cooldown%", cd > 0 ? cd + "s" : "Ready")
                .replace("%queue_size%", String.valueOf(plugin.manager().queueSize()))
                .replace("%required%", String.valueOf(plugin.manager().queueRequired()));
    }

    // -------------------------------------------------------------- config GUI

    public void openConfig(Player p) {
        FileConfiguration c = plugin.getConfig();
        Holder h = new Holder("config");
        Inventory inv = Bukkit.createInventory(h, 27, mm.deserialize("<dark_gray>NiceRTP <gray>» <white>Config"));
        h.inv = inv;

        ItemStack filler = item("BLACK_STAINED_GLASS_PANE", " ", List.of(), false);
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);

        for (int i = 0; i < TOGGLES.size(); i++) {
            Toggle t = TOGGLES.get(i);
            boolean on = c.getBoolean(t.path());
            int slot = 9 * (i / 7 + 1) + 1 + (i % 7);
            List<String> lore = List.of(
                    "<gray>" + t.desc(),
                    "",
                    "<gray>Status: " + (on ? "<green>ON" : "<red>OFF"),
                    "<dark_gray>" + t.path(),
                    "<yellow>Click to toggle");
            inv.setItem(slot, item(t.icon().name(), (on ? "<green>" : "<red>") + t.name(), lore, on));
        }
        p.openInventory(inv);
    }

    // ------------------------------------------------------------------ events

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != top) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;

        int slot = e.getSlot();
        if (h.type.equals("rtp")) handleRtpClick(p, slot);
        else handleConfigClick(p, slot);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }

    private void handleRtpClick(Player p, int slot) {
        FileConfiguration c = plugin.getConfig();
        for (String key : KEYS) {
            String b = "gui.items." + key + ".";
            if (c.getInt(b + "slot", -1) != slot) continue;

            plugin.manager().play(p, "gui-click");
            if (key.equals("rtpq")) {
                if (!c.getBoolean("rtpq.enabled", false)) return;
                p.closeInventory();
                plugin.manager().toggleQueue(p);
                return;
            }
            if (!c.getBoolean(b + "enabled", true)) return;
            p.closeInventory();
            plugin.manager().rtp(p, plugin.manager().worldFor(key), false);
            return;
        }
    }

    private void handleConfigClick(Player p, int slot) {
        int row = slot / 9 - 1;
        int col = slot % 9 - 1;
        if (row < 0 || row > 1 || col < 0 || col > 6) return;
        int i = row * 7 + col;
        if (i >= TOGGLES.size()) return;

        Toggle t = TOGGLES.get(i);
        FileConfiguration c = plugin.getConfig();
        c.set(t.path(), !c.getBoolean(t.path()));
        plugin.saveConfig();
        plugin.manager().play(p, "gui-click");
        Bukkit.getScheduler().runTask(plugin, () -> openConfig(p));
    }

    // ----------------------------------------------------------------- helpers

    private ItemStack item(String mat, String name, List<String> lore, boolean glow) {
        Material m = Material.matchMaterial(mat);
        if (m == null) m = Material.STONE;
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(noItalic(mm.deserialize(name)));
            if (!lore.isEmpty()) {
                List<Component> comps = new ArrayList<>();
                for (String l : lore) comps.add(noItalic(mm.deserialize(l)));
                meta.lore(comps);
            }
            if (glow) meta.setEnchantmentGlintOverride(true);
            it.setItemMeta(meta);
        }
        return it;
    }

    private Component noItalic(Component c) {
        return c.decoration(TextDecoration.ITALIC, false);
    }
}
