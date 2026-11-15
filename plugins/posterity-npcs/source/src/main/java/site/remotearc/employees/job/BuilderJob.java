package site.remotearc.employees.job;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Door;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import site.remotearc.employees.build.Blueprint;
import site.remotearc.employees.build.Blueprints;
import site.remotearc.employees.build.BuildOrder;
import site.remotearc.employees.storage.Supply;
import site.remotearc.employees.util.BlockPos;
import site.remotearc.employees.util.Items;
import site.remotearc.employees.util.Pathfinder;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a blueprint bottom-up, one layer at a time: first clears what's in
 * the way and places the solid blocks, then the things that hang on them
 * (torches, doors, carpets, flowers). Blocks are placed exactly as drawn,
 * without block updates, the way WorldEdit pastes, so nothing pops off
 * half-built. Materials come from the backpack, topped up from the supply
 * (the team's storage by default). Blocks with no materials anywhere are
 * skipped and retried later, so one missing item doesn't stop the build.
 * When every layer is done it checks the whole build once more.
 *
 * In repair mode a finished build is looked over every
 * jobs.builder.repair-check-seconds, and blocks that have gone missing (blown
 * up, burnt, broken) are put back from the supply. Repairs only fill gaps:
 * nothing is cleared or swapped, so things added since stay.
 */
final class BuilderJob implements Job {

    /** One piece of work: a world position and the palette index wanted there (-1 = just clear it). */
    private record Op(BlockPos pos, int idx) {}

    private BuildOrder order;
    private String loadingFor;
    private int scanned = -1;
    /** Digging out the plan's air from the top down, before building: done this pass; the layer it's at. */
    private boolean excavated;
    private int digLayer = -1, digScanned = -1;
    private final List<Op> main = new ArrayList<>();
    private final List<Op> later = new ArrayList<>();
    /** Ops put off this pass (no materials, someone standing there), and how often each was. */
    private final Map<Long, Integer> deferred = new HashMap<>();
    private final Set<Long> givenUp = new HashSet<>();
    private final Map<Material, Integer> missing = new EnumMap<>(Material.class);
    private Map<Material, Integer> supply = Map.of();
    private boolean passClean = true;
    /** Extra passes over the whole build after the first, to fix what got disturbed. */
    private int passes;
    /** Blocks it couldn't get to this pass, and when the next pass may start (so "unreachable" marks expire first). */
    private int unreachedThisPass;
    private long recheckAt;
    private Set<Material> uses = Set.of();
    private BuildOrder usesFor;
    /** The blueprint the lists above were worked out from. */
    private site.remotearc.employees.build.Blueprint planSeen;
    /** The swaps uses was worked out for. */
    private int swapsSeen;
    private String idle = "No blueprint";
    /** A repair pass is under way; how many blocks it put back; when the next one may start. */
    private boolean repairing;
    private int repaired;
    private long nextRepair;

    @Override
    public Task next(Worker w) {
        BuildOrder o = w.e.build;
        if (o != order) {
            order = o;
            restart(o);
            planSeen = o == null ? null : o.plan;
        } else if (o != null && o.plan != planSeen) {
            // The plan itself changed (planned in the world, edited while building): work it out afresh.
            if (planSeen != null) restart(o);
            planSeen = o.plan;
        }
        if (o == null) {
            idle = "No blueprint: /employees build " + w.e.name + " <file>";
            return null;
        }
        if (o.onHold) {
            idle = "Waiting while you plan (builds once you press Done)";
            return null;
        }
        if (o.finished && o.repair && o.started && w.world().getName().equals(o.world)) {
            if (o.plan == null) {
                load(w, o);
                idle = o.loadError != null ? "Can't load " + o.blueprint + ": " + o.loadError : "Reading blueprint " + o.blueprint;
                return null;
            }
            if (w.now() < nextRepair) {
                idle = "Looking after " + o.blueprint + " (repairs what goes missing)";
                return null;
            }
            nextRepair = w.now() + 20L * w.settings().repairCheckSeconds;
            o.finished = false;
            repairing = true;
            repaired = 0;
            givenUp.clear();
            deferred.clear();
            startPass(o);
            passes = 3;   // one look over the build, not the extra checking passes of a new build
        }
        if (o.finished) {
            idle = "Finished building " + o.blueprint;
            return null;
        }
        if (o.plan == null) {
            load(w, o);
            idle = o.loadError != null ? "Can't load " + o.blueprint + ": " + o.loadError : "Reading blueprint " + o.blueprint;
            return null;
        }
        if (!o.started) {
            idle = "Ready to build " + o.blueprint + ": /employees build " + w.e.name + " start";
            return null;
        }
        if (o.finished) {
            idle = "Finished building " + o.blueprint;
            return null;
        }
        World world = w.world();
        if (!world.getName().equals(o.world)) {
            idle = "The build is in another world";
            return null;
        }
        if (o.dismantle) return dismantle(w, o, world);
        int swapsNow = o.swaps.hashCode() * 31 + o.replacements.hashCode();
        if (usesFor != o || uses.isEmpty() || swapsNow != swapsSeen) {
            if (usesFor == o && swapsNow != swapsSeen) {
                // Swapped mid-build: look the build over again with the new blocks.
                startPass(o);
                craftTries.clear();
                craftFetched = null;
            }
            uses = itemsUsed(o);
            usesFor = o;
            swapsSeen = swapsNow;
        }

        // First dig out what the plan has as air, from the top down, like a basement: each layer
        // dug opens the way to the one below. Then build from the bottom up.
        if (!excavated && o.clearAir && !repairing) {
            if (digLayer < 0) {
                digLayer = o.plan.sy - 1;
                digScanned = -1;
            }
            for (int guard = 0; guard < 8 && digLayer >= 0; guard++) {
                if (digScanned != digLayer) scanClearing(w, o, world, digLayer);
                Task t = work(w, o, world);
                if (t != null) {
                    passClean = false;
                    return t;
                }
                if (!main.isEmpty() || !later.isEmpty()) return null;
                digLayer--;
            }
            if (digLayer >= 0) return null;
            excavated = true;
            main.clear();
            later.clear();
            scanned = -1;
        }

        for (int guard = 0; guard < 8 && o.layer < o.plan.sy; guard++) {
            if (scanned != o.layer) scan(w, o, world);
            Task t = work(w, o, world);
            if (t != null) {
                passClean = false;
                return t;
            }
            if (!main.isEmpty() || !later.isEmpty()) return null;   // waiting on something reachable later
            o.layer++;
            scanned = -1;
            w.plugin().manager().markDirty();
        }
        if (o.layer < o.plan.sy) return null;

        // Every layer has been through once.
        if (repairing && !missing.isEmpty()) {
            // Repairs never wait for materials: what's missing is tried again next time.
            int n = missing.values().stream().mapToInt(Integer::intValue).sum();
            w.plugin().manager().events().add(w.e.owner, w.e.name, "Couldn't put back " + n + " block" + (n == 1 ? "" : "s")
                    + " of " + o.blueprint + ": no " + missingText(3) + " in storage", true);
            missing.clear();
        }
        if (!missing.isEmpty() && !repairing) {
            if (crafted) {
                // Made some of what was missing: go round again and place it.
                crafted = false;
                startPass(o);
                idle = "Placing what it crafted";
                return null;
            }
            Task craft = craftMissing(w, o);
            if (craft != null) return craft;
        }
        if (!missing.isEmpty()) {
            idle = "Waiting for materials: " + missingText(4) + (craftNote != null ? " (can't craft " + craftNote + ")" : "");
            if (w.cooldownReady("build-retry", 20 * 30)) startPass(o);
            return null;
        }
        if (resumed && !repairing) {
            // Picked up part-way (a restart, a change): blocks skipped further down were never re-checked.
            resumed = false;
            idle = "Checking the whole build";
            startPass(o);
            return null;
        }
        if (!passClean && passes < 3) {
            if (unreachedThisPass > 0 && recheckAt == 0) recheckAt = w.now() + 20 * 60;
            if (w.now() < recheckAt) {
                idle = "Couldn't reach " + unreachedThisPass + " blocks; trying again shortly";
                return null;
            }
            idle = "Checking the build";
            passes++;
            startPass(o);
            return null;
        }
        if (!o.tunnel.isEmpty() && !repairing) {
            // Done: fill in the way it dug in through the ground, nearest first.
            double x = w.e.location.getX(), y = w.e.location.getY(), z = w.e.location.getZ();
            long best = 0;
            double bestD = Double.MAX_VALUE;
            for (long k : o.tunnel.keySet()) {
                double d = BlockPos.unpack(k).distanceSq(x, y, z);
                if (d < bestD) {
                    bestD = d;
                    best = k;
                }
            }
            BlockData was;
            try {
                was = org.bukkit.Bukkit.createBlockData(o.tunnel.get(best));
            } catch (IllegalArgumentException ex) {
                o.tunnel.remove(best);
                return null;
            }
            return new FillTunnelTask(world, BlockPos.unpack(best), was);
        }
        o.finished = true;
        w.plugin().manager().markDirty();
        if (repairing) {
            repairing = false;
            if (repaired > 0) {
                w.plugin().manager().notifyOwner(w.e, "<green>" + w.e.name + " repaired " + repaired + " block" + (repaired == 1 ? "" : "s")
                        + " of " + o.blueprint + ".", false);
            }
            idle = "Looking after " + o.blueprint + " (repairs what goes missing)";
            return null;
        }
        int left = givenUp.size() + unreachedThisPass;
        double refund = refundStock(w, o);
        w.plugin().manager().notifyOwner(w.e, "<green>" + w.e.name + " finished building " + o.blueprint
                + (left > 0 ? "<yellow>, except " + left + " blocks it couldn't reach or place" : "") + "."
                + (refund > 0 ? " <gray>The bought materials it didn't need were refunded: <white>"
                    + site.remotearc.employees.util.Msg.money(refund) + "</white>." : ""), true);
        idle = "Finished building " + o.blueprint;
        return null;
    }

    // ── crafting what's missing ───────────────────────────────────────────

    /** Fetched to craft with, or crafted: kept in the backpack instead of delivered. */
    private final Set<Material> craftKeep = EnumSet.noneOf(Material.class);
    private boolean crafted;
    /** Just fetched the ingredients for this: craft it from the backpack straight away, before a pass uses them up. */
    private Material craftFetched;
    /** Items it tried to craft and couldn't (the ingredients couldn't be fetched): skipped for a while so others get their turn. */
    private final Map<Material, Integer> craftTries = new EnumMap<>(Material.class);
    /** Why what's missing wasn't crafted, for the status line. */
    private String craftNote;

    /**
     * At the end of a pass with blocks skipped for lack of materials: craft
     * them from what's spare (in the backpack, storage, or bought for the
     * build, less what the build itself still needs). Fetches the ingredients
     * first, then crafts, then starts a new pass to place what it made.
     */
    private Task craftMissing(Worker w, BuildOrder o) {
        if (!w.e.craft) {
            craftNote = null;
            return null;
        }
        Map<Material, Integer> spare = site.remotearc.employees.build.BuildMaterials.spare(w.plugin(), w.e, o);
        if (craftFetched != null) {
            Material m = craftFetched;
            craftFetched = null;
            Integer n = missing.get(m);
            if (n != null) {
                // Only what's in the backpack now, and only what the build can spare of it.
                Map<Material, Integer> inPack = new EnumMap<>(Material.class);
                for (ItemStack it : w.e.backpack.getStorageContents()) if (Items.isPlain(it)) inPack.merge(it.getType(), it.getAmount(), Integer::sum);
                inPack.replaceAll((k, v) -> Math.min(v, spare.getOrDefault(k, 0)));
                Crafting.Plan plan = Crafting.plan(m, Math.min(n, 256), 1, inPack, w.e.craftNever, site.remotearc.employees.build.BuildMaterials.DEPTH);
                if (plan != null) {
                    craftTries.remove(m);
                    for (Crafting.Step st : plan.steps()) craftKeep.add(st.result());
                    crafted = true;
                    return new CraftTask(plan.steps(), "for " + o.blueprint);
                }
            }
            craftTries.merge(m, 1, Integer::sum);
        }
        if (!w.cooldownReady("build-craft", 20 * 10)) return null;   // the last reason stays on show meanwhile
        craftNote = null;
        if (w.cooldownReady("build-craft-forget", 20 * 300)) craftTries.clear();
        List<String> why = new ArrayList<>();
        List<Map.Entry<Material, Integer>> wanted = new ArrayList<>(missing.entrySet());
        wanted.sort((a, b) -> b.getValue() - a.getValue());
        for (Map.Entry<Material, Integer> m : wanted) {
            String name = Items.name(m.getKey());
            if (craftTries.getOrDefault(m.getKey(), 0) >= 3) {
                why.add(name + ": couldn't fetch what it's made from");
                continue;
            }
            Crafting.Plan plan = Crafting.plan(m.getKey(), Math.min(m.getValue(), 256), 1, spare, w.e.craftNever, site.remotearc.employees.build.BuildMaterials.DEPTH);
            if (plan == null) {
                why.add(name + ": nothing spare to craft it from");
                continue;
            }
            Map<Material, Integer> fetch = new EnumMap<>(Material.class);
            for (Map.Entry<Material, Integer> r : plan.raw().entrySet()) {
                Material raw = r.getKey();
                craftKeep.add(raw);
                int short_ = r.getValue() - Items.count(w.e.backpack, raw);
                int fromStock = Math.min(short_, o.stocked(raw));
                if (fromStock > 0) {
                    // Bought for this build: it has them already, they just go into the backpack.
                    o.takeStock(raw, fromStock);
                    List<ItemStack> items = new ArrayList<>();
                    for (int left = fromStock; left > 0; left -= raw.getMaxStackSize()) items.add(new ItemStack(raw, Math.min(left, raw.getMaxStackSize())));
                    w.stash(items);
                    w.plugin().manager().markDirty();
                    short_ -= fromStock;
                }
                if (short_ > 0) fetch.put(raw, short_);
            }
            for (Crafting.Step st : plan.steps()) craftKeep.add(st.result());
            // Fetched or not, it crafts from the backpack next, before anything else can use the ingredients.
            craftFetched = m.getKey();
            if (!fetch.isEmpty()) {
                Task t = FetchTask.create(w, fetch, "materials to craft " + name);
                if (t != null) return t;
                why.add(name + ": no room in the backpack for what it's made from");
                craftFetched = null;
                craftTries.merge(m.getKey(), 1, Integer::sum);
                continue;
            }
            return craftMissing(w, o);
        }
        if (!why.isEmpty()) craftNote = String.join("; ", why.subList(0, Math.min(2, why.size())));
        return null;
    }

    // ── taking it down ────────────────────────────────────────────────────

    /** Started part-way through (o.layer > 0): check every layer once more before calling it finished. */
    private boolean resumed;
    private int taken;

    /**
     * Takes the build down, top layer first: breaks every block that's still
     * what the blueprint has there (nothing else: things added since stay,
     * and chests with something in them too) and keeps the block itself, so
     * it all goes back to storage with the normal deliveries.
     */
    private Task dismantle(Worker w, BuildOrder o, World world) {
        if (w.backpackFull()) {
            idle = "Taking down " + o.blueprint + ": backpack full, dropping it off first";
            return null;
        }
        for (int guard = 0; guard < 8 && o.layer < o.plan.sy; guard++) {
            if (scanned != o.layer) scanDismantle(w, o, world);
            Task t = work(w, o, world);
            if (t != null) {
                passClean = false;
                return t;
            }
            if (!main.isEmpty() || !later.isEmpty()) return null;
            o.layer++;
            scanned = -1;
            w.plugin().manager().markDirty();
        }
        if (o.layer < o.plan.sy) return null;
        if (!passClean && passes < 2) {
            passes++;
            startPass(o);
            return null;
        }
        w.e.build = null;
        w.plugin().manager().markDirty();
        w.plugin().manager().notifyOwner(w.e, "<green>" + w.e.name + " took down " + o.blueprint + (taken > 0 ? " (" + taken + " blocks)" : "")
                + ". The blocks go back to " + (w.e.storage.kind() == site.remotearc.employees.storage.StorageTarget.Kind.NONE
                    ? "its backpack." : w.e.storage.describe() + "."), true);
        idle = "Took down " + o.blueprint;
        return null;
    }

    private void scanDismantle(Worker w, BuildOrder o, World world) {
        main.clear();
        later.clear();
        scanned = o.layer;
        int ly = o.plan.sy - 1 - o.layer;
        for (int lz = 0; lz < o.plan.sz; lz++) {
            for (int lx = 0; lx < o.plan.sx; lx++) {
                int idx = o.cell(lx, ly, lz);
                if (idx < 0) continue;
                BlockData want = o.data(idx);
                if (want.getMaterial().isAir() || BuildOrder.unbuildable(want)) continue;
                BlockPos p = o.toWorld(lx, ly, lz);
                if (givenUp.contains(p.key()) || !world.isChunkLoaded(p.x() >> 4, p.z() >> 4) || !w.mayWork(p)) continue;
                Block b = p.block(world);
                if (!matches(b, want) || b.getType().getHardness() < 0) continue;
                if (b.getState(false) instanceof org.bukkit.block.Container c && !c.getInventory().isEmpty()) continue;
                // Torches, doors and the like come off before what they hang on.
                (attaches(want) ? main : later).add(new Op(p, -2 - idx));
            }
        }
    }

    private void restart(BuildOrder o) {
        repairing = false;
        nextRepair = 0;
        scanned = -1;
        main.clear();
        later.clear();
        deferred.clear();
        givenUp.clear();
        missing.clear();
        passClean = true;
        passes = 0;
        uses = Set.of();
        loadingFor = null;
        craftKeep.clear();
        needLeft = null;
        crafted = false;
        excavated = false;
        digLayer = -1;
        craftFetched = null;
        craftTries.clear();
        resumed = o != null && o.layer > 0 && !o.dismantle;
        if (o == null || !o.dismantle) taken = 0;
    }

    private void startPass(BuildOrder o) {
        excavated = false;
        digLayer = -1;
        o.layer = 0;
        scanned = -1;
        missing.clear();
        passClean = true;
        unreachedThisPass = 0;
        recheckAt = 0;
        supply = Map.of();
    }

    private void load(Worker w, BuildOrder o) {
        if (o.blueprint.equals(loadingFor) || o.loadError != null) return;
        loadingFor = o.blueprint;
        w.plugin().blueprints().load(o.blueprint).whenComplete((bp, err) -> {
            if (w.e.build != o) return;
            if (err != null) o.loadError = Blueprints.reason(err);
            else {
                o.setPlan(bp);
                site.remotearc.employees.build.Plans.rememberOrigin(w.plugin(), o, bp);
            }
            loadingFor = null;
        });
    }

    @Override
    public void reset(Worker w) {
        order = null;   // re-read the order (moved, turned, swapped, new blueprint) on the next call
        usesFor = null;
    }

    /** Pays the owner back for materials bought for a build that it didn't use; returns the amount. */
    static double refundStock(Worker w, BuildOrder o) {
        double v = o.takeStockValue();
        var econ = w.plugin().economy();
        if (v <= 0 || econ == null) return 0;
        econ.depositPlayer(org.bukkit.Bukkit.getOfflinePlayer(w.e.owner), v);
        w.plugin().manager().markDirty();
        return v;
    }

    // ── layers ────────────────────────────────────────────────────────────

    /** Lists what has to be dug out of one layer: cells the plan has as air that aren't. */
    private void scanClearing(Worker w, BuildOrder o, World world, int ly) {
        main.clear();
        later.clear();
        digScanned = ly;
        for (int lz = 0; lz < o.plan.sz; lz++) {
            for (int lx = 0; lx < o.plan.sx; lx++) {
                int idx = o.cell(lx, ly, lz);
                if (idx < 0 || !o.data(idx).getMaterial().isAir()) continue;
                BlockPos p = o.toWorld(lx, ly, lz);
                if (givenUp.contains(p.key()) || !world.isChunkLoaded(p.x() >> 4, p.z() >> 4) || !w.mayWork(p)) continue;
                Block b = p.block(world);
                if (needsClearing(b) && !Ground.protectedBlock(b)) main.add(new Op(p, -1));
            }
        }
    }

    /** Lists what layer o.layer still needs, comparing the blueprint with the world. */
    private void scan(Worker w, BuildOrder o, World world) {
        main.clear();
        later.clear();
        scanned = o.layer;
        Blueprint bp = o.plan;
        int ly = o.layer;
        for (int lz = 0; lz < bp.sz; lz++) {
            for (int lx = 0; lx < bp.sx; lx++) {
                int idx = o.cell(lx, ly, lz);
                if (idx < 0) continue;
                BlockPos p = o.toWorld(lx, ly, lz);
                if (givenUp.contains(p.key()) || !world.isChunkLoaded(p.x() >> 4, p.z() >> 4) || !w.mayWork(p)) continue;
                Block b = p.block(world);
                BlockData want = o.data(idx);
                if (repairing) {
                    if (!want.getMaterial().isAir() && !BuildOrder.unbuildable(want) && !growsBack(want) && gap(b) && !matches(b, want)) {
                        (attaches(want) ? later : main).add(new Op(p, idx));
                    }
                    continue;
                }
                if (want.getMaterial().isAir()) {
                    if (o.clearAir && needsClearing(b)) main.add(new Op(p, -1));
                } else if (!BuildOrder.unbuildable(want) && !matches(b, want)) {
                    if (Ground.protectedBlock(b) && b.getType() != want.getMaterial()) continue;
                    (attaches(want) ? later : main).add(new Op(p, idx));
                }
            }
        }
    }

    /** Ground blocks that turn into each other on their own (grass under a floor becomes dirt). */
    private static final Set<Material> SOIL = EnumSet.of(Material.GRASS_BLOCK, Material.DIRT, Material.PODZOL, Material.MYCELIUM,
            Material.DIRT_PATH, Material.FARMLAND, Material.ROOTED_DIRT, Material.COARSE_DIRT);

    /**
     * Built as drawn. Only the block type is compared: states change by
     * themselves (fences connect, crops grow, grass gets snowy), and chasing
     * them would never finish.
     */
    private static boolean matches(Block b, BlockData want) {
        Material have = b.getType(), m = want.getMaterial();
        return have == m || (SOIL.contains(have) && SOIL.contains(m));
    }

    /** Plants that come and go by themselves (grass, crops, leaves): not repaired. */
    private static boolean growsBack(BlockData d) {
        Material m = d.getMaterial();
        return org.bukkit.Tag.REPLACEABLE.isTagged(m) || org.bukkit.Tag.LEAVES.isTagged(m) || org.bukkit.Tag.SAPLINGS.isTagged(m)
                || (d instanceof org.bukkit.block.data.Ageable && !(d instanceof org.bukkit.block.data.type.Fire));
    }

    /** Somewhere a block has gone missing: air, water or lava that flowed in, fire, or grass that grew there. */
    private static boolean gap(Block b) {
        Material m = b.getType();
        return m.isAir() || b.isLiquid() || m == Material.FIRE || m == Material.SOUL_FIRE || (b.isReplaceable() && !m.isSolid());
    }

    private static boolean needsClearing(Block b) {
        Material m = b.getType();
        return !m.isAir() && !b.isLiquid() && !Ground.protectedBlock(b);
    }

    /** Placed after the layer's solid blocks: things that sit on or hang from others. */
    private static boolean attaches(BlockData d) {
        Material m = d.getMaterial();
        return !m.isSolid() || d instanceof Door || m == Material.LADDER;
    }

    /** The next job in the current layer: build here, or fetch what's needed first. */
    private Task work(Worker w, BuildOrder o, World world) {
        List<Op> list = !main.isEmpty() ? main : later;
        if (list.isEmpty()) return null;
        Map<Material, Integer> fetch = null;
        double x = w.e.location.getX(), y = w.e.location.getY(), z = w.e.location.getZ();
        List<Op> sorted = new ArrayList<>(list);
        sorted.sort((a, b) -> Double.compare(a.pos.distanceSq(x, y, z), b.pos.distanceSq(x, y, z)));
        Set<Op> drop = new HashSet<>();
        Task result = null;
        for (Op op : sorted) {
            if (w.isUnreachable(op.pos)) continue;
            Block b = op.pos.block(world);
            if (done(o, op, b)) {
                drop.add(op);
                continue;
            }
            if (op.idx < 0 || hasMaterials(w, o, op, b)) {
                result = new BuildTask(world, op);
                break;
            }
            Material item = BuildOrder.itemFor(o.data(op.idx));
            if (item == null) continue;
            if (fetch == null) fetch = fetchList(w, o, list);
            if (fetch.containsKey(item) || (BuildOrder.substitute(item) != null && fetch.containsKey(BuildOrder.substitute(item)))) {
                result = FetchTask.create(w, fetch, "materials");
                if (result != null) break;
            }
            drop.add(op);
            note(op, item, BuildOrder.countFor(o.data(op.idx)));
        }
        if (result == null) {
            // Only unreachable work left in this layer: try it again on a later pass.
            for (Op op : list) {
                if (!drop.contains(op) && w.isUnreachable(op.pos) && passes >= 2) {
                    // The last look over and still no way to it: do it from where it stands (a bit of magic).
                    drop.add(op);
                    remoteOps.add(op);
                    continue;
                }
                if (!drop.contains(op) && w.isUnreachable(op.pos)) {
                    drop.add(op);
                    unreachedThisPass++;
                    passClean = false;
                    if (w.e.debug && unreachedThisPass <= 40) {
                        Block b = op.pos.block(world);
                        w.plugin().getLogger().info("[debug " + w.e.name + "] can't reach " + op.pos.x() + " " + op.pos.y() + " " + op.pos.z()
                                + ": it's " + b.getType().name().toLowerCase() + ", wants " + (op.idx < 0 ? "clearing" : o.data(op.idx).getMaterial().name().toLowerCase()));
                    }
                }
            }
        }
        if (!drop.isEmpty()) list.removeAll(drop);
        for (Op op : remoteOps) {
            if (!doFromAfar(w, o, world, op)) {
                unreachedThisPass++;
                passClean = false;
            }
        }
        remoteOps.clear();
        if (result == null && list.isEmpty() && list == main && !later.isEmpty()) return work(w, o, world);
        return result;
    }

    private final List<Op> remoteOps = new ArrayList<>();

    /**
     * A block it still can't get to on its last look over the build: placed
     * (or cleared) from where it is, with materials from its backpack or
     * stock as usual, and a puff of particles. False if it has nothing to place it with.
     */
    private boolean doFromAfar(Worker w, BuildOrder o, World world, Op op) {
        Block b = op.pos.block(world);
        if (done(o, op, b)) return true;
        if (op.idx < 0) {
            w.plugin().blockLog().removing(w.e, b);
            w.stash(w.breakBlock(b, true, true));
        } else {
            if (!hasMaterials(w, o, op, b)) {
                Material item = BuildOrder.itemFor(o.data(op.idx));
                if (item != null) missing.merge(item, BuildOrder.countFor(o.data(op.idx)), Integer::sum);
                return true;   // counted as missing, not unreachable: crafting or buying can sort it
            }
            if (!b.getType().isAir() && !b.isLiquid() && !b.isReplaceable()) w.stash(w.breakBlock(b, true, true));
            BuildTask t = new BuildTask(world, op);
            t.current = op;
            t.place(w, o, b);
        }
        world.spawnParticle(org.bukkit.Particle.PORTAL, op.pos.centerX(), op.pos.centerY(), op.pos.centerZ(), 12, 0.3, 0.3, 0.3, 0.2);
        return done(o, op, b);
    }

    /** Puts an op off until the next pass: for lack of materials, or because something stands in the way. */
    private void defer(Op op, List<Op> list, Material item, int count) {
        list.remove(op);
        note(op, item, count);
    }

    private void note(Op op, Material item, int count) {
        int times = deferred.merge(op.pos.key(), 1, Integer::sum);
        if (item != null) {
            missing.merge(item, count, Integer::sum);
        } else if (times >= 3) {
            givenUp.add(op.pos.key());   // something keeps standing there
        } else {
            passClean = false;
        }
    }

    private static boolean done(BuildOrder o, Op op, Block b) {
        if (op.idx <= -2) return !matches(b, o.data(-2 - op.idx));   // taking down: gone already
        if (op.idx < 0) return !needsClearing(b);
        return matches(b, o.data(op.idx));
    }

    private boolean hasMaterials(Worker w, BuildOrder o, Op op, Block b) {
        BlockData want = o.data(op.idx);
        if (b.getType() == want.getMaterial()) return true;   // only the state differs: no new item
        Material item = BuildOrder.itemFor(want);
        if (item == null) return true;                         // comes free with its other half
        int count = BuildOrder.countFor(want);
        if (Items.count(w.e.backpack, item) >= count || o.stocked(item) >= count) return true;
        Material sub = BuildOrder.substitute(item);
        return sub != null && (Items.count(w.e.backpack, sub) >= count || o.stocked(sub) >= count);
    }

    /** What the rest of this layer and the next two need, minus the backpack, limited to what the supply has. */
    private Map<Material, Integer> fetchList(Worker w, BuildOrder o, List<Op> list) {
        supply = Supply.available(w.plugin(), w.e);
        Map<Material, Integer> need = new EnumMap<>(Material.class);
        for (Op op : list) addNeed(o, op.idx, need);
        for (int ly = o.layer + 1; ly <= Math.min(o.layer + 2, o.plan.sy - 1); ly++) {
            for (int lz = 0; lz < o.plan.sz; lz++) {
                for (int lx = 0; lx < o.plan.sx; lx++) addNeed(o, o.cell(lx, ly, lz), need);
            }
        }
        Map<Material, Integer> out = new EnumMap<>(Material.class);
        for (Map.Entry<Material, Integer> e : need.entrySet()) {
            Material m = e.getKey();
            int want = e.getValue() - Items.count(w.e.backpack, m) - o.stocked(m);
            if (want <= 0 || w.recentlyMissed(m)) continue;
            int there = supply.getOrDefault(m, 0);
            if (there > 0) out.put(m, Math.min(want, there));
            else {
                Material sub = BuildOrder.substitute(m);
                if (sub != null && supply.getOrDefault(sub, 0) > 0) out.merge(sub, Math.min(want, supply.get(sub)), Integer::sum);
            }
        }
        return out;
    }

    private static void addNeed(BuildOrder o, int idx, Map<Material, Integer> need) {
        if (idx < 0) return;
        BlockData d = o.data(idx);
        if (BuildOrder.unbuildable(d)) return;
        Material item = BuildOrder.itemFor(d);
        if (item != null) need.merge(item, BuildOrder.countFor(d), Integer::sum);
    }

    private static Set<Material> itemsUsed(BuildOrder o) {
        Set<Material> out = EnumSet.noneOf(Material.class);
        for (int i = 0; i < o.plan.palette.size(); i++) {
            Material item = BuildOrder.itemFor(o.data(i));
            if (item == null) continue;
            out.add(item);
            Material sub = BuildOrder.substitute(item);
            if (sub != null) out.add(sub);
        }
        return out;
    }

    /** Internal state for /employees debug. */
    String debugState() {
        BuildOrder o = order;
        return "layer " + (o == null ? -1 : o.layer) + ", main " + main.size() + ", later " + later.size() + ", missing " + missing
                + ", unreached " + unreachedThisPass + ", passes " + passes + ", clean " + passClean + ", resumed " + resumed
                + ", crafted " + crafted + ", craftFetched " + craftFetched + ", tries " + craftTries + ", note " + craftNote;
    }

    String missingText(int max) {
        List<Map.Entry<Material, Integer>> list = new ArrayList<>(missing.entrySet());
        list.sort((a, b) -> b.getValue() - a.getValue());
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < list.size() && i < max; i++) parts.add(list.get(i).getValue() + " " + Items.name(list.get(i).getKey()));
        if (list.size() > max) parts.add("and " + (list.size() - max) + " more");
        return String.join(", ", parts);
    }

    // ── the building itself ───────────────────────────────────────────────

    /** Flies to within reach of a spot, then clears and places every op it can reach from there. */
    private final class BuildTask implements Task {

        private Op current;
        private final Vector target;
        private final Pathfinder.Goal goal;
        private boolean arrived;
        private int timer, need;
        private boolean cleared;
        private int left = 48;

        @Override
        public Vector workTarget() {
            return current != null ? new Vector(current.pos.centerX(), current.pos.centerY(), current.pos.centerZ()) : target;
        }

        BuildTask(World world, Op first) {
            this.current = first;
            this.target = new Vector(first.pos.centerX(), first.pos.centerY(), first.pos.centerZ());
            this.goal = Pathfinder.reachBlock(world, first.pos, Pathfinder.REACH - 0.3, false);
        }

        @Override
        public Result tick(Worker w) {
            if (!arrived) {
                switch (w.walk(goal, target)) {
                    case MOVING -> {
                        return Result.RUNNING;
                    }
                    case FAILED -> {
                        // Can't get to that block: the pass skips it. Not being stuck, so no "go home".
                        w.markUnreachable(target);
                        return Result.DONE;
                    }
                    case ARRIVED -> arrived = true;
                }
            }
            BuildOrder o = w.e.build;
            if (o != order || o == null || o.plan == null) return Result.DONE;
            if (current == null) {
                if (left-- <= 0) return Result.DONE;
                current = nextInReach(w, o);
                timer = need = 0;
                cleared = false;
                if (current == null) return Result.DONE;
            }
            Block b = current.pos.block(w.world());
            if (done(o, current, b)) {
                finish();
                return Result.RUNNING;
            }
            w.lookAt(current.pos);
            if (current.idx <= -2) {
                // Taking down: break it like a player would, and keep the block itself.
                if (need == 0) {
                    need = Math.max(1, w.breakTicks(b));
                    timer = 0;
                }
                timer++;
                if (timer % 4 == 1) w.swing();
                if (need > 2) w.crack(current.pos, (float) timer / need);
                if (timer < need) return Result.RUNNING;
                w.crack(current.pos, 0f);
                BlockData was = b.getBlockData();
                b.getWorld().playEffect(b.getLocation(), org.bukkit.Effect.STEP_SOUND, b.getType());
                w.plugin().blockLog().removing(w.e, b);
                b.setType(Material.AIR, false);
                Material item = BuildOrder.itemFor(was);
                if (item != null) {
                    List<ItemStack> items = new ArrayList<>();
                    for (int left = BuildOrder.countFor(was); left > 0; left -= item.getMaxStackSize()) {
                        items.add(new ItemStack(item, Math.min(left, item.getMaxStackSize())));
                    }
                    w.stash(items);
                }
                taken++;
                need = 0;
                w.plugin().manager().markDirty();
                finish();
                return Result.RUNNING;
            }
            boolean sameType = current.idx >= 0 && matches(b, o.data(current.idx));
            // Break what's in the way first (unless only its state is wrong, or it's something placing replaces).
            if (!cleared && !sameType && !b.getType().isAir() && !b.isLiquid() && (current.idx < 0 || !b.isReplaceable())) {
                if (Ground.protectedBlock(b)) {
                    givenUp.add(current.pos.key());
                    finish();
                    return Result.RUNNING;
                }
                if (need == 0) {
                    need = w.breakTicks(b);
                    timer = 0;
                }
                timer++;
                if (timer % 4 == 1) w.swing();
                if (need > 2) w.crack(current.pos, (float) timer / need);
                if (timer < need) return Result.RUNNING;
                w.crack(current.pos, 0f);
                w.stash(w.breakBlock(b, true, true));
                cleared = true;
                need = 0;
                timer = 0;
                if (current.idx < 0) {
                    finish();
                    return Result.RUNNING;
                }
            }
            if (current.idx < 0) {
                finish();
                return Result.RUNNING;
            }
            if (++timer < w.clickTicks()) return Result.RUNNING;
            place(w, o, b);
            return Result.RUNNING;
        }

        private void place(Worker w, BuildOrder o, Block b) {
            BlockData want = o.data(current.idx);
            BoundingBox cell = BoundingBox.of(b);
            boolean blocked = !w.world().getNearbyEntities(cell, en -> en instanceof LivingEntity && !en.equals(w.e.entity)).isEmpty();
            if (blocked && want.getMaterial().isSolid()) {
                List<Op> list = main.contains(current) ? main : later;
                defer(current, list, null, 0);
                current = null;
                return;
            }
            if (b.getType() != want.getMaterial()) {
                Material item = BuildOrder.itemFor(want);
                if (item != null) {
                    int count = BuildOrder.countFor(want);
                    Material sub = BuildOrder.substitute(item);
                    if (Items.count(w.e.backpack, item) >= count) Items.take(w.e.backpack, item, count);
                    else if (o.stocked(item) >= count) o.takeStock(item, count);   // bought for this build
                    else if (sub != null && Items.count(w.e.backpack, sub) >= count) Items.take(w.e.backpack, sub, count);
                    else if (sub != null && o.stocked(sub) >= count) o.takeStock(sub, count);
                    else {
                        current = null;   // ran out meanwhile; the layer scan will sort it out
                        return;
                    }
                }
            }
            if (want instanceof org.bukkit.block.data.type.Leaves leaves && !leaves.isPersistent()) {
                want = want.clone();
                ((org.bukkit.block.data.type.Leaves) want).setPersistent(true);   // or they'd decay with no log nearby
            }
            w.swing();
            if (b.getType() != want.getMaterial()) w.plugin().blockLog().removing(w.e, b);
            b.setBlockData(want, false);
            if (b.getType() != Material.AIR) w.plugin().blockLog().placed(w.e, b);
            w.placeEffect(b);
            if (repairing) {
                w.e.stats.blocksRepaired++;
                repaired++;
            } else {
                w.e.stats.blocksPlaced++;
            }
            w.plugin().manager().markDirty();
            finish();
        }

        private void finish() {
            main.remove(current);
            later.remove(current);
            current = null;
        }

        /** The nearest op of the current pass this spot can reach, with materials for it. */
        private Op nextInReach(Worker w, BuildOrder o) {
            List<Op> list = !main.isEmpty() ? main : later;
            double ex = w.e.location.getX(), ey = w.e.location.getY() + Pathfinder.EYE_HEIGHT, ez = w.e.location.getZ();
            int bx = w.e.location.getBlockX(), fy = (int) Math.floor(w.e.location.getY() + 0.01), bz = w.e.location.getBlockZ();
            double reach = Pathfinder.REACH - 0.3;
            Op best = null;
            double bestD = Double.MAX_VALUE;
            World world = w.world();
            for (Op op : list) {
                BlockPos p = op.pos;
                if (p.x() == bx && p.z() == bz && p.y() >= fy - 1 && p.y() <= fy + 1) continue;
                double dx = p.centerX() - ex, dy = p.centerY() - ey, dz = p.centerZ() - ez;
                double d = dx * dx + dy * dy + dz * dz;
                if (d > reach * reach || d >= bestD) continue;
                if (op.idx >= 0 && !hasMaterials(w, o, op, p.block(world))) continue;
                best = op;
                bestD = d;
            }
            return best;
        }

        @Override
        public void end(Worker w) {
            if (current != null) w.crack(current.pos, 0f);
        }

        @Override
        public String status(Worker w) {
            BuildOrder o = w.e.build;
            if (o == null || o.plan == null) return "Building";
            if (repairing) return "Repairing " + o.blueprint;
            if (o.dismantle) return "Taking down " + o.blueprint + " (layer " + (o.plan.sy - o.layer) + ")";
            return "Building " + o.blueprint + " (layer " + (o.layer + 1) + " of " + o.plan.sy + ")";
        }
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

    @Override
    public int reserve(Worker w, Material m) {
        BuildOrder o = w.e.build;
        if (o == null || o.finished || o.dismantle) return 0;
        if (o.plan == null) return o.loadError == null ? Integer.MAX_VALUE : 0;   // still loading: keep everything
        // Deliveries are checked before next() runs, so this can't wait for next() to work it out.
        if (usesFor != o || uses.isEmpty()) {
            uses = itemsUsed(o);
            usesFor = o;
        }
        if (craftKeep.contains(m)) return Integer.MAX_VALUE;
        if (!uses.contains(m)) return 0;
        // Only as many as the build still needs: the rest (dirt it dug out...) goes to storage.
        if (needLeft == null || w.now() - needAt > 200) {
            Map<Material, Integer> need = new EnumMap<>(Material.class);
            o.remaining(w.world()).forEach((k, n) -> {
                need.merge(k, n, Integer::sum);
                Material sub = BuildOrder.substitute(k);
                if (sub != null) need.merge(sub, n, Integer::sum);
            });
            needLeft = need;
            needAt = w.now();
        }
        return needLeft.getOrDefault(m, 0);
    }

    /** What the build still needs, worked out now and then for {@link #reserve}. */
    private Map<Material, Integer> needLeft;
    private long needAt;

    /**
     * What a builder may dig through to get to its work: only blocks of its
     * own site that are going anyway: where the plan has air, or a block not
     * built yet (rock where a wall will go). Never what's finished, what the
     * plan leaves alone, or anything outside the site.
     */
    @Override
    public site.remotearc.employees.util.Pathfinder.Digger digger(Worker w) {
        BuildOrder o = w.e.build;
        if (o == null || o.plan == null || o.dismantle || !o.started || o.finished || !w.world().getName().equals(o.world)) return null;
        return b -> {
            if (b.isLiquid() || b.getType().getHardness() < 0 || Ground.protectedBlock(b)) return -1;
            // Natural ground round or in the site that the plan doesn't cover: dig an access tunnel
            // through it if there's no other way (dearer, so the plan's own cells come first); it's put back later.
            if (tunnelCell(o, b)) return Math.max(1, w.breakTicks(b)) * 2 + 10;
            int[] l = o.toLocal(b.getX(), b.getY(), b.getZ());
            if (l == null) return -1;
            int idx = o.cell(l[0], l[1], l[2]);
            if (idx < 0) return -1;
            BlockData want = o.data(idx);
            if (matches(b, want)) return -1;
            if (want.getMaterial().isAir() ? !o.clearAir : l[1] < o.layer && excavated) return -1;
            return Math.max(1, w.breakTicks(b));
        };
    }

    /** Natural ground the plan doesn't cover, in the site or within 3 blocks of it: fine to tunnel through and put back. */
    private static boolean tunnelCell(BuildOrder o, Block b) {
        if (!Ground.NATURAL.contains(b.getType())) return false;
        int[] l = o.toLocal(b.getX(), b.getY(), b.getZ());
        if (l != null) return o.plan.get(l[0], l[1], l[2]) < 0;   // in the box, but not part of the plan
        site.remotearc.employees.util.WorkArea box = o.bounds();
        return b.getX() >= box.minX() - 3 && b.getX() <= box.maxX() + 3 && b.getZ() >= box.minZ() - 3 && b.getZ() <= box.maxZ() + 3
                && b.getY() >= box.minY() - 3 && b.getY() <= box.maxY() + 3;
    }

    @Override
    public boolean digsTemporarily(Worker w, Block b) {
        BuildOrder o = w.e.build;
        if (o == null || o.plan == null || !tunnelCell(o, b)) return false;
        o.tunnel.putIfAbsent(BlockPos.of(b).key(), b.getBlockData().getAsString());
        w.plugin().manager().markDirty();
        return true;
    }

    /** Puts one block of its access tunnel back, as it was. */
    private final class FillTunnelTask implements Task {
        private final BlockPos at;
        private final BlockData was;
        private final Pathfinder.Goal goal;
        private final Vector target;

        FillTunnelTask(World world, BlockPos at, BlockData was) {
            this.at = at;
            this.was = was;
            this.target = new Vector(at.centerX(), at.centerY(), at.centerZ());
            this.goal = Pathfinder.reachBlock(world, at, Pathfinder.REACH - 0.3, false);
        }

        @Override
        public Result tick(Worker w) {
            BuildOrder o = w.e.build;
            if (o == null) return Result.DONE;
            Worker.Move m = w.walk(goal, target);
            if (m == Worker.Move.MOVING) return Result.RUNNING;
            // Arrived, or no way there any more (it'd be walled in): put it back regardless; it's the ground.
            Block b = at.block(w.world());
            if (b.isReplaceable() || b.getType().isAir()) {
                w.lookAt(at);
                w.swing();
                b.setBlockData(was, true);
                w.plugin().blockLog().placed(w.e, b);
            }
            o.tunnel.remove(at.key());
            w.plugin().manager().markDirty();
            return Result.DONE;
        }

        @Override
        public String status(Worker w) {
            return "Filling in its way in (" + w.e.build.tunnel.size() + " blocks left)";
        }
    }

    @Override
    public boolean collects(Material m) {
        return false;
    }

    @Override
    public boolean canWorkWhenFull() {
        return true;
    }

    @Override
    public Movement movement() {
        return Movement.FLY;
    }

    /** A builder carries a pickaxe, axe, shovel and hoe of its tool's tier and uses the fastest. */
    @Override
    public ItemStack toolFor(Worker w, Block b) {
        Material base = w.jobSettings().tool();
        String name = base.name();
        int cut = name.lastIndexOf('_');
        if (cut < 0) return new ItemStack(base);
        String tier = name.substring(0, cut + 1);
        ItemStack best = new ItemStack(base);
        float bestSpeed = b.getBlockData().getDestroySpeed(best, false);
        for (String kind : new String[]{"PICKAXE", "AXE", "SHOVEL", "HOE"}) {
            Material m = Material.matchMaterial(tier + kind);
            if (m == null) continue;
            ItemStack t = new ItemStack(m);
            float s = b.getBlockData().getDestroySpeed(t, false);
            if (s > bestSpeed) {
                best = t;
                bestSpeed = s;
            }
        }
        return best;
    }

    @Override
    public String detail(Worker w) {
        BuildOrder o = w.e.build;
        return o == null ? null : o.blueprint + ": " + progress(w);
    }

    /** A short progress line for the menu and /employees build. */
    static String progress(Worker w) {
        BuildOrder o = w.e.build;
        if (o == null) return "No blueprint";
        if (o.finished) return o.repair ? "Finished; repairing what goes missing" : "Finished";
        if (o.plan == null) return o.loadError != null ? "Can't load: " + o.loadError : "Reading the blueprint";
        if (o.finished) return "Finished";
        if (!o.started) return "Not started";
        if (o.dismantle) return "Taking it down: " + Math.max(0, o.plan.sy - o.layer) + " layers left";
        String line = "Layer " + Math.min(o.layer + 1, o.plan.sy) + " of " + o.plan.sy;
        if (w.job instanceof BuilderJob b && !b.missing.isEmpty()) line += "; missing " + b.missingText(3);
        return line;
    }
}
