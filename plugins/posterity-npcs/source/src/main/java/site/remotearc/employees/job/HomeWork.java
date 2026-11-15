package site.remotearc.employees.job;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import site.remotearc.employees.Employee;
import site.remotearc.employees.build.Blueprints;
import site.remotearc.employees.build.BuildOrder;
import site.remotearc.employees.storage.Supply;
import site.remotearc.employees.util.BlockPos;
import site.remotearc.employees.util.Items;
import site.remotearc.employees.util.Pathfinder;

import java.util.EnumMap;
import java.util.Map;

/**
 * An employee building its own home ({@code /employees house <name> self on}):
 * on a break every few minutes ({@code homes.self-build-break-minutes}), and
 * whenever its job has nothing to do, it spends a minute placing blocks of its
 * home site, bottom up, with materials from its backpack and the team's
 * storage. Slow, but free.
 */
public final class HomeWork {

    private HomeWork() {}

    private static final int SESSION_TICKS = 20 * 60;
    private static final int MAX_DISTANCE = 96;

    /** A home-building task if it's time for a break (or one is under way), or null. */
    static Task check(Worker w) {
        if (w.homeSessionUntil != 0 && w.now() >= w.homeSessionUntil) endSession(w);   // break's over
        if (!ready(w)) return null;
        if (w.homeSessionUntil != 0) return session(w);
        if (w.now() < w.nextHomeBreak) return null;
        w.homeSessionUntil = w.now() + SESSION_TICKS;
        return session(w);
    }

    /** Its job has nothing for it to do: free time, so it works on its home. */
    static Task idle(Worker w) {
        if (!ready(w) || !w.cooldownReady("home-idle", 200)) return null;
        if (w.homeSessionUntil == 0) w.homeSessionUntil = w.now() + SESSION_TICKS;
        return session(w);
    }

    private static Task session(Worker w) {
        Task t = next(w);
        if (t == null) endSession(w);
        return t;
    }

    private static void endSession(Worker w) {
        w.homeSessionUntil = 0;
        w.nextHomeBreak = w.now() + w.settings().homeSelfBuildBreakTicks;
    }

    private static boolean ready(Worker w) {
        Employee e = w.e;
        BuildOrder o = e.homeSite;
        if (!e.selfBuild || o == null || e.siteBed == null || !w.settings().homesEnabled) return false;
        // Duty first: a bodyguard following its owner, anyone being attacked, and guards with enemies about don't go off to build.
        // (It keeps going after moving in, at the finished-percent mark, until the last block of the roof is on.)
        if (!w.job.wandersHome(w) || w.attacker() != null) return false;
        if ((e.job == JobType.GUARD || e.job == JobType.BODYGUARD)
                && !w.world().getNearbyEntities(e.location, 16, 8, 16, en -> en instanceof org.bukkit.entity.Enemy).isEmpty()) return false;
        if (!w.world().getName().equals(o.world)) return false;
        if (e.siteBed.horizontalDistanceSq(e.location.getX(), e.location.getZ()) > MAX_DISTANCE * MAX_DISTANCE) return false;
        if (!w.world().isChunkLoaded(e.siteBed.x() >> 4, e.siteBed.z() >> 4)) return false;
        if (o.plan == null) {
            if (o.loadError == null && w.cooldownReady("home-load", 200)) {
                w.plugin().blueprints().load(o.blueprint).whenComplete((bp, err) -> {
                    if (err != null) o.loadError = Blueprints.reason(err);
                    else if (o.plan == null) o.setPlan(bp);
                });
            }
            return false;
        }
        return true;
    }

    /** The next block to place (fetching materials first if need be), or null if there's nothing it can do now. */
    private static Task next(Worker w) {
        BuildOrder o = w.e.homeSite;
        World world = w.world();
        Map<Material, Integer> inStorage = null;
        Material missing = null;
        boolean skipped = false;   // something left that it can't do right now (so the home isn't finished)
        int fx = w.e.location.getBlockX(), fy = (int) Math.floor(w.e.location.getY() + 0.01), fz = w.e.location.getBlockZ();
        for (int ly = 0; ly < o.plan.sy; ly++) {
            for (int lz = 0; lz < o.plan.sz; lz++) {
                for (int lx = 0; lx < o.plan.sx; lx++) {
                    int idx = o.cell(lx, ly, lz);
                    if (idx < 0) continue;
                    BlockData want = o.data(idx);
                    if (BuildOrder.unbuildable(want)) continue;
                    BlockPos p = o.toWorld(lx, ly, lz);
                    if (!world.isChunkLoaded(p.x() >> 4, p.z() >> 4)) {
                        skipped = true;
                        continue;
                    }
                    Block b = p.block(world);
                    if (b.getType() == want.getMaterial()) continue;
                    boolean air = want.getMaterial().isAir();
                    if (air) {
                        // Inside the house: dig out what's in the way (dirt in the living room). The yard round it is left alone.
                        if (!o.clearAir || o.isAround(lx, ly, lz) || b.getType().isAir() || b.isLiquid() || b.isReplaceable()) continue;
                    }
                    // Never: chests and other containers (their contents would be lost), bedrock, spawners;
                    // no-work zones and other people's land; spots it couldn't get to lately.
                    if (Ground.protectedBlock(b) || !w.mayWork(p) || w.isUnreachable(p)) {
                        skipped = true;
                        continue;
                    }
                    if (!air && p.x() == fx && p.z() == fz && (p.y() == fy || p.y() == fy + 1)) {   // where it's standing
                        skipped = true;
                        continue;
                    }
                    // The free half of a bed or door goes in once the half that costs the item is in (no free beds).
                    if (BuildOrder.isFree(want) && !partnerPlaced(world, p, want)) {
                        skipped = true;
                        continue;
                    }
                    if (!air) {
                        Material item = BuildOrder.itemFor(want);
                        int count = BuildOrder.countFor(want);
                        if (item != null && Items.count(w.e.backpack, item) < count) {
                            if (inStorage == null) inStorage = Supply.available(w.plugin(), w.e);
                            if (inStorage.getOrDefault(item, 0) >= count) {
                                Task f = fetch(w, o, inStorage, item);
                                if (f != null) return f;
                            }
                            if (missing == null) missing = item;
                            continue;
                        }
                    }
                    return new PlaceHome(p, want);
                }
            }
        }
        if (missing != null && w.cooldownReady("home-missing", 20 * 600)) {
            w.plugin().manager().events().add(w.e.owner, w.e.name, "Needs " + Items.name(missing).toLowerCase()
                    + " to keep building their home (put some in storage)", true);
        }
        if (missing == null && !skipped) {
            // Every block is in: done for good.
            w.e.selfBuild = false;
            w.homeMaterials.clear();
            w.plugin().manager().refreshTickets();
            w.plugin().manager().markDirty();
            w.plugin().manager().events().add(w.e.owner, w.e.name, "Finished building their home", false);
        }
        return null;
    }

    /** For the free half of a bed or door: is the half that costs the item already in place? */
    private static boolean partnerPlaced(World world, BlockPos p, BlockData want) {
        if (want instanceof org.bukkit.block.data.type.Bed bed) {
            org.bukkit.block.BlockFace back = bed.getFacing().getOppositeFace();
            return world.getBlockAt(p.x() + back.getModX(), p.y(), p.z() + back.getModZ()).getType() == want.getMaterial();
        }
        return world.getBlockAt(p.x(), p.y() - 1, p.z()).getType() == want.getMaterial();   // the top half of a door, a tall flower
    }

    /** True for materials it fetched for its home: they stay in its backpack instead of being delivered (or sold). */
    static boolean reserves(Worker w, Material m) {
        if (w.homeMaterials.isEmpty()) return false;
        if (!w.e.selfBuild || w.e.homeSite == null) {
            w.homeMaterials.clear();
            return false;
        }
        return w.homeMaterials.contains(m);
    }

    /**
     * Fetches materials for the next blocks: the one it needs now first, then
     * more of what the home still needs that storage has, up to a stack of each.
     */
    private static Task fetch(Worker w, BuildOrder o, Map<Material, Integer> inStorage, Material first) {
        Map<Material, Integer> remaining = o.remaining(w.world());
        Map<Material, Integer> want = new java.util.LinkedHashMap<>();
        java.util.List<Material> order = new java.util.ArrayList<>();
        order.add(first);
        for (Material m : remaining.keySet()) if (m != first) order.add(m);
        for (Material m : order) {
            int have = Items.count(w.e.backpack, m);
            int need = Math.min(remaining.getOrDefault(m, 1) - have, m.getMaxStackSize());
            int there = inStorage.getOrDefault(m, 0);
            if (need > 0 && there > 0) want.put(m, Math.min(need, there));
            if (want.size() >= 6) break;
        }
        if (want.isEmpty()) return null;
        Task t = FetchTask.create(w, want, "materials for their home");
        if (t != null) w.homeMaterials.addAll(want.keySet());
        return t;
    }

    /** Walks within reach of one cell of its home, digs out what's in the way, and places the block. */
    static final class PlaceHome implements Task {
        private final BlockPos pos;
        private final BlockData want;
        private final Vector target;
        private Pathfinder.Goal goal;
        private boolean arrived;
        private int timer, need = -1;

        PlaceHome(BlockPos pos, BlockData want) {
            this.pos = pos;
            this.want = want;
            this.target = new Vector(pos.centerX(), pos.centerY(), pos.centerZ());
        }

        @Override
        public Vector workTarget() {
            return target;
        }

        @Override
        public Result tick(Worker w) {
            if (!arrived) {
                // Miners walk to their home like anyone else rather than tunnelling to it.
                if (w.job.movement() == Job.Movement.DIG) w.moveAs = Job.Movement.WALK;
                if (goal == null) goal = Pathfinder.reachBlock(w.world(), pos, Pathfinder.REACH - 0.3, false);
                switch (w.walk(goal, target)) {
                    case MOVING -> {
                        return Result.RUNNING;
                    }
                    case FAILED -> {
                        // Not a failure of its job: skip this spot for a while, and don't count it
                        // toward the "stuck, go back to the work spot" streak.
                        w.markUnreachable(target);
                        return Result.DONE;
                    }
                    case ARRIVED -> arrived = true;
                }
            }
            w.lookAt(pos);
            Block b = pos.block(w.world());
            if (b.getType() == want.getMaterial()) return Result.DONE;
            if (Ground.protectedBlock(b) || !w.mayWork(pos)) {   // the world changed meanwhile
                w.markUnreachable(target);
                return Result.DONE;
            }
            boolean clear = b.getType().isAir() || b.isLiquid() || b.isReplaceable();
            if (!clear) {
                // Dig it out first, as slowly as a player would.
                if (need < 0) need = w.breakTicks(b);
                timer++;
                if (timer % 4 == 1) w.swing();
                if (need > 2) w.crack(pos, (float) timer / need);
                if (timer < need) return Result.RUNNING;
                w.crack(pos, 0f);
                w.stash(w.breakBlock(b, true, true));
                w.e.stats.blocksMined++;
                timer = 0;
                need = -1;
                if (want.getMaterial().isAir()) return Result.DONE;
                return Result.RUNNING;
            }
            if (want.getMaterial().isAir()) return Result.DONE;
            if (++timer < w.clickTicks()) return Result.RUNNING;
            if (want.getMaterial().isSolid() && !w.world().getNearbyEntities(BoundingBox.of(b), en -> en instanceof LivingEntity).isEmpty()) {
                w.markUnreachable(target);   // someone's standing there: another block first, this one in a minute
                return Result.DONE;
            }
            Material item = BuildOrder.itemFor(want);
            if (item != null) {
                int count = BuildOrder.countFor(want);
                if (Items.count(w.e.backpack, item) < count) return Result.DONE;   // ran out meanwhile; the next pick fetches more
                Items.take(w.e.backpack, item, count);
            }
            w.swing();
            w.plugin().blockLog().removing(w.e, b);
            b.setBlockData(want, false);
            w.plugin().blockLog().placed(w.e, b);
            w.placeEffect(b);
            w.e.stats.blocksPlaced++;
            w.plugin().manager().markDirty();
            return Result.DONE;
        }

        @Override
        public void end(Worker w) {
            w.moveAs = null;
        }

        @Override
        public String status(Worker w) {
            return "Building their home" + (w.e.siteProgress >= 0 ? " (" + w.e.siteProgress + "% done)" : "");
        }
    }
}
