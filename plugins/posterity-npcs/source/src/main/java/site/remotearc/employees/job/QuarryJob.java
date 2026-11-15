package site.remotearc.employees.job;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import site.remotearc.employees.util.BlockPos;
import site.remotearc.employees.util.Items;
import site.remotearc.employees.util.Pathfinder;
import site.remotearc.employees.util.WorkArea;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Digs out the work area one layer at a time, top down. One block of every
 * layer is left standing along the wall, each one step further round than
 * the last, so the pit always has a spiral staircase out. Water and lava in
 * the layer being dug are plugged with cobblestone (or dirt) first, and the
 * plugs are left standing as dams (kept in Employee.spots); blocks
 * that border liquid outside the area, or that can't be mined with its
 * pickaxe, are left as part of the wall.
 */
final class QuarryJob implements Job {

    private static final int[][] SIDES_AND_TOP = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}};

    private WorkArea forArea;
    /** The ring of columns along the area's edge, in walking order. */
    private List<int[]> ring = List.of();
    private int layer;
    private List<BlockPos> blocks = List.of();
    private List<BlockPos> liquids = List.of();
    private final Set<Long> skipped = new HashSet<>();
    private int plugsThisLayer;
    private boolean flooded;
    private int stuckRounds;
    private String idle = "Surveying the quarry";

    @Override
    public Task next(Worker w) {
        WorkArea a = w.e.area;
        World world = w.world();
        if (a == null || !a.world().equals(world.getName())) {
            idle = "No work area in this world";
            return null;
        }
        if (!a.equals(forArea)) start(a);
        if (blocks.isEmpty() && liquids.isEmpty() || w.cooldownReady("quarry-scan", 60)) scan(w, world, a);
        if (layer < a.minY()) {
            idle = "Finished: the whole area is dug out";
            return null;
        }

        // The staircase step for this layer: a solid block that won't fall.
        BlockPos stair = stairAt(layer);
        Block step = stair.block(world);
        if (step.getType().hasGravity()) {
            return new MineTask(world, stair, b -> b.getType().hasGravity(), this::mined, "Replacing a loose stair step");
        }
        boolean stairMissing = (!step.getType().isSolid() || !step.getType().isOccluding()) && PlaceTask.replaceable(step);
        if (stairMissing) {
            Material f = filler(w);
            // With nothing to build it from yet, dig first: the stone that comes out is the material.
            // (If it can't get there yet, e.g. it starts below the top, carry on digging and come back to it.)
            if (f != null && !w.isUnreachable(stair)) return new PlaceTask(world, stair, f, "Building the staircase");
        }

        if (!flooded && !liquids.isEmpty()) {
            BlockPos wet = nearest(w, liquids);   // skips ones it couldn't reach lately
            Material f = filler(w);
            if (wet != null && f != null) {
                if (++plugsThisLayer > a.width() * a.length() * 2) flooded = true;
                // The plug stays as a dam: mining it again would just let the water back in.
                w.e.spots.put(wet, f);
                w.plugin().manager().markDirty();
                return new PlaceTask(world, wet, f, "Plugging " + (wet.block(world).getType() == Material.LAVA ? "lava" : "water"));
            }
            // Can't reach it or nothing to plug it with: leave the liquid and keep digging round it.
            for (BlockPos p : liquids) skipped.add(p.key());
            liquids = List.of();
        }

        BlockPos target = nearest(w, blocks);
        if (target != null) {
            stuckRounds = 0;
            int y = layer;
            return new MineTask(world, target, b -> b.getY() == y && minable(w, b, a), this::mined, "Quarrying (y " + layer + ")");
        }
        if (!blocks.isEmpty() && ++stuckRounds == 2 && getDown(w, a)) {
            return null;   // was stranded up on a ledge or a lone block: now on the pit floor, try again from there
        }
        if (!blocks.isEmpty() && stuckRounds >= 4) {
            // Still unreachable from the floor: leave them as part of the wall.
            for (BlockPos p : blocks) skipped.add(p.key());
            stuckRounds = 0;
            blocks = List.of();
        }
        if (blocks.isEmpty() && stairMissing && filler(w) == null) {
            idle = "Needs stone, cobblestone or dirt for the staircase (put some in its backpack)";
        } else {
            idle = blocks.isEmpty() ? "Surveying the quarry" : "Can't reach the rest of layer y " + layer;
        }
        return null;
    }

    /**
     * Stranded (on the last block of a layer in mid-air, on a ledge it can't
     * step down from): jumps down to the nearest spot on the pit floor, as a
     * player would. True if it moved.
     */
    private boolean getDown(Worker w, WorkArea a) {
        World world = w.world();
        org.bukkit.util.BoundingBox box = new org.bukkit.util.BoundingBox(a.minX(), a.minY(), a.minZ(), a.maxX() + 1, a.maxY() + 2, a.maxZ() + 1);
        Pathfinder pf = new Pathfinder(world, box);
        double mx = w.e.location.getX(), my = w.e.location.getY(), mz = w.e.location.getZ();
        org.bukkit.util.Vector best = null;
        double bestD = Double.MAX_VALUE;
        for (int x = a.minX(); x <= a.maxX(); x++) {
            for (int z = a.minZ(); z <= a.maxZ(); z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                // The floor of the layer being dug: standing in it (on the layer below), or on it.
                for (int y = layer + 1; y >= layer; y--) {
                    double f = pf.floor(x, y, z);
                    if (Double.isNaN(f) || f > my + 0.5 || !openAbove(world, x, (int) Math.floor(f), z, a.maxY())) continue;
                    double d = (x + 0.5 - mx) * (x + 0.5 - mx) + (z + 0.5 - mz) * (z + 0.5 - mz) + (my - f) * 0.2;
                    if (d < bestD && d > 0.5) {
                        bestD = d;
                        best = new org.bukkit.util.Vector(x + 0.5, f, z + 0.5);
                    }
                    break;
                }
            }
        }
        if (best == null) return false;
        world.spawnParticle(org.bukkit.Particle.CLOUD, w.e.location.clone().add(0, 0.5, 0), 8, 0.3, 0.2, 0.3, 0.02);
        w.plugin().manager().teleport(w.e, new org.bukkit.Location(world, best.getX(), best.getY(), best.getZ(), w.e.location.getYaw(), 0));
        w.forgetUnreachable();
        return true;
    }

    /**
     * Open sky over a spot up to the top of the area: part of the pit, not a
     * cave pocket sealed in under the layer being dug.
     */
    private static boolean openAbove(World world, int x, int feet, int z, int top) {
        for (int y = feet; y <= top + 1; y++) {
            if (!world.getBlockAt(x, y, z).isPassable()) return false;
        }
        return true;
    }

    private void start(WorkArea a) {
        forArea = a;
        layer = a.maxY();
        skipped.clear();
        blocks = List.of();
        liquids = List.of();
        plugsThisLayer = 0;
        flooded = false;
        List<int[]> r = new ArrayList<>();
        for (int x = a.minX(); x <= a.maxX(); x++) r.add(new int[]{x, a.minZ()});
        for (int z = a.minZ() + 1; z <= a.maxZ(); z++) r.add(new int[]{a.maxX(), z});
        if (a.maxZ() > a.minZ()) for (int x = a.maxX() - 1; x >= a.minX(); x--) r.add(new int[]{x, a.maxZ()});
        if (a.maxX() > a.minX()) for (int z = a.maxZ() - 1; z > a.minZ(); z--) r.add(new int[]{a.minX(), z});
        ring = r;
    }

    private BlockPos stairAt(int y) {
        int[] c = ring.get(Math.floorMod(forArea.maxY() - y, ring.size()));
        return new BlockPos(c[0], y, c[1]);
    }

    private boolean isStair(int x, int y, int z) {
        BlockPos s = stairAt(y);
        return s.x() == x && s.z() == z;
    }

    /** Lists what is left in the current layer, moving down past finished layers. */
    private void scan(Worker w, World world, WorkArea a) {
        for (int tries = 0; tries < 16 && layer >= a.minY(); tries++) {
            List<BlockPos> solid = new ArrayList<>(), wet = new ArrayList<>();
            for (int x = a.minX(); x <= a.maxX(); x++) {
                for (int z = a.minZ(); z <= a.maxZ(); z++) {
                    if (!world.isChunkLoaded(x >> 4, z >> 4) || isStair(x, layer, z)) continue;
                    Block b = world.getBlockAt(x, layer, z);
                    if (b.isLiquid()) {
                        if (!flooded && !skipped.contains(BlockPos.pack(x, layer, z)) && w.mayWork(x, layer, z)) wet.add(BlockPos.of(b));
                    } else if (!skipped.contains(BlockPos.pack(x, layer, z)) && minable(w, b, a)) {
                        solid.add(BlockPos.of(b));
                    }
                }
            }
            if (!solid.isEmpty() || !wet.isEmpty()) {
                blocks = solid;
                liquids = wet;
                return;
            }
            layer--;
            plugsThisLayer = 0;
            flooded = false;
            stuckRounds = 0;
            w.plugin().manager().markDirty();
        }
        blocks = List.of();
        liquids = List.of();
    }

    private boolean minable(Worker w, Block b, WorkArea a) {
        if (b.getType().isAir() || b.isLiquid() || !a.contains(b.getX(), b.getY(), b.getZ()) || !w.mayWork(b.getX(), b.getY(), b.getZ())) return false;
        if (isStair(b.getX(), b.getY(), b.getZ()) || skipped.contains(BlockPos.pack(b.getX(), b.getY(), b.getZ()))) return false;
        if (w.e.spots.containsKey(BlockPos.of(b))) return false;   // a dam it built against water or lava
        if (Ground.protectedBlock(b) || !Ground.harvestable(b, w.tool())) return false;
        for (int[] d : SIDES_AND_TOP) {
            Block n = b.getRelative(d[0], d[1], d[2]);
            // Liquid that this quarry won't plug (outside the area, or a flooded layer) stays walled off.
            if (n.isLiquid() && (flooded || !a.contains(n.getX(), n.getY(), n.getZ()) || n.getY() != layer)) return false;
        }
        return true;
    }

    private BlockPos nearest(Worker w, List<BlockPos> list) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : list) {
            if (w.isUnreachable(p)) continue;
            double d = p.distanceSq(w.e.location.getX(), w.e.location.getY(), w.e.location.getZ());
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    private Material filler(Worker w) {
        return Ground.buildingBlock(w);
    }

    private void mined(Worker w, Material type, Collection<ItemStack> drops) {
        List<ItemStack> keep = new ArrayList<>();
        for (ItemStack s : drops) if (!w.e.filter.contains(s.getType())) keep.add(s);
        w.stash(keep);
        w.e.stats.blocksMined++;
        w.plugin().manager().markDirty();
    }

    @Override
    public void reset(Worker w) {
        forArea = null;
    }

    @Override
    public String idleStatus(Worker w) {
        return idle;
    }

    @Override
    public boolean isReserved(Material m) {
        return false;
    }

    @Override
    public int reserve(Worker w, Material m) {
        return w.settings().quarryFiller.contains(m) || Ground.isFiller(m) ? w.jobSettings().reserve() : 0;
    }

    @Override
    public boolean collects(Material m) {
        return false;
    }

    @Override
    public Movement movement() {
        return Movement.DIG;
    }

    @Override
    public Pathfinder.Digger digger(Worker w) {
        WorkArea a = w.e.area;
        // Anything it will dig out anyway (never the staircase) may be tunnelled through; it may also pillar
        // and bridge, but only below the layer it's digging, so those blocks get dug out with a later layer.
        // (A block put down in the layer itself became the next thing to dig: it would stand on it, dig it
        // out from under itself, and put another down, round and round.)
        boolean canBuild = Ground.buildingBlocks(w) > 0;
        int current = layer;
        return new Pathfinder.Digger() {
            @Override
            public int digTicks(Block b) {
                return a != null && minable(w, b, a) ? w.breakTicks(b) : -1;
            }

            @Override
            public boolean mayPlace(int x, int y, int z) {
                return canBuild && a != null && a.contains(x, y, z) && y < current && !isStair(x, y, z);
            }
        };
    }

    @Override
    public boolean keepsDug(Worker w, Material block, Material drop) {
        return !w.e.filter.contains(drop);
    }

    @Override
    public Material buildingBlock(Worker w) {
        return Ground.buildingBlock(w);
    }

    @Override
    public String detail(Worker w) {
        WorkArea a = w.e.area;
        if (a == null || forArea == null) return null;
        if (layer < a.minY()) return "Dug out down to y " + a.minY();
        return "Digging y " + layer + " (" + (a.maxY() - layer) + " of " + a.height() + " layers done)";
    }
}
