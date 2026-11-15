package site.remotearc.employees.build;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import java.util.List;
import java.util.Set;

/**
 * A loaded schematic: a box of sx × sy × sz cells, each a palette index
 * (air included, so the builder knows what to clear) or -1 for "leave
 * whatever is there" (structure voids, blocks this server doesn't know).
 */
public final class Blueprint {

    public final String name;
    public final String format;
    public final int sx, sy, sz;
    public final List<BlockData> palette;
    private final short[] cells;
    /** Block names in the file this server could not read (newer or modded blocks). */
    public final Set<String> unknown;
    /**
     * Palette entries standing in for those blocks (a structure void: left
     * alone unless a build says what to use instead): index → the state as
     * written in the file, e.g. "minecraft:poplar_stairs[facing=east]".
     */
    public final java.util.Map<Integer, String> unknownStates;
    /** Where it was planned or copied from (its low corner), for blueprints made in the game; null otherwise. */
    public String originWorld;
    public int originX, originY, originZ;

    /** Saved by a newer Minecraft than this server runs. */
    public final boolean newer;

    Blueprint(String name, String format, int sx, int sy, int sz, List<BlockData> palette, short[] cells, Set<String> unknown,
              java.util.Map<Integer, String> unknownStates, boolean newer) {
        this.name = name;
        this.format = format;
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.palette = List.copyOf(palette);
        this.cells = cells;
        this.unknown = Set.copyOf(unknown);
        this.unknownStates = java.util.Map.copyOf(unknownStates);
        this.newer = newer;
    }

    /** "poplar_trapdoor" from "minecraft:poplar_trapdoor[facing=east]". */
    public static String blockName(String state) {
        int bracket = state.indexOf('[');
        String n = bracket < 0 ? state : state.substring(0, bracket);
        return n.startsWith("minecraft:") ? n.substring(10) : n;
    }

    /** How many cells hold each block this server doesn't know, by name. */
    public java.util.Map<String, Integer> unknownCounts() {
        java.util.Map<String, Integer> out = new java.util.TreeMap<>();
        if (unknownStates.isEmpty()) return out;
        for (short c : cells) {
            String st = c >= 0 ? unknownStates.get((int) c) : null;
            if (st != null) out.merge(blockName(st), 1, Integer::sum);
        }
        return out;
    }

    /** Palette index at (x,y,z), or -1 to leave the world as it is. */
    public int get(int x, int y, int z) {
        return cells[(y * sz + z) * sx + x];
    }

    public int volume() {
        return cells.length;
    }

    /** Cells holding a real block (not air, not left alone). */
    public int solidCount() {
        int n = 0;
        for (short c : cells) if (c >= 0 && !palette.get(c).getMaterial().isAir() && !unknownStates.containsKey((int) c)) n++;
        return n;
    }

    public String size() {
        return sx + "x" + sy + "x" + sz;
    }

    static boolean isAir(BlockData d) {
        Material m = d.getMaterial();
        return m.isAir();
    }
}
