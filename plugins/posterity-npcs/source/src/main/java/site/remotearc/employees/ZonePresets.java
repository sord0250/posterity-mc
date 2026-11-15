package site.remotearc.employees;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import site.remotearc.employees.util.BlockPos;
import site.remotearc.employees.util.WorkArea;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Level;

/**
 * No-work zones: boxes an employee leaves alone (no mining, chopping,
 * harvesting, building, fighting or picking up there; walking through is
 * fine). An employee has its own boxes plus any of its owner's named presets,
 * saved once from a WorldEdit selection and handed to as many employees as
 * needed. A preset is used by name, so redrawing it moves it for everyone.
 * Presets live in zones.yml.
 */
public final class ZonePresets {

    private final ArcEmployees plugin;
    private final File file;
    /** Owner → preset name (lower case) → box. */
    private final Map<UUID, Map<String, WorkArea>> presets = new HashMap<>();
    /** Bumped on any change to presets or to an employee's zones, so workers refresh their copy. */
    private int version;

    public ZonePresets(ArcEmployees plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "zones.yml");
        load();
    }

    public int version() {
        return version;
    }

    /** Call after changing an employee's zones. */
    public void changed() {
        version++;
    }

    public static boolean validName(String name) {
        return name.matches("[A-Za-z0-9_-]{1,24}");
    }

    public Map<String, WorkArea> of(UUID owner) {
        return presets.getOrDefault(owner, Map.of());
    }

    public WorkArea get(UUID owner, String name) {
        return of(owner).get(name.toLowerCase(Locale.ROOT));
    }

    public void put(UUID owner, String name, WorkArea box) {
        presets.computeIfAbsent(owner, k -> new TreeMap<>()).put(name.toLowerCase(Locale.ROOT), box);
        changed();
        save();
    }

    /** Deletes a preset and takes it off every employee that had it. */
    public boolean remove(UUID owner, String name) {
        String key = name.toLowerCase(Locale.ROOT);
        Map<String, WorkArea> m = presets.get(owner);
        if (m == null || m.remove(key) == null) return false;
        for (Employee e : plugin.manager().ownedBy(owner)) {
            if (e.zonePresets.removeIf(n -> n.equalsIgnoreCase(key))) plugin.manager().markDirty();
        }
        changed();
        save();
        return true;
    }

    /** How many of the owner's employees use a preset. */
    public int users(UUID owner, String name) {
        int n = 0;
        for (Employee e : plugin.manager().ownedBy(owner)) {
            for (String z : e.zonePresets) if (z.equalsIgnoreCase(name)) n++;
        }
        return n;
    }

    /** Every box the employee must leave alone: its own and its presets'. */
    public List<WorkArea> boxes(Employee e) {
        if (e.zones.isEmpty() && e.zonePresets.isEmpty()) return List.of();
        List<WorkArea> out = new ArrayList<>(e.zones);
        for (String name : e.zonePresets) {
            WorkArea box = get(e.owner, name);
            if (box != null) out.add(box);
        }
        return out;
    }

    /** True if the block is in none of the employee's zones. */
    public static boolean outside(List<WorkArea> zones, String world, int x, int y, int z) {
        for (WorkArea a : zones) {
            if (a.world().equals(world) && a.contains(x, y, z)) return false;
        }
        return true;
    }

    public static String save(WorkArea z) {
        return z.world() + ";" + z.minX() + "," + z.minY() + "," + z.minZ() + ";" + z.maxX() + "," + z.maxY() + "," + z.maxZ();
    }

    public static WorkArea parse(String s) {
        String[] p = s.split(";");
        if (p.length != 3) return null;
        BlockPos a, b;
        try {
            a = BlockPos.parse(p[1]);
            b = BlockPos.parse(p[2]);
        } catch (RuntimeException ex) {
            return null;
        }
        return WorkArea.of(p[0], a.x(), a.y(), a.z(), b.x(), b.y(), b.z());
    }

    private void load() {
        if (!file.exists()) return;
        ConfigurationSection all = YamlConfiguration.loadConfiguration(file).getConfigurationSection("presets");
        if (all == null) return;
        for (String owner : all.getKeys(false)) {
            ConfigurationSection s = all.getConfigurationSection(owner);
            if (s == null) continue;
            try {
                UUID id = UUID.fromString(owner);
                for (String name : s.getKeys(false)) {
                    WorkArea box = parse(s.getString(name, ""));
                    if (box != null) presets.computeIfAbsent(id, k -> new TreeMap<>()).put(name.toLowerCase(Locale.ROOT), box);
                }
            } catch (IllegalArgumentException ignored) {
                // skip
            }
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Map<String, WorkArea>> e : presets.entrySet()) {
            for (Map.Entry<String, WorkArea> p : e.getValue().entrySet()) y.set("presets." + e.getKey() + "." + p.getKey(), save(p.getValue()));
        }
        try {
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not save zones.yml", ex);
        }
    }
}
