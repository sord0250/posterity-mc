package site.remotearc.employees.market;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.plugin.Plugin;
import site.remotearc.employees.ArcEmployees;

import java.io.File;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Reads which materials a player has discovered from ArcShop's database
 * (plugins/ArcShop/data.db, table "discovered"), so merchants follow the
 * same rule as /shop. Read-only, cached for a minute per player. ArcShop
 * bundles the SQLite driver, so it's borrowed from ArcShop's class loader.
 * If anything is missing, everything counts as discovered.
 */
final class ArcShopDiscovery {

    private record Cached(Set<Material> materials, long at) {}

    private final ArcEmployees plugin;
    private final Map<UUID, Cached> cache = new HashMap<>();
    private Driver driver;
    private boolean broken;

    ArcShopDiscovery(ArcEmployees plugin) {
        this.plugin = plugin;
    }

    boolean discovered(UUID player, Material m) {
        Set<Material> set = load(player);
        return set == null || set.contains(m);
    }

    private Set<Material> load(UUID player) {
        Cached c = cache.get(player);
        if (c != null && System.currentTimeMillis() - c.at() < 60_000) return c.materials();
        if (broken) return null;
        File db = new File(plugin.getDataFolder().getParentFile(), "ArcShop/data.db");
        Plugin arc = Bukkit.getPluginManager().getPlugin("ArcShop");
        if (!db.exists() || arc == null) return null;
        try {
            if (driver == null) {
                Class<?> cls = Class.forName("org.sqlite.JDBC", true, arc.getClass().getClassLoader());
                driver = (Driver) cls.getConstructor().newInstance();
            }
            Set<Material> out = EnumSet.noneOf(Material.class);
            try (Connection con = driver.connect("jdbc:sqlite:" + db.getAbsolutePath(), new Properties());
                 PreparedStatement st = con.prepareStatement("SELECT material FROM discovered WHERE player = ?")) {
                st.setString(1, player.toString());
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        Material m = Material.matchMaterial(rs.getString(1));
                        if (m != null) out.add(m);
                    }
                }
            }
            cache.put(player, new Cached(out, System.currentTimeMillis()));
            return out;
        } catch (Exception | LinkageError ex) {
            broken = true;
            plugin.getLogger().log(Level.WARNING, "Couldn't read ArcShop's discoveries; merchants will sell everything in its catalog.", ex);
            return null;
        }
    }

    /** Forget the cached list (after an unlock or lock). */
    void invalidate(UUID player) {
        cache.remove(player);
    }

    /**
     * Unlocks (or locks) a material for a player in /shop, through ArcShop's
     * own store so its cache stays right. Returns an error, or null when done.
     */
    String set(UUID player, Material m, boolean discovered) {
        Plugin arc = Bukkit.getPluginManager().getPlugin("ArcShop");
        if (arc == null || !arc.isEnabled()) return "ArcShop isn't running.";
        try {
            Object store = arc.getClass().getMethod("store").invoke(arc);
            if (discovered) {
                store.getClass().getMethod("discover", UUID.class, Material.class, String.class).invoke(store, player, m, "admin");
            } else {
                // ArcShop has no "forget": delete the row with its own connection and drop it from its cache.
                java.lang.reflect.Field connF = store.getClass().getDeclaredField("conn");
                java.lang.reflect.Field cacheF = store.getClass().getDeclaredField("cache");
                connF.setAccessible(true);
                cacheF.setAccessible(true);
                Connection con = (Connection) connF.get(store);
                try (PreparedStatement st = con.prepareStatement("DELETE FROM discovered WHERE player = ? AND material = ?")) {
                    st.setString(1, player.toString());
                    st.setString(2, m.name());
                    st.executeUpdate();
                }
                Object cached = ((Map<?, ?>) cacheF.get(store)).get(player);
                if (cached instanceof Set<?> set) set.remove(m);
            }
            invalidate(player);
            return null;
        } catch (ReflectiveOperationException | java.sql.SQLException | RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING, "Couldn't change ArcShop's discoveries", ex);
            return "Couldn't change ArcShop's discoveries (see the server log): this ArcShop version may work differently.";
        }
    }
}
