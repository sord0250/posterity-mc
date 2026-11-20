package site.remotearc.employees;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Each employee's books, in hourly buckets kept for 30 days: what it made,
 * what that's worth at merchant prices, what it sold, the materials it took
 * from storage and its wages. Profit is
 *   goods put in storage + sales − materials taken from storage − wages,
 * every item valued at what a merchant pays for it today. Moving goods
 * between employees nets out: the miner is credited with the raw iron, the
 * smelter with what smelting adds to it.
 *
 * While an owner is offline the same figures also go into an "away" tally,
 * reported (and cleared) when they come back. Saved in ledger.yml.
 */
public final class Ledger {

    private static final long HOUR = 3_600_000L;
    private static final long KEEP = 30 * 24 * HOUR;

    /** Activity counters, in Employee.Stats, sampled into the buckets. */
    public static final String[] ACTS = {"trees", "logs", "saplings", "crops", "mined", "ores", "placed", "smelted",
            "bred", "culled", "sheared", "mobs", "trades", "moved", "repaired"};

    public static final class Bucket {
        /** Value of goods it put into storage. */
        public double stored;
        /** Money from merchants. */
        public double sales;
        /** Value of what it took out of storage (materials, or stock to sell). */
        public double taken;
        public double wages;
        /** Items it produced (left its backpack for storage or a merchant). */
        public final Map<Material, Integer> made = new EnumMap<>(Material.class);
        public final Map<Material, Integer> sold = new EnumMap<>(Material.class);
        /** Materials it took from storage to work with. */
        public final Map<Material, Integer> used = new EnumMap<>(Material.class);
        public final long[] acts = new long[ACTS.length];

        public double profit() {
            return stored + sales - taken - wages;
        }

        public boolean isEmpty() {
            if (stored != 0 || sales != 0 || taken != 0 || wages != 0 || !made.isEmpty() || !sold.isEmpty() || !used.isEmpty()) return false;
            for (long a : acts) if (a != 0) return false;
            return true;
        }

        public void add(Bucket o) {
            stored += o.stored;
            sales += o.sales;
            taken += o.taken;
            wages += o.wages;
            o.made.forEach((m, n) -> made.merge(m, n, Integer::sum));
            o.sold.forEach((m, n) -> sold.merge(m, n, Integer::sum));
            o.used.forEach((m, n) -> used.merge(m, n, Integer::sum));
            for (int i = 0; i < acts.length; i++) acts[i] += o.acts[i];
        }

        public long act(String name) {
            for (int i = 0; i < ACTS.length; i++) if (ACTS[i].equals(name)) return acts[i];
            return 0;
        }

        String save() {
            StringBuilder a = new StringBuilder();
            for (int i = 0; i < ACTS.length; i++) {
                if (acts[i] == 0) continue;
                if (!a.isEmpty()) a.append(',');
                a.append(ACTS[i]).append('=').append(acts[i]);
            }
            return round(stored) + ";" + round(sales) + ";" + round(taken) + ";" + round(wages) + ";"
                    + counts(made) + ";" + counts(sold) + ";" + counts(used) + ";" + a;
        }

        static Bucket load(String s) {
            Bucket b = new Bucket();
            String[] p = s.split(";", -1);
            try {
                b.stored = Double.parseDouble(p[0]);
                b.sales = Double.parseDouble(p[1]);
                b.taken = Double.parseDouble(p[2]);
                b.wages = Double.parseDouble(p[3]);
                if (p.length > 4) parseCounts(p[4], b.made);
                if (p.length > 5) parseCounts(p[5], b.sold);
                if (p.length > 6) parseCounts(p[6], b.used);
                if (p.length > 7 && !p[7].isEmpty()) {
                    for (String kv : p[7].split(",")) {
                        String[] q = kv.split("=");
                        for (int i = 0; i < ACTS.length; i++) if (ACTS[i].equals(q[0])) b.acts[i] = Long.parseLong(q[1]);
                    }
                }
            } catch (RuntimeException ignored) {
                // a damaged line: keep what was read
            }
            return b;
        }

        private static String round(double v) {
            return String.valueOf(Math.round(v * 10000) / 10000.0);
        }

        private static String counts(Map<Material, Integer> m) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<Material, Integer> e : m.entrySet()) {
                if (!sb.isEmpty()) sb.append(',');
                sb.append(e.getKey().name()).append('=').append(e.getValue());
            }
            return sb.toString();
        }

        private static void parseCounts(String s, Map<Material, Integer> into) {
            if (s.isEmpty()) return;
            for (String kv : s.split(",")) {
                String[] q = kv.split("=");
                Material m = q.length == 2 ? Material.matchMaterial(q[0]) : null;
                if (m != null) into.merge(m, Integer.parseInt(q[1]), Integer::sum);
            }
        }
    }

    /** What an owner's employees did while the owner was offline. */
    public static final class Away {
        public final long since;
        public final Map<UUID, Bucket> employees = new HashMap<>();

        Away(long since) {
            this.since = since;
        }
    }

    private final ArcEmployees plugin;
    private final File file;
    /** Employee → hour number → bucket. */
    private final Map<UUID, TreeMap<Long, Bucket>> books = new HashMap<>();
    private final Map<UUID, Away> away = new HashMap<>();
    /** The activity counters as last sampled, per employee (runtime). */
    private final Map<UUID, long[]> sampled = new HashMap<>();
    private final List<BukkitTask> tasks = new ArrayList<>();
    private boolean dirty;

    public Ledger(ArcEmployees plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "ledger.yml");
        load();
    }

    public void start() {
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::sample, 20L * 60, 20L * 60));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (dirty) save();
        }, 20L * 300, 20L * 300));
    }

    public void stop() {
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
        sample();
        save();
    }

    // ── recording ─────────────────────────────────────────────────────────

    /** The buckets a record goes into: this hour's, and the away tally while the owner is offline. */
    private List<Bucket> into(Employee e) {
        long hour = System.currentTimeMillis() / HOUR;
        List<Bucket> out = new ArrayList<>(2);
        out.add(books.computeIfAbsent(e.id, k -> new TreeMap<>()).computeIfAbsent(hour, k -> new Bucket()));
        if (Bukkit.getPlayer(e.owner) == null) {
            out.add(away.computeIfAbsent(e.owner, k -> new Away(System.currentTimeMillis()))
                    .employees.computeIfAbsent(e.id, k -> new Bucket()));
        }
        dirty = true;
        return out;
    }

    private double value(Material m) {
        return plugin.market() == null ? 0 : plugin.market().basePrice(m);
    }

    public static Map<Material, Integer> count(Collection<ItemStack> items) {
        Map<Material, Integer> out = new EnumMap<>(Material.class);
        for (ItemStack s : items) if (s != null && !s.getType().isAir()) out.merge(s.getType(), s.getAmount(), Integer::sum);
        return out;
    }

    /**
     * A delivery: {@code fromBackpack} is what left the backpack, {@code toStorage}
     * what was then put into storage (after selling), {@code leftover} what didn't fit.
     */
    public void delivered(Employee e, Map<Material, Integer> fromBackpack, Map<Material, Integer> toStorage, List<ItemStack> leftover) {
        Map<Material, Integer> left = count(leftover);
        double stored = 0;
        for (Map.Entry<Material, Integer> en : toStorage.entrySet()) {
            int n = en.getValue() - left.getOrDefault(en.getKey(), 0);
            if (n > 0) stored += n * value(en.getKey());
        }
        Map<Material, Integer> made = new EnumMap<>(Material.class);
        for (Map.Entry<Material, Integer> en : fromBackpack.entrySet()) {
            int n = en.getValue() - Math.min(en.getValue(), left.getOrDefault(en.getKey(), 0));
            if (n > 0) made.put(en.getKey(), n);
        }
        if (stored == 0 && made.isEmpty()) return;
        for (Bucket b : into(e)) {
            b.stored += stored;
            made.forEach((m, n) -> b.made.merge(m, n, Integer::sum));
        }
    }

    public void sold(Employee e, Map<Material, Integer> what, double money) {
        if (money <= 0 && what.isEmpty()) return;
        for (Bucket b : into(e)) {
            b.sales += money;
            what.forEach((m, n) -> b.sold.merge(m, n, Integer::sum));
        }
    }

    /** Items taken out of storage: materials to work with ({@code used}), or stock to sell. */
    public void taken(Employee e, Collection<ItemStack> items, boolean used) {
        if (items.isEmpty()) return;
        Map<Material, Integer> c = count(items);
        double v = 0;
        for (Map.Entry<Material, Integer> en : c.entrySet()) v += en.getValue() * value(en.getKey());
        for (Bucket b : into(e)) {
            b.taken += v;
            if (used) c.forEach((m, n) -> b.used.merge(m, n, Integer::sum));
        }
    }

    /** Moves the activity counters' growth since the last sample into the books. */
    public void sample() {
        if (plugin.manager() == null) return;
        for (Employee e : plugin.manager().all()) {
            long[] now = e.stats.counters();
            long[] before = sampled.put(e.id, now);
            if (before == null) continue;
            boolean any = false;
            for (int i = 0; i < now.length; i++) any |= now[i] != before[i];
            if (!any) continue;
            for (Bucket b : into(e)) for (int i = 0; i < now.length; i++) b.acts[i] += Math.max(0, now[i] - before[i]);
        }
        long cutoff = (System.currentTimeMillis() - KEEP) / HOUR;
        for (TreeMap<Long, Bucket> m : books.values()) m.headMap(cutoff).clear();
    }

    // ── reading ───────────────────────────────────────────────────────────

    /** An employee's totals over the last {@code millis}. */
    public Bucket last(UUID employee, long millis) {
        Bucket out = new Bucket();
        TreeMap<Long, Bucket> m = books.get(employee);
        if (m == null) return out;
        for (Bucket b : m.tailMap((System.currentTimeMillis() - millis) / HOUR, true).values()) out.add(b);
        return out;
    }

    // ── away from the server ──────────────────────────────────────────────

    public void quit(UUID owner) {
        sample();   // what happened before they left stays out of the away tally
        away.put(owner, new Away(System.currentTimeMillis()));
        dirty = true;
    }

    /** The owner's away tally, taken off the books (null if there is none). */
    public Away takeAway(UUID owner) {
        sample();
        Away a = away.remove(owner);
        if (a != null) dirty = true;
        return a;
    }

    public void forget(UUID employee) {
        books.remove(employee);
        sampled.remove(employee);
        for (Away a : away.values()) a.employees.remove(employee);
        dirty = true;
    }

    // ── storage ───────────────────────────────────────────────────────────

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection emp = y.getConfigurationSection("employees");
        if (emp != null) {
            for (String id : emp.getKeys(false)) {
                ConfigurationSection s = emp.getConfigurationSection(id);
                if (s == null) continue;
                try {
                    TreeMap<Long, Bucket> m = new TreeMap<>();
                    for (String hour : s.getKeys(false)) m.put(Long.parseLong(hour), Bucket.load(s.getString(hour, "")));
                    books.put(UUID.fromString(id), m);
                } catch (IllegalArgumentException ignored) {
                    // skip
                }
            }
        }
        ConfigurationSection aw = y.getConfigurationSection("away");
        if (aw != null) {
            for (String owner : aw.getKeys(false)) {
                ConfigurationSection s = aw.getConfigurationSection(owner);
                if (s == null) continue;
                try {
                    Away a = new Away(s.getLong("since"));
                    ConfigurationSection es = s.getConfigurationSection("employees");
                    if (es != null) for (String id : es.getKeys(false)) a.employees.put(UUID.fromString(id), Bucket.load(es.getString(id, "")));
                    away.put(UUID.fromString(owner), a);
                } catch (IllegalArgumentException ignored) {
                    // skip
                }
            }
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, TreeMap<Long, Bucket>> e : books.entrySet()) {
            for (Map.Entry<Long, Bucket> b : e.getValue().entrySet()) {
                if (!b.getValue().isEmpty()) y.set("employees." + e.getKey() + "." + b.getKey(), b.getValue().save());
            }
        }
        for (Map.Entry<UUID, Away> e : away.entrySet()) {
            y.set("away." + e.getKey() + ".since", e.getValue().since);
            for (Map.Entry<UUID, Bucket> b : e.getValue().employees.entrySet()) {
                y.set("away." + e.getKey() + ".employees." + b.getKey(), b.getValue().save());
            }
        }
        try {
            java.nio.file.Path tmp = file.toPath().resolveSibling("ledger.yml.tmp");
            java.nio.file.Files.writeString(tmp, y.saveToString(), java.nio.charset.StandardCharsets.UTF_8);
            java.nio.file.Files.move(tmp, file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not save ledger.yml", ex);
        }
    }
}
