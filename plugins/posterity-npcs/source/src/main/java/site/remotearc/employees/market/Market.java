package site.remotearc.employees.market;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.Employee;
import site.remotearc.employees.Skin;
import site.remotearc.employees.util.Items;
import site.remotearc.employees.util.Msg;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The market: merchants (NPCs players visit to sell) and the prices they pay.
 *
 * Prices are a fraction (market.sell-ratio) of ArcShop's buy prices from its
 * catalog.yml, so buying and reselling never pays, plus config extras for
 * things ArcShop doesn't sell. Two brakes keep income modest:
 *  - demand: each item's price halves for every `demand` sold by the same
 *    player, recovering over about demand-recovery-hours;
 *  - a cap on what one player (with their employees) earns per 24 hours.
 * Sales history is kept in market.yml along with the merchants.
 */
public final class Market {

    public record Merchant(UUID id, String name, String world, double x, double y, double z, float yaw, Skin skin) {
        Merchant with(String name, Location at, Skin skin) {
            return new Merchant(id, name, at == null ? world : at.getWorld().getName(), at == null ? x : at.getX(),
                    at == null ? y : at.getY(), at == null ? z : at.getZ(), at == null ? yaw : at.getYaw(), skin);
        }
    }

    /** What a sale did: money paid, how many of each item went, and what's left over. */
    public record Sale(double money, int count, Map<Material, Integer> sold, List<ItemStack> leftover) {}

    private static final long DAY = 86_400_000L;

    private final ArcEmployees plugin;
    private final File file;
    private final NamespacedKey key;
    private final Map<UUID, Merchant> merchants = new LinkedHashMap<>();
    private final Map<UUID, Mannequin> shown = new HashMap<>();
    /** Per player: material → {decayed amount sold, when it was last updated}. */
    private final Map<UUID, Map<Material, double[]>> sold = new HashMap<>();
    /** Per player and item: {time, count, money} of sales in the last day, for the business board. */
    private final Map<UUID, Map<Material, List<double[]>>> revenue = new HashMap<>();
    /** Per player: {time, amount} of each sale in the last day. */
    private final Map<UUID, List<double[]>> income = new HashMap<>();
    private Map<Material, Double> base = new EnumMap<>(Material.class);
    /** ArcShop's categories, in its order: what merchants sell. */
    public record Category(String key, String name, Material icon, boolean gated, List<Material> items) {}
    private List<Category> categories = List.of();
    private final ArcShopDiscovery discovery;
    private long catalogStamp = -1;
    private boolean dirty;
    private final List<BukkitTask> tasks = new ArrayList<>();
    private final RealPrices real;

    public Market(ArcEmployees plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "market.yml");
        this.key = new NamespacedKey(plugin, "merchant");
        this.real = new RealPrices(plugin);
        this.discovery = new ArcShopDiscovery(plugin);
    }

    public RealPrices real() {
        return real;
    }

    public void start() {
        load();
        real.restart();
        for (World w : Bukkit.getWorlds()) for (Mannequin m : w.getEntitiesByClass(Mannequin.class)) removeIfStray(m);
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::tickMerchants, 20L, 10L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (dirty) save();
        }, 1200L, 1200L));
    }

    public void stop() {
        tasks.forEach(BukkitTask::cancel);
        real.stop();
        for (Mannequin m : shown.values()) if (m.isValid()) m.remove();
        shown.clear();
        save();
    }

    // ── prices ────────────────────────────────────────────────────────────

    /** What a merchant pays for one item with no demand used up, or 0 if it doesn't buy it. */
    public double basePrice(Material m) {
        refreshCatalog();
        Double buy = base.get(m);
        double price = buy != null ? buy * plugin.settings().marketRatio : plugin.settings().marketExtras.getOrDefault(m, 0.0);
        return price * real.multiplier(m);   // real-world markets, if switched on
    }

    /** The price of the next one this player sells, after the demand they've used up. */
    public double priceNow(UUID player, Material m) {
        return basePrice(m) * Math.pow(0.5, soldRecently(player, m) / plugin.settings().marketDemand);
    }

    /** Everything merchants buy, with base prices. */
    public Map<Material, Double> catalog() {
        refreshCatalog();
        Map<Material, Double> out = new EnumMap<>(Material.class);
        for (Material m : base.keySet()) out.put(m, basePrice(m));
        for (Material m : plugin.settings().marketExtras.keySet()) out.put(m, basePrice(m));
        out.values().removeIf(v -> v <= 0);
        return out;
    }

    /** Reads ArcShop's catalog.yml (again if it changed): tier names or numbers per item. */
    private void refreshCatalog() {
        File f = new File(plugin.getDataFolder().getParentFile(), "ArcShop/catalog.yml");
        long stamp = f.exists() ? f.lastModified() : 0;
        if (stamp == catalogStamp) return;
        catalogStamp = stamp;
        Map<Material, Double> out = new EnumMap<>(Material.class);
        List<Category> cats0 = new ArrayList<>();
        if (f.exists()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            ConfigurationSection tiers = y.getConfigurationSection("tiers");
            ConfigurationSection cats = y.getConfigurationSection("categories");
            if (cats != null) {
                for (String c : cats.getKeys(false)) {
                    ConfigurationSection items = cats.getConfigurationSection(c + ".items");
                    if (items == null) continue;
                    List<Material> list = new ArrayList<>();
                    for (String name : items.getKeys(false)) {
                        Material m = Material.matchMaterial(name);
                        if (m == null) continue;
                        Object v = items.get(name);
                        double price = v instanceof Number n ? n.doubleValue()
                                : tiers != null && v != null ? tiers.getDouble(v.toString(), 0) : 0;
                        if (price > 0) {
                            out.put(m, price);
                            list.add(m);
                        }
                    }
                    Material icon = Material.matchMaterial(cats.getString(c + ".icon", "CHEST"));
                    cats0.add(new Category(c, cats.getString(c + ".name", c), icon == null ? Material.CHEST : icon,
                            cats.getBoolean(c + ".gated", true), list));
                }
            }
        }
        base = out;
        categories = cats0;
    }

    // ── buying (merchants sell ArcShop's catalog) ─────────────────────────

    public List<Category> categories() {
        refreshCatalog();
        return categories;
    }

    /** What a merchant charges for one: ArcShop's price, moved by real-world markets like sell prices are. */
    public double buyPrice(Material m) {
        double listed = listedPrice(m);
        return listed > 0 ? listed : fallbackPrice(m) * real.multiplier(m);
    }

    /** ArcShop's price, or a build price from the config (leaves and the like); 0 if neither lists it. */
    public double listedPrice(Material m) {
        refreshCatalog();
        Double p = base.get(m);
        if (p == null) p = plugin.settings().buildPrices.get(m);
        return p == null ? 0 : p * real.multiplier(m);
    }

    /**
     * For builds only: anything nothing lists (leather, flint, a bookshelf...)
     * at market.build-fallback-price, except progression and treasure items
     * nobody should be able to buy (ores, netherite, nether stars, elytra...).
     */
    public double fallbackPrice(Material m) {
        double price = plugin.settings().buildFallbackPrice;
        if (price <= 0 || !m.isItem() || m.isAir() || m.isLegacy()) return 0;
        refreshCatalog();
        if (base.containsKey(m)) return 0;   // /shop sells it: its price and discovery apply
        String n = m.name();
        for (String bad : NEVER_SOLD) if (n.contains(bad)) return 0;
        if (m.isBlock() && site.remotearc.employees.build.BuildOrder.unbuildable(m.createBlockData())) return 0;
        return price;
    }

    private static final List<String> NEVER_SOLD = List.of("SPAWN_EGG", "SMITHING_TEMPLATE", "MUSIC_DISC", "_ORE", "NETHERITE", "ANCIENT_DEBRIS",
            "SHULKER", "DRAGON", "TOTEM", "ELYTRA", "NETHER_STAR", "BEACON", "CONDUIT", "HEART_OF_THE_SEA", "NAUTILUS", "TRIDENT", "ECHO_SHARD",
            "HEAVY_CORE", "MACE", "ENCHANTED", "POTION", "TRIAL_KEY", "VAULT", "SPAWNER", "COMMAND", "STRUCTURE", "JIGSAW", "BARRIER",
            "BEDROCK", "DEBUG", "KNOWLEDGE_BOOK", "BUNDLE", "DIAMOND", "EMERALD", "GOLD", "IRON", "LAPIS", "REDSTONE", "QUARTZ",
            "AMETHYST", "COAL", "ENDER_", "END_CRYSTAL", "BLAZE", "GHAST", "WITHER", "BREEZE_ROD", "SNIFFER", "ARMADILLO", "PHANTOM",
            "EXPERIENCE", "OMINOUS", "_HELMET", "_CHESTPLATE", "_LEGGINGS", "_BOOTS", "_SWORD", "_PICKAXE", "_AXE", "_SHOVEL", "_HOE",
            "SADDLE", "_HORSE_ARMOR", "WOLF_ARMOR", "FILLED_MAP", "WRITTEN_BOOK", "PLAYER_HEAD", "OBSIDIAN");

    /** Whether the player may buy it: ArcShop only sells what they've discovered (except ungated categories). */
    public boolean mayBuy(UUID player, Category c, Material m) {
        return !c.gated() || discovery.discovered(player, m);
    }

    /** Whether merchants sell it to this player at all (it's in the catalog, and discovered if its category needs that). */
    public boolean mayBuy(UUID player, Material m) {
        for (Category c : categories()) if (c.items().contains(m)) return mayBuy(player, c, m);
        return plugin.settings().buildPrices.containsKey(m) || fallbackPrice(m) > 0;
    }

    /** Unlocks or locks a material in /shop for a player (ArcShop's discovery); returns an error or null. */
    public String setDiscovered(UUID player, Material m, boolean on) {
        return discovery.set(player, m, on);
    }

    public boolean discovered(UUID player, Material m) {
        return discovery.discovered(player, m);
    }

    /** Buys items for a player; returns an error, or null when done. */
    public String buy(org.bukkit.entity.Player p, Material m, int amount) {
        double price = buyPrice(m) * amount;
        if (price <= 0) return "Merchants don't sell that.";
        Economy econ = plugin.economy();
        if (econ == null) return "There's no economy on this server.";
        if (!econ.has(p, price)) return "That costs " + Msg.money(price) + "; you have " + Msg.money(econ.getBalance(p)) + ".";
        if (!econ.withdrawPlayer(p, price).transactionSuccess()) return "The payment didn't go through.";
        int left = amount;
        while (left > 0) {
            int n = Math.min(left, m.getMaxStackSize());
            for (ItemStack s : p.getInventory().addItem(new ItemStack(m, n)).values()) p.getWorld().dropItem(p.getLocation(), s);
            left -= n;
        }
        return null;
    }

    // ── selling ───────────────────────────────────────────────────────────

    /** How much more this player may earn at full price right now (the rolling 24-hour limit). */
    public double roomToday(UUID player) {
        double cap = limit(player);
        if (cap <= 0) return Double.MAX_VALUE;
        return Math.max(0, cap - earnedToday(player));
    }

    /**
     * The day's full-price limit for a player (0 = none): the configured amount
     * plus, with market.wages-raise-limit, a day's wages for their employees,
     * so paying the staff never eats into what they may make for themselves.
     */
    public double limit(UUID player) {
        double cap = plugin.settings().marketDailyCap;
        if (cap <= 0) return 0;
        return plugin.settings().marketWagesRaiseCap ? cap + plugin.manager().dailyWages(player) : cap;
    }

    /** One line about the limit for menus: "$84 of $340 at full price; after that merchants pay 25%". */
    public String limitLine(UUID player) {
        double cap = limit(player);
        if (cap <= 0) return "<gray>No daily limit";
        double rate = plugin.settings().marketOverRate;
        return "<gray>Full price for <white>" + Msg.money(roomToday(player)) + "</white> more today"
                + (rate > 0 ? ", then " + Math.round(rate * 100) + "%" : ", then nothing");
    }

    /** What each item earned this player in the last 24 hours: item → {count, money}. */
    public Map<Material, double[]> revenueToday(UUID player) {
        Map<Material, double[]> out = new EnumMap<>(Material.class);
        Map<Material, List<double[]>> rev = revenue.get(player);
        if (rev == null) return out;
        long cutoff = System.currentTimeMillis() - DAY;
        for (Map.Entry<Material, List<double[]>> e : rev.entrySet()) {
            e.getValue().removeIf(r -> r[0] < cutoff);
            double count = 0, money = 0;
            for (double[] r : e.getValue()) {
                count += r[1];
                money += r[2];
            }
            if (count > 0) out.put(e.getKey(), new double[]{count, money});
        }
        return out;
    }

    public double earnedToday(UUID player) {
        List<double[]> list = income.get(player);
        if (list == null) return 0;
        long now = System.currentTimeMillis();
        list.removeIf(e -> now - e[0] > DAY);
        double sum = 0;
        for (double[] e : list) sum += e[1];
        return sum;
    }

    private double soldRecently(UUID player, Material m) {
        Map<Material, double[]> map = sold.get(player);
        double[] e = map == null ? null : map.get(m);
        if (e == null) return 0;
        double halfLife = plugin.settings().marketRecoveryHours * 3_600_000.0 / 4;   // mostly back after the full time
        return e[0] * Math.pow(0.5, (System.currentTimeMillis() - e[1]) / halfLife);
    }

    /**
     * Prices (and with {@code commit}, sells) plain items for a player: the
     * value of each kind follows the demand curve, and the total stops at the
     * day's cap. Items merchants don't buy, and those over the cap, are left over.
     */
    public Sale sell(UUID player, List<ItemStack> items, boolean commit) {
        return sell(player, items, commit, true);
    }

    /**
     * As above; {@code pastLimit} lets the sale go on past the day's limit at
     * market.over-limit-rate (players at a merchant). Employees stop at the
     * limit instead, and their goods stay in storage.
     */
    public Sale sell(UUID player, List<ItemStack> items, boolean commit, boolean pastLimit) {
        Map<Material, Integer> offered = new LinkedHashMap<>();
        List<ItemStack> leftover = new ArrayList<>();
        for (ItemStack s : items) {
            if (s == null || s.getType().isAir()) continue;
            if (!Items.isSellable(s) || basePrice(s.getType()) <= 0) leftover.add(s);
            else offered.merge(s.getType(), s.getAmount(), Integer::sum);
        }
        double room = roomToday(player), money = 0, full = 0, over = plugin.settings().marketOverRate;
        int count = 0;
        double d = plugin.settings().marketDemand;
        Map<Material, Integer> soldNow = new EnumMap<>(Material.class);
        Map<Material, Double> valueNow = new EnumMap<>(Material.class);
        for (Map.Entry<Material, Integer> e : offered.entrySet()) {
            Material m = e.getKey();
            double p0 = basePrice(m), s0 = soldRecently(player, m);
            int n = e.getValue();
            double value = value(p0, d, s0, n);
            if (full + value > room) {
                // As many as still fit under the limit at full price...
                double left = room - full;
                double inside = Math.pow(2, -s0 / d) - left * Math.log(2) / (p0 * d);
                int fit = inside <= 0 ? n : Math.max(0, Math.min(n, (int) Math.floor(-d * Math.log(inside) / Math.log(2) - s0)));
                value = value(p0, d, s0, fit);
                full += value;
                // ...and the rest at the over-limit rate, if this sale may go past it.
                if (pastLimit && over > 0 && fit < n) value += over * value(p0, d, s0 + fit, n - fit);
                else n = fit;
            } else {
                full += value;
            }
            if (n > 0) {
                soldNow.put(m, n);
                valueNow.put(m, value);
                money += value;
                count += n;
            }
            int rest = e.getValue() - n;
            while (rest > 0) {
                int stack = Math.min(rest, m.getMaxStackSize());
                leftover.add(new ItemStack(m, stack));
                rest -= stack;
            }
        }
        money = Math.floor(money * 100) / 100;
        if (commit && money > 0) {
            Economy econ = plugin.economy();
            OfflinePlayer op = Bukkit.getOfflinePlayer(player);
            if (econ == null || !econ.depositPlayer(op, money).transactionSuccess()) {
                return new Sale(0, 0, Map.of(), items);
            }
            long now = System.currentTimeMillis();
            Map<Material, double[]> map = sold.computeIfAbsent(player, k -> new EnumMap<>(Material.class));
            for (Map.Entry<Material, Integer> e : soldNow.entrySet()) {
                map.put(e.getKey(), new double[]{soldRecently(player, e.getKey()) + e.getValue(), now});
            }
            income.computeIfAbsent(player, k -> new ArrayList<>()).add(new double[]{now, money});
            Map<Material, List<double[]>> rev = revenue.computeIfAbsent(player, k -> new EnumMap<>(Material.class));
            for (Map.Entry<Material, Integer> e : soldNow.entrySet()) {
                rev.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(new double[]{now, e.getValue(), valueNow.get(e.getKey())});
            }
            dirty = true;
        }
        return new Sale(money, count, soldNow, leftover);
    }

    /** ∫ p0·2^(-s/d) ds from s0 to s0+n: the price falls smoothly as each one sells. */
    private static double value(double p0, double d, double s0, int n) {
        if (n <= 0) return 0;
        return p0 * d / Math.log(2) * (Math.pow(2, -s0 / d) - Math.pow(2, -(s0 + n) / d));
    }

    /**
     * Sells part of an employee's delivery if it's set to sell: for each item
     * it sells (everything, or its list), it first keeps its storage stocked
     * up to the item's keep amount and sells only what comes in beyond that.
     * Returns what's left to store.
     */
    public List<ItemStack> sellFor(Employee e, List<ItemStack> items) {
        if (!plugin.settings().marketEnabled || !e.selling() || merchants.isEmpty()) return items;
        Map<Material, Integer> incoming = new EnumMap<>(Material.class);
        for (ItemStack s : items) {
            if (s == null || !Items.isPlain(s) || e.ruleFor(s.getType()) == null) continue;
            incoming.merge(s.getType(), s.getAmount(), Integer::sum);
        }
        Map<Material, Integer> storedNow = storedAll(e);
        // Keep rules sell down storage that's above the stock (e.g. from before the rule was set).
        List<ItemStack> fromStorage = new ArrayList<>();
        Map<Material, Integer> extra = new EnumMap<>(Material.class);
        for (Map.Entry<Material, Integer> st : storedNow.entrySet()) {
            Employee.SellRule rule = e.ruleFor(st.getKey());
            if (rule == null || rule.keep() <= 0 || rule.percent() < 100 || basePrice(st.getKey()) <= 0) continue;
            int over = st.getValue() - rule.keep();
            if (rule.daily() > 0) over = Math.min(over, rule.daily() - e.soldToday(st.getKey()));
            if (over > 0) extra.put(st.getKey(), Math.min(over, 640));
        }
        if (!extra.isEmpty()) {
            fromStorage.addAll(takeFromStorage(e, extra));
            plugin.ledger().taken(e, fromStorage, false);   // whatever doesn't sell goes back, and counts as stored again
        }
        if (incoming.isEmpty() && fromStorage.isEmpty()) return items;
        // How many of each to sell: of what would take storage past the keep amount, the rule's percentage.
        Map<Material, Integer> toSell = new EnumMap<>(Material.class);
        for (Map.Entry<Material, Integer> en : incoming.entrySet()) {
            Material m = en.getKey();
            Employee.SellRule rule = e.ruleFor(m);
            int in = en.getValue();
            int beyond = rule.keep() > 0 ? Math.min(in, Math.max(0, storedNow.getOrDefault(m, 0) + in - rule.keep())) : in;
            double exact = beyond * Math.max(0, Math.min(100, rule.percent())) / 100.0 + e.sellCarry.getOrDefault(m, 0.0);
            int n = Math.min(beyond, (int) Math.floor(exact));
            e.sellCarry.put(m, exact - n);   // the fraction is owed to the next delivery
            if (rule.daily() > 0) n = Math.min(n, Math.max(0, rule.daily() - e.soldToday(m)));
            if (n > 0) toSell.put(m, n);
        }
        List<ItemStack> offer = new ArrayList<>(), keepList = new ArrayList<>();
        for (ItemStack s : items) {
            if (s == null) continue;
            int want = toSell.getOrDefault(s.getType(), 0);
            if (want <= 0 || !Items.isPlain(s)) {
                keepList.add(s);
                continue;
            }
            int take = Math.min(want, s.getAmount());
            toSell.put(s.getType(), want - take);
            ItemStack sold = s.clone();
            sold.setAmount(take);
            offer.add(sold);
            if (take < s.getAmount()) {
                ItemStack rest = s.clone();
                rest.setAmount(s.getAmount() - take);
                keepList.add(rest);
            }
        }
        offer.addAll(fromStorage);
        if (offer.isEmpty()) return items;
        Sale sale = sell(e.owner, offer, true, false);
        keepList.addAll(sale.leftover());
        record(e, sale);
        return keepList;
    }

    /**
     * Sells what a courier carried to a merchant in person (stopping at the
     * day's limit, as employees do). Returns what didn't sell.
     */
    public List<ItemStack> sellCarried(Employee e, List<ItemStack> items) {
        Sale sale = sell(e.owner, items, true, false);
        record(e, sale);
        return sale.leftover();
    }

    /** Puts a sale on the employee's books, menus and boards. */
    private void record(Employee e, Sale sale) {
        plugin.ledger().sold(e, sale.sold(), sale.money());
        if (sale.money() > 0) {
            List<String> parts = new ArrayList<>();
            sale.sold().entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(3)
                    .forEach(en -> parts.add(en.getValue() + " " + name(en.getKey())));
            if (sale.sold().size() > 3) parts.add("...");
            String what = String.join(", ", parts);
            e.stats.earned += sale.money();
            e.stats.itemsSold += sale.count();
            long now = System.currentTimeMillis();
            e.sales.addFirst(new Employee.SaleRecord(now, sale.count(), sale.money(), what));
            for (Map.Entry<Material, Integer> en : sale.sold().entrySet()) {
                e.soldLog.computeIfAbsent(en.getKey(), k -> new java.util.ArrayDeque<>()).addFirst(new long[]{now, en.getValue()});
            }
            while (e.sales.size() > 200) e.sales.removeLast();
            plugin.manager().events().add(e.owner, e.name, "Sold " + what + " for " + Msg.money(sale.money()), false);
            plugin.manager().markDirty();
        }
    }

    /** The merchant closest to a spot: in the same world if there is one, else any. */
    public Merchant closest(Location at) {
        Merchant best = null;
        double bestD = Double.MAX_VALUE;
        for (Merchant m : merchants.values()) {
            double d = m.world().equals(at.getWorld().getName())
                    ? at.distanceSquared(new Location(at.getWorld(), m.x(), m.y(), m.z())) : 1e18;
            if (best == null || d < bestD) {
                best = m;
                bestD = d;
            }
        }
        return best;
    }

    /** What the employee has stored now: its chest (and overflow), or its backpack if it has no chest. */
    public Map<Material, Integer> storedAll(Employee e) {
        Map<Material, Integer> count = new EnumMap<>(Material.class);
        switch (e.storage.kind()) {
            case VIRTUAL -> {
                Items.countPlain(plugin.vchests().contents(e.owner, e.storage.chestNumber()), count);
                for (int n : e.overflow) Items.countPlain(plugin.vchests().contents(e.owner, n), count);
            }
            case CHEST -> {
                var c = e.storage.container();
                if (c != null) Items.countPlain(c.getInventory().getStorageContents(), count);
            }
            case NONE -> Items.countPlain(e.backpack.getStorageContents(), count);
        }
        return count;
    }

    /** Takes items out of the employee's storage chests to be sold (whatever isn't sold goes back with the delivery). */
    private List<ItemStack> takeFromStorage(Employee e, Map<Material, Integer> want) {
        List<ItemStack> got = new ArrayList<>();
        switch (e.storage.kind()) {
            case VIRTUAL -> {
                got.addAll(plugin.vchests().withdraw(e.owner, e.storage.chestNumber(), want));
                for (int n : e.overflow) if (!want.isEmpty()) got.addAll(plugin.vchests().withdraw(e.owner, n, want));
            }
            case CHEST -> {
                var c = e.storage.container();
                if (c != null) got.addAll(site.remotearc.employees.storage.Supply.takeFrom(c, want));
            }
            default -> { }
        }
        return got;
    }

    public boolean hasMerchants() {
        return !merchants.isEmpty();
    }

    /** Whether a player stands within reach of a merchant (or may sell anywhere). */
    public boolean nearMerchant(Player p) {
        if (plugin.settings().marketSellAnywhere) return true;
        for (Merchant m : merchants.values()) {
            if (m.world().equals(p.getWorld().getName())
                    && p.getLocation().distanceSquared(new Location(p.getWorld(), m.x(), m.y(), m.z())) < 8 * 8) return true;
        }
        return false;
    }

    // ── merchants ─────────────────────────────────────────────────────────

    public Merchant place(Location at, String name) {
        UUID id = UUID.randomUUID();
        Skin skin = Skin.builtIn(List.of("noor", "makena", "sunny", "zuri").get(Math.floorMod(id.hashCode(), 4)), false);
        Merchant m = new Merchant(id, name, at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), at.getYaw(), skin);
        merchants.put(m.id(), m);
        save();
        tickMerchants();
        return m;
    }

    public void remove(UUID id) {
        merchants.remove(id);
        Mannequin e = shown.remove(id);
        if (e != null && e.isValid()) e.remove();
        save();
    }

    /** Removes the merchant nearest to a spot (within 8 blocks); returns its name or null. */
    public String removeNear(Location at) {
        Merchant best = null;
        double bestD = 64;
        for (Merchant m : merchants.values()) {
            if (!m.world().equals(at.getWorld().getName())) continue;
            double d = at.distanceSquared(new Location(at.getWorld(), m.x(), m.y(), m.z()));
            if (d < bestD) {
                bestD = d;
                best = m;
            }
        }
        if (best == null) return null;
        merchants.remove(best.id());
        Mannequin e = shown.remove(best.id());
        if (e != null && e.isValid()) e.remove();
        save();
        return best.name();
    }

    /** The merchant an entity shows, or null. */
    public Merchant byEntity(Entity e) {
        String id = e.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        return id == null ? null : merchants.get(UUID.fromString(id));
    }

    /** The merchant nearest to a spot within 8 blocks, or null. */
    public Merchant nearest(Location at) {
        Merchant best = null;
        double bestD = 64;
        for (Merchant m : merchants.values()) {
            if (!m.world().equals(at.getWorld().getName())) continue;
            double d = at.distanceSquared(new Location(at.getWorld(), m.x(), m.y(), m.z()));
            if (d < bestD) {
                bestD = d;
                best = m;
            }
        }
        return best;
    }

    /** Changes a merchant's name, place or skin (nulls keep the old one) and shows the change. */
    public Merchant update(UUID id, String name, Location at, Skin skin) {
        Merchant m = merchants.get(id);
        if (m == null) return null;
        Merchant n = m.with(name != null ? name : m.name(), at, skin != null ? skin : m.skin());
        merchants.put(id, n);
        Mannequin e = shown.remove(id);
        if (e != null && e.isValid()) e.remove();   // respawned as it is now on the next tick
        save();
        tickMerchants();
        return n;
    }

    public List<Merchant> merchants() {
        return List.copyOf(merchants.values());
    }

    public boolean isMerchant(Entity e) {
        return e.getPersistentDataContainer().has(key, PersistentDataType.STRING);
    }

    public void removeIfStray(Entity e) {
        String id = e.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (id == null) return;
        Mannequin mine = shown.get(UUID.fromString(id));
        if (mine == null || !mine.equals(e)) e.remove();
    }

    /** Spawns missing merchants in loaded chunks and turns them toward the nearest player. */
    private void tickMerchants() {
        for (Merchant m : merchants.values()) {
            World w = Bukkit.getWorld(m.world());
            if (w == null || !w.isChunkLoaded((int) Math.floor(m.x()) >> 4, (int) Math.floor(m.z()) >> 4)) continue;
            Mannequin e = shown.get(m.id());
            Location home = new Location(w, m.x(), m.y(), m.z(), m.yaw(), 0);
            if (e == null || !e.isValid()) {
                e = spawn(m, home);
                shown.put(m.id(), e);
            }
            Player near = null;
            double best = 6 * 6;
            for (Player p : w.getPlayers()) {
                double d = p.getLocation().distanceSquared(home);
                if (d < best) {
                    best = d;
                    near = p;
                }
            }
            Location look = home.clone();
            if (near != null) look.setDirection(near.getEyeLocation().toVector().subtract(home.clone().add(0, 1.62, 0).toVector()));
            if (Math.abs(e.getLocation().getYaw() - look.getYaw()) > 2) e.teleport(look);
        }
    }

    private Mannequin spawn(Merchant m, Location at) {
        return at.getWorld().spawn(at, Mannequin.class, mm -> {
            mm.setPersistent(false);
            mm.setRemoveWhenFarAway(false);
            mm.setGravity(false);
            mm.setInvulnerable(true);
            mm.setCollidable(false);
            mm.setSilent(true);
            mm.setImmovable(true);
            mm.setCustomNameVisible(true);
            mm.customName(Component.text(m.name(), NamedTextColor.GOLD));
            mm.setDescription(Component.text("Merchant · right-click to buy and sell", NamedTextColor.GREEN));
            mm.getEquipment().setItemInMainHand(new ItemStack(Material.EMERALD));
            mm.setProfile(m.skin().profile(m.id()));
            mm.getPersistentDataContainer().set(key, PersistentDataType.STRING, m.id().toString());
        });
    }

    // ── storage ───────────────────────────────────────────────────────────

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection ms = y.getConfigurationSection("merchants");
        if (ms != null) {
            for (String k : ms.getKeys(false)) {
                ConfigurationSection s = ms.getConfigurationSection(k);
                try {
                    UUID id = UUID.fromString(k);
                    Skin skin = s.isConfigurationSection("skin") ? Skin.load(s.getConfigurationSection("skin"), new java.util.Random())
                            : Skin.builtIn(List.of("noor", "makena", "sunny", "zuri").get(Math.floorMod(id.hashCode(), 4)), false);
                    merchants.put(id, new Merchant(id, s.getString("name", "Merchant"), s.getString("world"),
                            s.getDouble("x"), s.getDouble("y"), s.getDouble("z"), (float) s.getDouble("yaw"), skin));
                } catch (RuntimeException ex) {
                    plugin.getLogger().warning("Skipping a broken merchant entry " + k);
                }
            }
        }
        ConfigurationSection ps = y.getConfigurationSection("players");
        if (ps != null) {
            for (String k : ps.getKeys(false)) {
                UUID id;
                try {
                    id = UUID.fromString(k);
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                ConfigurationSection s = ps.getConfigurationSection(k);
                ConfigurationSection so = s.getConfigurationSection("sold");
                if (so != null) {
                    Map<Material, double[]> map = new EnumMap<>(Material.class);
                    for (String mat : so.getKeys(false)) {
                        Material m = Material.matchMaterial(mat);
                        List<Double> v = so.getDoubleList(mat);
                        if (m != null && v.size() == 2) map.put(m, new double[]{v.get(0), v.get(1)});
                    }
                    sold.put(id, map);
                }
                List<double[]> inc = new ArrayList<>();
                for (String line : s.getStringList("income")) {
                    String[] p = line.split(":");
                    if (p.length == 2) inc.add(new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1])});
                }
                income.put(id, inc);
                Map<Material, List<double[]>> rev = new EnumMap<>(Material.class);
                for (String line : s.getStringList("revenue")) {   // ITEM:time:count:money
                    String[] p = line.split(":");
                    Material m = p.length == 4 ? Material.matchMaterial(p[0]) : null;
                    if (m != null) rev.computeIfAbsent(m, mk -> new ArrayList<>()).add(new double[]{Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3])});
                }
                revenue.put(id, rev);
            }
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Merchant m : merchants.values()) {
            ConfigurationSection s = y.createSection("merchants." + m.id());
            s.set("name", m.name());
            s.set("world", m.world());
            s.set("x", m.x());
            s.set("y", m.y());
            s.set("z", m.z());
            s.set("yaw", m.yaw());
            m.skin().save(s.createSection("skin"));
        }
        for (Map.Entry<UUID, Map<Material, double[]>> e : sold.entrySet()) {
            for (Map.Entry<Material, double[]> m : e.getValue().entrySet()) {
                y.set("players." + e.getKey() + ".sold." + m.getKey().name(), List.of(m.getValue()[0], m.getValue()[1]));
            }
        }
        for (Map.Entry<UUID, List<double[]>> e : income.entrySet()) {
            List<String> lines = new ArrayList<>();
            for (double[] v : e.getValue()) if (System.currentTimeMillis() - v[0] < DAY) lines.add((long) v[0] + ":" + v[1]);
            y.set("players." + e.getKey() + ".income", lines);
        }
        for (Map.Entry<UUID, Map<Material, List<double[]>>> e : revenue.entrySet()) {
            List<String> lines = new ArrayList<>();
            for (Map.Entry<Material, List<double[]>> m : e.getValue().entrySet()) {
                for (double[] r : m.getValue()) {
                    if (System.currentTimeMillis() - r[0] < DAY) lines.add(m.getKey().name() + ":" + (long) r[0] + ":" + (long) r[1] + ":" + r[2]);
                }
            }
            y.set("players." + e.getKey() + ".revenue", lines);
        }
        try {
            y.save(file);
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not save market.yml", ex);
        }
    }

    public static String name(Material m) {
        return m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

}
