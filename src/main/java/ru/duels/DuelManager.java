package ru.duels;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Дуэли 1 на 1 в отдельном void-мире. Для каждой пары — своя кабинка (остров + барьер) в случайной точке.
 * Анти-релог: выход во время боя = поражение. Команды во время боя запрещены. После боя кабинка стирается.
 */
public final class DuelManager {

    public enum State { COUNTDOWN, ACTIVE, ENDED }

    public static final class Arena {
        final int id;
        final int cx;
        final int cz;
        final UUID[] players = new UUID[2];
        State state = State.COUNTDOWN;
        int countdown;
        int removeIn;
        UUID winner;
        final Set<UUID> left = new HashSet<>();
        boolean removing = false;

        Arena(int id, int cx, int cz) {
            this.id = id;
            this.cx = cx;
            this.cz = cz;
        }

        public State getState() { return state; }
    }

    private static final class Ret {
        final Location loc;
        final GameMode mode;

        Ret(Location loc, GameMode mode) {
            this.loc = loc;
            this.mode = mode;
        }
    }

    private record Invite(UUID sender, UUID target, long expires) {}

    public static final class VoidGen extends ChunkGenerator {
        @Override
        public Location getFixedSpawnLocation(World world, Random random) {
            return new Location(world, 0.5, 101, 0.5);
        }
    }

    private final DuelsPlugin plugin;
    private final Random random = new Random();
    private final List<Arena> arenas = new ArrayList<>();
    private final Map<UUID, Arena> byPlayer = new HashMap<>();
    private final Map<UUID, Ret> returns = new HashMap<>();
    private final Map<UUID, Ret> respawnReturn = new HashMap<>();
    private final List<Invite> invites = new ArrayList<>();
    private World world;
    private BukkitTask task;
    private int nextId = 1;
    private int ticks = 0;

    public DuelManager(DuelsPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------- утилиты ----------

    public static Component c(String legacy) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(legacy);
    }

    public static void msg(Player p, String legacy) { p.sendMessage(c(legacy)); }

    private int radius() { return Math.max(8, plugin.getConfig().getInt("island-radius", 20)); }

    private int topY() { return plugin.getConfig().getInt("arena-y", 100); }

    public World world() { return world; }

    public boolean isDuelWorld(World w) { return world != null && world.equals(w); }

    public Arena arenaOf(UUID id) { return byPlayer.get(id); }

    public boolean inDuel(Player p) { return byPlayer.containsKey(p.getUniqueId()); }

    // ---------- запуск / остановка ----------

    public void start() {
        String name = plugin.getConfig().getString("world", "duel_world");
        world = Bukkit.getWorld(name);
        if (world == null) {
            WorldCreator wc = new WorldCreator(name);
            wc.environment(World.Environment.NORMAL);
            wc.generator(new VoidGen());
            world = wc.createWorld();
        }
        if (world == null) {
            plugin.getLogger().severe("Не удалось создать мир дуэлей!");
            return;
        }
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.KEEP_INVENTORY, false);
        world.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, true);
        world.setTime(6000);
        world.setStorm(false);
        loadReturns();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick5, 5L, 5L);
    }

    public void shutdown() {
        if (task != null) task.cancel();
        for (Arena a : new ArrayList<>(arenas)) {
            for (UUID id : a.players) {
                if (id == null) continue;
                Player p = Bukkit.getPlayer(id);
                if (p != null && isDuelWorld(p.getWorld()) && byPlayer.containsKey(id)) returnPlayer(p);
            }
            wipeNow(a);
        }
        arenas.clear();
        byPlayer.clear();
        saveReturns();
    }

    // ---------- сохранение мест возврата ----------

    private File returnsFile() { return new File(plugin.getDataFolder(), "returns.yml"); }

    private void loadReturns() {
        if (!returnsFile().exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(returnsFile());
        for (String k : y.getKeys(false)) {
            try {
                String[] p = y.getString(k, "").split(";");
                World w = Bukkit.getWorld(p[0]);
                if (w == null) continue;
                Location l = new Location(w, Double.parseDouble(p[1]), Double.parseDouble(p[2]),
                        Double.parseDouble(p[3]), Float.parseFloat(p[4]), Float.parseFloat(p[5]));
                returns.put(UUID.fromString(k), new Ret(l, GameMode.valueOf(p[6])));
            } catch (Exception ignored) {
            }
        }
    }

    private void saveReturns() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Ret> e : returns.entrySet()) {
            Location l = e.getValue().loc;
            y.set(e.getKey().toString(), l.getWorld().getName() + ";" + l.getX() + ";" + l.getY() + ";" + l.getZ()
                    + ";" + l.getYaw() + ";" + l.getPitch() + ";" + e.getValue().mode.name());
        }
        try {
            plugin.getDataFolder().mkdirs();
            y.save(returnsFile());
        } catch (IOException ex) {
            plugin.getLogger().warning("Не удалось сохранить returns.yml: " + ex.getMessage());
        }
    }

    private Location fallback() {
        World w = Bukkit.getWorlds().get(0);
        if (isDuelWorld(w) && Bukkit.getWorlds().size() > 1) w = Bukkit.getWorlds().get(1);
        return w.getSpawnLocation();
    }

    private void returnPlayer(Player p) {
        Ret r = returns.remove(p.getUniqueId());
        saveReturns();
        p.setFallDistance(0f);
        if (r != null) {
            p.teleport(r.loc);
            p.setGameMode(r.mode);
        } else {
            p.teleport(fallback());
            p.setGameMode(GameMode.SURVIVAL);
        }
    }

    // ---------- приглашения ----------

    public void invite(Player s, Player t) {
        if (s.equals(t)) { msg(s, "&cНельзя вызвать самого себя."); return; }
        if (world == null) { msg(s, "&cМир дуэлей недоступен."); return; }
        if (inDuel(s)) { msg(s, "&cВы уже в дуэли."); return; }
        if (inDuel(t)) { msg(s, "&cИгрок &f" + t.getName() + " &cсейчас в дуэли."); return; }
        if (isDuelWorld(s.getWorld()) || isDuelWorld(t.getWorld())) { msg(s, "&cСейчас это невозможно."); return; }
        for (Invite i : invites) {
            if (i.sender().equals(s.getUniqueId()) && i.target().equals(t.getUniqueId()) && i.expires() > System.currentTimeMillis()) {
                msg(s, "&cВы уже отправили приглашение этому игроку.");
                return;
            }
        }
        long secs = Math.max(10, plugin.getConfig().getInt("invite-seconds", 60));
        invites.add(new Invite(s.getUniqueId(), t.getUniqueId(), System.currentTimeMillis() + secs * 1000L));

        msg(s, "&6⚔ &fВы вызвали на дуэль игрока &e" + t.getName() + "&f. Ожидание ответа (" + secs + " сек.)");
        Component accept = Component.text("[✔ ПРИНЯТЬ]", NamedTextColor.GREEN, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/duel accept " + s.getName()))
                .hoverEvent(HoverEvent.showText(Component.text("Принять вызов на дуэль")));
        Component deny = Component.text("[✘ ОТКЛОНИТЬ]", NamedTextColor.RED, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/duel deny " + s.getName()))
                .hoverEvent(HoverEvent.showText(Component.text("Отказаться от дуэли")));
        t.sendMessage(c(""));
        t.sendMessage(c("&6⚔ &fИгрок &e" + s.getName() + " &fвызывает вас на дуэль! &7(действует " + secs + " сек.)"));
        t.sendMessage(accept.append(Component.text("   ")).append(deny));
        t.sendMessage(c("&8Выход с сервера во время боя = поражение. Команды во время боя запрещены."));
        t.sendMessage(c(""));
        t.playSound(t.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f);
    }

    private Invite find(Player target, String senderName) {
        long now = System.currentTimeMillis();
        for (Invite i : invites) {
            if (!i.target().equals(target.getUniqueId()) || i.expires() < now) continue;
            Player s = Bukkit.getPlayer(i.sender());
            if (s != null && s.getName().equalsIgnoreCase(senderName)) return i;
        }
        return null;
    }

    public void deny(Player target, String senderName) {
        Invite i = find(target, senderName);
        if (i == null) { msg(target, "&cПриглашение не найдено или уже истекло."); return; }
        invites.remove(i);
        Player s = Bukkit.getPlayer(i.sender());
        String sn = s != null ? s.getName() : senderName;
        if (plugin.getConfig().getBoolean("broadcast-decline", true)) {
            Bukkit.broadcast(c("&c" + target.getName() + " &7с позором отклонил приглашение на дуэль игрока &e" + sn));
        } else {
            msg(target, "&7Вы отклонили приглашение.");
            if (s != null) msg(s, "&c" + target.getName() + " &7с позором отклонил приглашение на дуэль.");
        }
    }

    public void accept(Player target, String senderName) {
        Invite i = find(target, senderName);
        if (i == null) { msg(target, "&cПриглашение не найдено или уже истекло."); return; }
        Player s = Bukkit.getPlayer(i.sender());
        invites.remove(i);
        if (s == null || !s.isOnline()) { msg(target, "&cИгрок уже вышел."); return; }
        if (inDuel(s) || inDuel(target)) { msg(target, "&cКто-то из вас уже в дуэли."); return; }
        startDuel(s, target);
    }

    // ---------- арена ----------

    private int[] pickCenter() {
        int range = Math.max(1000, plugin.getConfig().getInt("coords-range", 50000));
        int minDist = Math.max(100, plugin.getConfig().getInt("min-arena-distance", 500));
        for (int attempt = 0; attempt < 100; attempt++) {
            int cx = (random.nextInt(range * 2) - range) / 16 * 16 + 8;
            int cz = (random.nextInt(range * 2) - range) / 16 * 16 + 8;
            boolean ok = true;
            for (Arena a : arenas) {
                double dx = a.cx - cx;
                double dz = a.cz - cz;
                if (dx * dx + dz * dz < (double) minDist * minDist) { ok = false; break; }
            }
            if (ok) return new int[]{cx, cz};
        }
        return new int[]{random.nextInt(range), random.nextInt(range)};
    }

    private void set(int x, int y, int z, Material m) {
        world.getBlockAt(x, y, z).setType(m, false);
    }

    private void build(Arena a) {
        int R = radius();
        int Y = topY();
        // остров
        for (int dx = -R; dx <= R; dx++) {
            for (int dz = -R; dz <= R; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > R) continue;
                int thick = (int) (6 * (1 - d / R)) + 2;
                for (int k = 0; k < thick; k++) {
                    Material m = k == 0 ? Material.GRASS_BLOCK : (k <= 2 ? Material.DIRT : Material.STONE);
                    set(a.cx + dx, Y - k, a.cz + dz, m);
                }
            }
        }
        // барьер: стена кольцом + потолок
        int W = R + 3;
        for (int dx = -W - 1; dx <= W + 1; dx++) {
            for (int dz = -W - 1; dz <= W + 1; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > R + 2 && d < R + 3.2) {
                    for (int y = Y - 10; y <= Y + 30; y++) set(a.cx + dx, y, a.cz + dz, Material.BARRIER);
                }
                if (d <= R + 3.2) set(a.cx + dx, Y + 31, a.cz + dz, Material.BARRIER);
            }
        }
    }

    private Location spawn(Arena a, int idx) {
        int Y = topY();
        if (idx == 0) return new Location(world, a.cx - 14 + 0.5, Y + 1, a.cz + 0.5, -90f, 0f);
        return new Location(world, a.cx + 14 + 0.5, Y + 1, a.cz + 0.5, 90f, 0f);
    }

    public boolean inBounds(Arena a, Location l) {
        double dx = l.getX() - a.cx;
        double dz = l.getZ() - a.cz;
        return Math.sqrt(dx * dx + dz * dz) <= radius() + 1.5 && l.getY() <= topY() + 29;
    }

    public Arena arenaAt(Location l) {
        if (!isDuelWorld(l.getWorld())) return null;
        for (Arena a : arenas) {
            double dx = l.getX() - a.cx;
            double dz = l.getZ() - a.cz;
            if (dx * dx + dz * dz <= (radius() + 8.0) * (radius() + 8.0)) return a;
        }
        return null;
    }

    private void prepare(Player p) {
        p.closeInventory();
        p.leaveVehicle();
        p.setGameMode(GameMode.SURVIVAL);
        AttributeInstance mh = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(mh == null ? 20 : mh.getValue());
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setFireTicks(0);
        p.setFallDistance(0f);
        for (PotionEffect ef : new ArrayList<>(p.getActivePotionEffects())) p.removePotionEffect(ef.getType());
    }

    private void startDuel(Player p1, Player p2) {
        int[] c = pickCenter();
        Arena a = new Arena(nextId++, c[0], c[1]);
        a.players[0] = p1.getUniqueId();
        a.players[1] = p2.getUniqueId();
        a.countdown = Math.max(1, plugin.getConfig().getInt("countdown-seconds", 5));
        arenas.add(a);
        build(a);
        Player[] ps = {p1, p2};
        for (int i = 0; i < 2; i++) {
            Player p = ps[i];
            returns.put(p.getUniqueId(), new Ret(p.getLocation().clone(), p.getGameMode()));
            byPlayer.put(p.getUniqueId(), a);
        }
        saveReturns();
        for (int i = 0; i < 2; i++) {
            prepare(ps[i]);
            ps[i].teleport(spawn(a, i));
            msg(ps[i], "&6⚔ &fДуэль против &e" + ps[1 - i].getName() + "&f! Бой начнётся через &c" + a.countdown + " &fсек.");
        }
    }

    // ---------- тик ----------

    private void tick5() {
        ticks++;
        // падение в пустоту = смерть
        for (Arena a : new ArrayList<>(arenas)) {
            if (a.state != State.ACTIVE) continue;
            for (UUID id : a.players) {
                Player p = id == null ? null : Bukkit.getPlayer(id);
                if (p != null && byPlayer.get(id) == a && p.getLocation().getY() < topY() - 25 && !p.isDead()) p.setHealth(0);
            }
        }
        if (ticks % 4 != 0) return;
        // ---- раз в секунду ----
        long now = System.currentTimeMillis();
        Iterator<Invite> it = invites.iterator();
        while (it.hasNext()) {
            Invite i = it.next();
            if (i.expires() < now) {
                it.remove();
                Player s = Bukkit.getPlayer(i.sender());
                Player t = Bukkit.getPlayer(i.target());
                if (s != null) msg(s, "&7Игрок &f" + (t != null ? t.getName() : "?") + " &7не ответил на приглашение.");
            }
        }
        for (Arena a : new ArrayList<>(arenas)) {
            if (a.removing) continue;
            if (a.state == State.COUNTDOWN) {
                for (UUID id : a.players) {
                    Player p = id == null ? null : Bukkit.getPlayer(id);
                    if (p == null) continue;
                    if (a.countdown > 0) {
                        p.showTitle(Title.title(c("&e&l" + a.countdown), c("&7Приготовьтесь!")));
                        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1f);
                    } else {
                        p.showTitle(Title.title(c("&c&lБОЙ!"), c("&7Победитель забирает лут")));
                        p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1.2f);
                    }
                }
                if (a.countdown <= 0) a.state = State.ACTIVE;
                else a.countdown--;
            } else if (a.state == State.ENDED) {
                a.removeIn--;
                if (a.winner != null) {
                    Player w = Bukkit.getPlayer(a.winner);
                    if (w != null && !a.left.contains(a.winner)) {
                        w.sendActionBar(c("&aПобеда! &fСоберите лут и введите &e/duel leave &7— кабинка исчезнет через &c"
                                + (a.removeIn / 60) + ":" + String.format("%02d", Math.max(0, a.removeIn % 60))));
                    }
                }
                if (a.removeIn <= 0) endArena(a);
            }
        }
    }

    // ---------- завершение боя ----------

    private UUID other(Arena a, UUID id) {
        return a.players[0].equals(id) ? a.players[1] : a.players[0];
    }

    /** Подводит итог: победитель определён, кабинка живёт ещё after-fight-seconds. */
    private void finish(Arena a, UUID winner, UUID loser, boolean loserQuit) {
        if (a.state == State.ENDED) return;
        a.state = State.ENDED;
        a.winner = winner;
        a.removeIn = Math.max(10, plugin.getConfig().getInt("after-fight-seconds", 120));
        a.left.add(loser);
        Player w = Bukkit.getPlayer(winner);
        Player l = Bukkit.getPlayer(loser);
        String wn = w != null ? w.getName() : "?";
        String ln = l != null ? l.getName() : "?";
        Bukkit.broadcast(c("&6⚔ &e" + wn + " &fпобедил в дуэли игрока &c" + ln + (loserQuit ? " &7(выход во время боя)" : "")));
        if (w != null) {
            w.showTitle(Title.title(c("&a&lПОБЕДА!"), c("&7Заберите лут и введите /duel leave")));
            w.playSound(w.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            msg(w, "&aВы победили! &fЛут проигравшего лежит на острове. Когда соберёте его, введите &e/duel leave&f.");
        }
    }

    public void leave(Player p) {
        Arena a = byPlayer.get(p.getUniqueId());
        if (a == null) { msg(p, "&cВы не в дуэли."); return; }
        if (a.state != State.ENDED) { msg(p, "&cПокинуть бой нельзя: выход во время боя засчитывается как поражение."); return; }
        a.left.add(p.getUniqueId());
        byPlayer.remove(p.getUniqueId());
        returnPlayer(p);
        msg(p, "&7Вы вернулись.");
        checkRemove(a);
    }

    private void checkRemove(Arena a) {
        if (a.removing || a.state != State.ENDED) return;
        boolean allGone = true;
        for (UUID id : a.players) if (id != null && !a.left.contains(id)) allGone = false;
        if (allGone) endArena(a);
    }

    /** Возвращает оставшихся игроков и стирает кабинку. */
    private void endArena(Arena a) {
        if (a.removing) return;
        a.removing = true;
        for (UUID id : a.players) {
            if (id == null) continue;
            Player p = Bukkit.getPlayer(id);
            if (p != null && byPlayer.get(id) == a && isDuelWorld(p.getWorld())) {
                byPlayer.remove(id);
                returnPlayer(p);
                msg(p, "&7Кабинка исчезла, вы возвращены.");
            }
            if (byPlayer.get(id) == a) byPlayer.remove(id);
        }
        wipe(a);
    }

    // ---------- стирание кабинки ----------

    private void wipe(Arena a) {
        int R = radius() + 6;
        int Y = topY();
        for (Entity en : world.getNearbyEntities(new Location(world, a.cx + 0.5, Y, a.cz + 0.5), R, 60, R)) {
            if (!(en instanceof Player)) en.remove();
        }
        final int[] x = {a.cx - R};
        new BukkitRunnable() {
            @Override
            public void run() {
                for (int s = 0; s < 4 && x[0] <= a.cx + R; s++, x[0]++) {
                    for (int z = a.cz - R; z <= a.cz + R; z++) {
                        for (int y = Y - 14; y <= Y + 34; y++) {
                            Block b = world.getBlockAt(x[0], y, z);
                            if (!b.getType().isAir()) b.setType(Material.AIR, false);
                        }
                    }
                }
                if (x[0] > a.cx + R) {
                    cancel();
                    unloadChunks(a, R);
                    arenas.remove(a);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** Мгновенная очистка (при выключении сервера). */
    private void wipeNow(Arena a) {
        int R = radius() + 6;
        int Y = topY();
        for (Entity en : world.getNearbyEntities(new Location(world, a.cx + 0.5, Y, a.cz + 0.5), R, 60, R)) {
            if (!(en instanceof Player)) en.remove();
        }
        for (int x = a.cx - R; x <= a.cx + R; x++) {
            for (int z = a.cz - R; z <= a.cz + R; z++) {
                for (int y = Y - 14; y <= Y + 34; y++) {
                    Block b = world.getBlockAt(x, y, z);
                    if (!b.getType().isAir()) b.setType(Material.AIR, false);
                }
            }
        }
    }

    private void unloadChunks(Arena a, int R) {
        for (int cx = (a.cx - R) >> 4; cx <= (a.cx + R) >> 4; cx++) {
            for (int cz = (a.cz - R) >> 4; cz <= (a.cz + R) >> 4; cz++) {
                if (world.isChunkLoaded(cx, cz)) world.getChunkAt(cx, cz).unload(false);
            }
        }
    }

    // ---------- события от listener ----------

    public void handleQuit(Player p) {
        Arena a = byPlayer.get(p.getUniqueId());
        if (a == null) return;
        if (a.state == State.ENDED) {
            a.left.add(p.getUniqueId());
            checkRemove(a);
            return;
        }
        // анти-релог: выход во время боя = поражение, вещи падают на остров
        Location loc = p.getLocation();
        for (ItemStack it : p.getInventory().getContents()) {
            if (it != null && it.getType() != Material.AIR) loc.getWorld().dropItemNaturally(loc, it);
        }
        p.getInventory().clear();
        UUID winner = other(a, p.getUniqueId());
        finish(a, winner, p.getUniqueId(), true);
        // проигравший остаётся в returns.yml и вернётся при следующем входе (без вещей)
        byPlayer.remove(p.getUniqueId());
    }

    public void handleJoin(Player p) {
        if (byPlayer.containsKey(p.getUniqueId())) return;
        Ret r = returns.get(p.getUniqueId());
        if (r != null || isDuelWorld(p.getWorld())) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline() || byPlayer.containsKey(p.getUniqueId())) return;
                if (returns.containsKey(p.getUniqueId()) || isDuelWorld(p.getWorld())) {
                    returnPlayer(p);
                    msg(p, "&7Вы вернулись из мира дуэлей.");
                }
            }, 5L);
        }
    }

    public void handleDeath(Player victim) {
        Arena a = byPlayer.get(victim.getUniqueId());
        if (a == null) return;
        UUID vid = victim.getUniqueId();
        Ret r = returns.remove(vid);
        saveReturns();
        if (r != null) respawnReturn.put(vid, r);
        byPlayer.remove(vid);
        if (a.state == State.ENDED) {
            a.left.add(vid);
            checkRemove(a);
        } else {
            finish(a, other(a, vid), vid, false);
        }
    }

    public void handleRespawn(org.bukkit.event.player.PlayerRespawnEvent e) {
        Ret r = respawnReturn.remove(e.getPlayer().getUniqueId());
        if (r != null) {
            e.setRespawnLocation(r.loc);
            Player p = e.getPlayer();
            Bukkit.getScheduler().runTaskLater(plugin, () -> p.setGameMode(r.mode), 2L);
        }
    }
}
