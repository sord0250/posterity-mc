package site.remotearc.employees.util;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * A* over walkable block columns for a player-sized walker (0.6 wide, 1.8 tall).
 *
 * Employees are Mannequins, which have no AI of their own, so they cannot use
 * the vanilla mob pathfinder. This walks on anything with a top surface between
 * half a block and one block high, steps up at most 1.25 blocks, drops at most
 * 3, never enters liquids, and never cuts diagonal corners. Leaves are walked
 * through, not on: tree canopies often hang at head height, and a lumberjack
 * that could not push through them could never reach a trunk. Paths are short
 * (inside one work area), so a bounded search per trip is cheap.
 *
 * Two extra modes:
 *  - with a {@link Digger} (miners), a step may also tunnel: the blocks in the
 *    way are listed on the waypoint and broken before the walker moves in.
 *    Digging costs as much as the time it takes, so open ground is preferred.
 *  - {@link #findFlying} (builders) moves through any open space, like a
 *    player on scaffolding, so it can reach the top of a wall it is building.
 */
public final class Pathfinder {

    private static final double NO_FLOOR = Double.NaN;
    private static final double MAX_STEP_UP = 1.25;
    private static final double MAX_DROP = 3.25;
    /** Survival block reach (blocks from the eyes). */
    public static final double REACH = 4.5;
    public static final double EYE_HEIGHT = 1.62;

    private static final Set<Material> AVOID_INSIDE = EnumSet.of(
            Material.WATER, Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.COBWEB,
            Material.POWDER_SNOW, Material.SWEET_BERRY_BUSH, Material.WITHER_ROSE, Material.BUBBLE_COLUMN,
            Material.NETHER_PORTAL, Material.END_PORTAL, Material.END_GATEWAY);
    private static final Set<Material> AVOID_ON = EnumSet.of(
            Material.MAGMA_BLOCK, Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.CACTUS,
            Material.POINTED_DRIPSTONE);

    private static final int[][] DIRS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /** Decides which blocks a walker may tunnel through, and where it may put blocks down. */
    public interface Digger {
        /** Ticks to break this (solid) block, or -1 if it must not be broken. */
        int digTicks(Block b);

        /** Whether it may place a block here (to pillar up or bridge a gap). */
        default boolean mayPlace(int x, int y, int z) {
            return false;
        }
    }

    /** A point on a path (feet position), the blocks to break and the blocks to place before moving there. */
    public record Waypoint(Vector pos, List<BlockPos> dig, List<BlockPos> place) {}

    private final World world;
    private final BoundingBox limits;
    private Digger digger;
    private final Map<Long, Double> floorCache = new HashMap<>();
    private final Map<Long, Boolean> clearCache = new HashMap<>();

    public Pathfinder(World world, BoundingBox limits) {
        this.world = world;
        this.limits = limits;
    }

    /** Lets the search tunnel through blocks the digger allows. */
    public Pathfinder digging(Digger digger) {
        this.digger = digger;
        // Miners take no fall damage, so like a player who doesn't mind a few hearts they jump down
        // into a pit or a cave rather than tunnel all the way round.
        this.maxDrop = 12.25;
        // Through rock each block costs about three walking steps, so the plain
        // distance estimate is far too hopeful and the search would spread out
        // until it ran out of budget. Weighting it finds a good tunnel quickly.
        this.weight = 3.0;
        return this;
    }

    /** Lets a flying search dig through the blocks the digger allows (a builder into its own site). */
    public Pathfinder flyDigging(Digger digger) {
        this.digger = digger;
        return this;
    }

    /** Leaves count as open air (lumberjacks push through canopies); for everyone else they're walls and floors. */
    private boolean leavesOpen = true;

    public Pathfinder leavesOpen(boolean open) {
        this.leavesOpen = open;
        return this;
    }

    /** Which closed fence gates this walker may open; null: all of them. */
    private java.util.function.Predicate<Block> gates;

    public Pathfinder gates(java.util.function.Predicate<Block> mayOpen) {
        this.gates = mayOpen;
        return this;
    }

    /** Closed gates it may not open, checked once per search. */
    private final Map<Long, Boolean> gateCache = new HashMap<>();

    /** Collision top as this walker sees it (leaves solid unless leavesOpen; gates it mayn't open are fences). */
    private double topOf(Block b) {
        if (!leavesOpen && Tag.LEAVES.isTagged(b.getType())) return 1.0;
        if (gates != null && Tag.FENCE_GATES.isTagged(b.getType()) && b.getBlockData() instanceof org.bukkit.block.data.Openable o && !o.isOpen()
                && !gateCache.computeIfAbsent(BlockPos.of(b).key(), k -> gates.test(b))) return 1.5;
        return top(b);
    }

    /** How far down it will step off an edge. */
    private double maxDrop = MAX_DROP;

    /** Heuristic weight: a little over 1 walking (near-optimal paths), more when tunnelling. */
    private double weight = 1.1;
    /** Whether the last search ran out of budget and returned a path only part of the way. */
    private boolean partial;

    /** True if the last path only gets closer: walk it, then search again from there. */
    public boolean partial() {
        return partial;
    }

    /** A goal test on a candidate standing spot (block x/z centre, floor height). */
    public interface Goal {
        boolean reached(double x, double floor, double z);

        double estimate(double x, double floor, double z);
    }

    /** Stand anywhere within {@code reach} blocks (horizontally) of a point, at a sensible height. */
    public static Goal near(double tx, double ty, double tz, double reach, double maxBelow, double maxAbove) {
        return new Goal() {
            @Override
            public boolean reached(double x, double floor, double z) {
                double dx = x - tx, dz = z - tz;
                double dy = ty - floor;
                return dx * dx + dz * dz <= reach * reach && dy >= -maxBelow && dy <= maxAbove;
            }

            @Override
            public double estimate(double x, double floor, double z) {
                double dx = x - tx, dz = z - tz, dy = (ty - floor) * 0.5;
                return Math.max(0, Math.sqrt(dx * dx + dz * dz + dy * dy) - reach);
            }
        };
    }

    /**
     * Stand where a player could click the block: within reach of the eyes, not
     * inside or right on top of it, and (with {@code sight}) with nothing solid
     * in between.
     */
    public static Goal reachBlock(World world, BlockPos t, double reach, boolean sight) {
        return new Goal() {
            @Override
            public boolean reached(double x, double floor, double z) {
                double ex = x, ey = floor + EYE_HEIGHT, ez = z;
                double dx = t.centerX() - ex, dy = t.centerY() - ey, dz = t.centerZ() - ez;
                if (dx * dx + dy * dy + dz * dz > reach * reach) return false;
                int bx = (int) Math.floor(x), bz = (int) Math.floor(z), fy = (int) Math.floor(floor + 0.01);
                if (bx == t.x() && bz == t.z() && t.y() >= fy - 1 && t.y() <= fy + 1) return false;
                return !sight || canSee(world, ex, ey, ez, t, bx, fy, bz);
            }

            @Override
            public double estimate(double x, double floor, double z) {
                double dx = t.centerX() - x, dy = t.centerY() - (floor + EYE_HEIGHT), dz = t.centerZ() - z;
                return Math.max(0, Math.sqrt(dx * dx + dy * dy + dz * dz) - reach);
            }
        };
    }

    private record Node(int x, int y, int z, double floor, double g, double f, Node parent, List<BlockPos> dig, List<BlockPos> place) {
        Node(int x, int y, int z, double floor, double g, double f, Node parent, List<BlockPos> dig) {
            this(x, y, z, floor, g, f, parent, dig, null);
        }
    }

    /**
     * Finds a path from a standing position to any spot satisfying the goal.
     * Returns the waypoints (feet positions, block-centred) excluding the start,
     * an empty list if already there, or null if unreachable within maxNodes.
     */
    public List<Waypoint> find(Vector start, Goal goal, int maxNodes) {
        int sx = start.getBlockX(), sz = start.getBlockZ();
        int sy = (int) Math.floor(start.getY() + 0.01);
        Node first = null;
        for (int dy : new int[]{0, 1, -1}) {
            double f = floor(sx, sy + dy, sz);
            if (!Double.isNaN(f)) {
                first = new Node(sx, sy + dy, sz, f, 0, 0, null, null);
                break;
            }
        }
        // The walker is standing here, so trust it even if something was built around it.
        if (first == null) first = new Node(sx, sy, sz, start.getY(), 0, 0, null, null);
        if (goal.reached(sx + 0.5, first.floor, sz + 0.5)) return new ArrayList<>();

        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
        Map<Long, Double> best = new HashMap<>();
        open.add(first);
        best.put(BlockPos.pack(first.x, first.y, first.z), 0.0);
        int expanded = 0;
        List<Node> next = new ArrayList<>(4);
        partial = false;
        double startH = goal.estimate(sx + 0.5, first.floor, sz + 0.5);
        Node closest = first;
        double closestH = startH;

        while (!open.isEmpty() && expanded < maxNodes) {
            Node cur = open.poll();
            Double known = best.get(BlockPos.pack(cur.x, cur.y, cur.z));
            if (known != null && known < cur.g - 1e-9) continue;
            expanded++;
            if (goal.reached(cur.x + 0.5, cur.floor, cur.z + 0.5)) return build(cur);
            double h = goal.estimate(cur.x + 0.5, cur.floor, cur.z + 0.5);
            if (h < closestH) {
                closestH = h;
                closest = cur;
            }

            if (digger != null) pillar(cur, goal, open, best);
            for (int[] d : DIRS) {
                next.clear();
                Node walk = step(cur, d[0], d[1], goal);
                if (walk != null) next.add(walk);
                if (digger != null && (d[0] == 0 || d[1] == 0)) {
                    for (int dy = -1; dy <= 1; dy++) {
                        if (walk != null && walk.y == cur.y + dy) continue;
                        Node dug = digStep(cur, cur.x + d[0], cur.y + dy, cur.z + d[1], goal);
                        if (dug != null) next.add(dug);
                    }
                }
                if (digger != null && (d[0] == 0 || d[1] == 0)) {
                    Node bridge = bridgeStep(cur, cur.x + d[0], cur.z + d[1], goal);
                    if (bridge != null) next.add(bridge);
                }
                for (Node n : next) {
                    long key = BlockPos.pack(n.x, n.y, n.z);
                    Double prev = best.get(key);
                    if (prev != null && prev <= n.g) continue;
                    best.put(key, n.g);
                    open.add(n);
                }
            }
        }
        // Too far to plan in one go (a miner heading 100 blocks down): go as far as the
        // search got toward the goal, then the walker plans again from there.
        if (expanded >= maxNodes && closest != first && closestH < startH - 3) {
            partial = true;
            return build(closest);
        }
        return null;
    }

    private void pillar(Node cur, Goal goal, PriorityQueue<Node> open, Map<Long, Double> best) {
        // Jump and put a block where the feet were, like a player towering up.
        if (Math.abs(cur.floor - cur.y) > 1e-6 || !digger.mayPlace(cur.x, cur.y, cur.z)) return;
        if (!limits.contains(cur.x + 0.5, cur.y + 1.5, cur.z + 0.5)) return;
        List<BlockPos> dig = new ArrayList<>(1);
        double cost = 3;
        if (!clear(cur.x, cur.y + 2, cur.z)) {
            Block b = world.getBlockAt(cur.x, cur.y + 2, cur.z);
            if (isUnsafeInside(b) || !safeToDig(b)) return;
            int t = digger.digTicks(b);
            if (t < 0) return;
            dig.add(BlockPos.of(b));
            cost += t / 5.0;
        }
        double g = cur.g + cost;
        Node n = new Node(cur.x, cur.y + 1, cur.z, cur.y + 1, g, g + goal.estimate(cur.x + 0.5, cur.y + 1, cur.z + 0.5) * weight,
                cur, dig, List.of(new BlockPos(cur.x, cur.y, cur.z)));
        long key = BlockPos.pack(n.x, n.y, n.z);
        Double prev = best.get(key);
        if (prev != null && prev <= g) return;
        best.put(key, g);
        open.add(n);
    }

    /**
     * A level step over a gap: a block goes down where the floor is missing
     * (tunnelling the body room if needed). The gap may be air, or lava or
     * water: the block is put into it, as a player bridges across a lava pool.
     */
    private Node bridgeStep(Node cur, int nx, int nz, Goal goal) {
        int ny = cur.y;
        if (Math.abs(cur.floor - cur.y) > 1e-6 || !limits.contains(nx + 0.5, ny + 0.5, nz + 0.5)) return null;
        if (!world.isChunkLoaded(nx >> 4, nz >> 4)) return null;
        Block below = world.getBlockAt(nx, ny - 1, nz);
        if (!(below.getType().isAir() || below.isReplaceable() || below.isLiquid()) || !digger.mayPlace(nx, ny - 1, nz)) return null;
        List<BlockPos> dig = new ArrayList<>(2);
        double cost = below.isLiquid() ? 5 : 4;
        for (int dy = 1; dy >= 0; dy--) {
            if (clear(nx, ny + dy, nz)) continue;
            Block b = world.getBlockAt(nx, ny + dy, nz);
            if (isUnsafeInside(b) || !safeToDig(b)) return null;
            int t = digger.digTicks(b);
            if (t < 0) return null;
            dig.add(BlockPos.of(b));
            cost += t / 5.0;
        }
        double g = cur.g + cost;
        return new Node(nx, ny, nz, ny, g, g + goal.estimate(nx + 0.5, ny, nz + 0.5) * weight, cur, dig, List.of(new BlockPos(nx, ny - 1, nz)));
    }

    private Node step(Node cur, int dx, int dz, Goal goal) {
        int nx = cur.x + dx, nz = cur.z + dz;
        if (!limits.contains(nx + 0.5, cur.y + 0.5, nz + 0.5)) return null;
        boolean diagonal = dx != 0 && dz != 0;

        if (diagonal) {
            // No corner cutting: both orthogonal neighbours must be open at body height.
            if (!bodyClear(cur.x + dx, cur.y, cur.z) || !bodyClear(cur.x, cur.y, cur.z + dz)) return null;
        }

        for (int ny = cur.y + 1; ny >= cur.y - (int) maxDrop; ny--) {
            double f = floor(nx, ny, nz);
            if (Double.isNaN(f)) {
                if (ny < cur.y && !clear(nx, ny, nz)) return null;   // something solid (or liquid) in the way down
                continue;
            }
            double rise = f - cur.floor;
            if (rise > MAX_STEP_UP || -rise > maxDrop) return null;
            if (diagonal && Math.abs(rise) > 0.6) return null;
            if (rise > 0.6 && !clear(cur.x, cur.y + 2, cur.z)) return null;   // room to jump
            if (ny < cur.y && !bodyClear(nx, cur.y, nz)) return null;           // room to walk off the edge
            double cost = (diagonal ? 1.414 : 1.0) + (rise > 0.6 ? 0.6 : 0) + (rise < -1.1 ? 0.4 : 0) + (rise < -3.3 ? -rise * 0.3 : 0);
            double g = cur.g + cost;
            double cx = nx + 0.5, cz = nz + 0.5;
            return new Node(nx, ny, nz, f, g, g + goal.estimate(cx, f, cz) * weight, cur, null);
        }
        return null;
    }

    /**
     * A straight step (level, one up or one down) that needs blocks broken
     * first. The floor must already be there: miners tunnel, they don't bridge.
     */
    private Node digStep(Node cur, int nx, int ny, int nz, Goal goal) {
        if (!limits.contains(nx + 0.5, ny + 0.5, nz + 0.5)) return null;
        if (!world.isChunkLoaded(nx >> 4, nz >> 4)) return null;
        Block below = world.getBlockAt(nx, ny - 1, nz);
        double belowTop = topOf(below);
        if (belowTop < 0.5 - 1e-6 || belowTop > 1.0 + 1e-6) return null;
        if (AVOID_ON.contains(below.getType()) || isUnsafeInside(below)) return null;

        List<BlockPos> dig = new ArrayList<>(3);
        double cost = 1;
        int[][] cells = ny > cur.y ? new int[][]{{cur.x, cur.y + 2, cur.z}, {nx, ny, nz}, {nx, ny + 1, nz}}
                : ny < cur.y ? new int[][]{{nx, cur.y + 1, nz}, {nx, ny + 1, nz}, {nx, ny, nz}}
                : new int[][]{{nx, ny + 1, nz}, {nx, ny, nz}};
        for (int[] c : cells) {
            if (clear(c[0], c[1], c[2])) continue;
            Block b = world.getBlockAt(c[0], c[1], c[2]);
            if (isUnsafeInside(b) || !safeToDig(b)) return null;
            int t = digger.digTicks(b);
            if (t < 0) return null;
            dig.add(BlockPos.of(b));
            cost += t / 5.0;   // a walker covers about one block per 5 ticks
        }
        if (dig.isEmpty()) return null;   // nothing to dig: plain walking already covers it
        double f = ny - 1 + belowTop;
        double g = cur.g + cost;
        return new Node(nx, ny, nz, f, g, g + goal.estimate(nx + 0.5, f, nz + 0.5) * weight, cur, dig);
    }

    /** A flying step into cells that need digging first (the body's two blocks). */
    private Node flyDig(Node cur, int nx, int ny, int nz, Goal goal) {
        if (!world.isChunkLoaded(nx >> 4, nz >> 4)) return null;
        List<BlockPos> dig = new ArrayList<>(2);
        double cost = 1.4;
        for (int y = ny; y <= ny + 1; y++) {
            if (clear(nx, y, nz)) continue;
            Block b = world.getBlockAt(nx, y, nz);
            if (isUnsafeInside(b) || b.isLiquid() || !safeToDig(b)) return null;
            int t = digger.digTicks(b);
            if (t < 0) return null;
            dig.add(BlockPos.of(b));
            cost += 1 + t / 5.0;
        }
        if (dig.isEmpty()) return null;
        double f = floor(nx, ny, nz);
        double feet = Double.isNaN(f) ? ny : f;
        double g = cur.g + cost;
        return new Node(nx, ny, nz, feet, g, g + goal.estimate(nx + 0.5, feet, nz + 0.5) * 1.5, cur, dig);
    }

    /** Breaking this won't let liquid in or drop sand and gravel on the walker. */
    private boolean safeToDig(Block b) {
        if (b.getRelative(0, 1, 0).getType().hasGravity()) return false;
        for (int[] d : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
            Material m = world.getType(b.getX() + d[0], b.getY() + d[1], b.getZ() + d[2]);
            if (m == Material.WATER || m == Material.LAVA) return false;
        }
        return true;
    }

    private static List<Waypoint> build(Node end) {
        List<Waypoint> out = new ArrayList<>();
        for (Node n = end; n.parent != null; n = n.parent) {
            out.add(new Waypoint(new Vector(n.x + 0.5, n.floor, n.z + 0.5), n.dig == null ? List.of() : n.dig,
                    n.place == null ? List.of() : n.place));
        }
        Collections.reverse(out);
        return out;
    }

    // ── flying (builders) ─────────────────────────────────────────────────

    private static final int[][] FLY_DIRS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0},
            {1, 0, 1}, {1, 0, -1}, {-1, 0, 1}, {-1, 0, -1}};

    /**
     * Like {@link #find}, but through any open space with room for the body,
     * as a player on scaffolding would move. Resting on a floor is a little
     * cheaper than hanging in the air, so builders stay on the ground when they can.
     */
    public List<Waypoint> findFlying(Vector start, Goal goal, int maxNodes) {
        int sx = start.getBlockX(), sy = (int) Math.floor(start.getY() + 0.01), sz = start.getBlockZ();
        Node first = new Node(sx, sy, sz, start.getY(), 0, 0, null, null);
        if (goal.reached(sx + 0.5, first.floor, sz + 0.5)) return new ArrayList<>();
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
        Map<Long, Double> best = new HashMap<>();
        open.add(first);
        best.put(BlockPos.pack(sx, sy, sz), 0.0);
        int expanded = 0;
        partial = false;
        double startH = goal.estimate(sx + 0.5, first.floor, sz + 0.5);
        Node closest = first;
        double closestH = startH;
        while (!open.isEmpty() && expanded < maxNodes) {
            Node cur = open.poll();
            Double known = best.get(BlockPos.pack(cur.x, cur.y, cur.z));
            if (known != null && known < cur.g - 1e-9) continue;
            expanded++;
            if (goal.reached(cur.x + 0.5, cur.floor, cur.z + 0.5)) return build(cur);
            double h = goal.estimate(cur.x + 0.5, cur.floor, cur.z + 0.5);
            if (h < closestH) {
                closestH = h;
                closest = cur;
            }
            for (int[] d : FLY_DIRS) {
                int nx = cur.x + d[0], ny = cur.y + d[1], nz = cur.z + d[2];
                if (!limits.contains(nx + 0.5, ny + 0.5, nz + 0.5)) continue;
                boolean diagonal = d[0] != 0 && d[2] != 0;
                if (!bodyClear(nx, ny, nz)) {
                    // Straight moves only: dig through what's in the way, if the digger allows it.
                    if (digger == null || diagonal) continue;
                    Node dug = flyDig(cur, nx, ny, nz, goal);
                    if (dug != null) {
                        long key = BlockPos.pack(nx, ny, nz);
                        Double prev = best.get(key);
                        if (prev == null || prev > dug.g) {
                            best.put(key, dug.g);
                            open.add(dug);
                        }
                    }
                    continue;
                }
                if (diagonal && (!bodyClear(cur.x + d[0], cur.y, cur.z) || !bodyClear(cur.x, cur.y, cur.z + d[2]))) continue;
                double f = floor(nx, ny, nz);
                boolean standing = !Double.isNaN(f);
                double feet = standing ? f : ny;
                double g = cur.g + (diagonal ? 1.414 : 1.0) + (standing ? 0 : 0.4);
                long key = BlockPos.pack(nx, ny, nz);
                Double prev = best.get(key);
                if (prev != null && prev <= g) continue;
                best.put(key, g);
                // Flying costs 1.4 a block in the open: weight the estimate to match, or the search
                // spreads out through all the air round it and runs out before getting anywhere.
                open.add(new Node(nx, ny, nz, feet, g, g + goal.estimate(nx + 0.5, feet, nz + 0.5) * 1.5, cur, null));
            }
        }
        // Too far to plan in one go: fly as far as the search got, then plan again from there.
        if (expanded >= maxNodes && closest != first && closestH < startH - 3) {
            partial = true;
            return build(closest);
        }
        return null;
    }

    // ── block tests ───────────────────────────────────────────────────────

    /**
     * The height a walker's feet rest at when standing in block (x,y,z), or NaN
     * if it cannot stand there.
     */
    public double floor(int x, int y, int z) {
        long key = BlockPos.pack(x, y, z);
        Double cached = floorCache.get(key);
        if (cached != null) return cached;
        double f = computeFloor(x, y, z);
        floorCache.put(key, f);
        return f;
    }

    private double computeFloor(int x, int y, int z) {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return NO_FLOOR;   // never load chunks to plan a path
        Block feet = world.getBlockAt(x, y, z);
        if (isUnsafeInside(feet)) return NO_FLOOR;
        double base;
        double feetTop = topOf(feet);
        if (feetTop > 0) {
            if (feetTop > 0.5 + 1e-6) return NO_FLOOR;   // a full block occupies the feet
            if (AVOID_ON.contains(feet.getType())) return NO_FLOOR;
            base = y + feetTop;                          // slab, carpet, snow layer...
        } else {
            Block below = world.getBlockAt(x, y - 1, z);
            double belowTop = topOf(below);
            if (belowTop < 0.5 - 1e-6 || belowTop > 1.0 + 1e-6) return NO_FLOOR;   // nothing to stand on, or a fence/wall
            if (AVOID_ON.contains(below.getType()) || isUnsafeInside(below)) return NO_FLOOR;
            base = y - 1 + belowTop;
        }
        if (!clear(x, y + 1, z)) return NO_FLOOR;
        if (base + 1.8 > y + 2 && !clear(x, y + 2, z)) return NO_FLOOR;
        return base;
    }

    private boolean bodyClear(int x, int y, int z) {
        return clear(x, y, z) && clear(x, y + 1, z);
    }

    /** No collision and not something to avoid standing in. */
    private boolean clear(int x, int y, int z) {
        long key = BlockPos.pack(x, y, z);
        Boolean cached = clearCache.get(key);
        if (cached != null) return cached;
        boolean ok = world.isChunkLoaded(x >> 4, z >> 4);
        if (ok) {
            Block b = world.getBlockAt(x, y, z);
            ok = isClear(b) && (leavesOpen || !Tag.LEAVES.isTagged(b.getType())) && topOf(b) <= 0;
        }
        clearCache.put(key, ok);
        return ok;
    }

    /** A walker can be inside this block: no collision, no liquid, nothing harmful. */
    public static boolean isClear(Block b) {
        return !isUnsafeInside(b) && top(b) <= 0;
    }

    private static boolean isUnsafeInside(Block b) {
        Material m = b.getType();
        if (AVOID_INSIDE.contains(m)) return true;
        BlockData data = b.getBlockData();
        return data instanceof Waterlogged w && w.isWaterlogged();
    }

    /** Top of the block's collision shape, relative to the block (0 = no collision; leaves count as none). */
    private static double top(Block b) {
        Material m = b.getType();
        if (m.isAir() || Tag.LEAVES.isTagged(m) || opensItself(m)) return 0;
        // An open trapdoor is a thin panel against one side (a hatch into a shaft), and a ladder or vine
        // lies flat on its wall: a body fits past either, as a player does.
        if (Tag.TRAPDOORS.isTagged(m) && b.getBlockData() instanceof org.bukkit.block.data.type.TrapDoor t && t.isOpen()) return 0;
        if (m == Material.LADDER || m == Material.VINE) return 0;
        double max = 0;
        for (BoundingBox box : b.getCollisionShape().getBoundingBoxes()) {
            max = Math.max(max, box.getMaxY());
        }
        return max;
    }

    /**
     * Whether a player with eyes at (ex,ey,ez) could click the target block:
     * walks the voxels on the line between and fails on anything with a
     * collision shape. The walker's own two body blocks (bx,fy,bz and the one
     * above) are skipped, since a digger clears them before it stands there.
     */
    public static boolean canSee(World world, double ex, double ey, double ez, BlockPos t, int bx, int fy, int bz) {
        double tx = t.centerX(), ty = t.centerY(), tz = t.centerZ();
        double dx = tx - ex, dy = ty - ey, dz = tz - ez;
        int x = (int) Math.floor(ex), y = (int) Math.floor(ey), z = (int) Math.floor(ez);
        int stepX = dx > 0 ? 1 : -1, stepY = dy > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
        double tDeltaX = dx == 0 ? Double.MAX_VALUE : Math.abs(1 / dx);
        double tDeltaY = dy == 0 ? Double.MAX_VALUE : Math.abs(1 / dy);
        double tDeltaZ = dz == 0 ? Double.MAX_VALUE : Math.abs(1 / dz);
        double tMaxX = dx == 0 ? Double.MAX_VALUE : ((dx > 0 ? x + 1 - ex : ex - x) * tDeltaX);
        double tMaxY = dy == 0 ? Double.MAX_VALUE : ((dy > 0 ? y + 1 - ey : ey - y) * tDeltaY);
        double tMaxZ = dz == 0 ? Double.MAX_VALUE : ((dz > 0 ? z + 1 - ez : ez - z) * tDeltaZ);
        for (int i = 0; i < 16; i++) {
            if (x == t.x() && y == t.y() && z == t.z()) return true;
            boolean own = x == bx && z == bz && (y == fy || y == fy + 1);
            if (!own && i > 0) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) return false;
                Block b = world.getBlockAt(x, y, z);
                if (!b.getType().isAir() && !b.getCollisionShape().getBoundingBoxes().isEmpty()) return false;
            }
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                x += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY < tMaxZ) {
                y += stepY;
                tMaxY += tDeltaY;
            } else {
                z += stepZ;
                tMaxZ += tDeltaZ;
            }
        }
        return false;
    }

    /** Top of a block's collision shape (0 = none), as the walker sees it. */
    public static double collisionTop(Block b) {
        return top(b);
    }

    /** Wooden doors and fence gates: employees open them on the way through, like villagers. */
    public static boolean opensItself(Material m) {
        return Tag.WOODEN_DOORS.isTagged(m) || Tag.FENCE_GATES.isTagged(m);
    }

    /** Snap a rough position to the nearest standable spot in its column (searching up then down). */
    public Vector snap(double x, double y, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z), by = (int) Math.floor(y + 0.01);
        for (int i = 0; i <= 6; i++) {
            for (int dy : i == 0 ? new int[]{0} : new int[]{i, -i}) {
                double f = floor(bx, by + dy, bz);
                if (!Double.isNaN(f)) return new Vector(bx + 0.5, f, bz + 0.5);
            }
        }
        return null;
    }
}
