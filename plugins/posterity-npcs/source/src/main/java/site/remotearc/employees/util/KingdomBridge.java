package site.remotearc.employees.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.UUID;

/**
 * Which kingdom (PosterityKingdoms) a player owns or rules, if it's installed:
 * tags a newly hired employee with its kingdom automatically. Reached by
 * reflection, so neither plugin needs the other.
 */
public final class KingdomBridge {

    private MethodHandle kingdomOf;
    private boolean looked;

    /** The kingdom this player owns or rules, or null (not in one, or the plugin isn't installed). */
    public String kingdomOf(UUID player) {
        if (!looked) {
            looked = true;
            Plugin p = Bukkit.getPluginManager().getPlugin("PosterityKingdoms");
            if (p != null) {
                try {
                    kingdomOf = MethodHandles.publicLookup().findStatic(p.getClass(), "kingdomOf",
                            MethodType.methodType(String.class, UUID.class));
                } catch (ReflectiveOperationException ignored) {
                    kingdomOf = null;
                }
            }
        }
        if (kingdomOf == null) return null;
        try {
            return (String) kingdomOf.invokeExact(player);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Look PosterityKingdoms up again (after a reload). */
    public void reset() {
        looked = false;
        kingdomOf = null;
    }
}
