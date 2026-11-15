package site.remotearc.employees.job;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import site.remotearc.employees.util.BlockPos;
import site.remotearc.employees.util.Items;
import site.remotearc.employees.util.WorkArea;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Harvests ripe crops in the work area and replants them from the harvest.
 * Remembers what grew where, so trampled or empty farmland is replanted with
 * the same crop (or, for never-planted farmland, the crop its neighbours grow).
 */
final class FarmerJob implements Job {

    /** Crop block → the item that plants it. Cocoa is replanted in place, never on empty ground. */
    static final Map<Material, Material> SEED = new EnumMap<>(Map.of(
            Material.WHEAT, Material.WHEAT_SEEDS,
            Material.CARROTS, Material.CARROT,
            Material.POTATOES, Material.POTATO,
            Material.BEETROOTS, Material.BEETROOT_SEEDS,
            Material.NETHER_WART, Material.NETHER_WART,
            Material.COCOA, Material.COCOA_BEANS));

    /** Harvested down to the bottom block, which keeps growing. */
    static final Set<Material> COLUMNS = EnumSet.of(Material.SUGAR_CANE, Material.CACTUS, Material.BAMBOO);

    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    private List<BlockPos> ripe = List.of();
    private List<BlockPos> empty = List.of();
    private String idle = "Looking for crops";

    @Override
    public Task next(Worker w) {
        WorkArea a = w.e.area;
        World world = w.world();
        if (a == null || !a.world().equals(world.getName())) {
            idle = "No work area in this world";
            return null;
        }
        if (w.cooldownReady("crop-scan", 40)) {
            scan(world, a, w.e.spots);
            ripe.removeIf(p -> !w.mayWork(p));
            empty.removeIf(p -> !w.mayWork(p));
        }

        BlockPos target = nearest(w, ripe, p -> {
            Block b = p.block(world);
            return isRipe(world, b.getX(), b.getY(), b.getZ(), b.getBlockData());
        });
        if (target != null) return new HarvestTask(target);

        boolean missingSeeds = false;
        List<BlockPos> plantable = new ArrayList<>();
        for (BlockPos p : empty) {
            Material crop = cropFor(world, p, w.e.spots);
            if (crop == null || !world.getType(p.x(), p.y(), p.z()).isAir()) continue;
            if (Items.count(w.e.backpack, SEED.get(crop)) == 0) missingSeeds = true;
            else plantable.add(p);
        }
        target = nearest(w, plantable, p -> true);
        if (target != null) return new PlantCropsTask(target);

        if (missingSeeds && w.cooldownReady("seed-fetch", 20 * 30)) {
            Map<Material, Integer> avail = site.remotearc.employees.storage.Supply.available(w.plugin(), w.e);
            Map<Material, Integer> want = new EnumMap<>(Material.class);
            for (Material seed : SEED.values()) {
                if (Items.count(w.e.backpack, seed) == 0 && avail.getOrDefault(seed, 0) > 0) want.put(seed, Math.min(avail.get(seed), 64));
            }
            Task t = FetchTask.create(w, want, "seeds");
            if (t != null) return t;
        }
        if (missingSeeds) idle = "Out of seeds: none in its backpack or your employees' chests";
        else if (w.e.spots.isEmpty() && ripe.isEmpty()) idle = "No crops in the work area";
        else idle = "Waiting for crops to grow";
        return null;
    }

    @Override
    public String idleStatus(Worker w) {
        return idle;
    }

    @Override
    public boolean isReserved(Material m) {
        return SEED.containsValue(m);
    }

    private static final Set<Material> PRODUCE = EnumSet.of(
            Material.WHEAT, Material.WHEAT_SEEDS, Material.CARROT, Material.POTATO, Material.POISONOUS_POTATO,
            Material.BEETROOT, Material.BEETROOT_SEEDS, Material.NETHER_WART, Material.COCOA_BEANS,
            Material.SWEET_BERRIES, Material.MELON_SLICE, Material.MELON, Material.PUMPKIN,
            Material.SUGAR_CANE, Material.CACTUS, Material.BAMBOO);

    @Override
    public boolean collects(Material m) {
        return PRODUCE.contains(m);
    }

    private interface Check {
        boolean ok(BlockPos p);
    }

    private static BlockPos nearest(Worker w, List<BlockPos> list, Check check) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        double x = w.e.location.getX(), y = w.e.location.getY(), z = w.e.location.getZ();
        for (BlockPos p : list) {
            double d = p.distanceSq(x, y, z);
            if (d >= bestD || w.isUnreachable(p) || !check.ok(p)) continue;
            bestD = d;
            best = p;
        }
        return best;
    }

    private void scan(World world, WorkArea a, Map<BlockPos, Material> spots) {
        List<BlockPos> ripeNow = new ArrayList<>();
        List<BlockPos> emptyNow = new ArrayList<>();
        for (int x = a.minX(); x <= a.maxX(); x++) {
            for (int z = a.minZ(); z <= a.maxZ(); z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                Material below = world.getType(x, a.minY() - 1, z);
                for (int y = a.minY(); y <= a.maxY(); y++) {
                    Material m = world.getType(x, y, z);
                    if (m.isAir()) {
                        if (below == Material.FARMLAND || below == Material.SOUL_SAND) emptyNow.add(new BlockPos(x, y, z));
                    } else if (SEED.containsKey(m) || COLUMNS.contains(m) || m == Material.SWEET_BERRY_BUSH
                            || m == Material.MELON || m == Material.PUMPKIN) {
                        if (SEED.containsKey(m) && m != Material.COCOA) spots.put(new BlockPos(x, y, z), m);
                        if (isRipe(world, x, y, z, world.getBlockData(x, y, z))) ripeNow.add(new BlockPos(x, y, z));
                    }
                    below = m;
                }
            }
        }
        spots.keySet().removeIf(p -> {
            if (!a.contains(p)) return true;
            if (!world.isChunkLoaded(p.x() >> 4, p.z() >> 4)) return false;
            Material soil = world.getType(p.x(), p.y() - 1, p.z());
            return soil != Material.FARMLAND && soil != Material.SOUL_SAND;
        });
        ripe = ripeNow;
        empty = emptyNow;
    }

    static boolean isRipe(World w, int x, int y, int z, BlockData data) {
        Material m = data.getMaterial();
        if (SEED.containsKey(m)) return data instanceof Ageable age && age.getAge() >= age.getMaximumAge();
        if (m == Material.SWEET_BERRY_BUSH) return data instanceof Ageable age && age.getAge() >= 3;
        if (m == Material.MELON || m == Material.PUMPKIN) return hasAttachedStem(w, x, y, z, m);
        if (COLUMNS.contains(m)) return w.getType(x, y - 1, z) == m && w.getType(x, y - 2, z) != m;
        return false;
    }

    private static boolean hasAttachedStem(World w, int x, int y, int z, Material fruit) {
        Material stem = fruit == Material.MELON ? Material.ATTACHED_MELON_STEM : Material.ATTACHED_PUMPKIN_STEM;
        for (BlockFace f : SIDES) {
            int sx = x + f.getModX(), sz = z + f.getModZ();
            if (w.getType(sx, y, sz) != stem) continue;
            if (w.getBlockData(sx, y, sz) instanceof Directional d
                    && sx + d.getFacing().getModX() == x && sz + d.getFacing().getModZ() == z) return true;
        }
        return false;
    }

    /** The crop that belongs on an empty spot: what grew there before, else what its neighbours grow. */
    static Material cropFor(World w, BlockPos p, Map<BlockPos, Material> spots) {
        Material soil = w.getType(p.x(), p.y() - 1, p.z());
        Material known = spots.get(p);
        if (known == null) {
            Map<Material, Integer> votes = new EnumMap<>(Material.class);
            for (BlockFace f : SIDES) {
                BlockPos n = p.offset(f.getModX(), 0, f.getModZ());
                Material m = w.getType(n.x(), n.y(), n.z());
                if (!SEED.containsKey(m) || m == Material.COCOA) m = spots.get(n);
                if (m != null) votes.merge(m, 1, Integer::sum);
            }
            known = votes.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        }
        if (known == null || known == Material.COCOA) return null;
        Material needs = known == Material.NETHER_WART ? Material.SOUL_SAND : Material.FARMLAND;
        return soil == needs ? known : null;
    }

    /** Harvests one ripe block (and replants it when it is a seed crop). */
    static void harvest(Worker w, Block b, Map<BlockPos, Material> spots) {
        BlockData data = b.getBlockData();
        Material m = data.getMaterial();
        World world = w.world();
        if (SEED.containsKey(m)) {
            List<ItemStack> drops = new ArrayList<>(b.getDrops(w.tool(), w.e.entity));
            Material seed = SEED.get(m);
            boolean replant = removeOne(drops, seed) || Items.take(w.e.backpack, seed, 1) == 1;
            w.breakEffect(b, data, true);
            if (replant) {
                Ageable fresh = (Ageable) data.clone();
                fresh.setAge(0);
                b.setBlockData(fresh, true);
            } else {
                w.plugin().blockLog().removing(w.e, b);
                b.setType(Material.AIR, true);
            }
            if (m != Material.COCOA) spots.put(BlockPos.of(b), m);
            w.stash(drops);
        } else if (m == Material.SWEET_BERRY_BUSH && data instanceof Ageable age) {
            int berries = (age.getAge() >= 3 ? 2 : 1) + w.random().nextInt(2);
            Ageable fresh = (Ageable) data.clone();
            fresh.setAge(1);
            b.setBlockData(fresh, true);
            w.playSound(b.getLocation().add(0.5, 0.5, 0.5), Sound.BLOCK_SWEET_BERRY_BUSH_PICK_BERRIES, 1f, 0.9f + w.random().nextFloat() * 0.2f);
            w.stash(List.of(new ItemStack(Material.SWEET_BERRIES, berries)));
        } else if (COLUMNS.contains(m)) {
            int top = b.getY();
            while (world.getType(b.getX(), top + 1, b.getZ()) == m) top++;
            for (int y = top; y >= b.getY(); y--) {
                w.stash(w.breakBlock(world.getBlockAt(b.getX(), y, b.getZ()), true, y == b.getY()));
            }
        } else {
            w.stash(w.breakBlock(b, true, true));
        }
        w.e.stats.cropsHarvested++;
        w.plugin().manager().markDirty();
    }

    private static boolean removeOne(Collection<ItemStack> drops, Material m) {
        for (ItemStack s : drops) {
            if (s.getType() != m) continue;
            if (s.getAmount() > 1) s.setAmount(s.getAmount() - 1);
            else drops.remove(s);
            return true;
        }
        return false;
    }

    // ── tasks ─────────────────────────────────────────────────────────────

    private static final double REACH = 3.5;

    /** Walks to a ripe crop, then harvests every ripe block within reach. */
    static final class HarvestTask extends WalkThenAct {

        private Block current;
        private int need;
        private int timer;

        HarvestTask(BlockPos p) {
            super(new Vector(p.centerX(), p.y(), p.centerZ()), 2.5, 2, 3);
        }

        @Override
        protected Result act(Worker w) {
            World world = w.world();
            if (current == null) {
                current = closestNearby(w, (wd, x, y, z) ->
                        w.e.area.contains(x, y, z) && isRipe(wd, x, y, z, wd.getBlockData(x, y, z)));
                if (current == null) return Result.DONE;
                need = harvestTicks(w, current);
                timer = 0;
            }
            Block b = current;
            if (!isRipe(world, b.getX(), b.getY(), b.getZ(), b.getBlockData())) {
                current = null;
                return Result.RUNNING;
            }
            timer++;
            w.lookAt(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5);
            if (timer % 4 == 1) w.swing();
            if (timer < need) return Result.RUNNING;
            harvest(w, b, w.e.spots);
            current = null;
            return w.backpackFull() ? Result.DONE : Result.RUNNING;
        }

        /** Breaking the block(s) as a player would, plus a right-click to replant. */
        private static int harvestTicks(Worker w, Block b) {
            Material m = b.getType();
            if (m == Material.SWEET_BERRY_BUSH) return w.clickTicks();
            int ticks = w.breakTicks(b);
            if (COLUMNS.contains(m)) {
                int height = 1;
                while (b.getWorld().getType(b.getX(), b.getY() + height, b.getZ()) == m) height++;
                ticks *= height;
            }
            if (SEED.containsKey(m)) ticks += w.clickTicks();
            return ticks;
        }

        @Override
        public String status(Worker w) {
            return "Harvesting crops";
        }
    }

    /** Walks to empty farmland, then plants every remembered spot within reach. */
    static final class PlantCropsTask extends WalkThenAct {

        private int timer;

        PlantCropsTask(BlockPos p) {
            super(new Vector(p.centerX(), p.y(), p.centerZ()), 2.5, 2, 3);
        }

        @Override
        protected Result act(Worker w) {
            if (++timer < w.clickTicks()) return Result.RUNNING;
            timer = 0;
            Block next = closestNearby(w, (world, x, y, z) -> {
                if (!world.getType(x, y, z).isAir() || !w.e.area.contains(x, y, z)) return false;
                Material crop = cropFor(world, new BlockPos(x, y, z), w.e.spots);
                return crop != null && Items.count(w.e.backpack, SEED.get(crop)) > 0;
            });
            if (next == null) return Result.DONE;
            Material crop = cropFor(w.world(), BlockPos.of(next), w.e.spots);
            if (crop == null || Items.take(w.e.backpack, SEED.get(crop), 1) == 0) return Result.DONE;
            w.lookAt(next.getX() + 0.5, next.getY() + 0.2, next.getZ() + 0.5);
            w.swing();
            next.setType(crop, true);
            w.plugin().blockLog().placed(w.e, next);
            w.placeEffect(next);
            w.e.spots.put(BlockPos.of(next), crop);
            w.plugin().manager().markDirty();
            return Result.RUNNING;
        }

        @Override
        public String status(Worker w) {
            return "Planting crops";
        }
    }

    private interface BlockTest {
        boolean test(World world, int x, int y, int z);
    }

    private static Block closestNearby(Worker w, BlockTest test) {
        World world = w.world();
        double ex = w.e.location.getX(), ey = w.e.location.getY() + 1, ez = w.e.location.getZ();
        int bx = (int) Math.floor(ex), by = (int) Math.floor(w.e.location.getY()), bz = (int) Math.floor(ez);
        Block best = null;
        double bestD = REACH * REACH;
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                int x = bx + dx, z = bz + dz;
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int dy = -2; dy <= 3; dy++) {
                    int y = by + dy;
                    double ddx = x + 0.5 - ex, ddz = z + 0.5 - ez, ddy = (y + 0.5 - ey) * 0.5;
                    double d = ddx * ddx + ddz * ddz + ddy * ddy;
                    if (d > bestD) continue;
                    if (!test.test(world, x, y, z)) continue;
                    bestD = d;
                    best = world.getBlockAt(x, y, z);
                }
            }
        }
        return best;
    }
}
