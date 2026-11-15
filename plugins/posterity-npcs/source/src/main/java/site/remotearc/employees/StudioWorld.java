package site.remotearc.employees;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.io.File;

/**
 * ArcShop only loads its studio world when someone types /studio, so after a
 * restart a player who logged off there came back in the main world at the
 * same spot, often inside rock. The studio is loaded at startup (ArcShop's way,
 * so it stays a flat creative world) if it's ever been made, and anyone who
 * still joins inside blocks is lifted to the surface.
 */
final class StudioWorld implements Listener {

    private final ArcEmployees plugin;

    StudioWorld(ArcEmployees plugin) {
        this.plugin = plugin;
    }

    /** Loads the studio if it exists on disk and isn't loaded. */
    void load() {
        Plugin shop = Bukkit.getPluginManager().getPlugin("ArcShop");
        if (shop == null || !shop.isEnabled()) return;
        try {
            Object studio = shop.getClass().getMethod("studio").invoke(shop);
            String name = (String) studio.getClass().getMethod("worldName").invoke(studio);
            if (Bukkit.getWorld(name) != null) return;
            File root = Bukkit.getWorldContainer();
            File main = new File(root, Bukkit.getWorlds().get(0).getName());
            boolean made = new File(main, "dimensions/minecraft/" + name).isDirectory() || new File(root, name).isDirectory();
            if (!made) return;
            studio.getClass().getMethod("world").invoke(studio);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            plugin.getLogger().warning("Couldn't load ArcShop's studio world at startup: " + ex);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent ev) {
        Player p = ev.getPlayer();
        Location l = p.getLocation();
        if (!stuck(l)) return;
        World w = l.getWorld();
        Block top = w.getHighestBlockAt(l.getBlockX(), l.getBlockZ());
        Location up = top.getLocation().add(0.5, 1, 0.5);
        up.setYaw(l.getYaw());
        up.setPitch(l.getPitch());
        if (stuck(up)) up = w.getSpawnLocation();
        p.teleport(up);
        plugin.getLogger().info(p.getName() + " joined inside blocks at " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ() + " in " + w.getName()
                + "; moved up to " + up.getBlockX() + " " + up.getBlockY() + " " + up.getBlockZ());
    }

    private static boolean stuck(Location l) {
        Block feet = l.getBlock(), head = feet.getRelative(0, 1, 0);
        return solid(feet) || solid(head);
    }

    private static boolean solid(Block b) {
        Material m = b.getType();
        return m.isOccluding() || m == Material.LAVA;
    }
}
