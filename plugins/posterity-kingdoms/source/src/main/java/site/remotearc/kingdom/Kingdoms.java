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
    private final File retiredFile;
    private final Map<String, Kingdom> byId = new LinkedHashMap<>();
    /**
     * Every id a kingdom has ever had, even disbanded ones: kept forever so a
     * reused name can never inherit a disbanded kingdom's old employees (an
     * employee is tagged with the kingdom's id it was hired under, and that
     * id is never handed to a different kingdom).
     */
    private final java.util.Set<String> everUsed = new java.util.HashSet<>();

    Kingdoms(PosterityKingdoms plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "kingdoms.yml");
        this.retiredFile = new File(plugin.getDataFolder(), "retired-kingdoms.yml");
        load();
    }

    /** True once this name has ever belonged to a kingdom, disbanded or not. */
    public boolean everUsed(String name) {
        return name != null && everUsed.contains(Kingdom.key(name));
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
        if (everUsed.add(k.id)) saveRetired();
        save();
    }

    void disband(Kingdom k) {
        byId.remove(k.id);
        save();
    }

    /** An async write already in flight; a save() that lands while one is running just marks pendingResave instead of overlapping it. */
    private volatile boolean writing;
    private boolean pendingResave;

    /** Snapshots the kingdoms (cheap: no backpacks or entities here) on the caller's thread, then writes the file off it. */
    void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(java.util.List.of("Kingdoms: who founded one, where its Core is, and who else rules it.",
                "The plugin rewrites this file while it runs."));
        for (Kingdom k : byId.values()) k.save(y.createSection(k.id));
        String yaml = y.saveToString();
        if (writing) {
            pendingResave = true;
            return;
        }
        writing = true;
        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> writeToDisk(yaml));
    }

    private void writeToDisk(String yaml) {
        try {
            plugin.getDataFolder().mkdirs();
            java.nio.file.Path tmp = file.toPath().resolveSibling("kingdoms.yml.tmp");
            java.nio.file.Files.writeString(tmp, yaml, java.nio.charset.StandardCharsets.UTF_8);
            java.nio.file.Files.move(tmp, file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not save kingdoms.yml", ex);
        } finally {
            writing = false;
            if (pendingResave) {
                pendingResave = false;
                org.bukkit.Bukkit.getScheduler().runTask(plugin, this::save);
            }
        }
    }

    private void load() {
        if (retiredFile.exists()) everUsed.addAll(YamlConfiguration.loadConfiguration(retiredFile).getStringList("ids"));
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String key : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(key);
            if (s == null) continue;
            try {
                Kingdom k = Kingdom.load(s);
                byId.put(k.id, k);
                everUsed.add(k.id);
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("Couldn't load kingdom " + key + ": " + ex);
            }
        }
    }

    private void saveRetired() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("ids", new java.util.ArrayList<>(everUsed));
        try {
            plugin.getDataFolder().mkdirs();
            y.save(retiredFile);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not save retired-kingdoms.yml", ex);
        }
    }
}
