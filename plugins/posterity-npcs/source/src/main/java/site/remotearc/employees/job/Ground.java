package site.remotearc.employees.job;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;
import site.remotearc.employees.util.Items;
import site.remotearc.employees.util.MaterialList;
import site.remotearc.employees.util.Pathfinder;

import java.util.EnumSet;
import java.util.Set;

/** Block classifications shared by the digging jobs. */
final class Ground {

    /** Blocks that occur naturally underground and in hillsides: safe for a miner to tunnel through. */
    static final Set<Material> NATURAL = EnumSet.noneOf(Material.class);

    static {
        NATURAL.addAll(Tag.BASE_STONE_OVERWORLD.getValues());
        NATURAL.addAll(Tag.BASE_STONE_NETHER.getValues());
        NATURAL.addAll(Tag.DIRT.getValues());
        NATURAL.addAll(Tag.SUBSTRATE_OVERWORLD.getValues());
        NATURAL.addAll(Tag.SAND.getValues());
        NATURAL.addAll(Tag.BADLANDS_TERRACOTTA.getValues());
        NATURAL.addAll(MaterialList.ORES);
        NATURAL.addAll(EnumSet.of(Material.GRAVEL, Material.CLAY, Material.CALCITE, Material.DRIPSTONE_BLOCK,
                Material.SMOOTH_BASALT, Material.SANDSTONE, Material.RED_SANDSTONE, Material.END_STONE,
                Material.SOUL_SAND, Material.SOUL_SOIL, Material.SNOW_BLOCK, Material.GRASS_BLOCK, Material.PODZOL,
                Material.MYCELIUM, Material.COARSE_DIRT, Material.ROOTED_DIRT, Material.MUD, Material.MOSS_BLOCK,
                Material.TUFF, Material.DEEPSLATE, Material.STONE, Material.GRANITE, Material.DIORITE, Material.ANDESITE,
                Material.NETHERRACK, Material.BASALT, Material.BLACKSTONE, Material.TERRACOTTA, Material.PACKED_ICE));
        NATURAL.removeIf(m -> m.name().startsWith("INFESTED_"));
    }

    private static final int[][] SIDES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private Ground() {}

    /**
     * A block from the backpack to build with (pillars, bridges, plugs, stair
     * steps): the configured filler first (cobblestone, cobbled deepslate,
     * dirt...), otherwise any plain natural stone or dirt that won't fall.
     */
    static Material buildingBlock(Worker w) {
        for (Material m : w.settings().quarryFiller) {
            if (Items.count(w.e.backpack, m) > 0) return m;
        }
        for (ItemStack s : w.e.backpack.getStorageContents()) {
            if (!Items.isPlain(s)) continue;
            Material m = s.getType();
            if (isFiller(m)) return m;
        }
        return null;
    }

    /** Stone, deepslate, dirt and the like: cheap, solid, doesn't fall. Never ores. */
    static boolean isFiller(Material m) {
        return m.isBlock() && m.isSolid() && m.isOccluding() && !m.hasGravity() && !MaterialList.ORES.contains(m)
                && (NATURAL.contains(m) || m == Material.COBBLESTONE || m == Material.COBBLED_DEEPSLATE);
    }

    /** How many building blocks of any kind are in the backpack. */
    static int buildingBlocks(Worker w) {
        int n = 0;
        for (ItemStack s : w.e.backpack.getStorageContents()) {
            if (Items.isPlain(s) && (w.settings().quarryFiller.contains(s.getType()) || isFiller(s.getType()))) n += s.getAmount();
        }
        return n;
    }

    /** Open to a cave or the sky on some side, so a player exploring could see it. */
    static boolean exposed(Block b) {
        for (int[] d : SIDES) {
            Block n = b.getRelative(d[0], d[1], d[2]);
            if (n.getType().isAir() || Pathfinder.isClear(n)) return true;
        }
        return false;
    }

    static boolean nextToLava(Block b) {
        for (int[] d : SIDES) {
            if (b.getRelative(d[0], d[1], d[2]).getType() == Material.LAVA) return true;
        }
        return false;
    }

    /** Whether breaking it with this tool drops anything (obsidian needs diamond, and so on). */
    static boolean harvestable(Block b, ItemStack tool) {
        BlockData d = b.getBlockData();
        return !d.requiresCorrectToolForDrops() || d.isPreferredTool(tool);
    }

    /** Never broken by employees: unbreakable blocks, anything that holds items, spawners. */
    static boolean protectedBlock(Block b) {
        Material m = b.getType();
        if (m.getHardness() < 0 || m == Material.SPAWNER || m == Material.TRIAL_SPAWNER || m == Material.VAULT) return true;
        return b.getState(false) instanceof Container;
    }
}
