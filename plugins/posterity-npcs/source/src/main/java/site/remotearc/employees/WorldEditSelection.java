package site.remotearc.employees;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.World;
import org.bukkit.entity.Player;
import site.remotearc.employees.util.WorkArea;

/**
 * Reads a player's WorldEdit selection (wooden axe). Only loaded when
 * WorldEdit is enabled, so the plugin still runs without it.
 */
public final class WorldEditSelection {

    /** How far above the selection's bottom a work area reaches at least, so a flat ground selection still covers what grows on it. */
    static final int MIN_HEIGHT = 7;

    private WorldEditSelection() {}

    /** The selection's bounding box as a work area; throws IllegalStateException with a player-facing reason. */
    static WorkArea of(Player p) {
        WorkArea a = exact(p);
        int top = Math.max(a.maxY(), a.minY() + MIN_HEIGHT - 1);
        return WorkArea.of(a.world(), a.minX(), a.minY(), a.minZ(), a.maxX(), top, a.maxZ());
    }

    /** The selection's bounding box exactly as selected. */
    public static WorkArea exact(Player p) {
        LocalSession session = WorldEdit.getInstance().getSessionManager().get(BukkitAdapter.adapt(p));
        World world = session.getSelectionWorld();
        if (world == null) throw new IllegalStateException("You have no WorldEdit selection. Select two corners with the wooden axe (//wand).");
        Region region;
        try {
            region = session.getSelection(world);
        } catch (IncompleteRegionException ex) {
            throw new IllegalStateException("Your WorldEdit selection is incomplete: set both corners with the wooden axe.");
        }
        BlockVector3 min = region.getMinimumPoint(), max = region.getMaximumPoint();
        return WorkArea.of(BukkitAdapter.adapt(world).getName(), min.x(), min.y(), min.z(), max.x(), max.y(), max.z());
    }
}
