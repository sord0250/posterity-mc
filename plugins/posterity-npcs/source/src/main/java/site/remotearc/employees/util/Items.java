package site.remotearc.employees.util;

import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/** Small inventory helpers shared by jobs, delivery and menus. */
public final class Items {

    private Items() {}

    /** Adds every stack; returns whatever did not fit. */
    public static List<ItemStack> add(Inventory inv, Collection<ItemStack> stacks) {
        List<ItemStack> leftover = new ArrayList<>();
        for (ItemStack s : stacks) {
            if (s == null || s.getType().isAir() || s.getAmount() <= 0) continue;
            Map<Integer, ItemStack> rest = inv.addItem(s.clone());
            leftover.addAll(rest.values());
        }
        return leftover;
    }

    public static int count(Inventory inv, Material m) {
        int n = 0;
        for (ItemStack s : inv.getContents()) {
            if (s != null && s.getType() == m) n += s.getAmount();
        }
        return n;
    }

    /** Removes up to {@code amount} of a material; returns how many were removed. */
    public static int take(Inventory inv, Material m, int amount) {
        int left = amount;
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length && left > 0; i++) {
            ItemStack s = contents[i];
            if (s == null || s.getType() != m) continue;
            int use = Math.min(left, s.getAmount());
            left -= use;
            if (use == s.getAmount()) inv.setItem(i, null);
            else {
                s.setAmount(s.getAmount() - use);
                inv.setItem(i, s);
            }
        }
        return amount - left;
    }

    public static int freeSlots(Inventory inv) {
        int n = 0;
        for (ItemStack s : inv.getStorageContents()) {
            if (s == null || s.getType().isAir()) n++;
        }
        return n;
    }

    public static boolean isEmpty(Inventory inv) {
        for (ItemStack s : inv.getContents()) {
            if (s != null && !s.getType().isAir()) return false;
        }
        return true;
    }

    /**
     * Pulls every stack out of the inventory except a per-material reserve that
     * stays behind (e.g. saplings and seeds the employee needs to replant).
     */
    public static List<ItemStack> takeAllExcept(Inventory inv, ToIntFunction<Material> reserve) {
        List<ItemStack> out = new ArrayList<>();
        Map<Material, Integer> kept = new java.util.EnumMap<>(Material.class);
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack s = contents[i];
            if (s == null || s.getType().isAir()) continue;
            int want = reserve.applyAsInt(s.getType());
            if (want > 0) {
                int have = kept.getOrDefault(s.getType(), 0);
                int keep = Math.min(s.getAmount(), Math.max(0, want - have));
                kept.put(s.getType(), have + keep);
                if (keep == s.getAmount()) continue;
                ItemStack give = s.clone();
                give.setAmount(s.getAmount() - keep);
                out.add(give);
                if (keep == 0) inv.setItem(i, null);
                else {
                    s.setAmount(keep);
                    inv.setItem(i, s);
                }
            } else {
                out.add(s.clone());
                inv.setItem(i, null);
            }
        }
        return out;
    }

    /** How many items {@link #takeAllExcept} would hand over. */
    public static int countDeliverable(Inventory inv, ToIntFunction<Material> reserve) {
        int total = 0;
        Map<Material, Integer> kept = new java.util.EnumMap<>(Material.class);
        for (ItemStack s : inv.getContents()) {
            if (s == null || s.getType().isAir()) continue;
            int want = reserve.applyAsInt(s.getType());
            if (want > 0) {
                int have = kept.getOrDefault(s.getType(), 0);
                int keep = Math.min(s.getAmount(), Math.max(0, want - have));
                kept.put(s.getType(), have + keep);
                total += s.getAmount() - keep;
            } else {
                total += s.getAmount();
            }
        }
        return total;
    }

    /**
     * A plain stack of its material: no name, lore, contents or other changes.
     * Employees only take plain items from shared storage, so a renamed tool or
     * a filled shulker box is never placed as a block or burnt as fuel.
     */
    public static boolean isPlain(ItemStack s) {
        return s != null && !s.getType().isAir() && s.isSimilar(ItemStack.of(s.getType()));
    }

    /**
     * Ordinary enough to sell: not renamed, described, enchanted or worn.
     * Looser than {@link #isPlain}: hidden extra data (from another plugin,
     * an old version) doesn't stop a sale.
     */
    public static boolean isSellable(ItemStack s) {
        if (s == null || s.getType().isAir()) return false;
        if (isPlain(s)) return true;
        if (!s.hasItemMeta()) return true;
        var meta = s.getItemMeta();
        if (meta.hasDisplayName() || meta.hasLore() || meta.hasEnchants() || meta.hasCustomModelDataComponent()) return false;
        if (meta instanceof org.bukkit.inventory.meta.Damageable d && d.hasDamage()) return false;
        if (meta instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta es && es.hasStoredEnchants()) return false;
        if (meta instanceof org.bukkit.inventory.meta.BlockStateMeta) return false;   // a filled shulker box, a chest with items
        return true;
    }

    /**
     * Takes plain items out of an array of slots, up to the amounts wanted
     * (which are reduced by what was taken). Returns the stacks taken; the
     * array is changed in place.
     */
    public static List<ItemStack> takeWanted(ItemStack[] slots, Map<Material, Integer> want) {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < slots.length; i++) {
            ItemStack s = slots[i];
            if (s == null || !isPlain(s)) continue;
            int need = want.getOrDefault(s.getType(), 0);
            if (need <= 0) continue;
            int take = Math.min(need, s.getAmount());
            ItemStack got = s.clone();
            got.setAmount(take);
            out.add(got);
            want.put(s.getType(), need - take);
            if (take == s.getAmount()) slots[i] = null;
            else {
                s = s.clone();
                s.setAmount(s.getAmount() - take);
                slots[i] = s;
            }
        }
        want.values().removeIf(n -> n <= 0);
        return out;
    }

    /** Adds the plain items in the slots to a running count per material. */
    public static void countPlain(ItemStack[] slots, Map<Material, Integer> into) {
        for (ItemStack s : slots) {
            if (isPlain(s)) into.merge(s.getType(), s.getAmount(), Integer::sum);
        }
    }

    /** Free room for a material: empty slots plus space left in partial stacks of it. */
    public static int room(Inventory inv, Material m) {
        int n = 0;
        for (ItemStack s : inv.getStorageContents()) {
            if (s == null || s.getType().isAir()) n += m.getMaxStackSize();
            else if (s.getType() == m && isPlain(s)) n += Math.max(0, s.getMaxStackSize() - s.getAmount());
        }
        return n;
    }

    public static String name(Material m) {
        return m.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }

    /** The name as typed in commands: oak_planks. */
    public static String key(Material m) {
        return m.name().toLowerCase(java.util.Locale.ROOT);
    }
}
