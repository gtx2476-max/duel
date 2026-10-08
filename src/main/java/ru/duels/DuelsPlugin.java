package ru.duels;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class DuelsPlugin extends JavaPlugin {

    private DuelManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        manager = new DuelManager(this);
        manager.start();
        getServer().getPluginManager().registerEvents(new DuelListener(manager), this);
        PluginCommand cmd = getCommand("duel");
        if (cmd != null) {
            DuelCommand h = new DuelCommand(manager);
            cmd.setExecutor(h);
            cmd.setTabCompleter(h);
        }
        getLogger().info("DuelArena включен.");
    }

    @Override
    public void onDisable() {
        if (manager != null) manager.shutdown();
    }

    public DuelManager getManager() { return manager; }
}
