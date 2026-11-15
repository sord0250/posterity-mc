package site.remotearc.employees.build;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import site.remotearc.employees.util.BlockPos;
import site.remotearc.employees.util.WorkArea;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Blueprints made in the game rather than downloaded: from blocks planned in
 * the world (plan mode), or copied from a WorldEdit selection. Written as
 * Sponge schematics, so they're ordinary blueprint files afterwards.
 */
public final class Plans {

    private Plans() {}

    /** A blueprint and the world position of its low corner. */
    public record Built(Blueprint bp, int minX, int minY, int minZ) {}

    /** From planned blocks (position key → block, air meaning "clear it"); cells nobody planned are left alone. */
    public static Built build(String name, Map<Long, BlockData> blocks, int maxVolume) throws IOException {
        if (blocks.isEmpty()) throw new IOException("nothing is planned");
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (long k : blocks.keySet()) {
            BlockPos p = BlockPos.unpack(k);
            minX = Math.min(minX, p.x());
            minY = Math.min(minY, p.y());
            minZ = Math.min(minZ, p.z());
            maxX = Math.max(maxX, p.x());
            maxY = Math.max(maxY, p.y());
            maxZ = Math.max(maxZ, p.z());
        }
        BlueprintLoader.Plan plan = new BlueprintLoader.Plan(maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1, maxVolume, null);
        for (Map.Entry<Long, BlockData> e : blocks.entrySet()) {
            BlockPos p = BlockPos.unpack(e.getKey());
            plan.set(p.x() - minX, p.y() - minY, p.z() - minZ, plan.add(e.getValue()));
        }
        plan.dataVersion = Bukkit.getUnsafe().getDataVersion();
        return new Built(plan.done(name, "planned in the world"), minX, minY, minZ);
    }

    /** The same, remembering where in the world it was planned. */
    public static Built build(String name, Map<Long, BlockData> blocks, int maxVolume, String world) throws IOException {
        Built b = build(name, blocks, maxVolume);
        b.bp().originWorld = world;
        b.bp().originX = b.minX();
        b.bp().originY = b.minY();
        b.bp().originZ = b.minZ();
        return b;
    }

    /** Everything in a box of the world, air included (so the builder clears what the box has as air). */
    public static Built fromWorld(World w, WorkArea box, String name, int maxVolume) throws IOException {
        BlueprintLoader.Plan plan = new BlueprintLoader.Plan(box.maxX() - box.minX() + 1, box.maxY() - box.minY() + 1,
                box.maxZ() - box.minZ() + 1, maxVolume, null);
        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    plan.set(x - box.minX(), y - box.minY(), z - box.minZ(), plan.add(w.getBlockData(x, y, z)));
                }
            }
        }
        plan.dataVersion = Bukkit.getUnsafe().getDataVersion();
        Blueprint bp = plan.done(name, "copied from the world");
        bp.originWorld = w.getName();
        bp.originX = box.minX();
        bp.originY = box.minY();
        bp.originZ = box.minZ();
        return new Built(bp, box.minX(), box.minY(), box.minZ());
    }

    /** A plan saved before plans remembered their spot: it's where this order has it; the file is updated. */
    public static void rememberOrigin(site.remotearc.employees.ArcEmployees plugin, BuildOrder o, Blueprint bp) {
        if (!o.planned || bp.originWorld != null || o.rotation != 0 || o.flip) return;
        bp.originWorld = o.world;
        bp.originX = o.minX();
        bp.originY = o.y;
        bp.originZ = o.minZ();
        File f = plugin.blueprints().resolve(o.blueprint);
        if (f != null) Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                write(f, bp);
            } catch (IOException ignored) {
                // it still works; it just won't remember its spot
            }
        });
    }

    /** Saves a blueprint as a Sponge (v2) schematic, replacing the file in one step. */
    public static void write(File file, Blueprint bp) throws IOException {
        Map<String, Integer> ids = new LinkedHashMap<>();
        int voidId = -1;
        ByteArrayOutputStream data = new ByteArrayOutputStream(bp.volume());
        int[] paletteIds = new int[bp.palette.size()];
        for (int i = 0; i < bp.palette.size(); i++) {
            String st = bp.unknownStates.getOrDefault(i, bp.palette.get(i).getAsString());
            paletteIds[i] = ids.computeIfAbsent(st, k -> ids.size());
        }
        for (int y = 0; y < bp.sy; y++) {
            for (int z = 0; z < bp.sz; z++) {
                for (int x = 0; x < bp.sx; x++) {
                    int idx = bp.get(x, y, z);
                    int id;
                    if (idx >= 0) id = paletteIds[idx];
                    else {
                        if (voidId < 0) {
                            voidId = ids.size();
                            ids.put("minecraft:structure_void", voidId);
                        }
                        id = voidId;
                    }
                    while ((id & ~0x7F) != 0) {
                        data.write((id & 0x7F) | 0x80);
                        id >>>= 7;
                    }
                    data.write(id);
                }
            }
        }
        Map<String, Object> palette = new LinkedHashMap<>();
        ids.forEach(palette::put);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("Version", 2);
        root.put("DataVersion", Bukkit.getUnsafe().getDataVersion());
        root.put("Width", (short) bp.sx);
        root.put("Height", (short) bp.sy);
        root.put("Length", (short) bp.sz);
        root.put("Offset", new int[]{0, 0, 0});
        root.put("PaletteMax", ids.size());
        root.put("Palette", palette);
        root.put("BlockData", data.toByteArray());
        if (bp.originWorld != null) {
            // Where it came from, so loading it again can put it back there.
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("ArcWorld", bp.originWorld);
            meta.put("ArcX", bp.originX);
            meta.put("ArcY", bp.originY);
            meta.put("ArcZ", bp.originZ);
            root.put("Metadata", meta);
        }
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            Nbt.write(out, "Schematic", root);
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
