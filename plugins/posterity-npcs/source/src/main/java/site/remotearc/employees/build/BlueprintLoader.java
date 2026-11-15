package site.remotearc.employees.build;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.structure.Palette;
import org.bukkit.structure.Structure;
import org.bukkit.util.BlockVector;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;

/**
 * Reads schematic files into {@link Blueprint}s:
 *  - .schem / .schematic: WorldEdit's own reader when WorldEdit is installed
 *    (Sponge v1-v3 and old MCEdit files, with block names upgraded from older
 *    Minecraft versions); otherwise a built-in Sponge v1-v3 reader.
 *  - .litematic: Litematica's format, read here.
 *  - .nbt: vanilla structure-block files, through Paper's structure manager.
 */
public final class BlueprintLoader {

    public static final List<String> EXTENSIONS = List.of(".schem", ".schematic", ".litematic", ".nbt");

    private BlueprintLoader() {}

    public static boolean supported(String fileName) {
        String n = fileName.toLowerCase(Locale.ROOT);
        for (String ext : EXTENSIONS) if (n.endsWith(ext)) return true;
        return false;
    }

    /** Whether this file must be read on the main thread (Paper's structure loader). */
    static boolean needsMainThread(File f) {
        return f.getName().toLowerCase(Locale.ROOT).endsWith(".nbt");
    }

    /** Files bigger than this aren't even opened (a schematic that size would be tens of millions of blocks). */
    static final long MAX_FILE_BYTES = 100L * 1024 * 1024;
    /** .nbt structures load on the main thread, so they're kept small enough not to stall the server. */
    static final long MAX_NBT_BYTES = 4L * 1024 * 1024;

    static Blueprint load(File f, int maxVolume) throws IOException {
        String n = f.getName().toLowerCase(Locale.ROOT);
        if (f.length() > MAX_FILE_BYTES) {
            throw new IOException("the file is " + f.length() / 1024 / 1024 + " MB; blueprints over " + MAX_FILE_BYTES / 1024 / 1024
                    + " MB are refused so the server doesn't run out of memory");
        }
        if (n.endsWith(".nbt") && f.length() > MAX_NBT_BYTES) {
            throw new IOException("this structure file is " + f.length() / 1024 + " KB; .nbt files load on the main thread, so the limit is "
                    + MAX_NBT_BYTES / 1024 / 1024 + " MB. Save it as a .schem with WorldEdit instead");
        }
        String name = f.getName();
        if (n.endsWith(".litematic")) {
            try (InputStream in = new FileInputStream(f)) {
                return litematic(name, Nbt.read(in), maxVolume);
            }
        }
        if (n.endsWith(".nbt")) return structure(name, f, maxVolume);
        if (n.endsWith(".schem")) {
            // The built-in Sponge reader keeps blocks this server doesn't know (WorldEdit turns them into air).
            try (InputStream in = new FileInputStream(f)) {
                return sponge(name, Nbt.read(in), maxVolume);
            } catch (IOException | RuntimeException ex) {
                // fall through to WorldEdit
            }
        }
        if (Bukkit.getPluginManager().isPluginEnabled("WorldEdit")) {
            try {
                return WorldEditFormats.read(name, f, maxVolume);
            } catch (IOException | RuntimeException | LinkageError ex) {
                // fall through to the built-in reader, which may still understand it
                try (InputStream in = new FileInputStream(f)) {
                    return sponge(name, Nbt.read(in), maxVolume);
                } catch (IOException | RuntimeException ignored) {
                    throw ex instanceof IOException io ? io : new IOException(ex.getMessage(), ex);
                }
            }
        }
        try (InputStream in = new FileInputStream(f)) {
            return sponge(name, Nbt.read(in), maxVolume);
        }
    }

    // ── shared plan assembly ──────────────────────────────────────────────

    /** Collects cells and a de-duplicated palette while a file is read. */
    static final class Plan {
        final int sx, sy, sz;
        final short[] cells;
        final List<BlockData> palette = new ArrayList<>();
        final Map<String, Integer> index = new HashMap<>();
        private final Map<BlockData, Integer> byData = new HashMap<>();
        final Set<String> unknown = new TreeSet<>();
        final Map<Integer, String> unknownStates = new HashMap<>();
        /** The Minecraft data version the file was saved with (0: not said). */
        int dataVersion;
        private final UnaryOperator<String> fixer;

        Plan(int sx, int sy, int sz, int maxVolume, UnaryOperator<String> fixer) throws IOException {
            if (sx <= 0 || sy <= 0 || sz <= 0) throw new IOException("the schematic is empty");
            long volume = (long) sx * sy * sz;
            if (volume > maxVolume) {
                throw new IOException("it is " + sx + "x" + sy + "x" + sz + " (" + volume + " blocks); the limit is "
                        + maxVolume + " (jobs.builder.max-volume)");
            }
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
            this.cells = new short[(int) volume];
            Arrays.fill(cells, (short) -1);
            this.fixer = fixer == null ? UnaryOperator.identity() : fixer;
        }

        /** Palette index for a block state string like "minecraft:oak_stairs[facing=east]", or -1. */
        int state(String state) {
            Integer known = index.get(state);
            if (known != null) return known;
            int idx;
            String fixed = state;
            try {
                fixed = fixer.apply(state);
            } catch (RuntimeException ignored) {
                // keep the original name
            }
            BlockData data = parse(fixed);
            int bracket = fixed.indexOf('[');
            // Properties this version doesn't have (other versions, mods): keep the block, drop the properties.
            if (data == null && bracket > 0) data = parse(fixed.substring(0, bracket));
            if (data == null) {
                // Newer or modded: a placeholder the build can swap for something this server has.
                unknown.add(bracket < 0 ? fixed : fixed.substring(0, bracket));
                if (palette.size() >= Short.MAX_VALUE) idx = -1;
                else {
                    palette.add(Material.STRUCTURE_VOID.createBlockData());
                    idx = palette.size() - 1;
                    unknownStates.put(idx, fixed);
                }
            } else {
                idx = add(data);
            }
            index.put(state, idx);
            return idx;
        }

        private static BlockData parse(String state) {
            try {
                return Bukkit.createBlockData(state);
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }

        int add(BlockData data) {
            if (data.getMaterial() == Material.STRUCTURE_VOID) return -1;
            Integer known = byData.get(data);
            if (known != null) return known;
            if (palette.size() >= Short.MAX_VALUE) return -1;
            palette.add(data);
            byData.put(data, palette.size() - 1);
            return palette.size() - 1;
        }

        void set(int x, int y, int z, int idx) {
            if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) return;
            cells[(y * sz + z) * sx + x] = (short) idx;
        }

        Blueprint done(String name, String format) {
            return new Blueprint(name, format, sx, sy, sz, palette, cells, unknown, unknownStates,
                    dataVersion > Bukkit.getUnsafe().getDataVersion());
        }
    }

    static UnaryOperator<String> fixerFor(int dataVersion) {
        if (dataVersion <= 0 || !Bukkit.getPluginManager().isPluginEnabled("WorldEdit")) return null;
        if (dataVersion >= Bukkit.getUnsafe().getDataVersion()) return null;   // same or newer: nothing to upgrade
        try {
            return WorldEditFormats.blockStateFixer(dataVersion);
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    // ── Litematica ────────────────────────────────────────────────────────

    private static Blueprint litematic(String name, Map<String, Object> root, int maxVolume) throws IOException {
        Map<String, Object> regions = Nbt.compound(root, "Regions");
        if (regions == null || regions.isEmpty()) throw new IOException("no regions in the litematic");
        int dataVersion = Nbt.integer(root, "MinecraftDataVersion", 0);

        // Each region has a corner and a size that may be negative; work out the enclosing box.
        record Region(int x, int y, int z, int sx, int sy, int sz, List<Object> palette, long[] states) {}
        List<Region> list = new ArrayList<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Object o : regions.values()) {
            if (!(o instanceof Map<?, ?>)) continue;
            @SuppressWarnings("unchecked") Map<String, Object> r = (Map<String, Object>) o;
            Map<String, Object> pos = Nbt.compound(r, "Position"), size = Nbt.compound(r, "Size");
            if (pos == null || size == null || !(r.get("BlockStates") instanceof long[] states)) continue;
            int px = Nbt.integer(pos, "x", 0), py = Nbt.integer(pos, "y", 0), pz = Nbt.integer(pos, "z", 0);
            int sx = Nbt.integer(size, "x", 0), sy = Nbt.integer(size, "y", 0), sz = Nbt.integer(size, "z", 0);
            if (sx == 0 || sy == 0 || sz == 0) continue;
            int x0 = sx < 0 ? px + sx + 1 : px, y0 = sy < 0 ? py + sy + 1 : py, z0 = sz < 0 ? pz + sz + 1 : pz;
            Region reg = new Region(x0, y0, z0, Math.abs(sx), Math.abs(sy), Math.abs(sz), Nbt.list(r, "BlockStatePalette"), states);
            list.add(reg);
            minX = Math.min(minX, x0);
            minY = Math.min(minY, y0);
            minZ = Math.min(minZ, z0);
            maxX = Math.max(maxX, x0 + reg.sx - 1);
            maxY = Math.max(maxY, y0 + reg.sy - 1);
            maxZ = Math.max(maxZ, z0 + reg.sz - 1);
        }
        if (list.isEmpty()) throw new IOException("the litematic has no readable regions");
        Plan plan = new Plan(maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1, maxVolume, fixerFor(dataVersion));
        plan.dataVersion = dataVersion;

        for (Region r : list) {
            int[] ids = new int[r.palette.size()];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = r.palette.get(i) instanceof Map<?, ?> m ? plan.state(stateString(m)) : -1;
            }
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, ids.length - 1)));
            long mask = (1L << bits) - 1;
            long volume = (long) r.sx * r.sy * r.sz;
            if ((volume * bits + 63) / 64 > r.states.length) throw new IOException("a region's block data is truncated");
            int layer = r.sx * r.sz;
            for (int i = 0; i < volume; i++) {
                long start = (long) i * bits;
                int startArr = (int) (start >> 6), endArr = (int) (((long) (i + 1) * bits - 1) >> 6);
                int startBit = (int) (start & 63);
                long v = startArr == endArr
                        ? (r.states[startArr] >>> startBit) & mask
                        : ((r.states[startArr] >>> startBit) | (r.states[endArr] << (64 - startBit))) & mask;
                int idx = v < ids.length ? ids[(int) v] : -1;
                int y = i / layer, rest = i % layer, z = rest / r.sx, x = rest % r.sx;
                plan.set(r.x - minX + x, r.y - minY + y, r.z - minZ + z, idx);
            }
        }
        return plan.done(name, "Litematica");
    }

    private static String stateString(Map<?, ?> m) {
        Object nameObj = m.get("Name");
        String name = nameObj instanceof String s ? s : "minecraft:air";
        if (!(m.get("Properties") instanceof Map<?, ?> props) || props.isEmpty()) return name;
        StringBuilder sb = new StringBuilder(name).append('[');
        boolean first = true;
        for (Map.Entry<?, ?> p : props.entrySet()) {
            if (!first) sb.append(',');
            sb.append(p.getKey()).append('=').append(p.getValue());
            first = false;
        }
        return sb.append(']').toString();
    }

    // ── Sponge (.schem) ───────────────────────────────────────────────────

    static Blueprint sponge(String name, Map<String, Object> root, int maxVolume) throws IOException {
        Map<String, Object> s = Nbt.compound(root, "Schematic");
        if (s == null) s = root;
        int version = Nbt.integer(s, "Version", 0);
        int w = Nbt.integer(s, "Width", 0) & 0xFFFF, h = Nbt.integer(s, "Height", 0) & 0xFFFF, l = Nbt.integer(s, "Length", 0) & 0xFFFF;
        Map<String, Object> palette;
        Object data;
        if (version >= 3) {
            Map<String, Object> blocks = Nbt.compound(s, "Blocks");
            if (blocks == null) throw new IOException("no blocks in the schematic");
            palette = Nbt.compound(blocks, "Palette");
            data = blocks.get("Data");
        } else {
            palette = Nbt.compound(s, "Palette");
            data = s.get("BlockData");
        }
        if (palette == null || !(data instanceof byte[] bytes)) {
            if (s.containsKey("Blocks") && s.get("Blocks") instanceof byte[]) {
                throw new IOException("this is an old MCEdit .schematic; reading it needs WorldEdit installed");
            }
            throw new IOException("not a Sponge schematic");
        }
        Plan plan = new Plan(w, h, l, maxVolume, fixerFor(Nbt.integer(s, "DataVersion", 0)));
        plan.dataVersion = Nbt.integer(s, "DataVersion", 0);
        Map<Integer, Integer> ids = new HashMap<>();
        for (Map.Entry<String, Object> e : palette.entrySet()) {
            if (e.getValue() instanceof Number n) ids.put(n.intValue(), plan.state(e.getKey()));
        }
        int i = 0, pos = 0, total = w * h * l;
        while (pos < bytes.length && i < total) {
            int value = 0, shift = 0, b;
            do {
                if (pos >= bytes.length) throw new IOException("block data is truncated");
                b = bytes[pos++];
                value |= (b & 0x7F) << shift;
                shift += 7;
                if (shift > 35) throw new IOException("bad block data");
            } while ((b & 0x80) != 0);
            int y = i / (w * l), rest = i % (w * l), z = rest / w, x = rest % w;
            plan.set(x, y, z, ids.getOrDefault(value, -1));
            i++;
        }
        Blueprint bp = plan.done(name, "Sponge schematic v" + Math.max(1, version));
        Map<String, Object> meta = Nbt.compound(s, "Metadata");
        if (meta != null && Nbt.string(meta, "ArcWorld") != null) {
            bp.originWorld = Nbt.string(meta, "ArcWorld");
            bp.originX = Nbt.integer(meta, "ArcX", 0);
            bp.originY = Nbt.integer(meta, "ArcY", 0);
            bp.originZ = Nbt.integer(meta, "ArcZ", 0);
        }
        return bp;
    }

    // ── vanilla structure (.nbt) ──────────────────────────────────────────

    private static Blueprint structure(String name, File f, int maxVolume) throws IOException {
        Structure st = Bukkit.getStructureManager().loadStructure(f);
        BlockVector size = st.getSize();
        if (st.getPalettes().isEmpty()) throw new IOException("the structure has no blocks");
        Plan plan = new Plan(size.getBlockX(), size.getBlockY(), size.getBlockZ(), maxVolume, null);
        Palette palette = st.getPalettes().getFirst();
        for (BlockState bs : palette.getBlocks()) {
            plan.set(bs.getX(), bs.getY(), bs.getZ(), plan.add(bs.getBlockData()));
        }
        return plan.done(name, "structure");
    }
}
