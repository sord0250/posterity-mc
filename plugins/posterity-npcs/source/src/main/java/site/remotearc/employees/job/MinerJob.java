package site.remotearc.employees.job;

import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import site.remotearc.employees.util.BlockPos;
import site.remotearc.employees.util.Items;
import site.remotearc.employees.util.MaterialList;
import site.remotearc.employees.util.Pathfinder;
import site.remotearc.employees.util.WorkArea;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Explores the work area for the blocks in its filter (ores by default):
 * finds those open to a cave or the sky, tunnels to them through natural
 * stone and dirt when there is no walkable way, and mines the whole vein.
 *
 * Finding them: the area's chunks are copied (chunk snapshots) one at a time
 * and searched off the main thread, then candidates are checked again in the
 * live world before each trip.
 */
final class MinerJob implements Job {

    private static final int PER_CHUNK = 2000;
    private static final int RESCAN_TICKS = 20 * 60;

    private final Map<Long, List<BlockPos>> found = new HashMap<>();
    private final ArrayDeque<long[]> queue = new ArrayDeque<>();
    private boolean scanning;
    private int generation;
    private boolean firstPassDone;
    /** Trips made toward each target; after a few that didn't get it, it's left alone (until the area changes). */
    private final Map<BlockPos, Integer> attempts = new HashMap<>();
    private static final int MAX_ATTEMPTS = 3;
    private String idle = "Exploring";

    @Override
    public Task next(Worker w) {
        WorkArea a = w.e.area;
        World world = w.world();
        if (a == null || !a.world().equals(world.getName())) {
            idle = "No work area in this world";
            return null;
        }
        if (w.e.filter.isEmpty()) {
            idle = "Nothing to look for: /employees filter " + w.e.name + " add <block>";
            return null;
        }
        boolean xray = w.settings().minerXray;
        double mx = w.e.location.getX(), my = w.e.location.getY(), mz = w.e.location.getZ();
        // Nearest first, skipping ones it recently failed to reach, checked against the live world.
        List<BlockPos> all = new ArrayList<>();
        for (List<BlockPos> list : found.values()) all.addAll(list);
        all.sort((p, q) -> Double.compare(p.distanceSq(mx, my, mz), q.distanceSq(mx, my, mz)));
        BlockPos unreached = null;
        int checked = 0, gaveUp = 0;
        for (BlockPos p : all) {
            if (attempts.getOrDefault(p, 0) >= MAX_ATTEMPTS) {
                gaveUp++;
                continue;
            }
            if (w.isUnreachable(p)) {
                if (unreached == null) unreached = p;
                continue;
            }
            if (!world.isChunkLoaded(p.x() >> 4, p.z() >> 4)) continue;
            Block b = p.block(world);
            if (!wanted(w, b, a, xray)) {
                forget(p);
                if (++checked > 200) break;
                continue;
            }
            attempts.merge(p, 1, Integer::sum);
            return new MineTask(world, p, x -> wanted(w, x, a, xray), MinerJob::mined, "Mining " + Items.name(b.getType()));
        }
        if (firstPassDone && all.isEmpty() && w.e.roam && w.cooldownReady("roam", 20 * 60)) {
            if (roam(w)) return null;
        }
        if (!firstPassDone) idle = "Surveying the area for " + filterName(w) + " (" + queue.size() + " chunks to go)";
        else if (unreached == null && gaveUp > 0) idle = "Gave up on " + gaveUp + " block" + (gaveUp == 1 ? "" : "s")
                + " it couldn't get to; nothing else in sight";
        else if (unreached != null) idle = "Can't get to the " + Items.name(unreached.block(world).getType()) + " it found"
                + (Ground.buildingBlocks(w) == 0 ? " (has no stone to climb with)" : "") + "; trying again soon";
        else idle = "No " + filterName(w) + " left in sight" + (xray ? "" : " (it only finds what's open to caves)");
        return null;
    }

    /**
     * Moves the work area to the next area-sized patch round where the miner
     * started (a square spiral outward), skipping land that hasn't been
     * generated. Returns false when there's nowhere left within roam-distance.
     */
    private boolean roam(Worker w) {
        WorkArea a = w.e.area;
        if (w.e.roamBase == null) w.e.roamBase = a;
        WorkArea base = w.e.roamBase;
        World world = base.bukkitWorld();
        if (world == null) return false;
        int max = w.settings().minerRoamDistance;
        for (int tries = 0; tries < 200; tries++) {
            int step = ++w.e.roamStep;
            int[] d = spiral(step);
            int dx = d[0] * base.width(), dz = d[1] * base.length();
            if (Math.max(Math.abs(dx), Math.abs(dz)) > max) {
                idle = "Explored everything within " + max + " blocks of where it started";
                w.e.roam = false;
                w.plugin().manager().notifyOwner(w.e, "<yellow>" + w.e.name + " has mined out everything within " + max
                        + " blocks and stopped roaming.", true);
                w.plugin().manager().markDirty();
                return false;
            }
            WorkArea next = WorkArea.of(base.world(), base.minX() + dx, base.minY(), base.minZ() + dz, base.maxX() + dx, base.maxY(), base.maxZ() + dz);
            if (!generated(world, next)) continue;
            w.plugin().manager().events().add(w.e.owner, w.e.name, "Mined out its area; moving on to " + next.describe(), false);
            w.plugin().manager().setArea(w.e, next);   // resets the survey and brings its home along
            idle = "Moving on to new ground";
            return true;
        }
        return false;
    }

    /** Square-spiral offsets in whole areas: 1-8 are the ring round the start, 9-24 the next ring, and so on. */
    static int[] spiral(int n) {
        int ring = 1;
        while ((2 * ring + 1) * (2 * ring + 1) - 1 < n) ring++;
        int i = n - ((2 * ring - 1) * (2 * ring - 1) - 1) - 1;   // index within this ring, 0 .. 8*ring-1
        int side = 2 * ring;
        if (i < side) return new int[]{-ring + i, -ring};
        if (i < 2 * side) return new int[]{ring, -ring + (i - side)};
        if (i < 3 * side) return new int[]{ring - (i - 2 * side), ring};
        return new int[]{-ring, ring - (i - 3 * side)};
    }

    private static boolean generated(World world, WorkArea a) {
        for (int cx = a.minX() >> 4; cx <= a.maxX() >> 4; cx++) {
            for (int cz = a.minZ() >> 4; cz <= a.maxZ() >> 4; cz++) {
                if (!world.isChunkGenerated(cx, cz)) return false;
            }
        }
        return true;
    }

    private static boolean wanted(Worker w, Block b, WorkArea a, boolean xray) {
        if (!w.e.filter.contains(b.getType()) || !a.contains(b.getX(), b.getY(), b.getZ()) || !w.mayWork(b.getX(), b.getY(), b.getZ())) return false;
        if (!Ground.harvestable(b, w.tool()) || Ground.protectedBlock(b) || Ground.nextToLava(b)) return false;
        return xray || Ground.exposed(b);
    }

    private static void mined(Worker w, Material type, java.util.Collection<org.bukkit.inventory.ItemStack> drops) {
        w.stash(drops);
        w.e.stats.blocksMined++;
        if (MaterialList.ORES.contains(type)) w.e.stats.oresMined++;
        w.plugin().manager().markDirty();
    }

    private void forget(BlockPos p) {
        attempts.remove(p);
        List<BlockPos> list = found.get(chunkKey(p.x() >> 4, p.z() >> 4));
        if (list != null) list.remove(p);
    }

    private static String filterName(Worker w) {
        Set<Material> f = w.e.filter;
        if (f.equals(MaterialList.ORES) || f.containsAll(MaterialList.ORES)) return "ores";
        Material first = f.iterator().next();
        return f.size() == 1 ? Items.name(first) : Items.name(first) + " or the rest of its list";
    }

    // ── scanning ──────────────────────────────────────────────────────────

    /** Surveys continuously (one chunk every few ticks), also while it's busy mining. */
    @Override
    public void tick(Worker w) {
        WorkArea a = w.e.area;
        if (a == null || w.e.filter.isEmpty() || !a.world().equals(w.world().getName())) return;
        scanStep(w, a, w.world());
    }

    private void scanStep(Worker w, WorkArea a, World world) {
        if (scanning || !w.cooldownReady("ore-scan", 4)) return;
        if (queue.isEmpty()) {
            if (firstPassDone && !w.cooldownReady("ore-pass", RESCAN_TICKS)) return;
            for (int cx = a.minX() >> 4; cx <= a.maxX() >> 4; cx++) {
                for (int cz = a.minZ() >> 4; cz <= a.maxZ() >> 4; cz++) queue.add(new long[]{cx, cz});
            }
        }
        long[] c = queue.poll();
        if (c == null) return;
        int cx = (int) c[0], cz = (int) c[1];
        if (queue.isEmpty()) {
            firstPassDone = true;
            w.cooldownReady("ore-pass", RESCAN_TICKS);
        }
        if (!world.isChunkLoaded(cx, cz)) return;
        ChunkSnapshot snap = world.getChunkAt(cx, cz).getChunkSnapshot(false, false, false);
        Set<Material> targets = EnumSet.copyOf(w.e.filter);
        boolean xray = w.settings().minerXray;
        int minY = Math.max(a.minY(), world.getMinHeight()), maxY = Math.min(a.maxY(), world.getMaxHeight() - 1);
        int x0 = Math.max(a.minX(), cx << 4), x1 = Math.min(a.maxX(), (cx << 4) + 15);
        int z0 = Math.max(a.minZ(), cz << 4), z1 = Math.min(a.maxZ(), (cz << 4) + 15);
        int gen = generation;
        scanning = true;
        Bukkit.getScheduler().runTaskAsynchronously(w.plugin(), () -> {
            List<BlockPos> hits = new ArrayList<>();
            try {
                int worldMin = world.getMinHeight();
                for (int y = minY; y <= maxY && hits.size() < PER_CHUNK; y++) {
                    if (snap.isSectionEmpty((y - worldMin) >> 4)) {
                        y = ((((y - worldMin) >> 4) + 1) << 4) + worldMin - 1;
                        continue;
                    }
                    for (int x = x0; x <= x1; x++) {
                        for (int z = z0; z <= z1; z++) {
                            Material m = snap.getBlockType(x & 15, y, z & 15);
                            if (!targets.contains(m)) continue;
                            if (!xray && !openInSnapshot(snap, x & 15, y, z & 15, worldMin, maxY)) continue;
                            hits.add(new BlockPos(x, y, z));
                        }
                    }
                }
            } catch (RuntimeException ex) {
                w.plugin().getLogger().log(java.util.logging.Level.WARNING, "Ore scan failed for " + w.e.name, ex);
            } finally {
                Bukkit.getScheduler().runTask(w.plugin(), () -> {
                    scanning = false;
                    if (gen != generation) return;
                    long key = chunkKey(cx, cz);
                    if (hits.isEmpty()) found.remove(key);
                    else found.put(key, hits);
                });
            }
        });
    }

    /** Rough exposure test inside one snapshot; blocks on the chunk edge count as maybe-open and are checked live later. */
    private static boolean openInSnapshot(ChunkSnapshot s, int x, int y, int z, int minY, int maxY) {
        if (x == 0 || x == 15 || z == 0 || z == 15 || y <= minY || y >= maxY) return true;
        return open(s.getBlockType(x + 1, y, z)) || open(s.getBlockType(x - 1, y, z))
                || open(s.getBlockType(x, y + 1, z)) || open(s.getBlockType(x, y - 1, z))
                || open(s.getBlockType(x, y, z + 1)) || open(s.getBlockType(x, y, z - 1));
    }

    private static boolean open(Material m) {
        return m.isAir() || (!m.isSolid() && m != Material.WATER && m != Material.LAVA);
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    @Override
    public void reset(Worker w) {
        generation++;
        attempts.clear();
        found.clear();
        queue.clear();
        firstPassDone = false;
        scanning = false;
    }

    // ── job hooks ─────────────────────────────────────────────────────────

    @Override
    public String idleStatus(Worker w) {
        return idle;
    }

    @Override
    public boolean isReserved(Material m) {
        return false;
    }

    private static final Set<Material> ORE_DROPS = EnumSet.of(Material.RAW_IRON, Material.RAW_GOLD, Material.RAW_COPPER,
            Material.COAL, Material.DIAMOND, Material.EMERALD, Material.LAPIS_LAZULI, Material.REDSTONE, Material.QUARTZ,
            Material.GOLD_NUGGET, Material.ANCIENT_DEBRIS);

    @Override
    public boolean collects(Material m) {
        return ORE_DROPS.contains(m) || MaterialList.ORES.contains(m);
    }

    @Override
    public Movement movement() {
        return Movement.DIG;
    }

    @Override
    public Pathfinder.Digger digger(Worker w) {
        WorkArea a = w.e.area;
        boolean canBuild = Ground.buildingBlocks(w) > 0;
        return new Pathfinder.Digger() {
            @Override
            public int digTicks(org.bukkit.block.Block b) {
                if (a == null || !a.contains(b.getX(), b.getY(), b.getZ())) return -1;
                if (!Ground.NATURAL.contains(b.getType()) || Ground.protectedBlock(b)) return -1;
                return w.breakTicks(b);
            }

            @Override
            public boolean mayPlace(int x, int y, int z) {
                return canBuild && a != null && a.contains(x, y, z);
            }
        };
    }

    /** Stone it keeps for climbing and bridging. */
    private static final int BUILD_STOCK = 64;

    @Override
    public boolean keepsDug(Worker w, Material block, Material drop) {
        if (w.e.filter.contains(block) || w.settings().minerKeepTunnel) return true;
        return Ground.isFiller(drop) && Ground.buildingBlocks(w) < BUILD_STOCK;
    }

    @Override
    public int reserve(Worker w, Material m) {
        return Ground.isFiller(m) || w.settings().quarryFiller.contains(m) ? BUILD_STOCK : 0;
    }

    @Override
    public Material buildingBlock(Worker w) {
        return Ground.buildingBlock(w);
    }
}
