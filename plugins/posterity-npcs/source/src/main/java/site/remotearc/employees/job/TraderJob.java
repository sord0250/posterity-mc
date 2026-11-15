package site.remotearc.employees.job;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import site.remotearc.employees.Employee;
import site.remotearc.employees.storage.Supply;
import site.remotearc.employees.util.Items;
import site.remotearc.employees.util.WorkArea;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Works a trading hall: makes the trades you picked (a villager and one of
 * its offers, e.g. 32 sticks for an emerald) whenever the villager has stock,
 * paying from its backpack and topping up from your storage, and delivers
 * what it gets. Villagers restock at their workstations as usual. One trade
 * per right-click (every 4 ticks), like a player clicking the offer.
 */
final class TraderJob implements Job {

    /**
     * Gets about like a builder: through doors and open space, and up and
     * down shafts and drops without needing stairs or an elevator, since a
     * trading hall is often a maze of pods, levels and holes.
     */
    @Override
    public Movement movement() {
        return Movement.FLY;
    }

    private String idle = "Waiting for stock";

    /** A picked trade, resolved against the villager right now: its offer index and the offer itself. */
    record Live(Villager villager, int index, MerchantRecipe recipe) {}

    /** Finds the picked trade on its villager (offers keep their order), or null if it's gone. */
    static Live resolve(Employee.TradeChoice c) {
        Entity en = Bukkit.getEntity(c.villager());
        if (!(en instanceof Villager v) || !v.isValid()) return null;
        List<MerchantRecipe> list = v.getRecipes();
        for (int i = 0; i < list.size(); i++) {
            if (c.matches(list.get(i))) return new Live(v, i, list.get(i));
        }
        return null;
    }

    /** What one trade costs right now (demand and discounts included). */
    static Map<Material, Integer> cost(MerchantRecipe r) {
        Map<Material, Integer> out = new EnumMap<>(Material.class);
        List<ItemStack> ing = r.getIngredients();
        if (!ing.isEmpty()) {
            ItemStack first = r.getAdjustedIngredient1();
            if (first == null) first = ing.getFirst();
            if (first != null && !first.getType().isAir()) out.merge(first.getType(), first.getAmount(), Integer::sum);
        }
        if (ing.size() > 1 && ing.get(1) != null && !ing.get(1).getType().isAir()) out.merge(ing.get(1).getType(), ing.get(1).getAmount(), Integer::sum);
        return out;
    }

    private static boolean canPay(Worker w, Map<Material, Integer> cost) {
        for (Map.Entry<Material, Integer> e : cost.entrySet()) if (Items.count(w.e.backpack, e.getKey()) < e.getValue()) return false;
        return true;
    }

    @Override
    public Task next(Worker w) {
        WorkArea a = w.e.area;
        if (a == null || !a.world().equals(w.world().getName())) {
            idle = "No work area in this world";
            return null;
        }
        // The trading hall first: villagers into their stalls, the right jobs, the wanted trades.
        if (!w.e.stalls.isEmpty()) {
            String[] waiting = {null};
            Task t = Stalls.work(w, msg -> waiting[0] = msg);
            if (t != null) return t;
            if (w.e.trades.isEmpty()) {
                idle = waiting[0] != null ? waiting[0] : "Looking after " + w.e.stalls.size() + " stalls";
                return null;
            }
        }
        if (w.e.trades.isEmpty()) {
            idle = "No trades picked: /employees trades " + w.e.name;
            return null;
        }
        Map<Material, Integer> short_ = new EnumMap<>(Material.class);
        perTrade.clear();
        inputs.clear();
        for (Employee.TradeChoice c : w.e.trades) {
            inputs.add(c.input1());
            inputs.add(c.input2());
        }
        boolean anyStock = false, anyVillager = false;
        for (Employee.TradeChoice c : w.e.trades) {
            Live t = resolve(c);
            if (t == null) continue;
            Location l = t.villager().getLocation();
            if (l.getWorld() != w.world() || !a.expand(4, 4).contains(l.getBlockX(), l.getBlockY(), l.getBlockZ()) || !w.mayWork(l)) continue;
            anyVillager = true;
            if (t.recipe().getUses() >= t.recipe().getMaxUses() || t.villager().isSleeping() || t.villager().isTrading()) continue;
            anyStock = true;
            Map<Material, Integer> cost = cost(t.recipe());
            if (canPay(w, cost)) {
                if (!w.isUnreachable(l.toVector())) return new TradeTask(c, l);
            } else {
                int trades = t.recipe().getMaxUses() - t.recipe().getUses();
                cost.forEach((m, n) -> {
                    short_.merge(m, n * trades, Integer::sum);
                    perTrade.merge(m, n, Math::max);
                });
            }
        }
        if (short_.isEmpty()) craftKeep.clear();
        // Craft what it's short of from what it carries (logs it fetched for sticks).
        if (!short_.isEmpty() && w.e.craft && w.cooldownReady("craft-plan", 60)) {
            Task t = craftTask(w, short_, backpack(w));
            if (t != null) return t;
        }
        if (!short_.isEmpty() && w.cooldownReady("trade-fetch", 20 * 30)) {
            Map<Material, Integer> avail = Supply.available(w.plugin(), w.e);
            Map<Material, Integer> want = new EnumMap<>(Material.class);
            short_.forEach((m, n) -> {
                int have = avail.getOrDefault(m, 0);
                if (have > 0) want.put(m, Math.min(have, n));
            });
            if (w.e.craft) {
                // Not enough in storage either: fetch what it can be crafted from.
                Map<Material, Integer> carried = backpack(w), stock = new EnumMap<>(carried);
                avail.forEach((m, n) -> stock.merge(m, n, Integer::sum));
                want.forEach((m, n) -> stock.merge(m, -n, Integer::sum));
                for (Map.Entry<Material, Integer> e : short_.entrySet()) {
                    Material m = e.getKey();
                    int missing = Math.min(e.getValue(), w.jobSettings().reserve()) - carried.getOrDefault(m, 0) - want.getOrDefault(m, 0);
                    if (missing <= 0) continue;
                    Crafting.Plan plan = Crafting.plan(m, missing, perTrade.getOrDefault(m, 1), usable(w, stock, m), w.e.craftNever);
                    if (plan == null) continue;
                    plan.raw().forEach((r, n) -> {
                        int fromStorage = n - carried.getOrDefault(r, 0);
                        if (fromStorage > 0) want.merge(r, fromStorage, Integer::sum);
                        stock.merge(r, -n, Integer::sum);
                        craftKeep.add(r);
                    });
                    for (Crafting.Step st : plan.steps()) craftKeep.add(st.result());
                }
            }
            Task t = FetchTask.create(w, want, "trade goods");
            if (t != null) return t;
        }
        if (!anyVillager) idle = "Can't find the villagers it trades with in its area";
        else if (!short_.isEmpty()) idle = "Needs " + describe(short_) + " to trade (none in your storage"
                + (w.e.craft ? ", and nothing to craft it from)" : "; crafting is off)");
        else if (!anyStock) idle = "Villagers are out of stock (they restock at their workstations)";
        else idle = "Waiting for stock";
        return null;
    }

    // ── crafting ──────────────────────────────────────────────────────────

    /** What one trade costs, per material (the least worth crafting). */
    private final Map<Material, Integer> perTrade = new EnumMap<>(Material.class);
    /** What its picked trades take: never crafted into something else. */
    private final java.util.Set<Material> inputs = java.util.EnumSet.noneOf(Material.class);
    /** Fetched to craft with, or crafted on the way: kept in the backpack instead of delivered. */
    private final java.util.Set<Material> craftKeep = java.util.EnumSet.noneOf(Material.class);

    private static Map<Material, Integer> backpack(Worker w) {
        Map<Material, Integer> out = new EnumMap<>(Material.class);
        Items.countPlain(w.e.backpack.getStorageContents(), out);
        return out;
    }

    /** The stock it may craft with: not other trades' goods, nor anything on its never list. */
    private Map<Material, Integer> usable(Worker w, Map<Material, Integer> stock, Material target) {
        Map<Material, Integer> out = new EnumMap<>(Material.class);
        stock.forEach((m, n) -> {
            if (n > 0 && m != target && !inputs.contains(m) && !w.e.craftNever.contains(m)) out.put(m, n);
        });
        return out;
    }

    /** Crafting from the backpack toward what it's short of, or null if it can't make even one trade's worth. */
    private Task craftTask(Worker w, Map<Material, Integer> short_, Map<Material, Integer> carried) {
        for (Map.Entry<Material, Integer> e : short_.entrySet()) {
            Material m = e.getKey();
            int have = carried.getOrDefault(m, 0);
            int missing = Math.min(e.getValue(), w.jobSettings().reserve()) - have;
            int least = perTrade.getOrDefault(m, 1) - have;
            if (missing <= 0 || least > missing) continue;
            Crafting.Plan plan = Crafting.plan(m, missing, Math.max(1, least), usable(w, carried, m), w.e.craftNever);
            if (plan != null) return new CraftTask(plan.steps(), "for trades");
        }
        return null;
    }

    private static String describe(Map<Material, Integer> m) {
        StringBuilder sb = new StringBuilder();
        m.forEach((k, v) -> {
            if (sb.length() > 0) sb.append(", ");
            sb.append(Items.name(k));
        });
        return sb.toString();
    }

    /** Walks up to the villager and makes the trade over and over while it has stock and the trader can pay. */
    private static final class TradeTask extends WalkThenAct {

        private final Employee.TradeChoice choice;
        private int timer, done;

        TradeTask(Employee.TradeChoice choice, Location at) {
            super(at.toVector(), 3.0, 2, 2);
            this.choice = choice;
        }

        @Override
        protected Result act(Worker w) {
            Live t = resolve(choice);
            if (t == null || t.villager().isSleeping() || t.villager().isTrading()) return Result.DONE;
            Location vl = t.villager().getLocation();
            if (vl.distanceSquared(w.e.location) > 4.5 * 4.5) return Result.DONE;   // it walked off; come back later
            w.lookAt(vl.getX(), vl.getY() + 1.5, vl.getZ());
            if (++timer < w.clickTicks()) return Result.RUNNING;
            timer = 0;
            MerchantRecipe r = t.recipe();
            if (r.getUses() >= r.getMaxUses() || done >= 64) return Result.DONE;
            Map<Material, Integer> cost = cost(r);
            if (!canPay(w, cost) || w.backpackFull()) return Result.DONE;
            cost.forEach((m, n) -> Items.take(w.e.backpack, m, n));
            w.stash(List.of(r.getResult().clone()));
            r.setUses(r.getUses() + 1);
            t.villager().setRecipe(t.index(), r);
            t.villager().setVillagerExperience(t.villager().getVillagerExperience() + r.getVillagerExperience());
            w.swing();
            w.playSound(vl, Sound.ENTITY_VILLAGER_TRADE, 0.6f, 1f);
            vl.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, vl.clone().add(0, 2.1, 0), 3, 0.3, 0.2, 0.3);
            w.e.stats.tradesMade++;
            done++;
            w.plugin().manager().markDirty();
            return Result.RUNNING;
        }

        @Override
        public String status(Worker w) {
            return "Trading " + Items.name(choice.result()) + " with a villager";
        }
    }

    // ── job hooks ─────────────────────────────────────────────────────────

    @Override
    public String idleStatus(Worker w) {
        return idle;
    }

    @Override
    public boolean isReserved(Material m) {
        return false;
    }

    /** It keeps what its trades cost (up to its reserve); the results are delivered. */
    @Override
    public int reserve(Worker w, Material m) {
        for (Employee.TradeChoice c : w.e.trades) if (c.input1() == m || c.input2() == m) return w.jobSettings().reserve();
        if (!w.e.stalls.isEmpty() && (Stalls.WORKSTATIONS.containsKey(m) || org.bukkit.Tag.WOODEN_TRAPDOORS.isTagged(m))) {
            return 4;   // kept handy for shutting villagers into stalls
        }
        return craftKeep.contains(m) ? Integer.MAX_VALUE : 0;
    }

    @Override
    public boolean collects(Material m) {
        return false;
    }

    @Override
    public boolean canWorkWhenFull() {
        return true;
    }
}
