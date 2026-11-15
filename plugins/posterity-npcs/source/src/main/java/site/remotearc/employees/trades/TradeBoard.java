package site.remotearc.employees.trades;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.Employee;
import site.remotearc.employees.util.Items;
import site.remotearc.employees.util.Msg;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * /trades: every villager near you and what it sells, in a menu (click one to
 * be guided to it), a search (/trades mending: those that sell it light up),
 * and labels over their heads that only you see (/trades labels).
 */
public final class TradeBoard implements TabExecutor, Listener {

    static final int RADIUS = 64;
    private static final Particle.DustOptions MARK = new Particle.DustOptions(Color.fromRGB(60, 255, 120), 1.6f);

    /** One villager's trades, as shown. */
    record Offer(String result, String price, boolean sold, int uses, int max) {}

    record Seller(Villager v, String profession, int level, String stall, List<Offer> offers) {
        boolean sells(String q) {
            for (Offer o : offers) if (o.result().toLowerCase(Locale.ROOT).contains(q)) return true;
            return false;
        }
    }

    private final ArcEmployees plugin;
    /** Player → the labels over villagers they see. */
    private final Map<UUID, Map<UUID, TextDisplay>> labels = new HashMap<>();
    /** Player → villagers marked by a search, and until when. */
    private final Map<UUID, Set<UUID>> marked = new HashMap<>();
    private final Map<UUID, Long> markedUntil = new HashMap<>();
    private BukkitTask task;

    public TradeBoard(ArcEmployees plugin) {
        this.plugin = plugin;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 10L);
    }

    public void stop() {
        if (task != null) task.cancel();
        labels.values().forEach(m -> m.values().forEach(Entity::remove));
        labels.clear();
    }

    // ── reading villagers ───────────────────────────────────────────────

    static List<Seller> near(ArcEmployees plugin, Player p) {
        List<Seller> out = new ArrayList<>();
        Location at = p.getLocation();
        for (Villager v : at.getWorld().getNearbyEntitiesByType(Villager.class, at, RADIUS, 48, RADIUS)) {
            if (v.getRecipes().isEmpty()) continue;
            out.add(seller(plugin, p, v));
        }
        out.sort(Comparator.comparing(Seller::profession).thenComparing(s -> s.stall() == null ? "~" : s.stall())
                .thenComparingDouble(s -> s.v().getLocation().distanceSquared(at)));
        return out;
    }

    static Seller seller(ArcEmployees plugin, Player p, Villager v) {
        List<Offer> offers = new ArrayList<>();
        for (MerchantRecipe r : v.getRecipes()) {
            List<String> price = new ArrayList<>();
            for (ItemStack in : r.getIngredients()) {
                if (in == null || in.getType().isAir()) continue;
                price.add(in.getAmount() + " " + Items.name(in.getType()).toLowerCase(Locale.ROOT));
            }
            offers.add(new Offer(itemName(r.getResult()), String.join(" + ", price), r.getUses() >= r.getMaxUses(), r.getUses(), r.getMaxUses()));
        }
        return new Seller(v, title(v.getProfession().getKey().getKey()), v.getVillagerLevel(), stallOf(plugin, p, v), offers);
    }

    /** "Stall 3 (Juno)" if one of the player's traders has it in a stall. */
    private static String stallOf(ArcEmployees plugin, Player p, Villager v) {
        for (Employee e : plugin.manager().ownedBy(p.getUniqueId())) {
            for (int i = 0; i < e.stalls.size(); i++) {
                Villager in = site.remotearc.employees.job.Stalls.inStallPublic(v.getWorld(), e.stalls.get(i));
                if (in != null && in.getUniqueId().equals(v.getUniqueId())) return "stall " + (i + 1) + " (" + e.name + ")";
            }
        }
        return null;
    }

    /** The same, for other parts of the plugin. */
    public static String describe(ItemStack it) {
        return itemName(it);
    }

    /** "Enchanted book: Mending", "8 Emerald", "Diamond sword (Sharpness III)". */
    static String itemName(ItemStack it) {
        ItemMeta meta = it.getItemMeta();
        List<String> ench = new ArrayList<>();
        Map<Enchantment, Integer> map = meta instanceof EnchantmentStorageMeta es ? es.getStoredEnchants() : it.getEnchantments();
        map.forEach((en, lvl) -> ench.add(title(en.getKey().getKey()) + (en.getMaxLevel() > 1 ? " " + roman(lvl) : "")));
        if (it.getType() == Material.ENCHANTED_BOOK && !ench.isEmpty()) return String.join(", ", ench) + " book";
        String base = (it.getAmount() > 1 ? it.getAmount() + " " : "") + Items.name(it.getType());
        return ench.isEmpty() ? base : base + " (" + String.join(", ", ench) + ")";
    }

    public static String title(String key) {
        StringBuilder b = new StringBuilder();
        for (String w : key.split("_")) {
            if (w.isEmpty()) continue;
            if (!b.isEmpty()) b.append(' ');
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.toString();
    }

    private static String roman(int n) {
        return switch (n) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> String.valueOf(n);
        };
    }

    // ── the command ─────────────────────────────────────────────────────

    @Override
    public boolean onCommand(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, String @NotNull [] args) {
        if (!(s instanceof Player p)) {
            Msg.error(s, "Only players can look at trades.");
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("labels")) {
            boolean on = args.length > 1 ? args[1].equalsIgnoreCase("on") : !labels.containsKey(p.getUniqueId());
            if (on) labels.putIfAbsent(p.getUniqueId(), new HashMap<>());
            else clearLabels(p.getUniqueId());
            Msg.send(p, on ? "<green>Trade labels on:</green> <gray>villagers near you show what they sell (only you see it). <white>/trades labels off</white>"
                    : "<gray>Trade labels off.");
            return true;
        }
        List<Seller> all = near(plugin, p);
        report(p, all);
        if (all.isEmpty()) {
            Msg.send(p, "<gray>No villagers with trades within " + RADIUS + " blocks.");
            return true;
        }
        String q = String.join(" ", args).trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            new TradesMenu(plugin, all, null).open(p);
            return true;
        }
        List<Seller> hits = all.stream().filter(x -> x.sells(q)).toList();
        if (hits.isEmpty()) {
            Msg.send(p, "<gray>None of the " + all.size() + " villagers near you sells anything called \"" + q + "\".");
            return true;
        }
        Set<UUID> ids = new HashSet<>();
        for (Seller h : hits) ids.add(h.v().getUniqueId());
        marked.put(p.getUniqueId(), ids);
        markedUntil.put(p.getUniqueId(), System.currentTimeMillis() + 60_000L);
        Msg.send(p, "<green>" + hits.size() + " villager" + (hits.size() == 1 ? " sells" : "s sell") + " \"" + q + "\"</green> <gray>(marked with green sparks for a minute):");
        for (Seller h : hits) {
            Location l = h.v().getLocation();
            List<String> what = new ArrayList<>();
            for (Offer o : h.offers()) if (o.result().toLowerCase(Locale.ROOT).contains(q)) what.add(o.result() + " for " + o.price() + (o.sold() ? " (sold out)" : ""));
            Msg.send(p, " <aqua><click:run_command:'/guide " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ() + "'>[Go]</click></aqua> <white>"
                    + h.profession() + (h.stall() != null ? " in " + h.stall() : "") + "</white><gray>: " + String.join("; ", what));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, String @NotNull [] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) out.add("labels");
        if (args.length == 2 && args[0].equalsIgnoreCase("labels")) out.addAll(List.of("on", "off"));
        if (s instanceof Player p && args.length == 1) {
            Set<String> words = new java.util.TreeSet<>();
            for (Seller x : near(plugin, p)) for (Offer o : x.offers()) for (String w : o.result().toLowerCase(Locale.ROOT).split("[ ,()]+")) if (w.length() > 2 && !w.matches("\\d+|book|i+|iv|v")) words.add(w);
            out.addAll(words);
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent ev) {
        clearLabels(ev.getPlayer().getUniqueId());
        marked.remove(ev.getPlayer().getUniqueId());
    }

    private void clearLabels(UUID id) {
        Map<UUID, TextDisplay> m = labels.remove(id);
        if (m != null) m.values().forEach(Entity::remove);
    }

    // ── labels and marks ────────────────────────────────────────────────

    private int ticks;

    private void tick() {
        ticks++;
        long now = System.currentTimeMillis();
        for (UUID id : new ArrayList<>(marked.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || now > markedUntil.getOrDefault(id, 0L)) {
                marked.remove(id);
                continue;
            }
            for (UUID vid : marked.get(id)) {
                Entity v = Bukkit.getEntity(vid);
                if (v == null || !v.getWorld().equals(p.getWorld())) continue;
                Location l = v.getLocation().add(0, 2.4, 0);
                for (int i = 0; i < 4; i++) p.spawnParticle(Particle.DUST, l.getX(), l.getY() + i * 0.5, l.getZ(), 2, 0.05, 0.1, 0.05, 0, MARK);
            }
        }
        for (Map.Entry<UUID, Map<UUID, TextDisplay>> en : labels.entrySet()) {
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null) continue;
            Map<UUID, TextDisplay> mine = en.getValue();
            Set<UUID> seen = new HashSet<>();
            boolean refresh = ticks % 10 == 0;   // text every 5 seconds, position every half second
            Location at = p.getLocation();
            for (Villager v : at.getWorld().getNearbyEntitiesByType(Villager.class, at, 24, 16, 24)) {
                if (v.getRecipes().isEmpty()) continue;
                seen.add(v.getUniqueId());
                Location top = v.getLocation().add(0, v.getHeight() + 0.6, 0);
                TextDisplay d = mine.get(v.getUniqueId());
                if (d == null || !d.isValid()) {
                    d = top.getWorld().spawn(top, TextDisplay.class, t -> {
                        t.setPersistent(false);
                        t.setVisibleByDefault(false);
                        t.setBillboard(Display.Billboard.CENTER);
                        t.setShadowed(true);
                        t.setBackgroundColor(Color.fromARGB(110, 0, 0, 0));
                        t.setLineWidth(220);
                        t.setViewRange(0.5f);
                    });
                    p.showEntity(plugin, d);
                    mine.put(v.getUniqueId(), d);
                    refresh = true;
                } else if (d.getLocation().distanceSquared(top) > 0.04) d.teleport(top);
                if (refresh) d.text(Msg.parse(labelText(seller(plugin, p, v))));
            }
            mine.entrySet().removeIf(x -> {
                if (seen.contains(x.getKey())) return false;
                x.getValue().remove();
                return true;
            });
        }
    }

    /** The profession, then its best trades: books first. */
    private static String labelText(Seller s) {
        StringBuilder b = new StringBuilder("<gold>" + s.profession() + "</gold> <gray>" + level(s.level()));
        List<Offer> sorted = new ArrayList<>(s.offers());
        sorted.sort(Comparator.comparing((Offer o) -> !o.result().endsWith("book")).thenComparing(o -> o.result().startsWith("Emerald") || o.result().matches("\\d+ Emerald")));
        int n = 0;
        for (Offer o : sorted) {
            if (n++ >= 4) break;
            b.append("\n").append(o.sold() ? "<dark_gray><st>" : "<white>").append(site.remotearc.employees.gui.Describe.esc(o.result())).append(o.sold() ? "</st>" : "</white>")
                    .append(" <gray>").append(site.remotearc.employees.gui.Describe.esc(o.price()));
        }
        if (s.offers().size() > 4) b.append("\n<dark_gray>+").append(s.offers().size() - 4).append(" more: /trades");
        return b.toString();
    }

    public static String levelName(int l) {
        return level(l);
    }

    static String level(int l) {
        return switch (l) {
            case 1 -> "Novice";
            case 2 -> "Apprentice";
            case 3 -> "Journeyman";
            case 4 -> "Expert";
            case 5 -> "Master";
            default -> "";
        };
    }

    // ── a copy on disk ──────────────────────────────────────────────────

    /** Writes what was found to reports/trades-&lt;player&gt;.txt (for planning the hall from outside the game). */
    private void report(Player p, List<Seller> all) {
        StringBuilder b = new StringBuilder();
        b.append("# Villagers within ").append(RADIUS).append(" of ").append(p.getName()).append(" at ").append(p.getLocation().getBlockX()).append(' ')
                .append(p.getLocation().getBlockY()).append(' ').append(p.getLocation().getBlockZ()).append(", ").append(new java.util.Date()).append('\n');
        for (Seller s : all) {
            Location l = s.v().getLocation();
            b.append(s.profession()).append(" (").append(level(s.level())).append(") at ").append(l.getBlockX()).append(' ').append(l.getBlockY()).append(' ')
                    .append(l.getBlockZ()).append(s.stall() != null ? ", " + s.stall() : "").append('\n');
            for (Offer o : s.offers()) b.append("  ").append(o.result()).append(" <- ").append(o.price()).append(o.sold() ? " [sold out]" : "").append('\n');
        }
        File f = new File(plugin.getDataFolder(), "reports/trades-" + p.getName() + ".txt");
        String text = b.toString();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            f.getParentFile().mkdirs();
            try (Writer w = new FileWriter(f)) {
                w.write(text);
            } catch (IOException ignored) {
                // only a copy
            }
        });
    }
}
