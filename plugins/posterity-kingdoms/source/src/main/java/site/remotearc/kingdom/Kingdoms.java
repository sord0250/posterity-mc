package site.remotearc.kingdom;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/** Every kingdom, by id (its lowercase name). Saved in kingdoms.yml. */
public final class Kingdoms {

    private final PosterityKingdoms plugin;
    private final File file;
    private final Map<String, Kingdom> byId = new LinkedHashMap<>();

    Kingdoms(PosterityKingdoms plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "kingdoms.yml");
        load();
    }

    public Collection<Kingdom> all() {
        return byId.values();
    }

    public Kingdom get(String name) {
        return name == null ? null : byId.get(Kingdom.key(name));
    }

    /** The kingdom this player owns or rules, or null. */
    public Kingdom of(UUID player) {
        for (Kingdom k : byId.values()) if (k.isRuler(player)) return k;
        return null;
    }

    void found(Kingdom k) {
        byId.put(k.id, k);
        save();
    }

    void disband(Kingdom k) {
        byId.remove(k.id);
        save();
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(java.util.List.of("Kingdoms: who founded one, where its Core is, and who else rules it.",
                "The plugin rewrites this file while it runs."));
        for (Kingdom k : byId.values()) k.save(y.createSection(k.id));
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not save kingdoms.yml", ex);
        }
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String key : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(key);
            if (s == null) continue;
            try {
                Kingdom k = Kingdom.load(s);
                byId.put(k.id, k);
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("Couldn't load kingdom " + key + ": " + ex);
            }
        }
    }
}
