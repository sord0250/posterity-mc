package site.remotearc.employees.storage;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.Employee;
import site.remotearc.employees.job.JobType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * VirtualChests numbers handed out in groups, one per job (lumberjacks
 * 10-19, farmers 20-29...), so a player knows which chest is what. A player
 * can move a whole group to other numbers (contents and every employee's
 * reference follow), and sort existing chests into their groups. Moves are
 * stored per player in chests.yml.
 */
public final class ChestGroups {

    private final ArcEmployees plugin;
    private final File file;
    private final Map<UUID, Map<JobType, Integer>> moved = new HashMap<>();

    public ChestGroups(ArcEmployees plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "chests.yml");
        load();
    }

    public int size() {
        return plugin.settings().groupSize;
    }

    public int start(UUID owner, JobType job) {
        Map<JobType, Integer> m = moved.get(owner);
        Integer s = m == null ? null : m.get(job);
        return s != null ? s : plugin.settings().groupStart(job);
    }

    public boolean inGroup(UUID owner, JobType job, int n) {
        int s = start(owner, job);
        return n >= s && n < s + size();
    }

    /** The job whose group holds this number, or null. */
    public JobType groupOf(UUID owner, int n) {
        for (JobType t : JobType.values()) if (inGroup(owner, t, n)) return t;
        return null;
    }

    public String range(UUID owner, JobType job) {
        int s = start(owner, job);
        return s + "-" + (s + size() - 1);
    }

    /** First number in the job's group that no employee uses and that has nothing in it, or -1. */
    public int free(UUID owner, JobType job) {
        Set<Integer> used = plugin.manager().usedChests(owner);
        int s = start(owner, job);
        for (int n = s; n < s + size(); n++) {
            if (!used.contains(n) && !plugin.vchests().hasItems(owner, n)) return n;
        }
        return -1;
    }

    /** What each chest number is used for by the owner's employees: "Ned (storage)", "Bob (supply)". */
    public Map<Integer, List<String>> uses(UUID owner) {
        Map<Integer, List<String>> out = new LinkedHashMap<>();
        for (Employee e : plugin.manager().ownedBy(owner)) {
            if (e.storage.kind() == StorageTarget.Kind.VIRTUAL) out.computeIfAbsent(e.storage.chestNumber(), k -> new ArrayList<>()).add(e.name + " (storage)");
            for (int n : e.overflow) out.computeIfAbsent(n, k -> new ArrayList<>()).add(e.name + " (overflow)");
            for (StorageTarget t : e.supply) {
                if (t.kind() == StorageTarget.Kind.VIRTUAL) out.computeIfAbsent(t.chestNumber(), k -> new ArrayList<>()).add(e.name + " (takes from)");
            }
        }
        return out;
    }

    // ── moving ────────────────────────────────────────────────────────────

    /**
     * Moves a job's whole group to start at another number: every chest in
     * it that has items or that an employee uses goes to the same place in
     * the new range. Nothing changes unless every target is free. Returns an
     * error for the player, or null on success.
     */
    public String move(UUID owner, JobType job, int newStart) {
        if (newStart < 1) return "Chest numbers start at 1.";
        int oldStart = start(owner, job), size = size();
        if (newStart == oldStart) return "That group already starts at " + newStart + ".";
        for (JobType t : JobType.values()) {
            if (t == job) continue;
            int s = start(owner, t);
            if (newStart < s + size && s < newStart + size) {
                return "/chest " + newStart + "-" + (newStart + size - 1) + " overlaps the " + t.display().toLowerCase() + " group (" + range(owner, t) + ").";
            }
        }
        Set<Integer> used = plugin.manager().usedChests(owner);
        Map<Integer, Integer> plan = new LinkedHashMap<>();
        for (int n = oldStart; n < oldStart + size; n++) {
            if (!used.contains(n) && !plugin.vchests().hasItems(owner, n)) continue;
            int to = newStart + (n - oldStart);
            if (to >= oldStart && to < oldStart + size) {
                if (used.contains(to) || plugin.vchests().hasItems(owner, to)) {
                    return "The new range overlaps the old one where chests are in use; pick numbers further away.";
                }
            } else if (used.contains(to) || plugin.vchests().hasItems(owner, to)) {
                return "/chest " + to + " isn't empty or is used by an employee; pick another range.";
            }
            plan.put(n, to);
        }
        String err = transfer(owner, plan);
        if (err != null) return err;
        moved.computeIfAbsent(owner, k -> new EnumMap<>(JobType.class)).put(job, newStart);
        save();
        return null;
    }

    /**
     * Sorts the owner's employees' chests into their job groups: each
     * storage or overflow chest outside its group moves (contents and all) to
     * the group's first free number. Returns a line per move, or problems.
     */
    public List<String> organize(UUID owner) {
        List<String> report = new ArrayList<>();
        for (Employee e : plugin.manager().ownedBy(owner)) {
            List<Integer> mine = new ArrayList<>();
            if (e.storage.kind() == StorageTarget.Kind.VIRTUAL) mine.add(e.storage.chestNumber());
            mine.addAll(e.overflow);
            for (int n : mine) {
                if (inGroup(owner, e.job, n)) continue;
                int to = free(owner, e.job);
                if (to < 0) {
                    report.add("<red>No free chest left in the " + e.job.display().toLowerCase() + " group (" + range(owner, e.job) + ") for " + e.name + ".");
                    continue;
                }
                String err = transfer(owner, Map.of(n, to));
                report.add(err != null ? "<red>" + e.name + ": " + err : "<green>" + e.name + ": /chest " + n + " → /chest " + to);
            }
        }
        if (report.isEmpty()) report.add("<gray>Everything is already in its group.");
        return report;
    }

    private static StorageTarget moved(StorageTarget t, Map<Integer, Integer> plan) {
        if (t.kind() != StorageTarget.Kind.VIRTUAL || !plan.containsKey(t.chestNumber())) return t;
        return StorageTarget.virtual(plan.get(t.chestNumber()));
    }

    /** Moves chest contents (from → to) and repoints every employee; nothing happens if any chest is open. */
    private String transfer(UUID owner, Map<Integer, Integer> plan) {
        for (Map.Entry<Integer, Integer> m : plan.entrySet()) {
            if (plugin.vchests().isOpen(owner, m.getKey()) || plugin.vchests().isOpen(owner, m.getValue())) {
                return "/chest " + m.getKey() + " or /chest " + m.getValue() + " is open right now; close it and try again.";
            }
        }
        // Read everything first, so ranges that overlap themselves work.
        Map<Integer, ItemStack[]> contents = new HashMap<>();
        for (int from : plan.keySet()) contents.put(from, plugin.vchests().contents(owner, from));
        // Copy into the new chests first; only then empty the old ones, so a failure never loses items.
        for (Map.Entry<Integer, Integer> m : plan.entrySet()) {
            if (!plugin.vchests().write(owner, m.getValue(), contents.get(m.getKey()))) {
                return "VirtualChests refused to write /chest " + m.getValue() + "; the old chests were left as they were.";
            }
        }
        for (int from : plan.keySet()) {
            if (!plan.containsValue(from) && !plugin.vchests().write(owner, from, new ItemStack[0])) {
                plugin.getLogger().warning("Moved /chest " + from + " of " + owner + " but could not empty the old one; its items are now in both.");
            }
        }
        for (Employee e : plugin.manager().ownedBy(owner)) {
            boolean changed = false;
            if (e.storage.kind() == StorageTarget.Kind.VIRTUAL && plan.containsKey(e.storage.chestNumber())) {
                e.storage = StorageTarget.virtual(plan.get(e.storage.chestNumber()));
                changed = true;
            }
            for (int i = 0; i < e.overflow.size(); i++) {
                Integer to = plan.get(e.overflow.get(i));
                if (to != null) {
                    e.overflow.set(i, to);
                    changed = true;
                }
            }
            for (int i = 0; i < e.supply.size(); i++) {
                StorageTarget t = e.supply.get(i);
                if (t.kind() == StorageTarget.Kind.VIRTUAL && plan.containsKey(t.chestNumber())) {
                    e.supply.set(i, StorageTarget.virtual(plan.get(t.chestNumber())));
                    changed = true;
                }
            }
            // A courier's routes name chests too.
            for (int i = 0; i < e.routes.size(); i++) {
                Employee.Route r = e.routes.get(i);
                StorageTarget from = moved(r.from(), plan), to = moved(r.to(), plan);
                if (from != r.from() || to != r.to()) {
                    e.routes.set(i, new Employee.Route(from, to, r.items(), r.keep(), r.stock()));
                    changed = true;
                }
            }
            if (changed) e.worker.interrupt();
        }
        // And /sort's virtual chests (kept for a category, or sorted along).
        if (plugin.sorter() != null) {
            var prof = plugin.sorter().profiles().get(owner);
            boolean sortChanged = false;
            for (java.util.List<String> keys : prof.pins.values()) {
                for (int i = 0; i < keys.size(); i++) {
                    String k = keys.get(i);
                    if (!k.startsWith("vc:")) continue;
                    Integer to = plan.get(Integer.parseInt(k.substring(3)));
                    if (to != null) {
                        keys.set(i, "vc:" + to);
                        sortChanged = true;
                    }
                }
            }
            for (Map.Entry<Integer, Integer> m : plan.entrySet()) {
                if (prof.virtual.remove(m.getKey())) {
                    prof.virtual.add(m.getValue());
                    sortChanged = true;
                }
            }
            if (sortChanged) plugin.sorter().profiles().save();
        }
        plugin.manager().markDirty();
        return null;
    }

    // ── storage ───────────────────────────────────────────────────────────

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection all = y.getConfigurationSection("moved");
        if (all == null) return;
        for (String owner : all.getKeys(false)) {
            ConfigurationSection s = all.getConfigurationSection(owner);
            Map<JobType, Integer> m = new EnumMap<>(JobType.class);
            for (String job : s.getKeys(false)) {
                JobType t = JobType.parse(job);
                if (t != null) m.put(t, s.getInt(job));
            }
            try {
                moved.put(UUID.fromString(owner), m);
            } catch (IllegalArgumentException ignored) {
                // skip
            }
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Map<JobType, Integer>> e : moved.entrySet()) {
            for (Map.Entry<JobType, Integer> m : e.getValue().entrySet()) y.set("moved." + e.getKey() + "." + m.getKey().key(), m.getValue());
        }
        try {
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not save chests.yml", ex);
        }
    }
}
