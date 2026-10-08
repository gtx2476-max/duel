package ru.duels;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /duel <ник> | accept <ник> | deny <ник> | leave */
public final class DuelCommand implements CommandExecutor, TabCompleter {

    private final DuelManager m;

    public DuelCommand(DuelManager m) {
        this.m = m;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!(s instanceof Player p)) {
            s.sendMessage("Только для игроков.");
            return true;
        }
        if (a.length == 0) {
            DuelManager.msg(p, "&6⚔ &fИспользование: &e/duel <ник> &7— вызвать игрока на дуэль");
            return true;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "accept" -> {
                if (a.length < 2) DuelManager.msg(p, "&cИспользование: /duel accept <ник>");
                else m.accept(p, a[1]);
            }
            case "deny" -> {
                if (a.length < 2) DuelManager.msg(p, "&cИспользование: /duel deny <ник>");
                else m.deny(p, a[1]);
            }
            case "leave", "exit" -> m.leave(p);
            default -> {
                Player target = Bukkit.getPlayerExact(a[0]);
                if (target == null) DuelManager.msg(p, "&cИгрок &f" + a[0] + " &cне найден.");
                else m.invite(p, target);
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> res = new ArrayList<>();
        if (a.length == 1) {
            res.add("leave");
            for (Player p : Bukkit.getOnlinePlayers()) res.add(p.getName());
        } else if (a.length == 2 && (a[0].equalsIgnoreCase("accept") || a[0].equalsIgnoreCase("deny"))) {
            for (Player p : Bukkit.getOnlinePlayers()) res.add(p.getName());
        }
        return res;
    }
}
