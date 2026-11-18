package site.remotearc.employees;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.FoodProperties;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Container;
import org.bukkit.inventory.ItemStack;
import site.remotearc.employees.storage.StorageTarget;
import site.remotearc.employees.storage.Supply;
import site.remotearc.employees.util.Items;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Employees aren't paid: they eat. Each keeps a food store, counted in food
 * points (an item's nutrition plus saturation, as for a player: bread is 11),
 * and eats {@code food.points-per-day} of it every Minecraft day it's about.
 * When the store runs low it tops it up from its pantry (the chest set as its
 * pantry, otherwise where it delivers); with nothing left at all, it eats
 * from its own backpack. At 0 it's too hungry to work.
 */
public final class Food {

    private Food() {}

    private static final Map<Material, Double> POINTS = new EnumMap<>(Material.class);

    /** Food points one of this item gives, or 0 if it isn't food (or is on the never-eat list). */
    public static double points(Settings s, Material m) {
        if (m == null || m.isAir() || !m.isItem() || s.foodNever.contains(m)) return 0;
        Double known = POINTS.get(m);
        if (known != null) return known;
        double v = 0;
        try {
            FoodProperties f = ItemStack.of(m).getData(DataComponentTypes.FOOD);
            if (f != null) v = Math.max(0, f.nutrition() + f.saturation());
        } catch (RuntimeException ex) {
            v = 0;
        }
        POINTS.put(m, v);
        return v;
    }

    /** True if food is switched on (a day eats something). */
    public static boolean enabled(ArcEmployees plugin) {
        return plugin.settings().foodPerDay > 0;
    }

    /** What one tick of being about eats. */
    public static double perTick(ArcEmployees plugin) {
        return plugin.settings().foodPerDay / 24_000.0;
    }

    /** Where it gets its food from: its pantry, or (none set) wherever it delivers. */
    public static List<StorageTarget> pantries(ArcEmployees plugin, Employee e) {
        Set<StorageTarget> all = new LinkedHashSet<>();
        if (e.pantry.kind() != StorageTarget.Kind.NONE) {
            all.add(e.pantry);
        } else if (e.storage.kind() != StorageTarget.Kind.NONE) {
            all.add(e.storage);
            for (int n : e.overflow) all.add(StorageTarget.virtual(n));
        }
        List<StorageTarget> usable = new ArrayList<>();
        for (StorageTarget t : all) {
            if (t.kind() == StorageTarget.Kind.VIRTUAL && plugin.settings().vcEnabled && plugin.vchests().available()) usable.add(t);
            else if (t.kind() == StorageTarget.Kind.CHEST && plugin.settings().chestsEnabled) usable.add(t);
        }
        return usable;
    }

    /** "the chest at 4, 64, -2", "/chest 12": where its food comes from, for messages. */
    public static String pantryName(ArcEmployees plugin, Employee e) {
        List<StorageTarget> all = pantries(plugin, e);
        if (all.isEmpty()) return "its backpack (no pantry set)";
        return StorageTarget.describeAll(all);
    }

    private record Cached(long at, double points) {}

    private static final Map<java.util.UUID, Cached> PANTRY = new java.util.HashMap<>();

    /** Food points waiting in its pantry (placed chests only while loaded); read at most every 5 seconds. */
    public static double inPantry(ArcEmployees plugin, Employee e) {
        long now = System.currentTimeMillis();
        Cached c = PANTRY.get(e.id);
        if (c != null && now - c.at() < 5000) return c.points();
        double v = countPantry(plugin, e);
        PANTRY.put(e.id, new Cached(now, v));
        return v;
    }

    private static double countPantry(ArcEmployees plugin, Employee e) {
        double sum = 0;
        for (StorageTarget t : pantries(plugin, e)) {
            ItemStack[] slots;
            if (t.kind() == StorageTarget.Kind.VIRTUAL) {
                slots = plugin.vchests().contents(e.owner, t.chestNumber());
            } else {
                Container c = t.container();
                if (c == null) continue;
                slots = c.getInventory().getStorageContents();
            }
            for (ItemStack s : slots) {
                if (Items.isPlain(s)) sum += points(plugin.settings(), s.getType()) * s.getAmount();
            }
        }
        return sum;
    }

    /** Days a number of food points lasts. */
    public static double days(ArcEmployees plugin, double points) {
        double perDay = plugin.settings().foodPerDay;
        return perDay <= 0 ? Double.POSITIVE_INFINITY : points / perDay;
    }

    /** "about 3 days", "less than a day", "2 weeks". */
    public static String daysText(double days) {
        if (Double.isInfinite(days)) return "for ever";
        if (days < 1) return "less than a day";
        long d = Math.round(days);
        if (d >= 14) return "about " + (d / 7) + " weeks";
        return "about " + d + (d == 1 ? " day" : " days");
    }

    /** Puts food items into its store (hiring). */
    public static void store(ArcEmployees plugin, Employee e, Material m, int count) {
        e.food += points(plugin.settings(), m) * count;
    }

    /**
     * Tops up its store from the pantry if it's running low; with the pantry
     * empty and nothing left at all, eats from its own backpack. True if it ate.
     */
    public static boolean topUp(ArcEmployees plugin, Employee e) {
        Settings s = plugin.settings();
        if (s.foodPerDay <= 0) return false;
        if (e.food >= s.foodLowDays * s.foodPerDay) return false;
        double target = s.foodTopUpDays * s.foodPerDay;
        double before = e.food;
        for (StorageTarget t : pantries(plugin, e)) {
            if (e.food >= target) break;
            takeFrom(plugin, e, t, target);
        }
        if (e.food <= 0 && e.backpack != null) {
            // Nothing in the pantry: anything edible it's carrying (a farmer's carrots), a day's worth.
            ItemStack[] slots = e.backpack.getStorageContents();
            boolean changed = false;
            for (int i = 0; i < slots.length && e.food < s.foodPerDay; i++) {
                ItemStack it = slots[i];
                double pts = it == null ? 0 : points(s, it.getType());
                if (pts <= 0 || !Items.isPlain(it)) continue;
                int n = (int) Math.min(it.getAmount(), Math.ceil((s.foodPerDay - e.food) / pts));
                e.food += pts * n;
                if (n >= it.getAmount()) slots[i] = null;
                else {
                    it = it.clone();
                    it.setAmount(it.getAmount() - n);
                    slots[i] = it;
                }
                changed = true;
            }
            if (changed) e.backpack.setStorageContents(slots);
        }
        if (e.food > before) {
            PANTRY.remove(e.id);
            if (e.isLoaded()) e.location.getWorld().playSound(e.location, Sound.ENTITY_GENERIC_EAT, 0.5f, 1f);
            plugin.manager().markDirty();
            return true;
        }
        return false;
    }

    /** Takes food from one pantry, best food first, until the store reaches {@code target}. */
    private static void takeFrom(ArcEmployees plugin, Employee e, StorageTarget t, double target) {
        Settings s = plugin.settings();
        ItemStack[] slots;
        Container c = null;
        if (t.kind() == StorageTarget.Kind.VIRTUAL) {
            slots = plugin.vchests().contents(e.owner, t.chestNumber());
        } else {
            c = t.container();
            if (c == null) return;
            slots = c.getInventory().getStorageContents();
        }
        // What's there, by kind: the most filling first, so a full pantry lasts longest per trip.
        Map<Material, Integer> have = new EnumMap<>(Material.class);
        for (ItemStack it : slots) {
            if (Items.isPlain(it) && points(s, it.getType()) > 0) have.merge(it.getType(), it.getAmount(), Integer::sum);
        }
        List<Material> kinds = new ArrayList<>(have.keySet());
        kinds.sort((a, b) -> Double.compare(points(s, b), points(s, a)));
        for (Material m : kinds) {
            if (e.food >= target) return;
            double pts = points(s, m);
            int n = (int) Math.min(have.get(m), Math.ceil((target - e.food) / pts));
            if (n <= 0) continue;
            Map<Material, Integer> want = new EnumMap<>(Material.class);
            want.put(m, n);
            List<ItemStack> got = c != null ? Supply.takeFrom(c, want) : plugin.vchests().withdraw(e.owner, t.chestNumber(), want);
            for (ItemStack it : got) e.food += pts * it.getAmount();
        }
    }
}
