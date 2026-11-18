package com.nicertp;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

public final class NiceRTP extends JavaPlugin {

    private Messages messages;
    private LocationFinder finder;
    private RTPManager manager;
    private GuiManager gui;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        messages = new Messages(this);
        finder = new LocationFinder(this);
        manager = new RTPManager(this);
        gui = new GuiManager(this);

        RTPCommand rtp = new RTPCommand(this);
        bind("rtp", rtp, rtp);
        NiceRTPCommand admin = new NiceRTPCommand(this);
        bind("nicertp", admin, admin);

        Bukkit.getPluginManager().registerEvents(new PlayerListener(this), this);
        Bukkit.getPluginManager().registerEvents(gui, this);

        manager.startTasks();
        getLogger().info("NiceRTP enabled.");
    }

    @Override
    public void onDisable() {
        if (manager != null) manager.shutdown();
    }

    private void bind(String name, CommandExecutor executor, TabCompleter completer) {
        PluginCommand cmd = getCommand(name);
        if (cmd == null) return;
        cmd.setExecutor(executor);
        cmd.setTabCompleter(completer);
    }

    /** Run on the main thread (immediately if already there). */
    public void sync(Runnable r) {
        if (!isEnabled()) return;
        if (Bukkit.isPrimaryThread()) r.run();
        else Bukkit.getScheduler().runTask(this, r);
    }

    public void reloadAll() {
        reloadConfig();
    }

    public Messages messages() { return messages; }
    public LocationFinder finder() { return finder; }
    public RTPManager manager() { return manager; }
    public GuiManager gui() { return gui; }
}
