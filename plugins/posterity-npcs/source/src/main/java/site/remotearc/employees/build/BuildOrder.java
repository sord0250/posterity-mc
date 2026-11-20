package site.remotearc.employees.build;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.configuration.ConfigurationSection;
import site.remotearc.employees.util.BlockPos;
import site.remotearc.employees.util.WorkArea;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a builder has been asked to build and where: a blueprint, the spot its
 * bottom centre sits on, and how it is turned. Saved with the employee; the
 * blueprint itself is re-read from its file after a restart.
 */
public final class BuildOrder {

    public String blueprint;
    public String world;
    /** Bottom centre of the build. */
    public int x, y, z;
    /** Quarter turns clockwise (seen from above), 0-3. */
    public int rotation;
    /** Mirrored east-west before turning. */
    public boolean flip;
    /** Break whatever is in the way where the blueprint has air. */
    public boolean clearAir = true;
    /** Break a pre-existing block that's in the way of one the blueprint wants there; off just skips it, leaving it standing. */
    public boolean breakBlocks = true;
    /** Off while the owner previews and lines it up; the builder waits. */
    public boolean started;
    public boolean finished;
    /** Lowest layer (0-based, from the bottom) that may still need work. */
    public int layer;
    /** Once finished, keep it that way: put back blocks that go missing (creepers, griefing). */
    public boolean repair;
    /** Build the dirt and grass the blueprint stands on too (off: those bottom layers are left out). */
    public boolean keepGround;
    /**
     * Natural blocks (stone, dirt) dug outside the plan to get to work it
     * couldn't otherwise reach: position key → the block, put back once the build is done.
     */
    public final Map<Long, String> tunnel = new LinkedHashMap<>();

    /** Dig out the blueprint's empty space round the outside of the build, not just inside it. */
    public boolean clearAround = true;
    /** Taking it down instead: the builder breaks what it built, top first, and keeps the blocks. */
    public boolean dismantle;
    /** Blocks built from something else: the blueprint's item → the item used instead (AIR: left out). */
    public final Map<Material, Material> swaps = new EnumMap<>(Material.class);
    /** Materials bought for this build and handed straight to the builder: item → {count, money paid for them}. */
    public final Map<Material, double[]> stock = new EnumMap<>(Material.class);
    /** What to build for blocks this server doesn't have (newer versions, mods): block name → item. */
    public final Map<String, Material> replacements = new java.util.TreeMap<>();
    /** Single blocks left out (blueprint cells, as indexes into the plan). */
    public final java.util.Set<Integer> skipped = new java.util.HashSet<>();

    /** Once finished, blocks the owner changes in and round it become part of the plan (see FollowChanges). */
    public boolean follow = true;

    /** Planned in the world (plan mode) rather than placed from a blueprint someone made elsewhere. */
    public boolean planned;

    // Runtime only.
    /** Someone is planning it right now: the builder waits for Done, so nothing is dug or built from a half-made plan. */
    public boolean onHold;
    public Blueprint plan;
    public String loadError;
    private List<BlockData> turned;
    /** Palette entries that are left out (swapped for nothing). */
    private boolean[] leftOut;
    /** How many bottom layers are ground, and which cells of them (and of the plants on them) are left out. */
    private int groundLayers = -1;
    private java.util.BitSet groundCells;

    public BuildOrder(String blueprint, String world, int x, int y, int z) {
        this.blueprint = blueprint;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public void setPlan(Blueprint plan) {
        this.plan = plan;
        this.loadError = null;
        this.turned = null;
        this.leftOut = null;
        this.groundLayers = -1;
        this.groundCells = null;
        this.around = null;
    }

    /** Call after changing rotation, flip or swaps. */
    public void turnedChanged() {
        turned = null;
        leftOut = null;
    }

    // ── cells: what is built where ────────────────────────────────────────

    /**
     * The palette index to build at blueprint cell (lx,ly,lz), or -1 where
     * nothing is to be done: no block in the blueprint, a block that's been
     * left out, or the ground the build stands on (unless it's kept).
     */
    public int cell(int lx, int ly, int lz) {
        int idx = plan.get(lx, ly, lz);
        if (idx < 0) return -1;
        if (leftOut == null) {
            boolean[] out = new boolean[plan.palette.size()];
            for (int i = 0; i < out.length; i++) out[i] = swaps.get(itemKey(plan.palette.get(i))) == Material.AIR;
            leftOut = out;
        }
        if (leftOut[idx]) return -1;
        if (!skipped.isEmpty() && skipped.contains(index(lx, ly, lz))) return -1;
        if (!keepGround && ly < groundLayers() + 2 && groundCells.get(index(lx, ly, lz))) return -1;
        if (!clearAround && isAround(lx, ly, lz)) return -1;
        return idx;
    }

    /** Air cells round the outside of the build (not inside it), worked out once per blueprint. */
    private java.util.BitSet around;

    /**
     * Whether a cell is empty space round the outside of the build rather
     * than inside it: blueprint air that sees out of the blueprint's box
     * past no block of the build in at least two directions sideways, or
     * straight up and one sideways. A room inside, even one with a doorway,
     * sees out at most one way; the yard round a house sees out two or more.
     */
    public boolean isAround(int lx, int ly, int lz) {
        if (around == null) around = computeAround();
        return around.get(index(lx, ly, lz));
    }

    private boolean solid(int x, int y, int z) {
        int idx = plan.get(x, y, z);
        return idx >= 0 && (!plan.palette.get(idx).getMaterial().isAir() || plan.unknownStates.containsKey(idx));
    }

    private java.util.BitSet computeAround() {
        int sx = plan.sx, sy = plan.sy, sz = plan.sz;
        java.util.BitSet out = new java.util.BitSet(sx * sy * sz);
        boolean[] west = new boolean[sx], east = new boolean[sx], north = new boolean[sz], south = new boolean[sz];
        boolean[][] up = new boolean[sz][sx];   // open to the top, filled from the top layer down
        for (boolean[] row : up) java.util.Arrays.fill(row, true);
        for (int y = sy - 1; y >= 0; y--) {
            int[][] open = new int[sz][sx];
            for (int z = 0; z < sz; z++) {
                boolean clear = true;
                for (int x = 0; x < sx; x++) {
                    if (solid(x, y, z)) clear = false;
                    west[x] = clear;
                }
                clear = true;
                for (int x = sx - 1; x >= 0; x--) {
                    if (solid(x, y, z)) clear = false;
                    east[x] = clear;
                }
                for (int x = 0; x < sx; x++) open[z][x] = (west[x] ? 1 : 0) + (east[x] ? 1 : 0);
            }
            for (int x = 0; x < sx; x++) {
                boolean clear = true;
                for (int z = 0; z < sz; z++) {
                    if (solid(x, y, z)) clear = false;
                    north[z] = clear;
                }
                clear = true;
                for (int z = sz - 1; z >= 0; z--) {
                    if (solid(x, y, z)) clear = false;
                    south[z] = clear;
                }
                for (int z = 0; z < sz; z++) open[z][x] += (north[z] ? 1 : 0) + (south[z] ? 1 : 0);
            }
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    if (solid(x, y, z)) {
                        up[z][x] = false;
                        continue;
                    }
                    int idx = plan.get(x, y, z);
                    if (idx < 0) continue;
                    if (open[z][x] >= 2 || (up[z][x] && open[z][x] >= 1)) out.set(index(x, y, z));
                }
            }
        }
        return out;
    }

    /** Leaves out (or puts back) the one block at a world position; false if it isn't part of the build. */
    public boolean skip(int wx, int wy, int wz, boolean out) {
        int[] l = toLocal(wx, wy, wz);
        if (l == null) return false;
        if (out) skipped.add(index(l[0], l[1], l[2]));
        else skipped.remove(index(l[0], l[1], l[2]));
        return true;
    }

    /** A single cell left out of the build. */
    public boolean skippedCell(int lx, int ly, int lz) {
        return !skipped.isEmpty() && skipped.contains(index(lx, ly, lz));
    }

    private int index(int lx, int ly, int lz) {
        return (ly * plan.sz + lz) * plan.sx + lx;
    }

    /** Natural ground blocks: what a blueprint's base of terrain is made of. */
    private static final java.util.Set<Material> GROUND = java.util.EnumSet.of(Material.GRASS_BLOCK, Material.DIRT,
            Material.COARSE_DIRT, Material.PODZOL, Material.MYCELIUM, Material.ROOTED_DIRT, Material.MUD, Material.DIRT_PATH,
            Material.MOSS_BLOCK, Material.SAND, Material.RED_SAND, Material.GRAVEL, Material.CLAY, Material.SNOW_BLOCK,
            Material.STONE, Material.DEEPSLATE, Material.GRANITE, Material.DIORITE, Material.ANDESITE, Material.TUFF,
            Material.NETHERRACK, Material.SOUL_SOIL, Material.END_STONE);

    /**
     * How many of the blueprint's bottom layers are a slab of ground (dirt,
     * grass, sand, stone...) that it was saved standing on: from the bottom up,
     * layers where ground covers at least 60% of the footprint. The ground
     * blocks in them, and grass and flowers growing on top, are left out
     * unless {@link #keepGround}; anything else in those layers (a path, a
     * foundation) is still built.
     */
    public int groundLayers() {
        if (groundLayers >= 0) return groundLayers;
        int area = plan.sx * plan.sz, n = 0;
        for (int ly = 0; ly < Math.min(plan.sy - 1, 12); ly++) {
            int ground = 0;
            for (int lz = 0; lz < plan.sz; lz++) {
                for (int lx = 0; lx < plan.sx; lx++) {
                    int idx = plan.get(lx, ly, lz);
                    if (idx >= 0 && GROUND.contains(plan.palette.get(idx).getMaterial())) ground++;
                }
            }
            if (ground < area * 0.6) break;
            n++;
        }
        java.util.BitSet cells = new java.util.BitSet();
        for (int ly = 0; ly < Math.min(plan.sy, n + 2); ly++) {
            for (int lz = 0; lz < plan.sz; lz++) {
                for (int lx = 0; lx < plan.sx; lx++) {
                    int idx = plan.get(lx, ly, lz);
                    BlockData d = idx < 0 ? null : plan.palette.get(idx);
                    boolean skip;
                    if (ly < n) skip = d == null || d.getMaterial().isAir() || GROUND.contains(d.getMaterial());
                    else skip = d != null && org.bukkit.Tag.REPLACEABLE.isTagged(d.getMaterial()) && !d.getMaterial().isAir()
                            && !(d.getMaterial() == Material.WATER || d.getMaterial() == Material.LAVA)
                            && ly > 0 && cells.get(index(lx, ly - 1, lz));
                    if (skip) cells.set(index(lx, ly, lz));
                }
            }
        }
        groundCells = cells;
        groundLayers = n;
        return n;
    }

    /** The item a block is listed under in materials and swaps (a door's top half under the door). */
    public static Material itemKey(BlockData d) {
        Material m = d.getMaterial();
        if (m.isAir()) return null;
        Material item = d.getPlacementMaterial();
        return item == null || item.isAir() || !item.isItem() ? m : item;
    }

    /** Builds a block this server doesn't have out of {@code to} (null: leaves it out again). */
    public void replace(String blockName, Material to) {
        if (to == null || to == Material.AIR) replacements.remove(blockName);
        else replacements.put(blockName, to);
        turnedChanged();
    }

    /**
     * The nearest thing this server has to a block it doesn't: the same kind
     * (trapdoor for trapdoor, stairs for stairs), in the wood or stone the
     * builder has most of (counting logs for planks and the like).
     */
    public static Material closest(String blockName, Map<Material, Integer> have) {
        String n = blockName.toUpperCase(java.util.Locale.ROOT);
        String word = n.substring(n.lastIndexOf('_') + 1);
        Material best = null;
        long bestScore = -1;
        for (Material m : Material.values()) {
            if (m.isLegacy() || !m.isBlock() || !m.isItem() || m.isAir()) continue;
            String mn = m.name();
            if (!mn.substring(mn.lastIndexOf('_') + 1).equals(word) || unbuildable(m.createBlockData())) continue;
            String family = mn.lastIndexOf('_') < 0 ? mn : mn.substring(0, mn.lastIndexOf('_') + 1);
            long score = 0;
            for (Map.Entry<Material, Integer> h : have.entrySet()) if (h.getKey().name().startsWith(family)) score += h.getValue();
            score = score * 4 + (mn.startsWith("OAK_") ? 2 : 0) + (mn.equals(word) ? 1 : 0);
            if (score > bestScore) {
                bestScore = score;
                best = m;
            }
        }
        return best;
    }

    /** Builds the blueprint's {@code from} out of {@code to} instead (AIR: leaves it out, null: undoes the swap). */
    public void swap(Material from, Material to) {
        if (to == null || to == from) swaps.remove(from);
        else swaps.put(from, to);
        turnedChanged();
    }

    /** A block made of another item, keeping its facing, half, shape... where the new block has them. */
    static BlockData swapped(BlockData d, Material to) {
        Material from = d.getMaterial();
        Material item = d.getPlacementMaterial();
        return swapped(d.getAsString(), item != null && item != from && from.name().contains("WALL_"), to);
    }

    /** The same from a block state as text (which may be a block this server doesn't have). */
    static BlockData swapped(String state, boolean onWall, Material to) {
        Material block = to;
        if (onWall) {
            // A wall torch, sign, banner or head: use the new item's wall form if there is one.
            String n = to.name();
            int cut = n.lastIndexOf('_');
            Material wall = Material.matchMaterial(cut < 0 ? "WALL_" + n : n.substring(0, cut + 1) + "WALL_" + n.substring(cut + 1));
            if (wall != null && wall.isBlock()) block = wall;
        }
        if (!block.isBlock()) return Material.STRUCTURE_VOID.createBlockData();
        int open = state.indexOf('[');
        if (open < 0) return block.createBlockData();
        List<String> kept = new ArrayList<>();
        for (String prop : state.substring(open + 1, state.length() - 1).split(",")) {
            try {
                org.bukkit.Bukkit.createBlockData(block, "[" + prop + "]");
                kept.add(prop);
            } catch (IllegalArgumentException ex) {
                // The new block doesn't have this property (stairs → planks): leave it.
            }
        }
        try {
            return org.bukkit.Bukkit.createBlockData(block, kept.isEmpty() ? "" : "[" + String.join(",", kept) + "]");
        } catch (IllegalArgumentException ex) {
            return block.createBlockData();
        }
    }

    // ── bought materials ──────────────────────────────────────────────────

    public int stocked(Material m) {
        double[] s = stock.get(m);
        return s == null ? 0 : (int) s[0];
    }

    /** Uses up to n bought items; returns how many it had. */
    public int takeStock(Material m, int n) {
        double[] s = stock.get(m);
        if (s == null || s[0] < 1) return 0;
        int took = (int) Math.min(n, s[0]);
        s[1] -= s[1] * took / s[0];
        s[0] -= took;
        if (s[0] < 1) stock.remove(m);
        return took;
    }

    public void addStock(Material m, int n, double paid) {
        double[] s = stock.computeIfAbsent(m, k -> new double[2]);
        s[0] += n;
        s[1] += paid;
    }

    /** What the unused bought materials cost: refunded when the build ends. Clears the stock. */
    public double takeStockValue() {
        double v = 0;
        for (double[] s : stock.values()) v += s[1];
        stock.clear();
        return v;
    }

    public int sizeX() {
        return rotation % 2 == 0 ? plan.sx : plan.sz;
    }

    public int sizeZ() {
        return rotation % 2 == 0 ? plan.sz : plan.sx;
    }

    public int minX() {
        return x - sizeX() / 2;
    }

    public int minZ() {
        return z - sizeZ() / 2;
    }

    /** The world box the build occupies. */
    public WorkArea bounds() {
        return WorkArea.of(world, minX(), y, minZ(), minX() + sizeX() - 1, y + plan.sy - 1, minZ() + sizeZ() - 1);
    }

    /** World position of blueprint cell (lx,ly,lz). */
    public BlockPos toWorld(int lx, int ly, int lz) {
        if (flip) lx = plan.sx - 1 - lx;
        int wx, wz;
        switch (rotation) {
            case 1 -> {
                wx = plan.sz - 1 - lz;
                wz = lx;
            }
            case 2 -> {
                wx = plan.sx - 1 - lx;
                wz = plan.sz - 1 - lz;
            }
            case 3 -> {
                wx = lz;
                wz = plan.sx - 1 - lx;
            }
            default -> {
                wx = lx;
                wz = lz;
            }
        }
        return new BlockPos(minX() + wx, y + ly, minZ() + wz);
    }

    /**
     * The blueprint cell at a world position (inverse of {@link #toWorld}),
     * as {lx, ly, lz}, or null if outside.
     */
    public int[] toLocal(int wx, int wy, int wz) {
        int rx = wx - minX(), ly = wy - y, rz = wz - minZ();
        if (rx < 0 || rz < 0 || ly < 0 || rx >= sizeX() || rz >= sizeZ() || ly >= plan.sy) return null;
        int lx, lz;
        switch (rotation) {
            case 1 -> {
                lx = rz;
                lz = plan.sz - 1 - rx;
            }
            case 2 -> {
                lx = plan.sx - 1 - rx;
                lz = plan.sz - 1 - rz;
            }
            case 3 -> {
                lx = plan.sx - 1 - rz;
                lz = rx;
            }
            default -> {
                lx = rx;
                lz = rz;
            }
        }
        if (flip) lx = plan.sx - 1 - lx;
        return new int[]{lx, ly, lz};
    }

    /** Palette entry as it is placed: mirrored and turned to match. */
    /** A palette entry turned and mirrored like the build, without swaps. */
    public BlockData turnedRaw(int index) {
        BlockData c = plan.palette.get(index).clone();
        if (flip) c.mirror(Mirror.FRONT_BACK);
        StructureRotation rot = switch (rotation) {
            case 1 -> StructureRotation.CLOCKWISE_90;
            case 2 -> StructureRotation.CLOCKWISE_180;
            case 3 -> StructureRotation.COUNTERCLOCKWISE_90;
            default -> StructureRotation.NONE;
        };
        if (rot != StructureRotation.NONE) c.rotate(rot);
        return c;
    }

    public BlockData data(int index) {
        if (turned == null) {
            List<BlockData> list = new ArrayList<>(plan.palette.size());
            StructureRotation rot = switch (rotation) {
                case 1 -> StructureRotation.CLOCKWISE_90;
                case 2 -> StructureRotation.CLOCKWISE_180;
                case 3 -> StructureRotation.COUNTERCLOCKWISE_90;
                default -> StructureRotation.NONE;
            };
            for (int i = 0; i < plan.palette.size(); i++) {
                BlockData d = plan.palette.get(i);
                String unknown = plan.unknownStates.get(i);
                BlockData c;
                if (unknown != null) {
                    // A block this server doesn't have: its replacement, or the placeholder (left alone).
                    Material to = replacements.get(Blueprint.blockName(unknown));
                    Material swapTo = to == null ? null : swaps.get(to);
                    if (swapTo != null) to = swapTo == Material.AIR ? null : swapTo;
                    c = to != null ? swapped(unknown, Blueprint.blockName(unknown).contains("wall_"), to) : d.clone();
                } else {
                    Material to = swaps.get(itemKey(d));
                    c = to != null && to != Material.AIR ? swapped(d, to) : d.clone();
                }
                if (flip) c.mirror(Mirror.FRONT_BACK);
                if (rot != StructureRotation.NONE) c.rotate(rot);
                list.add(c);
            }
            turned = list;
        }
        return turned.get(index);
    }

    public String describeTurn() {
        String r = switch (rotation) {
            case 1 -> "turned 90° clockwise";
            case 2 -> "turned 180°";
            case 3 -> "turned 90° anticlockwise";
            default -> "as drawn";
        };
        return flip ? r + ", mirrored" : r;
    }

    public void save(ConfigurationSection s) {
        s.set("blueprint", blueprint);
        s.set("world", world);
        s.set("at", x + "," + y + "," + z);
        s.set("rotation", rotation);
        s.set("flip", flip);
        s.set("clear-air", clearAir);
        s.set("break-blocks", breakBlocks);
        s.set("started", started);
        s.set("finished", finished);
        s.set("layer", layer);
        if (repair) s.set("repair", true);
        s.set("ground", keepGround ? "keep" : "skip");
        if (!clearAround) s.set("around", "leave");
        if (!tunnel.isEmpty()) {
            List<String> t = new ArrayList<>();
            tunnel.forEach((k, v) -> {
                BlockPos b = BlockPos.unpack(k);
                t.add(b.x() + "," + b.y() + "," + b.z() + "|" + v);
            });
            s.set("tunnel", t);
        }
        if (dismantle) s.set("dismantle", true);
        if (planned) s.set("planned", true);
        if (!follow) s.set("follow", false);
        for (Map.Entry<Material, Material> en : swaps.entrySet()) s.set("swaps." + en.getKey().name(), en.getValue().name());
        for (Map.Entry<String, Material> en : replacements.entrySet()) s.set("replace." + en.getKey(), en.getValue().name());
        if (!skipped.isEmpty()) s.set("skipped", skipped.stream().sorted().map(String::valueOf).toList());
        for (Map.Entry<Material, double[]> en : stock.entrySet()) {
            s.set("stock." + en.getKey().name(), (int) en.getValue()[0] + ";" + en.getValue()[1]);
        }
    }

    public static BuildOrder load(ConfigurationSection s) {
        if (s == null || s.getString("blueprint") == null) return null;
        BlockPos at = BlockPos.parse(s.getString("at", "0,0,0"));
        BuildOrder o = new BuildOrder(s.getString("blueprint"), s.getString("world", "world"), at.x(), at.y(), at.z());
        o.rotation = Math.floorMod(s.getInt("rotation"), 4);
        o.flip = s.getBoolean("flip");
        o.clearAir = s.getBoolean("clear-air", true);
        o.breakBlocks = s.getBoolean("break-blocks", true);
        o.started = s.getBoolean("started");
        o.finished = s.getBoolean("finished");
        o.layer = Math.max(0, s.getInt("layer"));
        o.repair = s.getBoolean("repair");
        // Orders from before the ground was left out were built with it: keep building (and taking down) it.
        o.keepGround = s.contains("ground") ? "keep".equals(s.getString("ground")) : true;
        o.dismantle = s.getBoolean("dismantle");
        o.clearAround = !"leave".equals(s.getString("around"));
        for (String t : s.getStringList("tunnel")) {
            int bar = t.indexOf('|');
            if (bar < 0) continue;
            try {
                BlockPos b = BlockPos.parse(t.substring(0, bar));
                o.tunnel.put(b.key(), t.substring(bar + 1));
            } catch (RuntimeException ignored) {
                // a broken entry: drop it
            }
        }
        o.planned = s.getBoolean("planned");
        o.follow = s.getBoolean("follow", true);
        ConfigurationSection sw = s.getConfigurationSection("swaps");
        if (sw != null) for (String k : sw.getKeys(false)) {
            Material from = Material.matchMaterial(k), to = Material.matchMaterial(sw.getString(k, ""));
            if (from != null && to != null) o.swaps.put(from, to);
        }
        for (String k : s.getStringList("skipped")) {
            try {
                o.skipped.add(Integer.parseInt(k));
            } catch (NumberFormatException ignored) {
                // a broken entry: drop it
            }
        }
        ConfigurationSection rp = s.getConfigurationSection("replace");
        if (rp != null) for (String k : rp.getKeys(false)) {
            Material to = Material.matchMaterial(rp.getString(k, ""));
            if (to != null) o.replacements.put(k, to);
        }
        ConfigurationSection st = s.getConfigurationSection("stock");
        if (st != null) for (String k : st.getKeys(false)) {
            Material m = Material.matchMaterial(k);
            String[] v = st.getString(k, "").split(";");
            try {
                if (m != null && v.length == 2) o.stock.put(m, new double[]{Integer.parseInt(v[0]), Double.parseDouble(v[1])});
            } catch (NumberFormatException ignored) {
                // a broken entry: drop it
            }
        }
        return o;
    }

    /**
     * Items still needed to finish, per material: every buildable block of the
     * blueprint that isn't in place yet (block types compared, as the builder does).
     * Cells in unloaded chunks count as not built.
     */
    public java.util.Map<Material, Integer> remaining(org.bukkit.World w) {
        java.util.Map<Material, Integer> out = new java.util.EnumMap<>(Material.class);
        for (int ly = 0; ly < plan.sy; ly++) {
            for (int lz = 0; lz < plan.sz; lz++) {
                for (int lx = 0; lx < plan.sx; lx++) {
                    int idx = cell(lx, ly, lz);
                    if (idx < 0) continue;
                    BlockData d = data(idx);
                    if (d.getMaterial().isAir() || unbuildable(d)) continue;
                    Material item = itemFor(d);
                    if (item == null) continue;
                    BlockPos p = toWorld(lx, ly, lz);
                    if (w != null && w.isChunkLoaded(p.x() >> 4, p.z() >> 4) && w.getType(p.x(), p.y(), p.z()) == d.getMaterial()) continue;
                    out.merge(item, countFor(d), Integer::sum);
                }
            }
        }
        return out;
    }

    /** The item a block is built from, or null if it costs nothing (the top half of a door) or can't be built. */
    public static Material itemFor(BlockData d) {
        Material m = d.getMaterial();
        if (m.isAir() || isFree(d)) return null;
        Material item = d.getPlacementMaterial();
        if (item == null || item.isAir() || !item.isItem()) return null;
        return item;
    }

    /** Blocks that come with another one (top half of a door or tall flower, head of a bed). */
    public static boolean isFree(BlockData d) {
        if (d instanceof org.bukkit.block.data.type.Bed bed) return bed.getPart() == org.bukkit.block.data.type.Bed.Part.HEAD;
        if (d instanceof org.bukkit.block.data.Bisected b && !(d instanceof org.bukkit.block.data.type.Stairs)
                && !(d instanceof org.bukkit.block.data.type.TrapDoor)) {
            return b.getHalf() == org.bukkit.block.data.Bisected.Half.TOP;
        }
        return d.getMaterial() == Material.PISTON_HEAD;
    }

    /** How many items one block of this state takes (double slabs, candles, pickles...). */
    public static int countFor(BlockData d) {
        if (d instanceof org.bukkit.block.data.type.Slab s && s.getType() == org.bukkit.block.data.type.Slab.Type.DOUBLE) return 2;
        if (d instanceof org.bukkit.block.data.type.Candle c) return c.getCandles();
        if (d instanceof org.bukkit.block.data.type.SeaPickle p) return p.getPickles();
        if (d instanceof org.bukkit.block.data.type.TurtleEgg t) return t.getEggs();
        if (d instanceof org.bukkit.block.data.type.Snow s) return s.getLayers();
        return 1;
    }

    /** Blocks a survival builder can't get or shouldn't place: left out of builds. */
    public static boolean unbuildable(BlockData d) {
        Material m = d.getMaterial();
        return switch (m) {
            case BEDROCK, BARRIER, LIGHT, COMMAND_BLOCK, CHAIN_COMMAND_BLOCK, REPEATING_COMMAND_BLOCK, STRUCTURE_BLOCK,
                 JIGSAW, STRUCTURE_VOID, SPAWNER, TRIAL_SPAWNER, VAULT, END_PORTAL, END_PORTAL_FRAME, END_GATEWAY,
                 NETHER_PORTAL, MOVING_PISTON, FIRE, SOUL_FIRE, WATER, LAVA, BUBBLE_COLUMN, REINFORCED_DEEPSLATE,
                 BUDDING_AMETHYST, FROSTED_ICE, PETRIFIED_OAK_SLAB, TEST_BLOCK, TEST_INSTANCE_BLOCK -> true;
            default -> false;
        };
    }

    /**
     * Items that may stand in when the exact one is missing: silk-touch-only
     * ground blocks are built from dirt.
     */
    public static Material substitute(Material item) {
        return switch (item) {
            case GRASS_BLOCK, PODZOL, MYCELIUM, DIRT_PATH, FARMLAND, ROOTED_DIRT -> Material.DIRT;
            default -> null;
        };
    }
}
