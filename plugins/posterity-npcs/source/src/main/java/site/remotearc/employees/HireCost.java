package site.remotearc.employees;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import site.remotearc.employees.job.JobType;
import site.remotearc.employees.util.Items;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a villager asks for to take a job: the tools of its trade
 * ({@code jobs.<job>.hire-items}) and food to start with ({@code hiring.food}),
 * a little more for every employee the player already has. The tools become
 * theirs; the food goes into their food store.
 */
public final class HireCost {

    private HireCost() {}

    /** The items, merged by kind (amounts can be over a stack). */
    public static Map<Material, Integer> of(ArcEmployees plugin, UUID owner, JobType job) {
        Settings s = plugin.settings();
        Map<Material, Integer> out = new java.util.LinkedHashMap<>();
        for (ItemStack it : s.job(job).hireItems()) out.merge(it.getType(), it.getAmount(), Integer::sum);
        int food = s.hireFoodAmount + s.hireFoodPerEmployee * plugin.manager().ownedBy(owner).size();
        food = Math.min(food, s.hireFoodMax);
        if (food > 0) out.merge(s.hireFood, food, Integer::sum);
        return out;
    }

    /** How many of a kind the player has that count (not renamed). */
    public static int has(Player p, Material m) {
        int n = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) {
            if (counts(it, m)) n += it.getAmount();
        }
        return n;
    }

    /** What's still missing, by kind (empty: they have it all). */
    public static Map<Material, Integer> missing(Player p, Map<Material, Integer> cost) {
        Map<Material, Integer> out = new EnumMap<>(Material.class);
        for (Map.Entry<Material, Integer> c : cost.entrySet()) {
            int short_ = c.getValue() - has(p, c.getKey());
            if (short_ > 0) out.put(c.getKey(), short_);
        }
        return out;
    }

    /**
     * Takes the items from the player's inventory: plain ones first, then
     * worn or enchanted ones (never renamed ones). False (nothing taken) if
     * they don't have it all.
     */
    public static boolean take(Player p, Map<Material, Integer> cost) {
        if (p.getGameMode() == org.bukkit.GameMode.CREATIVE) return true;
        if (!missing(p, cost).isEmpty()) return false;
        PlayerInventory inv = p.getInventory();
        ItemStack[] slots = inv.getStorageContents();
        for (Map.Entry<Material, Integer> c : cost.entrySet()) {
            int left = c.getValue();
            for (int pass = 0; pass < 2 && left > 0; pass++) {
                for (int i = 0; i < slots.length && left > 0; i++) {
                    ItemStack it = slots[i];
                    if (!counts(it, c.getKey()) || (pass == 0 && !Items.isPlain(it))) continue;
                    int n = Math.min(left, it.getAmount());
                    left -= n;
                    if (n == it.getAmount()) slots[i] = null;
                    else {
                        it = it.clone();
                        it.setAmount(it.getAmount() - n);
                        slots[i] = it;
                    }
                }
            }
        }
        inv.setStorageContents(slots);
        return true;
    }

    private static boolean counts(ItemStack it, Material m) {
        if (it == null || it.getType() != m) return false;
        return !it.hasItemMeta() || !it.getItemMeta().hasDisplayName();
    }

    /** "1 iron hoe and 16 bread". */
    public static String describe(Map<Material, Integer> items) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Material, Integer> c : items.entrySet()) parts.add(c.getValue() + " " + Items.name(c.getKey()));
        if (parts.isEmpty()) return "nothing";
        if (parts.size() == 1) return parts.get(0);
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }
}
