package site.remotearc.employees.build;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import site.remotearc.employees.ArcEmployees;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The NPC Homes catalog: house blueprints an admin has picked for players to
 * choose from for their employees' homes. The files live in
 * plugins/ArcEmployees/blueprints/homes/ (so builders can load them by name
 * like any blueprint), described in homes.yml there:
 * <pre>
 * homes:
 *   cottage:
 *     file: home-cottage.schem
 *     name: Cozy Cottage
 *     icon: OAK_DOOR
 *     style: cottage
 *     description: A snug one-room home with a chimney.
 * </pre>
 * A house file dropped into the folder without an entry is listed too, named after its file.
 */
public final class HomeCatalog {

    public static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");
    /** How many houses of their own one player may upload. */
    public static final int MAX_PERSONAL = 20;

    public record Entry(String id, String file, String name, Material icon, String style, String description) {}

    private final ArcEmployees plugin;
    private final File dir;
    private final File index;
    /** Players' own houses: p-&lt;uuid&gt;-&lt;name&gt;.ext, only shown to that player. */
    private final File personalDir;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public HomeCatalog(ArcEmployees plugin) {
        this.plugin = plugin;
        this.dir = new File(new File(plugin.getDataFolder(), "blueprints"), "homes");
        this.index = new File(dir, "homes.yml");
        this.personalDir = new File(dir, "players");
        if (!personalDir.isDirectory() && !personalDir.mkdirs()) plugin.getLogger().warning("Could not create " + personalDir);
        if (!dir.isDirectory() && !dir.mkdirs()) plugin.getLogger().warning("Could not create " + dir);
        reload();
    }

    public File dir() {
        return dir;
    }

    public void reload() {
        entries.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(index);
        ConfigurationSection homes = y.getConfigurationSection("homes");
        if (homes != null) {
            for (String id : homes.getKeys(false)) {
                ConfigurationSection s = homes.getConfigurationSection(id);
                if (s == null) continue;
                String file = s.getString("file");
                if (file == null || !new File(dir, file).isFile()) {
                    plugin.getLogger().warning("NPC home '" + id + "' in homes.yml has no file " + file + " in " + dir + "; skipped.");
                    continue;
                }
                Material icon = Material.matchMaterial(s.getString("icon", "OAK_DOOR"));
                if (icon == null || !icon.isItem()) icon = Material.OAK_DOOR;
                entries.put(id, new Entry(id, file, s.getString("name", id), icon, s.getString("style", ""), s.getString("description", "")));
            }
        }
        // House files put in the folder by hand, without an entry.
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!f.isFile() || !BlueprintLoader.supported(f.getName())) continue;
            boolean listed = entries.values().stream().anyMatch(en -> en.file().equalsIgnoreCase(f.getName()));
            if (listed) continue;
            String base = f.getName().substring(0, f.getName().lastIndexOf('.'));
            String id = base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
            if (id.startsWith("home-")) id = id.substring(5);
            if (id.isEmpty() || entries.containsKey(id)) continue;
            entries.put(id, new Entry(id, f.getName(), base, Material.OAK_DOOR, "", ""));
        }
    }

    public List<Entry> list() {
        return new ArrayList<>(entries.values());
    }

    public Entry get(String id) {
        return id == null ? null : entries.get(id.toLowerCase(Locale.ROOT));
    }

    // ── players' own houses ───────────────────────────────────────────────

    public File personalDir() {
        return personalDir;
    }

    private static String prefix(UUID player) {
        return "p-" + player.toString().replace("-", "") + "-";
    }

    /** The name a player sees for one of their own house files. */
    public static String personalName(String file) {
        String n = file.startsWith("p-") && file.length() > 35 ? file.substring(35) : file;
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    /** A player's own house files, by name. */
    public List<File> personal(UUID player) {
        List<File> out = new ArrayList<>();
        File[] files = personalDir.listFiles();
        if (files == null) return out;
        String pre = prefix(player);
        for (File f : files) if (f.isFile() && f.getName().startsWith(pre) && BlueprintLoader.supported(f.getName())) out.add(f);
        out.sort(java.util.Comparator.comparing(f -> f.getName().toLowerCase(Locale.ROOT)));
        return out;
    }

    /** One of the player's own houses by name (or file name), or null. */
    public File personalFile(UUID player, String name) {
        if (name == null) return null;
        for (File f : personal(player)) {
            if (f.getName().equalsIgnoreCase(name) || personalName(f.getName()).equalsIgnoreCase(name)) return f;
        }
        return null;
    }

    /** Moves an uploaded house into the player's own houses (replacing one with the same name). */
    public File addPersonal(UUID player, File src) throws IOException {
        String n = src.getName();
        int dot = n.lastIndexOf('.');
        String base = n.substring(0, dot).replaceAll("[^A-Za-z0-9_-]", "_");
        File old = personalFile(player, base);
        if (old != null) Files.deleteIfExists(old.toPath());
        File target = new File(personalDir, prefix(player) + base + n.substring(dot).toLowerCase(Locale.ROOT));
        Files.move(src.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    public boolean removePersonal(UUID player, String name) throws IOException {
        File f = personalFile(player, name);
        return f != null && Files.deleteIfExists(f.toPath());
    }

    /** What's wrong with a blueprint as a home (no bed, no door), or null if it will do. */
    public static String problem(Blueprint bp) {
        boolean bed = false, door = false;
        for (BlockData d : bp.palette) {
            bed |= Tag.BEDS.isTagged(d.getMaterial());
            door |= Tag.DOORS.isTagged(d.getMaterial());
        }
        if (!bed && !door) return "it has no bed and no door";
        if (!bed) return "it has no bed";
        if (!door) return "it has no door";
        return null;
    }

    /** The blueprint name a catalog house is loaded by (see Blueprints.HOMES). */
    public static String blueprintName(Entry en) {
        return Blueprints.HOMES + en.file();
    }

    /** The blueprint name one of a player's own houses is loaded by (see Blueprints.PLAYERS). */
    public static String personalBlueprintName(File f) {
        return Blueprints.PLAYERS + f.getName();
    }

    /**
     * Adds a blueprint to the catalog (copied in as home-&lt;id&gt;.ext). The caller
     * has already checked it with {@link #problem}. Replacing a house keeps its
     * name, icon, style and description unless new ones are given (null = keep).
     */
    public Entry add(String id, File source, String name, Material icon, String style, String description) throws IOException {
        Entry old = entries.get(id);
        String n = source.getName();
        String file = "home-" + id + n.substring(n.lastIndexOf('.')).toLowerCase(Locale.ROOT);
        Files.copy(source.toPath(), new File(dir, file).toPath(), StandardCopyOption.REPLACE_EXISTING);
        if (old != null && !old.file().equals(file)) Files.deleteIfExists(new File(dir, old.file()).toPath());   // was another format
        Entry en = new Entry(id, file,
                name != null ? name : old != null ? old.name() : id,
                icon != null ? icon : old != null ? old.icon() : Material.OAK_DOOR,
                style != null ? style : old != null ? old.style() : "",
                description != null ? description : old != null ? old.description() : "");
        entries.put(id, en);
        save();
        return en;
    }

    /** Takes a house out of the catalog (and its file). Homes already placed with it keep it: each is built from its own copy. */
    public boolean remove(String id) throws IOException {
        Entry en = entries.remove(id);
        if (en == null) return false;
        Files.deleteIfExists(new File(dir, en.file()).toPath());
        save();
        return true;
    }

    /** Changes a catalog house's name, icon, style or description. */
    public void update(Entry en) throws IOException {
        entries.put(en.id(), en);
        save();
    }

    private void save() throws IOException {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of("The NPC Homes catalog: houses players pick for their employees. Manage with /employees homes."));
        for (Entry en : entries.values()) {
            String k = "homes." + en.id() + ".";
            y.set(k + "file", en.file());
            y.set(k + "name", en.name());
            y.set(k + "icon", en.icon().name());
            if (!en.style().isEmpty()) y.set(k + "style", en.style());
            if (!en.description().isEmpty()) y.set(k + "description", en.description());
        }
        y.save(index);
    }
}
