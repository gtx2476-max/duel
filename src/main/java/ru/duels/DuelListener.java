package ru.duels;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.Locale;

public final class DuelListener implements Listener {

    private final DuelManager m;

    public DuelListener(DuelManager m) {
        this.m = m;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        m.handleQuit(e.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        m.handleJoin(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        Player v = e.getEntity();
        DuelManager.Arena a = m.arenaOf(v.getUniqueId());
        if (a == null) return;
        e.setKeepInventory(false);
        e.setKeepLevel(false);
        Player k = v.getKiller();
        String text = k != null ? "&c" + v.getName() + " &7проиграл дуэль игроку &e" + k.getName()
                : "&c" + v.getName() + " &7проиграл дуэль";
        e.deathMessage(DuelManager.c(text));
        m.handleDeath(v);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent e) {
        m.handleRespawn(e);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        DuelManager.Arena a = m.arenaOf(p.getUniqueId());
        if (a == null || p.hasPermission("duel.admin")) return;
        String msg = e.getMessage().toLowerCase(Locale.ROOT).trim();
        boolean leave = msg.equals("/duel leave") || msg.equals("/duel exit") || msg.equals("/duels leave");
        if (leave && a.getState() == DuelManager.State.ENDED) return;
        e.setCancelled(true);
        DuelManager.msg(p, "&cВо время дуэли команды отключены.");
    }

    /** Во время отсчёта игроки стоят на месте и неуязвимы. */
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        DuelManager.Arena a = m.arenaOf(e.getPlayer().getUniqueId());
        if (a == null || a.getState() != DuelManager.State.COUNTDOWN) return;
        if (e.getTo() == null) return;
        if (e.getFrom().getX() != e.getTo().getX() || e.getFrom().getZ() != e.getTo().getZ()) {
            e.setTo(e.getFrom().clone().setDirection(e.getTo().getDirection()));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        DuelManager.Arena a = m.arenaOf(p.getUniqueId());
        if (a != null && a.getState() == DuelManager.State.COUNTDOWN) e.setCancelled(true);
    }

    // ---------- правила острова ----------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!m.isDuelWorld(e.getBlock().getWorld())) return;
        DuelManager.Arena a = m.arenaAt(e.getBlock().getLocation());
        if (a == null || !m.inBounds(a, e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!m.isDuelWorld(e.getBlock().getWorld())) return;
        if (e.getBlock().getType() == Material.BARRIER) {
            e.setCancelled(true);
            return;
        }
        DuelManager.Arena a = m.arenaAt(e.getBlock().getLocation());
        if (a == null || !m.inBounds(a, e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        if (m.isDuelWorld(e.getLocation().getWorld())) e.blockList().removeIf(b -> b.getType() == Material.BARRIER);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (m.isDuelWorld(e.getBlock().getWorld())) e.blockList().removeIf(b -> b.getType() == Material.BARRIER);
    }
}
