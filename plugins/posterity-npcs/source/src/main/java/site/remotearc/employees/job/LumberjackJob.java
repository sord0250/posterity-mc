package site.remotearc.employees.job;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import site.remotearc.employees.storage.Supply;
import site.remotearc.employees.util.BlockPos;
import site.remotearc.employees.util.Items;
import site.remotearc.employees.util.WorkArea;

import java.util.List;
import java.util.Map;

/**
 * Auto tree farm: fells every grown tree in the work area (logs, then its own
 * leaves), replants the trunk's spot(s) with the matching sapling, and keeps a
 * reserve of saplings for that. Saplings players plant in the area become
 * spots the lumberjack keeps replanting.
 */
final class LumberjackJob implements Job {

    private List<BlockPos> bases = List.of();
    private String idle = "Looking for trees";

    @Override
    public Task next(Worker w) {
        WorkArea a = w.e.area;
        World world = w.world();
        if (a == null || !a.world().equals(world.getName())) {
            idle = "No work area in this world";
            return null;
        }
        if (w.cooldownReady("tree-scan", 100)) {
            bases = Trees.scan(world, a, w.e.spots);
            bases.removeIf(p -> !w.mayWork(p));
            w.e.spots.keySet().removeIf(p -> !w.mayWork(p));
        }
        if (w.settings().lumberAutoPlant && w.cooldownReady("tree-layout", w.e.spots.isEmpty() || wantSaplings ? 100 : 600)) layout(w, world, a);

        BlockPos tree = null;
        double best = Double.MAX_VALUE;
        for (BlockPos b : bases) {
            if (!Trees.SAPLING_FOR_LOG.containsKey(world.getType(b.x(), b.y(), b.z()))) continue;
            if (w.isUnreachable(b)) continue;
            double d = b.distanceSq(w.e.location.getX(), w.e.location.getY(), w.e.location.getZ());
            if (d < best) {
                best = d;
                tree = b;
            }
        }
        if (tree != null) return new FellTreeTask(tree);

        BlockPos plant = null;
        best = Double.MAX_VALUE;
        boolean missingSaplings = false;
        for (Map.Entry<BlockPos, Material> spot : w.e.spots.entrySet()) {
            BlockPos p = spot.getKey();
            if (!Trees.canPlant(world, p)) continue;
            if (Items.count(w.e.backpack, spot.getValue()) == 0) {
                missingSaplings = true;
                continue;
            }
            if (w.isUnreachable(p)) continue;
            double d = p.distanceSq(w.e.location.getX(), w.e.location.getY(), w.e.location.getZ());
            if (d < best) {
                best = d;
                plant = p;
            }
        }
        if (plant != null) return new PlantSaplingsTask(plant);

        if ((missingSaplings || wantSaplings || (w.e.spots.isEmpty() && bases.isEmpty())) && w.cooldownReady("sapling-fetch", 20 * 30)) {
            Task fetch = fetchSaplings(w);
            if (fetch != null) return fetch;
        }
        if (missingSaplings) idle = "Out of saplings: none in its backpack or your employees' chests";
        else if (w.e.spots.isEmpty() && w.settings().lumberAutoPlant) {
            boolean hasSaplings = false;
            for (ItemStack s : w.e.backpack.getStorageContents()) if (s != null && (SINGLE.contains(s.getType()) || SQUARE.contains(s.getType()))) hasSaplings = true;
            idle = hasSaplings ? "No open ground to plant on in the work area (needs dirt or grass with room above)"
                    : "No saplings to plant (backpack and chests are empty)";
        } else if (w.e.spots.isEmpty()) idle = "No trees or saplings in the work area";
        else idle = "Waiting for trees to grow";
        return null;
    }

    @Override
    public String idleStatus(Worker w) {
        return idle;
    }

    @Override
    public boolean isReserved(Material m) {
        return Tag.SAPLINGS.isTagged(m);
    }

    @Override
    public boolean collects(Material m) {
        return Tag.SAPLINGS.isTagged(m) || Tag.LOGS.isTagged(m) || m == Material.STICK || m == Material.APPLE;
    }

    /** Saplings that grow from a single one; dark and pale oak need four in a square. */
    private static final java.util.Set<Material> SINGLE = java.util.EnumSet.of(Material.OAK_SAPLING, Material.BIRCH_SAPLING,
            Material.SPRUCE_SAPLING, Material.JUNGLE_SAPLING, Material.ACACIA_SAPLING, Material.CHERRY_SAPLING);
    private static final java.util.Set<Material> SQUARE = java.util.EnumSet.of(Material.DARK_OAK_SAPLING, Material.PALE_OAK_SAPLING);

    /**
     * Lays out new planting spots on open ground in rows (spacing apart
     * within a row, row-spacing between rows), keeping clear of trees and
     * spots already there. Uses the sapling kind it has most of (a 2x2 square for dark and
     * pale oak). The spots are then planted and replanted like any other.
     */
    /** Set when the last layout found more open ground than it had saplings for: fetch more. */
    private boolean wantSaplings;

    private void layout(Worker w, World world, WorkArea a) {
        wantSaplings = false;
        Map<Material, Integer> have = new java.util.EnumMap<>(Material.class);
        for (ItemStack s : w.e.backpack.getStorageContents()) {
            if (Items.isPlain(s) && (SINGLE.contains(s.getType()) || SQUARE.contains(s.getType()))) have.merge(s.getType(), s.getAmount(), Integer::sum);
        }
        // Spots that are empty right now use some up (not ones with a sapling or tree on them).
        for (Map.Entry<BlockPos, Material> spot : w.e.spots.entrySet()) {
            if (Trees.canPlant(world, spot.getKey())) have.computeIfPresent(spot.getValue(), (k, v) -> v - 1);
        }
        // Single-trunk kinds first: they fit neat rows; 2x2 kinds only when that's all there is.
        Material sapling = null;
        int most = 0;
        for (boolean squares : new boolean[]{false, true}) {
            for (Map.Entry<Material, Integer> e : have.entrySet()) {
                if (SQUARE.contains(e.getKey()) != squares) continue;
                int usable = squares ? e.getValue() / 4 : e.getValue();
                if (usable > most) {
                    most = usable;
                    sapling = e.getKey();
                }
            }
            if (sapling != null) break;
        }
        if (sapling == null) {
            sapling = Material.OAK_SAPLING;   // only to see whether there is ground worth fetching saplings for
            most = 0;
        }
        boolean square = SQUARE.contains(sapling);
        // Rows along x: saplings `spacing` apart in a row, rows `row-spacing` apart, with a walkway between.
        int along = w.settings().lumberSpacing + (square ? 1 : 0), between = w.settings().lumberRowSpacing + (square ? 1 : 0);
        int added = 0;
        for (int z = a.minZ() + 1; z + (square ? 1 : 0) <= a.maxZ() - 1 && !wantSaplings; z += between) {
            for (int x = a.minX() + 1; x + (square ? 1 : 0) <= a.maxX() - 1 && !wantSaplings; x += along) {
                if (!world.isChunkLoaded(x >> 4, z >> 4) || crowded(w, world, x, z, Math.min(along, between) - 1)) continue;
                BlockPos p = ground(world, a, x, z);
                if (p == null) continue;
                List<BlockPos> cells = square ? List.of(p, p.offset(1, 0, 0), p.offset(0, 0, 1), p.offset(1, 0, 1)) : List.of(p);
                boolean ok = true;
                for (BlockPos c : cells) {
                    if (c.y() != p.y() || !Trees.canPlant(world, c) || !roomAbove(world, c) || !w.mayWork(c)) {
                        ok = false;
                        break;
                    }
                }
                if (!ok) continue;
                if (added >= most) {
                    wantSaplings = true;   // open ground left over
                    break;
                }
                for (BlockPos c : cells) w.e.spots.put(c, sapling);
                added++;
            }
        }
        if (added > 0) w.plugin().manager().markDirty();
    }

    /** A trunk, sapling or planting spot within this many blocks (horizontally). */
    private static boolean crowded(Worker w, World world, int x, int z, int r) {
        for (BlockPos s : w.e.spots.keySet()) {
            if (Math.abs(s.x() - x) < r && Math.abs(s.z() - z) < r) return true;
        }
        int y = world.getHighestBlockYAt(x, z);
        for (int dx = -r + 1; dx < r; dx++) {
            for (int dz = -r + 1; dz < r; dz++) {
                for (int dy = -6; dy <= 2; dy++) {
                    if (Tag.LOGS.isTagged(world.getType(x + dx, y + dy, z + dz))) return true;
                }
            }
        }
        return false;
    }

    /** The air block on top of the ground in this column (within the area), or null. */
    private static BlockPos ground(World world, WorkArea a, int x, int z) {
        int top = Math.min(a.maxY(), world.getHighestBlockYAt(x, z) + 1);
        for (int y = top; y > a.minY(); y--) {
            if (Trees.isSoil(world.getType(x, y - 1, z))) return new BlockPos(x, y, z);
            Material m = world.getType(x, y - 1, z);
            if (!m.isAir() && !Tag.LEAVES.isTagged(m) && !Tag.REPLACEABLE.isTagged(m)) return null;   // something else is the ground
        }
        return null;
    }

    /** Room for a tree to grow: nothing solid in the next five blocks up. */
    private static boolean roomAbove(World world, BlockPos p) {
        for (int dy = 1; dy <= 5; dy++) {
            Material m = world.getType(p.x(), p.y() + dy, p.z());
            if (m.isSolid() && !Tag.LEAVES.isTagged(m)) return false;
        }
        return true;
    }

    /** Saplings from its own chest or the team's, of the kinds its spots need (or any, for a bare area). */
    private static Task fetchSaplings(Worker w) {
        Map<Material, Integer> avail = Supply.available(w.plugin(), w.e);
        Map<Material, Integer> want = new java.util.EnumMap<>(Material.class);
        java.util.Set<Material> needed = new java.util.HashSet<>();
        for (Map.Entry<BlockPos, Material> spot : w.e.spots.entrySet()) {
            if (Trees.canPlant(w.world(), spot.getKey()) && Items.count(w.e.backpack, spot.getValue()) == 0) needed.add(spot.getValue());
        }
        for (Map.Entry<Material, Integer> e : avail.entrySet()) {
            Material m = e.getKey();
            if (!SINGLE.contains(m) && !SQUARE.contains(m)) continue;
            if (!needed.isEmpty() && !needed.contains(m)) continue;
            want.put(m, Math.min(e.getValue(), 64));
        }
        return FetchTask.create(w, want, "saplings");
    }

    /** Plants a sapling if one is in the backpack. */
    static boolean plant(Worker w, BlockPos p, Material sapling) {
        if (!Trees.canPlant(w.world(), p)) return false;
        if (Items.take(w.e.backpack, sapling, 1) == 0) return false;
        Block b = p.block(w.world());
        b.setType(sapling, true);
        w.plugin().blockLog().placed(w.e, b);
        w.placeEffect(b);
        w.e.stats.saplingsPlanted++;
        return true;
    }

    // ── tasks ─────────────────────────────────────────────────────────────

    static final class FellTreeTask extends WalkThenAct {

        private final BlockPos base;
        private Trees.Tree tree;
        /** 0 = logs, 1 = leaves, 2 = replanting. */
        private int phase;
        private int index;
        private int timer;
        /** Ticks the current block takes to break; 0 = not started. */
        private int need;

        FellTreeTask(BlockPos base) {
            super(new Vector(base.centerX(), base.y(), base.centerZ()), 2.6, 1.5, 2.5);
            this.base = base;
        }

        @Override
        protected void arrive(Worker w) {
            tree = Trees.measure(w.world(), base);
        }

        @Override
        protected Result act(Worker w) {
            if (tree == null) return Result.DONE;
            return switch (phase) {
                case 0 -> breakNext(w, tree.logs(), b -> b.getType() == tree.log(), 1);
                case 1 -> breakNext(w, tree.leaves(), Trees::isNaturalLeaf, 2);
                default -> replant(w);
            };
        }

        /**
         * Breaks the blocks in order, one at a time, each taking as long as a
         * player with the same tool would need. Blocks that are already gone
         * (or changed) are skipped.
         */
        private Result breakNext(Worker w, List<BlockPos> blocks, java.util.function.Predicate<Block> valid, int nextPhase) {
            World world = w.world();
            if (need == 0) {
                while (index < blocks.size() && !valid.test(blocks.get(index).block(world))) index++;
                if (index >= blocks.size()) {
                    phase = nextPhase;
                    index = 0;
                    timer = 0;
                    return Result.RUNNING;
                }
                need = w.breakTicks(blocks.get(index).block(world));
                timer = 0;
            }
            BlockPos p = blocks.get(index);
            Block b = p.block(world);
            if (!valid.test(b)) {   // someone else broke it meanwhile
                w.crack(p, 0f);
                need = 0;
                index++;
                return Result.RUNNING;
            }
            timer++;
            w.lookAt(p);
            if (timer % 4 == 1) w.swing();
            if (need > 2) w.crack(p, (float) timer / need);
            if (timer >= need) {
                w.crack(p, 0f);
                w.stash(w.breakBlock(b, true, true));
                if (phase == 0) w.e.stats.logsCut++;
                need = 0;
                index++;
            }
            return Result.RUNNING;
        }

        private Result replant(Worker w) {
            if (++timer < w.clickTicks()) return Result.RUNNING;
            timer = 0;
            for (BlockPos b : tree.bases()) {
                Material sapling = w.e.spots.getOrDefault(b, tree.sapling());
                w.e.spots.put(b, sapling);
                if (Trees.canPlant(w.world(), b) && Items.count(w.e.backpack, sapling) > 0) {
                    w.lookAt(b);
                    w.swing();
                    plant(w, b, sapling);
                    return Result.RUNNING;   // one sapling per click
                }
            }
            w.e.stats.treesFelled++;
            w.plugin().manager().markDirty();
            return Result.DONE;
        }

        @Override
        public void end(Worker w) {
            if (tree != null && need > 0 && phase < 2) {
                List<BlockPos> list = phase == 0 ? tree.logs() : tree.leaves();
                if (index < list.size()) w.crack(list.get(index), 0f);
            }
        }

        @Override
        public String status(Worker w) {
            Material kind = tree != null ? tree.log() : w.world().getType(base.x(), base.y(), base.z());
            String wood = kind.name().endsWith("_LOG")
                    ? kind.name().substring(0, kind.name().length() - 4).toLowerCase().replace('_', ' ')
                    : null;
            return switch (phase) {
                case 0 -> wood == null ? "Felling a tree" : "Felling " + article(wood) + " " + wood + " tree";
                case 1 -> "Clearing leaves";
                default -> "Replanting";
            };
        }

        private static String article(String word) {
            return "aeiou".indexOf(word.charAt(0)) >= 0 ? "an" : "a";
        }
    }

    static final class PlantSaplingsTask extends WalkThenAct {

        private int timer;

        PlantSaplingsTask(BlockPos spot) {
            super(new Vector(spot.centerX(), spot.y(), spot.centerZ()), 3.0, 1.5, 2.5);
        }

        @Override
        protected Result act(Worker w) {
            if (++timer < w.clickTicks()) return Result.RUNNING;
            timer = 0;
            World world = w.world();
            for (Map.Entry<BlockPos, Material> spot : w.e.spots.entrySet()) {
                BlockPos p = spot.getKey();
                if (p.horizontalDistanceSq(w.e.location.getX(), w.e.location.getZ()) > 16) continue;
                if (!Trees.canPlant(world, p) || Items.count(w.e.backpack, spot.getValue()) == 0) continue;
                w.lookAt(p);
                w.swing();
                plant(w, p, spot.getValue());
                w.plugin().manager().markDirty();
                return Result.RUNNING;
            }
            return Result.DONE;
        }

        @Override
        public String status(Worker w) {
            return "Planting saplings";
        }
    }
}
