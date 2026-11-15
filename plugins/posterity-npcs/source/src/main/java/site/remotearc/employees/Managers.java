package site.remotearc.employees;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Players an owner lets manage their employees (/employees trust <player>):
 * a manager can do everything the owner can with them (settings, routes,
 * builds, pausing, firing), while wages and hire costs stay on the owner's
 * balance. Kept in managers.yml.
 */
public final class Managers {

    private final ArcEmployees plugin;
    private final File file;
    private final Map<UUID, Set<UUID>> byOwner = new HashMap<>();

    public Managers(ArcEmployees plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "managers.yml");
        load();
    }

    public boolean trusts(UUID owner, UUID player) {
        Set<UUID> s = byOwner.get(owner);
        return s != null && s.contains(player);
    }

    public Set<UUID> of(UUID owner) {
        return byOwner.getOrDefault(owner, Set.of());
    }

    /** Owners who let this player manage their employees. */
    public List<UUID> ownersFor(UUID player) {
        List<UUID> out = new ArrayList<>();
        for (Map.Entry<UUID, Set<UUID>> e : byOwner.entrySet()) if (e.getValue().contains(player)) out.add(e.getKey());
        return out;
    }

    public boolean add(UUID owner, UUID player) {
        boolean added = byOwner.computeIfAbsent(owner, k -> new LinkedHashSet<>()).add(player);
        if (added) save();
        return added;
    }

    public boolean remove(UUID owner, UUID player) {
        Set<UUID> s = byOwner.get(owner);
        boolean removed = s != null && s.remove(player);
        if (removed) save();
        return removed;
    }

    private void load() {
        if (!file.exists()) return;
        ConfigurationSection all = YamlConfiguration.loadConfiguration(file).getConfigurationSection("managers");
        if (all == null) return;
        for (String owner : all.getKeys(false)) {
            try {
                Set<UUID> set = new LinkedHashSet<>();
                for (String id : all.getStringList(owner)) set.add(UUID.fromString(id));
                byOwner.put(UUID.fromString(owner), set);
            } catch (IllegalArgumentException ignored) {
                // skip
            }
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Set<UUID>> e : byOwner.entrySet()) {
            if (!e.getValue().isEmpty()) y.set("managers." + e.getKey(), e.getValue().stream().map(UUID::toString).toList());
        }
        try {
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not save managers.yml", ex);
        }
    }
}
