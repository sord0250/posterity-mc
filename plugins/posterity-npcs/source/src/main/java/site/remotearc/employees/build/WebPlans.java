package site.remotearc.employees.build;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Gate;
import org.bukkit.block.data.type.Ladder;
import org.bukkit.block.data.type.Wall;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.Employee;
import site.remotearc.employees.util.BlockPos;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Changes planned on the web map, handed to a builder: they go into its plan
 * the way plan mode's do (only the changed blocks; the rest of the world is
 * left alone), and it builds them. Fences, panes, walls and ladders are
 * joined up and turned to fit what's round them; doors get their top half.
 */
public final class WebPlans {

    private WebPlans() {}

    /** One change: a world position and the block wanted there ("air": clear it). */
    public record Edit(int x, int y, int z, String block) {}

    /**
     * Puts the edits into the builder's plan (main thread). A message for the
     * player: what happened, or why not (starting with "!").
     */
    public static String send(ArcEmployees plugin, Employee e, String world, List<Edit> edits, boolean start) {
        if (edits.isEmpty()) return "!Nothing to send.";
        World w = Bukkit.getWorld(world);
        if (w == null) return "!That world isn't loaded.";
        if (PlanMode.planningBuilder(e.id)) return "!Someone is planning for " + e.name + " in the game right now. Finish that first (/emp plan done).";
        // What's asked for, as block data.
        Map<Long, BlockData> asked = new HashMap<>();
        for (Edit ed : edits) {
            BlockData d;
            try {
                d = ed.block().equals("air") ? Material.AIR.createBlockData() : Bukkit.createBlockData(ed.block().contains(":") ? ed.block() : "minecraft:" + ed.block());
            } catch (IllegalArgumentException ex) {
                return "!Unknown block: " + ed.block();
            }
            asked.put(BlockPos.pack(ed.x(), ed.y(), ed.z()), d);
        }
        fitTogether(w, asked);

        BuildOrder o = e.build;
        Map<Long, BlockData> blocks = new HashMap<>();
        String file;
        if (o != null) {
            if (o.plan == null) return "!" + e.name + " is still reading " + o.blueprint + ". Try again in a moment.";
            if (!o.world.equals(world)) return "!" + e.name + "'s build is in " + o.world + ". Send these to another builder, or cancel that build first.";
            if (o.dismantle) return "!" + e.name + " is taking " + o.blueprint + " down. Wait for that to finish.";
            // The plan as it is, world positions (blocks left out stay out).
            for (int y = 0; y < o.plan.sy; y++) {
                for (int z = 0; z < o.plan.sz; z++) {
                    for (int x = 0; x < o.plan.sx; x++) {
                        int idx = o.planned ? o.plan.get(x, y, z) : o.cell(x, y, z);
                        if (idx < 0 || o.planned && o.skippedCell(x, y, z)) continue;
                        blocks.put(o.toWorld(x, y, z).key(), o.turnedRaw(idx));
                    }
                }
            }
            file = o.blueprint;
            if (!o.planned) {
                String base = file.replaceAll("\\.[A-Za-z]+$", "");
                file = base + "-as-built.schem";
                for (int n = 2; plugin.blueprints().resolve(file.substring(0, file.length() - 6)) != null; n++) file = base + "-as-built-" + n + ".schem";
            }
        } else {
            String base = ("plan-" + e.ownerName() + "-" + e.name).replaceAll("[^A-Za-z0-9_.-]+", "_");
            file = base + ".schem";
            for (int n = 2; plugin.blueprints().resolve(file.substring(0, file.length() - 6)) != null; n++) file = base + "-" + n + ".schem";
        }
        int add = 0, clear = 0;
        for (Map.Entry<Long, BlockData> en : asked.entrySet()) {
            blocks.put(en.getKey(), en.getValue());
            if (en.getValue().getMaterial().isAir()) clear++;
            else add++;
        }
        Plans.Built b;
        try {
            b = Plans.build(file, blocks, plugin.settings().builderMaxVolume, world);
        } catch (IOException ex) {
            return "!That makes the build too big for one builder (" + ex.getMessage() + ").";
        }
        if (o == null) {
            o = new BuildOrder(file, world, 0, 0, 0);
            e.build = o;
        }
        boolean wasStarted = o.started;
        o.blueprint = file;
        o.planned = true;
        o.keepGround = true;
        o.skipped.clear();
        o.rotation = 0;
        o.flip = false;
        o.setPlan(b.bp());
        o.x = b.minX() + b.bp().sx / 2;
        o.y = b.minY();
        o.z = b.minZ() + b.bp().sz / 2;
        o.turnedChanged();
        o.finished = false;
        o.layer = 0;
        if (start) o.started = true;
        e.worker.interrupt();
        plugin.manager().setArea(e, o.bounds().expand(2, 2));
        plugin.manager().markDirty();
        Blueprint bp = b.bp();
        File f = new File(plugin.blueprints().dir(), file);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Plans.write(f, bp);
            } catch (IOException ex) {
                plugin.getLogger().log(Level.WARNING, "Could not save " + f.getName(), ex);
            }
        });
        String what = (add > 0 ? add + " block" + (add == 1 ? "" : "s") + " to place" : "") + (add > 0 && clear > 0 ? " and " : "")
                + (clear > 0 ? clear + " to clear" : "");
        return e.name + " has your " + what + " in " + file + ". " + (o.started ? (wasStarted ? e.name + " works them in now." : e.name + " starts building now.")
                : "Press Start when you're ready (/emp build " + e.name + " start).");
    }

    /** Fences, panes and walls join what's next to them; ladders face away from their wall; doors get a top half. */
    static void fitTogether(World w, Map<Long, BlockData> asked) {
        for (Map.Entry<Long, BlockData> en : new HashMap<>(asked).entrySet()) {
            BlockPos p = BlockPos.unpack(en.getKey());
            BlockData d = en.getValue();
            boolean joinsUp = Tag.FENCES.isTagged(d.getMaterial()) || d.getMaterial().name().endsWith("_PANE") || d.getMaterial() == Material.IRON_BARS;
            if (joinsUp && d instanceof MultipleFacing mf) {
                for (BlockFace f : List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
                    if (mf.getAllowedFaces().contains(f)) mf.setFace(f, joins(w, asked, p, f, d.getMaterial()));
                }
            } else if (d instanceof Wall wall) {
                for (BlockFace f : List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
                    wall.setHeight(f, joins(w, asked, p, f, d.getMaterial()) ? Wall.Height.LOW : Wall.Height.NONE);
                }
            } else if (d instanceof Ladder ladder) {
                for (BlockFace f : List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
                    if (solid(w, asked, p.x() + f.getModX(), p.y(), p.z() + f.getModZ())) {
                        ladder.setFacing(f.getOppositeFace());
                        break;
                    }
                }
            } else if (d instanceof Gate gate) {
                // Across the gap between two fence posts.
                boolean ew = post(w, asked, p, BlockFace.EAST) || post(w, asked, p, BlockFace.WEST);
                gate.setFacing(ew ? BlockFace.NORTH : BlockFace.EAST);
            } else if (d instanceof Door door && door.getHalf() == Bisected.Half.BOTTOM) {
                long up = BlockPos.pack(p.x(), p.y() + 1, p.z());
                if (!asked.containsKey(up)) {
                    Door top = (Door) door.clone();
                    top.setHalf(Bisected.Half.TOP);
                    asked.put(up, top);
                }
            } else if (d instanceof Directional dir && d.getMaterial().name().endsWith("_STAIRS")) {
                // Stairs climb toward whatever solid block is behind them.
                for (BlockFace f : List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
                    if (solid(w, asked, p.x() + f.getModX(), p.y(), p.z() + f.getModZ())) {
                        dir.setFacing(f);
                        break;
                    }
                }
            }
        }
    }

    private static boolean joins(World w, Map<Long, BlockData> asked, BlockPos p, BlockFace f, Material self) {
        int x = p.x() + f.getModX(), z = p.z() + f.getModZ();
        BlockData n = asked.get(BlockPos.pack(x, p.y(), z));
        if (n == null) n = w.getBlockData(x, p.y(), z);
        Material m = n.getMaterial();
        if (m.isAir()) return false;
        if (Tag.FENCES.isTagged(self)) return Tag.FENCES.isTagged(m) && Tag.WOODEN_FENCES.isTagged(m) == Tag.WOODEN_FENCES.isTagged(self)
                || Tag.FENCE_GATES.isTagged(m) || m.isOccluding();
        if (Tag.WALLS.isTagged(self)) return Tag.WALLS.isTagged(m) || Tag.FENCE_GATES.isTagged(m) || m.isOccluding();
        return m == self || m.name().endsWith("_PANE") || m == Material.IRON_BARS || Tag.WALLS.isTagged(m) || m.isOccluding();
    }

    /** A fence or wall there for a gate to hang between. */
    private static boolean post(World w, Map<Long, BlockData> asked, BlockPos p, BlockFace f) {
        int x = p.x() + f.getModX(), z = p.z() + f.getModZ();
        BlockData n = asked.get(BlockPos.pack(x, p.y(), z));
        if (n == null) n = w.getBlockData(x, p.y(), z);
        return Tag.FENCES.isTagged(n.getMaterial()) || Tag.WALLS.isTagged(n.getMaterial());
    }

    private static boolean solid(World w, Map<Long, BlockData> asked, int x, int y, int z) {
        BlockData n = asked.get(BlockPos.pack(x, y, z));
        if (n == null) n = w.getBlockData(x, y, z);
        return n.getMaterial().isOccluding();
    }
}
