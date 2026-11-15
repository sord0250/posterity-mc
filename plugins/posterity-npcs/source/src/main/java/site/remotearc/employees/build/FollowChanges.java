package site.remotearc.employees.build;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.Employee;
import site.remotearc.employees.util.BlockPos;
import site.remotearc.employees.util.WorkArea;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * A finished build keeps up with you: blocks you (or players you trust with
 * the builder) place, break or change with WorldEdit in and round it become
 * part of its plan, so the builder never undoes them (repairs, checking it
 * over, later plan edits) and the blueprint is the build as it is now.
 * Explosions, fire and strangers aren't taken in: repair mode still puts
 * those back. Off per build with /emp build &lt;builder&gt; follow off.
 */
public final class FollowChanges implements Listener {

    /** How far round a build changes still count, so building out to the side grows it. */
    static final int MARGIN = 3;

    private static ArcEmployees plugin;
    /** Builder → positions changed by hand since the plan was last brought up to date. */
    private static final Map<UUID, Set<Long>> PENDING = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> CHANGED_AT = new ConcurrentHashMap<>();

    public FollowChanges(ArcEmployees plugin) {
        FollowChanges.plugin = plugin;
        // Taken in a couple of seconds after the last change, so a burst of building is one update.
        Bukkit.getScheduler().runTaskTimer(plugin, FollowChanges::flushQuiet, 20L, 20L);
    }

    /** Whether this build takes changes in now. */
    private static boolean following(BuildOrder o) {
        return o != null && o.follow && o.finished && o.started && !o.dismantle && !o.onHold && o.plan != null;
    }

    /** The builders whose finished builds a change there by this player counts for. */
    static List<Employee> buildsAt(Player p, World w, int x, int y, int z) {
        List<Employee> out = new ArrayList<>();
        for (Employee e : plugin.manager().all()) {
            BuildOrder o = e.build;
            if (!following(o) && !(o != null && o.follow && o.started && o.repair && !o.dismantle && o.plan != null)) continue;
            if (!o.world.equals(w.getName()) || !o.bounds().expand(MARGIN, MARGIN).contains(x, y, z)) continue;
            if (PlanMode.planning(p) && e.id.equals(PlanMode.planningFor(p))) continue;   // plan mode takes it in itself
            if (plugin.manager().canManage(p, e)) out.add(e);
        }
        return out;
    }

    /** The boxes a player's WorldEdit edits in this world are watched in (empty: none). */
    static List<WorkArea> watched(Player p, String world) {
        List<WorkArea> out = new ArrayList<>();
        for (Employee e : plugin.manager().all()) {
            BuildOrder o = e.build;
            if (o == null || !o.follow || !o.started || o.dismantle || o.plan == null || !o.world.equals(world)) continue;
            if (!(o.finished || o.repair) || !plugin.manager().canManage(p, e)) continue;
            if (PlanMode.planning(p) && e.id.equals(PlanMode.planningFor(p))) continue;
            out.add(o.bounds().expand(MARGIN, MARGIN));
        }
        return out;
    }

    /** A block changed by this player (from WorldEdit too); taken in shortly. */
    static void touched(Player p, World w, int x, int y, int z) {
        for (Employee e : buildsAt(p, w, x, y, z)) {
            PENDING.computeIfAbsent(e.id, k -> ConcurrentHashMap.newKeySet()).add(BlockPos.pack(x, y, z));
            CHANGED_AT.put(e.id, (long) Bukkit.getCurrentTick());
        }
    }

    /**
     * A block one of the owner's other employees changed on purpose (a trader's
     * job blocks and trapdoors in its stalls): the owner's finished builds take it in too.
     */
    public static void changedBy(Employee by, Block b) {
        long k = BlockPos.pack(b.getX(), b.getY(), b.getZ());
        for (Employee e : plugin.manager().all()) {
            BuildOrder o = e.build;
            if (e == by || !e.owner.equals(by.owner) || o == null || !o.follow || !o.started || o.dismantle || o.plan == null) continue;
            if (!(o.finished || o.repair) || !o.world.equals(b.getWorld().getName()) || !o.bounds().expand(MARGIN, MARGIN).contains(b.getX(), b.getY(), b.getZ())) continue;
            PENDING.computeIfAbsent(e.id, id -> ConcurrentHashMap.newKeySet()).add(k);
            CHANGED_AT.put(e.id, (long) Bukkit.getCurrentTick());
        }
    }

    /** Many at once (a WorldEdit edit). */
    static void touched(Player p, World w, java.util.Collection<Long> keys) {
        for (Employee e : plugin.manager().all()) {
            BuildOrder o = e.build;
            if (o == null || !o.follow || !o.started || o.dismantle || o.plan == null || !o.world.equals(w.getName())) continue;
            if (!(o.finished || o.repair) || !plugin.manager().canManage(p, e)) continue;
            WorkArea box = o.bounds().expand(MARGIN, MARGIN);
            Set<Long> mine = null;
            for (long k : keys) {
                BlockPos b = BlockPos.unpack(k);
                if (!box.contains(b.x(), b.y(), b.z())) continue;
                if (mine == null) mine = PENDING.computeIfAbsent(e.id, id -> ConcurrentHashMap.newKeySet());
                mine.add(k);
            }
            if (mine != null) CHANGED_AT.put(e.id, (long) Bukkit.getCurrentTick());
        }
    }

    private static void touchedWithHalves(Player p, Block b, BlockData d) {
        touched(p, b.getWorld(), b.getX(), b.getY(), b.getZ());
        if (d instanceof Bisected bi) {
            int dy = bi.getHalf() == Bisected.Half.BOTTOM ? 1 : -1;
            touched(p, b.getWorld(), b.getX(), b.getY() + dy, b.getZ());
        }
        if (d instanceof Bed bed) {
            BlockFace f = bed.getPart() == Bed.Part.FOOT ? bed.getFacing() : bed.getFacing().getOppositeFace();
            Block o = b.getRelative(f);
            touched(p, b.getWorld(), o.getX(), o.getY(), o.getZ());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent ev) {
        Player p = ev.getPlayer();
        Block b = ev.getBlockPlaced();
        touchedWithHalves(p, b, b.getBlockData());
        if (ev instanceof BlockMultiPlaceEvent multi) {
            for (BlockState st : multi.getReplacedBlockStates()) touched(p, st.getWorld(), st.getX(), st.getY(), st.getZ());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent ev) {
        Block b = ev.getBlock();
        touchedWithHalves(ev.getPlayer(), b, b.getBlockData());
        // What stood on it (a torch, a carpet, a door) goes with it.
        Block up = b.getRelative(BlockFace.UP);
        if (!up.getType().isAir() && !up.getType().isSolid()) touchedWithHalves(ev.getPlayer(), up, up.getBlockData());
    }

    private static void flushQuiet() {
        long now = Bukkit.getCurrentTick();
        for (UUID id : new ArrayList<>(PENDING.keySet())) {
            Long at = CHANGED_AT.get(id);
            if (at != null && now - at < 40) continue;
            Employee e = plugin.manager().byId(id);
            if (e == null || e.build == null) {
                PENDING.remove(id);
                continue;
            }
            // Mid-repair (it's looking the build over): wait till it's done, then take the changes in.
            if (!e.build.finished && e.build.repair && e.build.started && at != null && now - at < 20 * 300) continue;
            Set<Long> keys = PENDING.remove(id);
            CHANGED_AT.remove(id);
            if (keys != null && following(e.build)) take(e, keys);
        }
    }

    /**
     * Takes in the build as it stands now, for changes made before it kept up:
     * every block in and round it that isn't what the plan has. Gaps aren't
     * taken in (they may be creeper holes): break those again to drop them.
     * How many blocks changed, or -1 if the build isn't finished.
     */
    public static int catchUp(Employee e) {
        BuildOrder o = e.build;
        // Finished, or not started yet (a plan of yours given back to the builder, to take in what changed meanwhile).
        boolean waiting = o != null && o.follow && !o.started && !o.dismantle && !o.onHold && o.plan != null;
        if (!following(o) && !waiting) return -1;
        World w = Bukkit.getWorld(o.world);
        if (w == null) return -1;
        Set<Long> keys = new java.util.HashSet<>();
        WorkArea box = o.bounds().expand(MARGIN, MARGIN);
        Map<Long, BlockData> planned = new HashMap<>();
        for (int y = 0; y < o.plan.sy; y++) for (int z = 0; z < o.plan.sz; z++) for (int x = 0; x < o.plan.sx; x++) {
            int idx = o.cell(x, y, z);
            if (idx >= 0) planned.put(o.toWorld(x, y, z).key(), o.turnedRaw(idx));
        }
        for (int y = box.minY(); y <= box.maxY(); y++) for (int z = box.minZ(); z <= box.maxZ(); z++) for (int x = box.minX(); x <= box.maxX(); x++) {
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            long k = BlockPos.pack(x, y, z);
            BlockData now = w.getBlockData(x, y, z), p = planned.get(k);
            if (notBuilt(now.getMaterial())) continue;
            // Round the outside only what's touching the build counts (not the ground it sits in).
            if (p == null && !o.bounds().contains(x, y, z)) continue;
            if (p == null && isNatural(now.getMaterial())) continue;
            if (!now.equals(p) && !swappedFor(o, p, now)) keys.add(k);
        }
        int before = keys.size();
        if (before > 0) take(e, keys);
        return before;
    }

    /** Ground and rock that was there before anyone built (not taken in by a catch-up). */
    private static boolean isNatural(Material m) {
        return switch (m) {
            case STONE, DEEPSLATE, DIRT, GRASS_BLOCK, COARSE_DIRT, ROOTED_DIRT, PODZOL, MYCELIUM, GRAVEL, SAND, RED_SAND, CLAY, TUFF, GRANITE,
                 DIORITE, ANDESITE, CALCITE, SANDSTONE, RED_SANDSTONE, BEDROCK, NETHERRACK, END_STONE, SNOW, SNOW_BLOCK, ICE, PACKED_ICE,
                 SHORT_GRASS, TALL_GRASS, FERN, LARGE_FERN, COAL_ORE, IRON_ORE, COPPER_ORE, GOLD_ORE, REDSTONE_ORE, LAPIS_ORE, DIAMOND_ORE,
                 EMERALD_ORE, DEEPSLATE_COAL_ORE, DEEPSLATE_IRON_ORE, DEEPSLATE_COPPER_ORE, DEEPSLATE_GOLD_ORE, DEEPSLATE_REDSTONE_ORE,
                 DEEPSLATE_LAPIS_ORE, DEEPSLATE_DIAMOND_ORE, DEEPSLATE_EMERALD_ORE, MOSS_BLOCK, MUD, DRIPSTONE_BLOCK, POINTED_DRIPSTONE,
                 GLOW_LICHEN, SMOOTH_BASALT, AMETHYST_BLOCK, BUDDING_AMETHYST, MAGMA_BLOCK, OBSIDIAN -> true;
            default -> false;
        };
    }

    /** Liquids, fire and the like aren't building blocks: there, the plan just lets go of the cell. */
    private static boolean notBuilt(Material m) {
        return m.isAir() || m == Material.WATER || m == Material.LAVA || m == Material.BUBBLE_COLUMN || m == Material.FIRE
                || m == Material.SOUL_FIRE || m == Material.NETHER_PORTAL || m == Material.END_PORTAL || m == Material.LIGHT
                || m == Material.MOVING_PISTON;
    }

    /** Brings the plan up to date with the world at these positions. */
    private static void take(Employee e, Set<Long> keys) {
        BuildOrder o = e.build;
        World w = Bukkit.getWorld(o.world);
        if (w == null) return;
        // The plan as world positions, as it's built (turned, mirrored).
        Map<Long, BlockData> blocks = new HashMap<>();
        for (int y = 0; y < o.plan.sy; y++) {
            for (int z = 0; z < o.plan.sz; z++) {
                for (int x = 0; x < o.plan.sx; x++) {
                    int idx = o.cell(x, y, z);   // what it builds: blocks left out, unbuilt ground and the like stay out
                    if (idx < 0) continue;
                    long k = o.toWorld(x, y, z).key();
                    blocks.put(k, o.turnedRaw(idx));
                }
            }
        }
        int changed = 0;
        for (long k : keys) {
            BlockPos b = BlockPos.unpack(k);
            if (!w.isChunkLoaded(b.x() >> 4, b.z() >> 4)) continue;
            BlockData now = w.getBlockData(b.x(), b.y(), b.z());
            BlockData planned = blocks.get(k);
            if (notBuilt(now.getMaterial())) {
                // Taken away: the plan leaves the spot be (nothing put back, nothing cleared).
                if (planned != null && !planned.getMaterial().isAir()) {
                    blocks.remove(k);
                    changed++;
                }
            } else if (!now.equals(planned) && !swappedFor(o, planned, now)) {
                blocks.put(k, now);
                changed++;
            }
        }
        if (changed == 0) return;
        String file = o.blueprint;
        if (!o.planned) {
            // A downloaded blueprint: the build as it is now gets its own file; the original stays as it was.
            String base = file.replaceAll("\\.[A-Za-z]+$", "");
            file = base + "-as-built.schem";
            for (int n = 2; plugin.blueprints().resolve(file.substring(0, file.length() - 6)) != null && !file.equals(o.blueprint); n++) {
                file = base + "-as-built-" + n + ".schem";
            }
        }
        Plans.Built built;
        try {
            built = Plans.build(file, blocks, plugin.settings().builderMaxVolume, o.world);
        } catch (IOException ex) {
            o.follow = false;
            plugin.manager().notifyOwner(e, "<yellow>" + e.name + "'s build has grown too big to keep up with (" + ex.getMessage()
                    + "), so your changes round it aren't added any more.", false);
            plugin.manager().markDirty();
            return;
        }
        o.blueprint = file;
        o.planned = true;
        o.keepGround = true;
        o.skipped.clear();
        o.rotation = 0;
        o.flip = false;
        o.setPlan(built.bp());
        o.x = built.minX() + built.bp().sx / 2;
        o.y = built.minY();
        o.z = built.minZ() + built.bp().sz / 2;
        o.turnedChanged();
        plugin.manager().setArea(e, o.bounds().expand(2, 2));
        plugin.manager().markDirty();
        Blueprint bp = built.bp();
        File f = new File(plugin.blueprints().dir(), file);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Plans.write(f, bp);
            } catch (IOException ex) {
                plugin.getLogger().log(Level.WARNING, "Could not save " + f.getName(), ex);
            }
        });
        if (e.debug) plugin.getLogger().info("[" + e.name + "] took " + changed + " changed blocks into " + file);
    }

    /** The builder built it from a swap (cobblestone for mossy cobblestone): that's the plan's block, not a change. */
    private static boolean swappedFor(BuildOrder o, BlockData planned, BlockData now) {
        if (planned == null) return false;
        Material to = o.swaps.get(planned.getMaterial());
        return to != null && to == now.getMaterial();
    }
}
