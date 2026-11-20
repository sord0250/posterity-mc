package site.remotearc.employees.job;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;
import site.remotearc.employees.util.BlockPos;
import site.remotearc.employees.util.WorkArea;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds and measures trees for the lumberjack.
 *
 * A "tree" is a trunk of one natural log type standing on soil, connected to
 * at least a few NATURAL leaves. Player-placed leaves are always persistent,
 * so log builds (cabins, fences of logs) are never mistaken for trees, even
 * inside the work area. Mangroves are left alone: their roots and propagules
 * do not fit the cut-and-replant cycle.
 */
final class Trees {

    static final Map<Material, Material> SAPLING_FOR_LOG = new EnumMap<>(Map.of(
            Material.OAK_LOG, Material.OAK_SAPLING,
            Material.SPRUCE_LOG, Material.SPRUCE_SAPLING,
            Material.BIRCH_LOG, Material.BIRCH_SAPLING,
            Material.JUNGLE_LOG, Material.JUNGLE_SAPLING,
            Material.ACACIA_LOG, Material.ACACIA_SAPLING,
            Material.DARK_OAK_LOG, Material.DARK_OAK_SAPLING,
            Material.CHERRY_LOG, Material.CHERRY_SAPLING,
            Material.PALE_OAK_LOG, Material.PALE_OAK_SAPLING));

    private static final int MAX_LOGS = 400;
    private static final int MAX_LEAVES = 2500;
    private static final int MAX_REACH_XZ = 10;
    private static final int MAX_HEIGHT = 40;
    private static final int MIN_NATURAL_LEAVES = 4;

    private static final int[][] FACES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    record Tree(Material log, Material sapling, List<BlockPos> bases, List<BlockPos> logs, List<BlockPos> leaves) {}

    private Trees() {}

    /**
     * Ground a sapling grows on. In 26.x #minecraft:dirt is only dirt, coarse
     * and rooted dirt; grass, podzol, moss and mud moved to other tags, all
     * gathered (with farmland) in #supports_vegetation.
     */
    static boolean isSoil(Material m) {
        return Tag.SUPPORTS_VEGETATION.isTagged(m);
    }

    static boolean isSapling(Material m) {
        return Tag.SAPLINGS.isTagged(m) && m != Material.MANGROVE_PROPAGULE;
    }

    /**
     * Looks at one column for a trunk base (logs on soil) or a sapling planting
     * spot. Called a column at a time so a huge area is spread over many ticks
     * instead of scanned all at once.
     */
    static void scanColumn(World w, WorkArea a, Map<BlockPos, Material> spots, int x, int z, List<BlockPos> basesOut) {
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return;
        Material below = w.getType(x, a.minY() - 1, z);
        for (int y = a.minY(); y <= a.maxY(); y++) {
            Material m = w.getType(x, y, z);
            if (isSoil(below)) {
                if (SAPLING_FOR_LOG.containsKey(m)) basesOut.add(new BlockPos(x, y, z));
                else if (isSapling(m)) spots.putIfAbsent(new BlockPos(x, y, z), m);
            }
            below = m;
        }
    }

    /** Forgets spots that can no longer hold a sapling; call once a full scan pass finishes. */
    static void pruneSpots(World w, WorkArea a, Map<BlockPos, Material> spots) {
        spots.keySet().removeIf(p -> !a.contains(p)
                || (w.isChunkLoaded(p.x() >> 4, p.z() >> 4) && !isSoil(w.getType(p.x(), p.y() - 1, p.z()))));
    }

    /** Measures the tree whose trunk includes {@code start}, or returns null if it is not a tree. */
    static Tree measure(World w, BlockPos start) {
        Material log = w.getType(start.x(), start.y(), start.z());
        Material sapling = SAPLING_FOR_LOG.get(log);
        if (sapling == null) return null;

        List<BlockPos> logs = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start.key());
        while (!queue.isEmpty()) {
            BlockPos p = queue.poll();
            logs.add(p);
            if (logs.size() > MAX_LOGS) return null;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos q = p.offset(dx, dy, dz);
                        if (Math.abs(q.x() - start.x()) > MAX_REACH_XZ || Math.abs(q.z() - start.z()) > MAX_REACH_XZ) continue;
                        if (q.y() < start.y() - 2 || q.y() > start.y() + MAX_HEIGHT) continue;
                        if (!seen.add(q.key())) continue;
                        if (!w.isChunkLoaded(q.x() >> 4, q.z() >> 4)) continue;
                        if (w.getType(q.x(), q.y(), q.z()) == log) queue.add(q);
                    }
                }
            }
        }

        int bottom = logs.stream().mapToInt(BlockPos::y).min().orElse(start.y());
        List<BlockPos> bases = new ArrayList<>();
        for (BlockPos p : logs) {
            if (p.y() == bottom && isSoil(w.getType(p.x(), p.y() - 1, p.z()))) bases.add(p);
        }
        if (bases.isEmpty()) return null;

        List<BlockPos> leaves = leavesOf(w, logs);
        if (leaves.size() < MIN_NATURAL_LEAVES) return null;

        BlockPos origin = bases.getFirst();
        logs.sort(Comparator.<BlockPos>comparingInt(BlockPos::y)
                .thenComparingInt(p -> Math.abs(p.x() - origin.x()) + Math.abs(p.z() - origin.z())));
        return new Tree(log, sapling, bases, logs, leaves);
    }

    /**
     * The natural leaves that belong to these logs: reached through leaves only,
     * where each leaf's own distance-to-nearest-log equals its distance from
     * this tree. A leaf whose distance is smaller is closer to some other tree's
     * logs, so it is left for that tree.
     */
    private static List<BlockPos> leavesOf(World w, List<BlockPos> logs) {
        List<BlockPos> out = new ArrayList<>();
        Map<Long, Integer> depth = new HashMap<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        for (BlockPos l : logs) {
            depth.put(l.key(), 0);
            queue.add(l);
        }
        while (!queue.isEmpty() && out.size() < MAX_LEAVES) {
            BlockPos p = queue.poll();
            int d = depth.get(p.key());
            if (d >= 6) continue;
            for (int[] f : FACES) {
                BlockPos q = p.offset(f[0], f[1], f[2]);
                if (depth.containsKey(q.key())) continue;
                if (!w.isChunkLoaded(q.x() >> 4, q.z() >> 4)) continue;
                BlockData data = w.getBlockData(q.x(), q.y(), q.z());
                if (data instanceof Leaves leaf && !leaf.isPersistent() && leaf.getDistance() == d + 1) {
                    depth.put(q.key(), d + 1);
                    out.add(q);
                    queue.add(q);
                }
            }
        }
        return out;
    }

    static boolean isNaturalLeaf(Block b) {
        return b.getBlockData() instanceof Leaves leaf && !leaf.isPersistent();
    }

    /** Whether a sapling can go here now: open space (no liquid) on soil. */
    static boolean canPlant(World w, BlockPos p) {
        if (!w.isChunkLoaded(p.x() >> 4, p.z() >> 4)) return false;
        Block b = p.block(w);
        if (b.isLiquid() || !(b.getType().isAir() || (b.isReplaceable() && !isSapling(b.getType())))) return false;
        return isSoil(w.getType(p.x(), p.y() - 1, p.z()));
    }
}
