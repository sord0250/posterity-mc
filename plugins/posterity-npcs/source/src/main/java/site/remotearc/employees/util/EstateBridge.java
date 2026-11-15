package site.remotearc.employees.util;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.UUID;

/**
 * Land ownership from ArcEstate, if it's installed: an employee only works
 * blocks outside every plot, or in plots its owner owns or is trusted on.
 * Reached by reflection (ArcEstate.mayWork), so neither plugin needs the other.
 */
public final class EstateBridge {

    private MethodHandle mayWork;
    private boolean looked;

    public boolean mayWork(UUID owner, World world, int x, int y, int z) {
        if (!looked) {
            looked = true;
            Plugin p = Bukkit.getPluginManager().getPlugin("ArcEstate");
            if (p != null) {
                try {
                    mayWork = MethodHandles.publicLookup().findStatic(p.getClass(), "mayWork",
                            MethodType.methodType(boolean.class, UUID.class, World.class, int.class, int.class, int.class));
                } catch (ReflectiveOperationException ignored) {
                    mayWork = null;
                }
            }
        }
        if (mayWork == null) return true;
        try {
            return (boolean) mayWork.invokeExact(owner, world, x, y, z);
        } catch (Throwable t) {
            return true;
        }
    }

    /** Look ArcEstate up again (after a reload). */
    public void reset() {
        looked = false;
        mayWork = null;
    }
}
