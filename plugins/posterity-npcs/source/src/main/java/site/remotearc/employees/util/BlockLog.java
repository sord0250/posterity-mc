package site.remotearc.employees.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.Plugin;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.Employee;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.logging.Level;

/**
 * Logs employees' block changes and chest visits to CoreProtect, under
 * "#Name" (coreprotect.user-prefix + the employee's name), so /co inspect
 * shows who did it and /co rollback u:#Ned t:2h undoes a miner's work.
 * Employees change blocks directly, without block events, so CoreProtect
 * would otherwise never see them. Reached by reflection: nothing happens
 * without CoreProtect.
 */
public final class BlockLog {

    private final ArcEmployees plugin;
    private Object api;
    private MethodHandle placement, removal, container;
    private boolean looked, announced;

    public BlockLog(ArcEmployees plugin) {
        this.plugin = plugin;
    }

    /** Forget the API (after a reload), so it is looked up again on next use. */
    public void reset() {
        looked = false;
        api = null;
    }

    private boolean ready() {
        if (!plugin.settings().coreProtect) return false;
        if (!looked) {
            looked = true;
            Plugin cp = Bukkit.getPluginManager().getPlugin("CoreProtect");
            if (cp == null || !cp.isEnabled()) return false;
            try {
                Object a = cp.getClass().getMethod("getAPI").invoke(cp);
                Class<?> c = a.getClass();
                if (!(boolean) c.getMethod("isEnabled").invoke(a) || (int) c.getMethod("APIVersion").invoke(a) < 9) return false;
                MethodHandles.Lookup l = MethodHandles.publicLookup();
                MethodType blockChange = MethodType.methodType(boolean.class, String.class, Location.class, Material.class, BlockData.class);
                placement = l.findVirtual(c, "logPlacement", blockChange);
                removal = l.findVirtual(c, "logRemoval", blockChange);
                container = l.findVirtual(c, "logContainerTransaction", MethodType.methodType(boolean.class, String.class, Location.class));
                api = a;
                if (!announced) plugin.getLogger().info("Logging employees' block changes to CoreProtect.");
                announced = true;
            } catch (ReflectiveOperationException | RuntimeException ex) {
                plugin.getLogger().log(Level.WARNING, "CoreProtect is here but its API couldn't be used; employee changes won't be logged", ex);
            }
        }
        return api != null;
    }

    /** The CoreProtect user an employee's changes are logged under. */
    public String user(Employee e) {
        return plugin.settings().coreProtectPrefix + e.name.replaceAll("\\s", "");
    }

    /** Call before a block is broken or replaced. */
    public void removing(Employee e, Block b) {
        if (b.getType().isAir() || !ready()) return;
        call(removal, user(e), b.getLocation(), b.getType(), b.getBlockData());
    }

    /** Call after a block is placed. */
    public void placed(Employee e, Block b) {
        if (b.getType().isAir() || !ready()) return;
        call(placement, user(e), b.getLocation(), b.getType(), b.getBlockData());
    }

    /** Call after a player's block is placed by the plugin for them (so it's logged under their name). */
    public void placed(org.bukkit.entity.Player p, Block b) {
        if (b.getType().isAir() || !ready()) return;
        call(placement, p.getName(), b.getLocation(), b.getType(), b.getBlockData());
    }

    /** Call before items go into or come out of a container. */
    public void container(Employee e, Location at) {
        if (at == null || !ready()) return;
        try {
            container.invoke(api, user(e), at);
        } catch (Throwable ex) {
            failed(ex);
        }
    }

    private void call(MethodHandle h, String user, Location at, Material type, BlockData data) {
        try {
            h.invoke(api, user, at, type, data);
        } catch (Throwable ex) {
            failed(ex);
        }
    }

    private void failed(Throwable ex) {
        plugin.getLogger().log(Level.WARNING, "CoreProtect logging failed; turning it off until the next reload", ex);
        api = null;
    }
}
