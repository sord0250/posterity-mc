package site.remotearc.employees.storage;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import site.remotearc.employees.util.Items;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Deposits into, and opens, a player's VirtualChests chest (de.kevin_stefan's
 * VirtualChests 1.2.0 from Modrinth).
 *
 * VirtualChests has no API, so this reaches its classes by reflection and
 * disables itself cleanly if they are not the ones it expects. Everything runs
 * on the main thread, as VirtualChests itself does: it loads a chest from its
 * database when opened, holds the live Inventory in VCManager.openChests (keyed
 * by OWNER uuid, one open chest per owner), and writes it back on close. So:
 *  - if the target chest is open right now, items go into that live Inventory
 *    and VirtualChests saves them on close;
 *  - otherwise the stored chest is read, filled and written back directly.
 * Writing the database while the same chest is open would be lost (or
 * duplicated) on close; checking openChests first is what prevents that.
 * No history entries are added for deposits: VirtualChests keeps only the last
 * 50 per chest, and frequent deliveries would push out the player's own.
 */
public final class VirtualChestsBridge {

    private final Logger log;
    private boolean available;
    private int size = 54;

    private Method spGetInstance, spGetVChest, spSetVChest;
    private Constructor<?> vcNew;
    private Method vcGetContent, vcSetContent, vcSetPlayer, vcSetNumber, vcGetNumber;
    private Field openChests, viewChest, viewInventory;
    private Method openChest;

    public VirtualChestsBridge(Logger log) {
        this.log = log;
    }

    public boolean hook(Plugin vc) {
        available = false;
        if (vc == null || !vc.isEnabled()) return false;
        try {
            ClassLoader cl = vc.getClass().getClassLoader();
            String base = "de.kevin_stefan.virtualChests.";
            Class<?> sp = Class.forName(base + "storage.StorageProvider", true, cl);
            Class<?> chest = Class.forName(base + "storage.model.VirtualChest", true, cl);
            Class<?> manager = Class.forName(base + "VCManager", true, cl);
            Class<?> view = Class.forName(base + "VCViewInfo", true, cl);

            spGetInstance = sp.getMethod("getInstance");
            spGetVChest = sp.getMethod("getVChest", UUID.class, int.class);
            spSetVChest = sp.getMethod("setVChest", chest);
            vcNew = chest.getConstructor();
            vcGetContent = chest.getMethod("getContent");
            vcSetContent = chest.getMethod("setContent", byte[].class);
            vcSetPlayer = chest.getMethod("setPlayer", UUID.class);
            vcSetNumber = chest.getMethod("setNumber", Integer.class);
            vcGetNumber = chest.getMethod("getNumber");
            openChest = manager.getMethod("openChest", Player.class, OfflinePlayer.class, int.class);
            openChests = manager.getDeclaredField("openChests");
            openChests.setAccessible(true);
            viewChest = view.getDeclaredField("virtualChest");
            viewChest.setAccessible(true);
            viewInventory = view.getDeclaredField("inventory");
            viewInventory.setAccessible(true);

            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(new File(vc.getDataFolder(), "config.yml"));
            size = Math.max(1, Math.min(6, cfg.getInt("chest_rows", 6))) * 9;
            available = true;
            log.info("Hooked VirtualChests " + vc.getPluginMeta().getVersion() + " (" + size + "-slot chests).");
        } catch (ReflectiveOperationException | RuntimeException ex) {
            log.warning("VirtualChests " + vc.getPluginMeta().getVersion()
                    + " is not a version this plugin understands; employees will keep items in their backpacks. (" + ex + ")");
        }
        return available;
    }

    public boolean available() {
        return available;
    }

    /** Whether the owner already has anything stored in this chest number. */
    public boolean hasContents(UUID owner, int number) {
        if (!available) return false;
        try {
            return spGetVChest.invoke(spGetInstance.invoke(null), owner, number) != null;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return true;   // unknown: treat as used so we never pick it as "free"
        }
    }

    /** Adds the items to the owner's chest; returns what did not fit (or everything, on failure). */
    public List<ItemStack> deposit(UUID owner, int number, List<ItemStack> items) {
        if (!available || items.isEmpty()) return items;
        try {
            Inventory live = liveInventory(owner, number);
            if (live != null) return Items.add(live, items);

            Object provider = spGetInstance.invoke(null);
            Object stored = spGetVChest.invoke(provider, owner, number);
            ItemStack[] contents = new ItemStack[size];
            if (stored != null) {
                byte[] bytes = (byte[]) vcGetContent.invoke(stored);
                if (bytes != null && bytes.length > 0) {
                    ItemStack[] old = ItemStack.deserializeItemsFromBytes(bytes);
                    if (old.length > size) {
                        log.warning("VirtualChest " + number + " of " + owner + " holds more slots than chest_rows allows; not touching it.");
                        return items;
                    }
                    System.arraycopy(old, 0, contents, 0, old.length);
                }
            }
            Inventory scratch = Bukkit.createInventory(null, size);
            scratch.setContents(contents);
            List<ItemStack> leftover = Items.add(scratch, items);
            if (countItems(leftover) == countItems(items)) return items;   // chest is full: nothing changed

            Object chest = stored;
            if (chest == null) {
                chest = vcNew.newInstance();
                vcSetPlayer.invoke(chest, owner);
                vcSetNumber.invoke(chest, number);
            }
            vcSetContent.invoke(chest, (Object) ItemStack.serializeItemsAsBytes(scratch.getContents()));
            Object saved = spSetVChest.invoke(provider, chest);
            if (saved == null) {
                log.warning("VirtualChests refused to save chest " + number + " of " + owner + ".");
                return items;
            }
            return leftover;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            log.log(Level.WARNING, "Could not deposit into VirtualChest " + number + " of " + owner, ex);
            return items;
        }
    }

    /**
     * Takes plain items out of the owner's chest, up to the amounts wanted
     * (the map is reduced by what was taken). Same live-or-stored rule as
     * {@link #deposit}. Returns the stacks taken.
     */
    public List<ItemStack> withdraw(UUID owner, int number, Map<Material, Integer> want) {
        if (!available || want.isEmpty()) return List.of();
        Map<Material, Integer> before = new java.util.EnumMap<>(want);
        try {
            Inventory live = liveInventory(owner, number);
            if (live != null) {
                ItemStack[] slots = live.getStorageContents();
                List<ItemStack> got = Items.takeWanted(slots, want);
                if (!got.isEmpty()) live.setStorageContents(slots);
                return got;
            }
            Object provider = spGetInstance.invoke(null);
            Object stored = spGetVChest.invoke(provider, owner, number);
            if (stored == null) return List.of();
            byte[] bytes = (byte[]) vcGetContent.invoke(stored);
            if (bytes == null || bytes.length == 0) return List.of();
            ItemStack[] slots = ItemStack.deserializeItemsFromBytes(bytes);
            List<ItemStack> got = Items.takeWanted(slots, want);
            if (got.isEmpty()) return got;
            vcSetContent.invoke(stored, (Object) ItemStack.serializeItemsAsBytes(slots));
            if (spSetVChest.invoke(provider, stored) == null) {
                log.warning("VirtualChests refused to save chest " + number + " of " + owner + ".");
                want.clear();
                want.putAll(before);
                return List.of();
            }
            return got;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            log.log(Level.WARNING, "Could not take items from VirtualChest " + number + " of " + owner, ex);
            want.clear();
            want.putAll(before);
            return List.of();
        }
    }

    /** A copy of the chest's slots (live if open), or an empty array. */
    public ItemStack[] contents(UUID owner, int number) {
        if (!available) return new ItemStack[0];
        try {
            Inventory live = liveInventory(owner, number);
            if (live != null) return live.getStorageContents().clone();
            Object stored = spGetVChest.invoke(spGetInstance.invoke(null), owner, number);
            if (stored == null) return new ItemStack[0];
            byte[] bytes = (byte[]) vcGetContent.invoke(stored);
            return bytes == null || bytes.length == 0 ? new ItemStack[0] : ItemStack.deserializeItemsFromBytes(bytes);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return new ItemStack[0];
        }
    }

    /** Whether someone has this chest open right now (it then can't be moved). */
    public boolean isOpen(UUID owner, int number) {
        if (!available) return false;
        try {
            return liveInventory(owner, number) != null;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return true;
        }
    }

    /** Replaces a closed chest's contents (creating it if needed). Returns false on failure. */
    public boolean write(UUID owner, int number, ItemStack[] contents) {
        if (!available || isOpen(owner, number)) return false;
        try {
            Object provider = spGetInstance.invoke(null);
            Object chest = spGetVChest.invoke(provider, owner, number);
            if (chest == null) {
                chest = vcNew.newInstance();
                vcSetPlayer.invoke(chest, owner);
                vcSetNumber.invoke(chest, number);
            }
            ItemStack[] sized = new ItemStack[size];
            System.arraycopy(contents, 0, sized, 0, Math.min(size, contents.length));
            vcSetContent.invoke(chest, (Object) ItemStack.serializeItemsAsBytes(sized));
            return spSetVChest.invoke(provider, chest) != null;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            log.log(Level.WARNING, "Could not write VirtualChest " + number + " of " + owner, ex);
            return false;
        }
    }

    /** Whether the chest holds any items (an emptied chest still exists but counts as empty). */
    public boolean hasItems(UUID owner, int number) {
        for (ItemStack s : contents(owner, number)) if (s != null && !s.getType().isAir()) return true;
        return false;
    }

    /** Opens the owner's chest for a viewer, exactly like /chest would. */
    public boolean open(Player viewer, OfflinePlayer owner, int number) {
        if (!available) return false;
        try {
            openChest.invoke(null, viewer, owner, number);
            return true;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            log.log(Level.WARNING, "Could not open VirtualChest " + number + " of " + owner.getUniqueId(), ex);
            return false;
        }
    }

    public int size() {
        return size;
    }

    private Inventory liveInventory(UUID owner, int number) throws ReflectiveOperationException {
        Map<?, ?> open = (Map<?, ?>) openChests.get(null);
        Object view = open.get(owner);
        if (view == null) return null;
        Object chest = viewChest.get(view);
        Object n = chest == null ? null : vcGetNumber.invoke(chest);
        if (n instanceof Integer i && i == number) return (Inventory) viewInventory.get(view);
        return null;
    }

    private static int countItems(List<ItemStack> list) {
        int n = 0;
        for (ItemStack s : list) n += s.getAmount();
        return n;
    }
}
