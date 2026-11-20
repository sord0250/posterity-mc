package site.remotearc.employees;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import site.remotearc.employees.util.WorkArea;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Named areas (picked out of body, from the kingdom menu's "NPC hangout
 * spots" button, or {@code /employees hangouts}) where off-duty employees
 * gather instead of just loitering at their own post - see
 * {@link site.remotearc.employees.job.Distractions#relax}. Kept per kingdom
 * (its owner's UUID, which survives the kingdom being renamed or refounded,
 * unlike its name), manageable by the owner or anyone they trust to rule it.
 * Saved in hangouts.yml.
 */
public final class HangoutSpots {

    public record Spot(String name, WorkArea area) {}

    private final ArcEmployees plugin;
    private final File file;
    private final Map<UUID, List<Spot>> byOwner = new LinkedHashMap<>();

    HangoutSpots(ArcEmployees plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "hangouts.yml");
        load();
    }

    /** This kingdom's spots (by its owner's UUID), oldest first; never null. */
    public List<Spot> of(UUID kingdomOwner) {
        return kingdomOwner == null ? List.of() : List.copyOf(byOwner.getOrDefault(kingdomOwner, List.of()));
    }

    /** Adds a spot, replacing one already using that name (case-insensitive) for this kingdom. */
    public void add(UUID kingdomOwner, Spot spot) {
        List<Spot> list = byOwner.computeIfAbsent(kingdomOwner, k -> new ArrayList<>());
        list.removeIf(s -> s.name().equalsIgnoreCase(spot.name()));
        list.add(spot);
        save();
    }

    /** Removes a spot by name (case-insensitive); true if one was. */
    public boolean remove(UUID kingdomOwner, String name) {
        List<Spot> list = byOwner.get(kingdomOwner);
        boolean removed = list != null && list.removeIf(s -> s.name().equalsIgnoreCase(name));
        if (removed) save();
        return removed;
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String ownerKey : y.getKeys(false)) {
            UUID owner;
            try {
                owner = UUID.fromString(ownerKey);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            ConfigurationSection spots = y.getConfigurationSection(ownerKey);
            if (spots == null) continue;
            List<Spot> list = new ArrayList<>();
            // Indexed, not keyed by name: a name with a dot in it would otherwise nest as its own section.
            for (String index : spots.getKeys(false)) {
                ConfigurationSection s = spots.getConfigurationSection(index);
                if (s == null) continue;
                String name = s.getString("name");
                WorkArea area = WorkArea.load(s.getConfigurationSection("area"));
                if (name != null && area != null) list.add(new Spot(name, area));
            }
            if (!list.isEmpty()) byOwner.put(owner, list);
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, List<Spot>> en : byOwner.entrySet()) {
            if (en.getValue().isEmpty()) continue;
            ConfigurationSection owner = y.createSection(en.getKey().toString());
            int i = 0;
            for (Spot s : en.getValue()) {
                ConfigurationSection one = owner.createSection(String.valueOf(i++));
                one.set("name", s.name());
                s.area().save(one.createSection("area"));
            }
        }
        try {
            File tmp = new File(file.getParentFile(), "hangouts.yml.tmp");
            Files.createDirectories(file.getParentFile().toPath());
            Files.writeString(tmp.toPath(), y.saveToString(), StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not save hangouts.yml", ex);
        }
    }
}
