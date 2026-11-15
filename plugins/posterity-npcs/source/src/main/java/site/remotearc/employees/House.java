package site.remotearc.employees;

import org.bukkit.Bukkit;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import site.remotearc.employees.util.BlockPos;

/**
 * An employee's home: its bed (the head half) and its front door. Without one
 * an employee is homeless: it works, but unhappily and slower
 * ({@code homes.homeless-work-speed}). For now a home is claimed by pointing at
 * a bed with a door near it ({@code /employees house}); building homes from
 * blueprints comes later.
 */
public final class House {

    /** How far from the bed its door may be. */
    public static final int DOOR_RANGE = 8;

    public final String world;
    public final BlockPos bed;
    public final BlockPos door;
    public final long since;

    public House(String world, BlockPos bed, BlockPos door, long since) {
        this.world = world;
        this.bed = bed;
        this.door = door;
        this.since = since;
    }

    public World bukkitWorld() {
        return Bukkit.getWorld(world);
    }

    /** False once the bed has been broken (checked only while its chunk is loaded); true if it can't be checked. */
    public boolean bedStanding() {
        World w = bukkitWorld();
        if (w == null || !w.isChunkLoaded(bed.x() >> 4, bed.z() >> 4)) return true;
        return Tag.BEDS.isTagged(bed.block(w).getType());
    }

    public String describe() {
        return "bed at " + bed.x() + " " + bed.y() + " " + bed.z() + (world.equals("world") ? "" : " (" + world + ")");
    }

    String save() {
        return world + "|" + bed + "|" + door + "|" + since;
    }

    static House load(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            String[] a = s.split("\\|");
            return new House(a[0], BlockPos.parse(a[1]), BlockPos.parse(a[2]), Long.parseLong(a[3]));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** The head half of a bed (where a sleeper's head goes), or null if it isn't a bed. */
    public static Block bedHead(Block b) {
        if (b == null || !Tag.BEDS.isTagged(b.getType()) || !(b.getBlockData() instanceof org.bukkit.block.data.type.Bed bed)) return null;
        // A whole bed only: a lone head (no foot) isn't one.
        BlockFace f = bed.getFacing();
        if (bed.getPart() == org.bukkit.block.data.type.Bed.Part.HEAD) {
            return Tag.BEDS.isTagged(b.getRelative(f.getOppositeFace()).getType()) ? b : null;
        }
        Block head = b.getRelative(f);
        return Tag.BEDS.isTagged(head.getType()) ? head : null;
    }

    /** The nearest door to a bed within {@link #DOOR_RANGE}, or null. */
    public static Block doorNear(Block bed) {
        Block best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -DOOR_RANGE; dx <= DOOR_RANGE; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -DOOR_RANGE; dz <= DOOR_RANGE; dz++) {
                    Block b = bed.getRelative(dx, dy, dz);
                    if (!Tag.DOORS.isTagged(b.getType())) continue;
                    // The bottom half stands for the door.
                    if (b.getBlockData() instanceof org.bukkit.block.data.Bisected h && h.getHalf() == org.bukkit.block.data.Bisected.Half.TOP) {
                        b = b.getRelative(BlockFace.DOWN);
                    }
                    double d = dx * dx + dy * dy + dz * dz;
                    if (d < bestD) {
                        bestD = d;
                        best = b;
                    }
                }
            }
        }
        return best;
    }
}
