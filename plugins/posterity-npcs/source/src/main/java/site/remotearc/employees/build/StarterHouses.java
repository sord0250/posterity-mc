package site.remotearc.employees.build;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import site.remotearc.employees.ArcEmployees;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.logging.Level;

/**
 * A couple of small one-room starter houses, built in memory (never
 * downloaded) and dropped into the NPC Homes catalog the first time it's
 * empty, so there's something to pick from out of the box.
 */
public final class StarterHouses {

    private StarterHouses() {}

    /** Adds them if the catalog is still empty (a fresh server, or one an admin emptied on purpose). */
    public static void seedIfEmpty(ArcEmployees plugin) {
        if (!Bukkit.getPluginManager().isPluginEnabled("WorldEdit")) return;
        HomeCatalog catalog = plugin.homeCatalog();
        if (!catalog.list().isEmpty()) return;
        try {
            File tmp = File.createTempFile("home-cabin", ".schem");
            tmp.deleteOnExit();
            cabin(tmp);
            catalog.add("tiny-cabin", tmp, "Tiny Cabin", Material.OAK_DOOR, "cabin", "A snug one-room starter home: four walls, a bed and a door.");
            tmp.delete();

            tmp = File.createTempFile("home-cottage", ".schem");
            tmp.deleteOnExit();
            cottage(tmp);
            catalog.add("stone-cottage", tmp, "Stone Cottage", Material.SPRUCE_DOOR, "cottage", "A small cobblestone cottage with a spruce roof and door.");
            tmp.delete();

            plugin.getLogger().info("Added 2 starter houses to the NPC Homes catalog.");
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Couldn't generate starter houses", ex);
        }
    }

    /** 5x5 footprint, oak throughout: floor, two-high walls with a door and two windows, flat roof, a bed inside. */
    private static void cabin(File out) throws IOException {
        BlockArrayClipboard cb = clipboard(5, 5, 5);
        fill(cb, "minecraft:oak_planks", 0, 0, 0, 4, 0, 4);          // floor
        walls(cb, "minecraft:oak_planks", 5, 5, 1, 3);               // y1..3
        fill(cb, "minecraft:oak_planks", 0, 4, 0, 4, 4, 4);          // roof
        door(cb, "oak", 2, 1, 0, "SOUTH");
        set(cb, 0, 2, 2, "minecraft:glass_pane");
        set(cb, 4, 2, 2, "minecraft:glass_pane");
        bed(cb, "white", 1, 1, 3, "NORTH");
        write(cb, out);
    }

    /** 5x5 footprint, cobblestone walls and floor, spruce roof and door, a bed inside. */
    private static void cottage(File out) throws IOException {
        BlockArrayClipboard cb = clipboard(5, 5, 5);
        fill(cb, "minecraft:stone_bricks", 0, 0, 0, 4, 0, 4);        // floor
        walls(cb, "minecraft:cobblestone", 5, 5, 1, 3);              // y1..3
        fill(cb, "minecraft:spruce_planks", 0, 4, 0, 4, 4, 4);       // roof
        door(cb, "spruce", 2, 1, 0, "SOUTH");
        set(cb, 0, 2, 2, "minecraft:glass_pane");
        set(cb, 4, 2, 2, "minecraft:glass_pane");
        bed(cb, "red", 1, 1, 3, "NORTH");
        write(cb, out);
    }

    private static BlockArrayClipboard clipboard(int w, int h, int l) {
        CuboidRegion region = new CuboidRegion(BlockVector3.at(0, 0, 0), BlockVector3.at(w - 1, h - 1, l - 1));
        BlockArrayClipboard cb = new BlockArrayClipboard(region);
        cb.setOrigin(BlockVector3.at(0, 0, 0));
        return cb;
    }

    private static void set(BlockArrayClipboard cb, int x, int y, int z, String blockData) {
        cb.setBlock(BlockVector3.at(x, y, z), BukkitAdapter.adapt(Bukkit.createBlockData(blockData)));
    }

    private static void fill(BlockArrayClipboard cb, String blockData, int x1, int y1, int z1, int x2, int y2, int z2) {
        var state = BukkitAdapter.adapt(Bukkit.createBlockData(blockData));
        for (int x = x1; x <= x2; x++) for (int y = y1; y <= y2; y++) for (int z = z1; z <= z2; z++) {
            cb.setBlock(BlockVector3.at(x, y, z), state);
        }
    }

    /** The four walls of a {@code width} x {@code length} footprint, from {@code yFrom} to {@code yTo}. */
    private static void walls(BlockArrayClipboard cb, String blockData, int width, int length, int yFrom, int yTo) {
        var state = BukkitAdapter.adapt(Bukkit.createBlockData(blockData));
        for (int y = yFrom; y <= yTo; y++) {
            for (int x = 0; x < width; x++) {
                cb.setBlock(BlockVector3.at(x, y, 0), state);
                cb.setBlock(BlockVector3.at(x, y, length - 1), state);
            }
            for (int z = 0; z < length; z++) {
                cb.setBlock(BlockVector3.at(0, y, z), state);
                cb.setBlock(BlockVector3.at(width - 1, y, z), state);
            }
        }
    }

    /** A door two blocks tall, lower at ({@code x},{@code y},{@code z}). {@code facing}: NORTH/SOUTH/EAST/WEST. */
    private static void door(BlockArrayClipboard cb, String wood, int x, int y, int z, String facing) {
        String f = facing.toLowerCase(java.util.Locale.ROOT);
        set(cb, x, y, z, "minecraft:" + wood + "_door[facing=" + f + ",half=lower,hinge=left,open=false]");
        set(cb, x, y + 1, z, "minecraft:" + wood + "_door[facing=" + f + ",half=upper,hinge=left,open=false]");
    }

    /** A bed's foot at ({@code x},{@code y},{@code z}); its head sits one step further in {@code facing}. */
    private static void bed(BlockArrayClipboard cb, String color, int x, int y, int z, String facing) {
        int dx = switch (facing) { case "EAST" -> 1; case "WEST" -> -1; default -> 0; };
        int dz = switch (facing) { case "SOUTH" -> 1; case "NORTH" -> -1; default -> 0; };
        String f = facing.toLowerCase(java.util.Locale.ROOT);
        set(cb, x, y, z, "minecraft:" + color + "_bed[facing=" + f + ",part=foot]");
        set(cb, x + dx, y, z + dz, "minecraft:" + color + "_bed[facing=" + f + ",part=head]");
    }

    private static void write(BlockArrayClipboard cb, File out) throws IOException {
        try (ClipboardWriter writer = BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC.getWriter(new FileOutputStream(out))) {
            writer.write(cb);
        }
    }
}
