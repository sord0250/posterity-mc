package site.remotearc.employees.build;

import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extension.platform.Capability;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.DataFixer;
import com.sk89q.worldedit.world.block.BlockState;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * The parts of blueprint loading that use WorldEdit. Only touched when
 * WorldEdit is enabled, so ArcEmployees still loads without it.
 */
public final class WorldEditFormats {

    private WorldEditFormats() {}

    /** Reads any format WorldEdit knows (.schem Sponge v1-v3, .schematic MCEdit). */
    static Blueprint read(String name, File f, int maxVolume) throws IOException {
        ClipboardFormat format = ClipboardFormats.findByPath(f.toPath());
        if (format == null) throw new IOException("WorldEdit doesn't recognise this file as a schematic");
        Clipboard cb;
        try (ClipboardReader reader = format.getReader(new FileInputStream(f))) {
            cb = reader.read();
        }
        BlockVector3 min = cb.getRegion().getMinimumPoint(), max = cb.getRegion().getMaximumPoint();
        BlueprintLoader.Plan plan = new BlueprintLoader.Plan(max.x() - min.x() + 1, max.y() - min.y() + 1,
                max.z() - min.z() + 1, maxVolume, null);
        Map<BlockState, Integer> ids = new HashMap<>();
        for (int y = min.y(); y <= max.y(); y++) {
            for (int z = min.z(); z <= max.z(); z++) {
                for (int x = min.x(); x <= max.x(); x++) {
                    BlockState st = cb.getBlock(BlockVector3.at(x, y, z));
                    Integer idx = ids.get(st);
                    if (idx == null) {
                        try {
                            idx = plan.add(BukkitAdapter.adapt(st));
                        } catch (RuntimeException ex) {
                            plan.unknown.add(st.getBlockType().id());
                            idx = -1;
                        }
                        ids.put(st, idx);
                    }
                    plan.set(x - min.x(), y - min.y(), z - min.z(), idx);
                }
            }
        }
        return plan.done(name, format.getName());
    }

    /**
     * Saves a box of the world as a Sponge .schem (what repair mode builds a
     * snapshot from). Reads the world, so call it on the main thread.
     */
    public static void write(org.bukkit.World world, site.remotearc.employees.util.WorkArea box, File out) throws IOException {
        com.sk89q.worldedit.world.World w = BukkitAdapter.adapt(world);
        com.sk89q.worldedit.regions.CuboidRegion region = new com.sk89q.worldedit.regions.CuboidRegion(w,
                BlockVector3.at(box.minX(), box.minY(), box.minZ()), BlockVector3.at(box.maxX(), box.maxY(), box.maxZ()));
        com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard cb = new com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard(region);
        cb.setOrigin(region.getMinimumPoint());
        try (com.sk89q.worldedit.EditSession es = WorldEdit.getInstance().newEditSession(w)) {
            com.sk89q.worldedit.function.operation.ForwardExtentCopy copy =
                    new com.sk89q.worldedit.function.operation.ForwardExtentCopy(es, region, cb, region.getMinimumPoint());
            copy.setCopyingEntities(false);
            copy.setCopyingBiomes(false);
            com.sk89q.worldedit.function.operation.Operations.complete(copy);
        } catch (com.sk89q.worldedit.WorldEditException ex) {
            throw new IOException(ex.getMessage(), ex);
        }
        try (com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter writer =
                     com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC.getWriter(new java.io.FileOutputStream(out))) {
            writer.write(cb);
        }
    }

    /** Upgrades block state strings saved by an older Minecraft version, using WorldEdit's data fixer. */
    static UnaryOperator<String> blockStateFixer(int dataVersion) {
        DataFixer fixer = WorldEdit.getInstance().getPlatformManager().queryCapability(Capability.WORLD_EDITING).getDataFixer();
        if (fixer == null) return null;
        return s -> fixer.fixUp(DataFixer.FixTypes.BLOCK_STATE, s, dataVersion);
    }
}
