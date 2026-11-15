package site.remotearc.employees.build;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.Employee;
import site.remotearc.employees.job.Crafting;
import site.remotearc.employees.market.Market;
import site.remotearc.employees.storage.Supply;
import site.remotearc.employees.util.Items;
import site.remotearc.employees.util.Msg;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a build still needs against what the builder can get (its backpack,
 * its supply, and materials bought for the build), and buying the shortfall
 * from the merchants' catalog straight into the build.
 */
public final class BuildMaterials {

    private BuildMaterials() {}

    /** How many crafting steps deep a builder goes (a lectern from sugar cane and leather is four). */
    public static final int DEPTH = 5;

    /** One kind of item: how many are still needed, how many there are, and how many more it can craft from spare materials. */
    public record Line(Material item, int need, int have, int craft) {
        /** Missing even after crafting. */
        public int short_() {
            return Math.max(0, need - have - craft);
        }
    }

    /**
     * The shortfall priced up: what would be bought (item → count: the item, a
     * substitute, or what it's crafted from), what's crafted from bought
     * ingredients, and what can't be had.
     */
    public record Quote(Map<Material, Integer> buy, double total, List<Material> crafted, List<Material> notSold, List<Material> locked) {
        public int count() {
            return buy.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    /** Everything the builder can use: backpack, supply and bought stock. */
    public static Map<Material, Integer> have(ArcEmployees plugin, Employee e, BuildOrder o) {
        Map<Material, Integer> have = new EnumMap<>(Material.class);
        for (ItemStack it : e.backpack.getStorageContents()) if (Items.isPlain(it)) have.merge(it.getType(), it.getAmount(), Integer::sum);
        Supply.available(plugin, e).forEach((m, n) -> have.merge(m, n, Integer::sum));
        for (Material m : o.stock.keySet()) have.merge(m, o.stocked(m), Integer::sum);
        return have;
    }

    /**
     * What it has that the build doesn't need: what it may craft with. Its
     * backpack, supply and bought stock, less what's still to be placed, less
     * the employee's never-craft list.
     */
    public static Map<Material, Integer> spare(Map<Material, Integer> have, Map<Material, Integer> need, Employee e) {
        Map<Material, Integer> out = new EnumMap<>(Material.class);
        have.forEach((m, n) -> {
            int left = n - need.getOrDefault(m, 0);
            if (left > 0 && !e.craftNever.contains(m)) out.put(m, left);
        });
        return out;
    }

    public static Map<Material, Integer> spare(ArcEmployees plugin, Employee e, BuildOrder o) {
        return spare(have(plugin, e, o), o.remaining(Bukkit.getWorld(o.world)), e);
    }

    /** What's left to place, most short first; what's short is crafted from spare materials where it can be. */
    public static List<Line> lines(ArcEmployees plugin, Employee e, BuildOrder o) {
        Map<Material, Integer> need = o.remaining(Bukkit.getWorld(o.world));
        Map<Material, Integer> have = have(plugin, e, o);
        Map<Material, Integer> spare = spare(have, need, e);
        List<Line> out = new ArrayList<>();
        List<Map.Entry<Material, Integer>> order = new ArrayList<>(need.entrySet());
        order.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        for (Map.Entry<Material, Integer> en : order) {
            Material m = en.getKey();
            int h = have.getOrDefault(m, 0);
            Material sub = BuildOrder.substitute(m);
            if (sub != null) h += have.getOrDefault(sub, 0);
            int craft = 0, short_ = en.getValue() - h;
            if (short_ > 0 && e.craft) {
                Crafting.Plan plan = Crafting.plan(m, short_, 1, spare, e.craftNever, DEPTH);
                if (plan != null) {
                    craft = Math.min(short_, plan.makes(m));
                    plan.raw().forEach((r, n) -> spare.merge(r, -n, Integer::sum));
                }
            }
            out.add(new Line(m, en.getValue(), h, craft));
        }
        out.sort((a, b) -> a.short_() != b.short_() ? Integer.compare(b.short_(), a.short_()) : Integer.compare(b.need, a.need));
        return out;
    }

    /**
     * What buying everything that's still short (after crafting from spare
     * materials) would cost this player: the item itself where merchants sell
     * it, otherwise what it's crafted from (logs for stairs), using spare
     * materials first.
     */
    public static Quote quote(ArcEmployees plugin, Employee e, BuildOrder o, UUID buyer, List<Line> lines) {
        Market market = plugin.market();
        Map<Material, Integer> buy = new LinkedHashMap<>();
        List<Material> crafted = new ArrayList<>(), notSold = new ArrayList<>(), locked = new ArrayList<>();
        Map<Material, Integer> spare = null, pool = null, wide = null;
        for (Line l : lines) {
            int n = l.short_();
            if (n <= 0) continue;
            Material m = l.item;
            if (market.listedPrice(m) <= 0) {
                Material sub = BuildOrder.substitute(m);
                if (sub != null && market.listedPrice(sub) > 0) m = sub;
            }
            // 1. Sold as it is (/shop's catalog, or a build price).
            if (market.listedPrice(m) > 0 && market.mayBuy(buyer, m)) {
                buy.merge(m, n, Integer::sum);
                continue;
            }
            if (e.craft) {
                if (spare == null) {
                    spare = spare(plugin, e, o);
                    for (Line x : lines) {
                        if (x.craft() <= 0) continue;
                        Crafting.Plan p = Crafting.plan(x.item(), x.craft(), 1, spare, e.craftNever, DEPTH);
                        if (p != null) for (Map.Entry<Material, Integer> r : p.raw().entrySet()) spare.merge(r.getKey(), -r.getValue(), Integer::sum);
                    }
                    spare.values().removeIf(k -> k <= 0);
                    pool = new EnumMap<>(Material.class);
                    for (Market.Category c : market.categories()) for (Material x : c.items()) if (market.mayBuy(buyer, x)) pool.put(x, 1_000_000);
                    for (Material x : plugin.settings().buildPrices.keySet()) pool.put(x, 1_000_000);
                    pool.keySet().removeAll(e.craftNever);
                    for (Map.Entry<Material, Integer> sp : spare.entrySet()) pool.merge(sp.getKey(), sp.getValue(), Integer::sum);
                    // The same plus anything nothing lists (leather, flint...) at the fallback price.
                    wide = new EnumMap<>(pool);
                    for (Material x : Material.values()) if (!wide.containsKey(x) && !e.craftNever.contains(x) && market.fallbackPrice(x) > 0) wide.put(x, 1_000_000);
                }
                // 2. Crafted from what's sold (and what's spare); 3. the same, with unlisted ingredients bought too.
                Crafting.Plan plan = Crafting.plan(l.item, n, n, pool, e.craftNever, DEPTH);
                if (plan == null && plugin.settings().buildFallbackPrice > 0) plan = Crafting.plan(l.item, n, n, wide, e.craftNever, DEPTH);
                if (plan != null) {
                    for (Map.Entry<Material, Integer> r : plan.raw().entrySet()) {
                        int fromSpare = Math.min(spare.getOrDefault(r.getKey(), 0), r.getValue());
                        if (fromSpare > 0) spare.merge(r.getKey(), -fromSpare, Integer::sum);
                        if (r.getValue() > fromSpare) buy.merge(r.getKey(), r.getValue() - fromSpare, Integer::sum);
                        pool.merge(r.getKey(), -r.getValue(), Integer::sum);
                        wide.merge(r.getKey(), -r.getValue(), Integer::sum);
                    }
                    crafted.add(l.item);
                    continue;
                }
            }
            // 4. Bought as it is at the fallback price (nothing lists it, and it can't be crafted).
            if (market.fallbackPrice(l.item) > 0) {
                buy.merge(l.item, n, Integer::sum);
                continue;
            }
            (market.listedPrice(m) > 0 ? locked : notSold).add(l.item);
        }
        double total = 0;
        for (Map.Entry<Material, Integer> b : buy.entrySet()) total += market.buyPrice(b.getKey()) * b.getValue();
        return new Quote(buy, total, crafted, notSold, locked);
    }

    /** One item's price for menus: "$2.50 each" or "not sold". */
    public static String price(ArcEmployees plugin, Material m) {
        double each = plugin.market().buyPrice(m);
        if (each <= 0) {
            Material sub = BuildOrder.substitute(m);
            if (sub != null && plugin.market().buyPrice(sub) > 0) return Msg.money(plugin.market().buyPrice(sub)) + " each (as " + Items.name(sub) + ")";
            return null;
        }
        return Msg.money(each) + " each";
    }

    /**
     * Buys the shortfall with the player's money and hands it to the builder
     * for this build (it's refunded if the build ends without using it).
     * Returns an error, or null when done.
     */
    public static String buy(ArcEmployees plugin, Player p, Employee e, BuildOrder o, Quote q) {
        var econ = plugin.economy();
        if (econ == null) return "There's no economy on this server.";
        if (q.buy().isEmpty()) return "There's nothing the merchants sell left to buy.";
        if (!econ.has(p, q.total())) return "That costs " + Msg.money(q.total()) + "; you have " + Msg.money(econ.getBalance(p)) + ".";
        if (!econ.withdrawPlayer(p, q.total()).transactionSuccess()) return "The payment didn't go through.";
        for (Map.Entry<Material, Integer> en : q.buy().entrySet()) {
            o.addStock(en.getKey(), en.getValue(), plugin.market().buyPrice(en.getKey()) * en.getValue());
        }
        plugin.manager().markDirty();
        return null;
    }
}
