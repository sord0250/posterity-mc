package site.remotearc.employees.sort;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import site.remotearc.employees.ArcEmployees;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Each player's sorting setup, in sort.yml: which categories are on, their
 * own categories, which chests (placed or virtual) belong to which category,
 * the virtual chests to sort too, and how far round them to look.
 */
public final class SortProfiles {

    /** A player's own category: the items they put in it. */
    public static final class Custom {
        public final String key;
        public String name;
        public Material icon;
        public final Set<Material> items = EnumSet.noneOf(Material.class);

        Custom(String key, String name, Material icon) {
            this.key = key;
            this.name = name;
            this.icon = icon;
        }
    }

    public static final class Profile {
        /** Categories switched from their default (main ones on, extras off). */
        public final Map<String, Boolean> switched = new HashMap<>();
        public final Map<String, Custom> custom = new LinkedHashMap<>();
        /** Category → its containers ("world;x;y;z" or "vc:N"), in order. */
        public final Map<String, List<String>> pins = new LinkedHashMap<>();
        /** VirtualChests numbers sorted along with what's nearby. */
        public final Set<Integer> virtual = new java.util.TreeSet<>();
        public int radius = 8;
        /** Labels on the containers round them, saying what's in each. */
        public boolean labels;

        public boolean on(String key) {
            if (custom.containsKey(key)) return switched.getOrDefault(key, true);
            if (Categories.MISC.equals(key)) return true;
            Categories.Def d = Categories.def(key);
            return switched.getOrDefault(key, d != null && d.main());
        }

        /** Which category an item goes to, for this player. */
        public String categoryOf(Material m) {
            for (Custom c : custom.values()) if (c.items.contains(m) && on(c.key)) return c.key;
            for (Categories.Def d : Categories.EXTRAS) if (on(d.key()) && d.takes().test(m)) return d.key();
            String main = Categories.main(m);
            return on(main) ? main : Categories.MISC;
        }

        /** Every category that's on, in display order: own ones, extras, main. */
        public List<String> active() {
            List<String> out = new ArrayList<>();
            for (Custom c : custom.values()) if (on(c.key)) out.add(c.key);
            for (Categories.Def d : Categories.EXTRAS) if (on(d.key())) out.add(d.key());
            for (Categories.Def d : Categories.MAIN) if (on(d.key())) out.add(d.key());
            return out;
        }

        public String name(String key) {
            Custom c = custom.get(key);
            if (c != null) return c.name;
            Categories.Def d = Categories.def(key);
            return d != null ? d.name() : key;
        }

        public Material icon(String key) {
            Custom c = custom.get(key);
            if (c != null) return c.icon;
            Categories.Def d = Categories.def(key);
            return d != null ? d.icon() : Material.CHEST;
        }

        /** The category a container is pinned to, or null. */
        public String pinnedTo(String box) {
            for (Map.Entry<String, List<String>> e : pins.entrySet()) if (e.getValue().contains(box)) return e.getKey();
            return null;
        }

        public Custom newCustom(String key, String name, Material icon) {
            Custom c = new Custom(key, name, icon);
            custom.put(key, c);
            return c;
        }

        /** Drops one of the player's own categories and the chests kept for it. */
        public void removeCustom(String key) {
            custom.remove(key);
            pins.remove(key);
            switched.remove(key);
        }

        public void pin(String category, String box) {
            unpin(box);
            pins.computeIfAbsent(category, k -> new ArrayList<>()).add(box);
        }

        public void unpin(String box) {
            for (List<String> l : pins.values()) l.remove(box);
            pins.values().removeIf(List::isEmpty);
        }
    }

    private final ArcEmployees plugin;
    private final File file;
    private final Map<UUID, Profile> profiles = new HashMap<>();

    public SortProfiles(ArcEmployees plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "sort.yml");
        load();
    }

    public Profile get(UUID player) {
        return profiles.computeIfAbsent(player, k -> {
            Profile p = new Profile();
            importArcShop(p);
            return p;
        });
    }

    /** Chests already given categories with ArcShop's /sort claim and organize become this player's pins. */
    private void importArcShop(Profile p) {
        File db = new File(plugin.getDataFolder().getParentFile(), "ArcShop/data.db");
        if (!db.isFile()) return;
        java.util.Properties props = new java.util.Properties();
        props.setProperty("open_mode", "1");   // read only
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.getPath(), props);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT world, x, y, z, category FROM chest_categories ORDER BY assigned_at")) {
            while (rs.next()) {
                String cat = rs.getString(5).toLowerCase(Locale.ROOT);
                if (Categories.def(cat) == null) continue;
                p.pin(cat, rs.getString(1) + ";" + rs.getInt(2) + ";" + rs.getInt(3) + ";" + rs.getInt(4));
            }
        } catch (Exception ex) {
            plugin.getLogger().log(Level.FINE, "No ArcShop chest categories to import", ex);
        }
    }

    private void load() {
        if (!file.isFile()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String id : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(id);
            if (s == null) continue;
            UUID uuid;
            try {
                uuid = UUID.fromString(id);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            Profile p = new Profile();
            p.radius = s.getInt("radius", 8);
            p.labels = s.getBoolean("labels");
            ConfigurationSection sw = s.getConfigurationSection("categories");
            if (sw != null) for (String k : sw.getKeys(false)) p.switched.put(k, sw.getBoolean(k));
            ConfigurationSection cs = s.getConfigurationSection("custom");
            if (cs != null) {
                for (String k : cs.getKeys(false)) {
                    Material icon = Material.matchMaterial(cs.getString(k + ".icon", "CHEST"));
                    Custom c = new Custom(k, cs.getString(k + ".name", k), icon == null ? Material.CHEST : icon);
                    for (String n : cs.getStringList(k + ".items")) {
                        Material m = Material.matchMaterial(n);
                        if (m != null) c.items.add(m);
                    }
                    p.custom.put(k, c);
                }
            }
            ConfigurationSection ps = s.getConfigurationSection("pins");
            if (ps != null) for (String k : ps.getKeys(false)) p.pins.put(k, new ArrayList<>(ps.getStringList(k)));
            p.virtual.addAll(s.getIntegerList("virtual"));
            profiles.put(uuid, p);
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Profile> e : profiles.entrySet()) {
            Profile p = e.getValue();
            String id = e.getKey().toString();
            y.set(id + ".radius", p.radius);
            if (p.labels) y.set(id + ".labels", true);
            p.switched.forEach((k, v) -> y.set(id + ".categories." + k, v));
            for (Custom c : p.custom.values()) {
                y.set(id + ".custom." + c.key + ".name", c.name);
                y.set(id + ".custom." + c.key + ".icon", c.icon.name());
                y.set(id + ".custom." + c.key + ".items", c.items.stream().map(Material::name).toList());
            }
            p.pins.forEach((k, v) -> y.set(id + ".pins." + k, v));
            y.set(id + ".virtual", new ArrayList<>(p.virtual));
        }
        try {
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not save sort.yml", ex);
        }
    }
}
