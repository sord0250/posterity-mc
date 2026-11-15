package site.remotearc.employees.storage;

import org.bukkit.Material;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.Employee;
import site.remotearc.employees.util.Items;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where builders, smelters and ranchers take materials from: their own
 * storage, any chests added with /employees supply, and (unless turned off)
 * wherever the owner's other employees deliver. VirtualChests chests are
 * reached remotely, like deliveries; placed chests have to be walked to.
 * Only plain items are ever taken.
 */
public final class Supply {

    private Supply() {}

    public static List<StorageTarget> sources(ArcEmployees plugin, Employee e) {
        Set<StorageTarget> out = new LinkedHashSet<>();
        addStorage(out, e);
        out.addAll(e.supply);
        if (e.teamSupply) {
            for (Employee o : plugin.manager().ownedBy(e.owner)) {
                if (o != e) addStorage(out, o);
            }
        }
        List<StorageTarget> usable = new ArrayList<>();
        for (StorageTarget t : out) {
            if (t.kind() == StorageTarget.Kind.VIRTUAL && plugin.settings().vcEnabled && plugin.vchests().available()) usable.add(t);
            else if (t.kind() == StorageTarget.Kind.CHEST && plugin.settings().chestsEnabled) usable.add(t);
        }
        return usable;
    }

    private static void addStorage(Set<StorageTarget> out, Employee e) {
        if (e.storage.kind() == StorageTarget.Kind.NONE) return;
        out.add(e.storage);
        for (int n : e.overflow) out.add(StorageTarget.virtual(n));
    }

    /** Plain items available in every source (placed chests only if their chunk is loaded). */
    public static Map<Material, Integer> available(ArcEmployees plugin, Employee e) {
        Map<Material, Integer> out = new EnumMap<>(Material.class);
        for (StorageTarget t : sources(plugin, e)) {
            if (t.kind() == StorageTarget.Kind.VIRTUAL) {
                Items.countPlain(plugin.vchests().contents(e.owner, t.chestNumber()), out);
            } else {
                // Only placed chests it can walk to: one it couldn't reach (or in another world) would be
                // counted, fetched from, come back empty, and be counted again, for ever.
                if (e.worker != null && (e.location == null || e.location.getWorld() == null
                        || !e.location.getWorld().getName().equals(t.world()) || e.worker.isUnreachable(t.pos()))) continue;
                Container c = t.container();
                if (c != null) Items.countPlain(c.getInventory().getStorageContents(), out);
            }
        }
        return out;
    }

    /** Takes what it can from the VirtualChests sources; {@code want} shrinks by what was taken. */
    public static List<ItemStack> takeRemote(ArcEmployees plugin, Employee e, Map<Material, Integer> want) {
        List<ItemStack> got = new ArrayList<>();
        for (StorageTarget t : sources(plugin, e)) {
            if (want.isEmpty()) break;
            if (t.kind() == StorageTarget.Kind.VIRTUAL) got.addAll(plugin.vchests().withdraw(e.owner, t.chestNumber(), want));
        }
        return got;
    }

    /** Takes from a placed container the employee is standing at. */
    public static List<ItemStack> takeFrom(Container c, Map<Material, Integer> want) {
        Inventory inv = c.getInventory();
        ItemStack[] slots = inv.getStorageContents();
        List<ItemStack> got = Items.takeWanted(slots, want);
        if (!got.isEmpty()) inv.setStorageContents(slots);
        return got;
    }

    /** Whether a placed chest source holds any of the wanted items. */
    public static boolean chestHas(StorageTarget t, Map<Material, Integer> want) {
        Container c = t.container();
        if (c == null) return false;
        for (ItemStack s : c.getInventory().getStorageContents()) {
            if (Items.isPlain(s) && want.getOrDefault(s.getType(), 0) > 0) return true;
        }
        return false;
    }

    public static String describe(ArcEmployees plugin, Employee e) {
        List<StorageTarget> all = sources(plugin, e);
        return all.isEmpty() ? "nowhere (only its backpack)" : StorageTarget.describeAll(all);
    }
}
