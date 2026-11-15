package site.remotearc.employees.build;

import io.papermc.paper.math.Position;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.gui.AreaPreview;
import site.remotearc.employees.util.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shows a build to one player as ghost blocks (client-side only, nothing in
 * the world changes) plus an outline, so it can be lined up and turned
 * before the builder starts. The real blocks come back after a while, or
 * when the preview is replaced or cleared.
 *
 * The client treats ghost blocks as solid, so standing or flying inside the
 * preview would get the player stuck. Ghosts within about a block of the
 * player are swapped back for the real blocks as they move, and come back
 * behind them: they can walk and fly through the preview freely.
 */
public final class BuildPreview {

    /** A preview on one player's screen; it goes away once they've been away from the build for preview-seconds. */
    private static final class Shown {
        final String world;
        final Map<BlockPos, BlockData> ghosts;
        final Set<BlockPos> melted;
        final site.remotearc.employees.util.WorkArea near;
        BukkitTask melter;
        int awayTicks;

        Shown(String world, Map<BlockPos, BlockData> ghosts, Set<BlockPos> melted, site.remotearc.employees.util.WorkArea near) {
            this.world = world;
            this.ghosts = ghosts;
            this.melted = melted;
            this.near = near;
        }
    }

    private static final Map<UUID, Shown> SHOWN = new HashMap<>();

    private BuildPreview() {}

    /** Returns how many ghost blocks were sent (big builds show only their outer surface). */
    public static int show(ArcEmployees plugin, Player p, BuildOrder order) {
        return show(plugin, p, order, false);
    }

    /**
     * With {@code digging}: instead of the build, what will be dug out to make
     * room for it, as glass: red round the outside of the build (only when
     * it's set to clear that too), orange inside it.
     */
    public static int show(ArcEmployees plugin, Player p, BuildOrder order, boolean digging) {
        clear(p);
        if (order.plan == null) return 0;
        World w = Bukkit.getWorld(order.world);
        if (w == null || !w.equals(p.getWorld())) return 0;
        Blueprint bp = order.plan;
        int limit = plugin.settings().previewBlocks;
        boolean shellOnly = bp.solidCount() > limit;
        Map<Position, BlockData> ghosts = new HashMap<>();
        Map<BlockPos, BlockData> positions = new HashMap<>();
        outer:
        for (int y = 0; y < bp.sy; y++) {
            for (int z = 0; z < bp.sz; z++) {
                for (int x = 0; x < bp.sx; x++) {
                    int idx = order.cell(x, y, z);
                    if (digging) {
                        if (idx < 0 || !Blueprint.isAir(order.data(idx)) || !order.clearAir) continue;
                        BlockPos wp = order.toWorld(x, y, z);
                        if (!w.isChunkLoaded(wp.x() >> 4, wp.z() >> 4)) continue;
                        org.bukkit.block.Block b = w.getBlockAt(wp.x(), wp.y(), wp.z());
                        if (b.getType().isAir() || b.isLiquid()) continue;
                        BlockData g = (order.isAround(x, y, z) ? Material.RED_STAINED_GLASS : Material.ORANGE_STAINED_GLASS).createBlockData();
                        ghosts.put(Position.block(wp.x(), wp.y(), wp.z()), g);
                        positions.put(wp, g);
                        if (positions.size() >= limit) break outer;
                        continue;
                    }
                    if (idx < 0 || Blueprint.isAir(order.data(idx)) || BuildOrder.unbuildable(order.data(idx))) continue;
                    if (shellOnly && !onSurface(bp, x, y, z)) continue;
                    BlockPos wp = order.toWorld(x, y, z);
                    if (!w.isChunkLoaded(wp.x() >> 4, wp.z() >> 4)) continue;
                    ghosts.put(Position.block(wp.x(), wp.y(), wp.z()), order.data(idx));
                    positions.put(wp, order.data(idx));
                    if (positions.size() >= limit) break outer;
                }
            }
        }
        Set<BlockPos> melted = new HashSet<>();
        for (BlockPos b : around(p)) if (positions.containsKey(b)) {
            ghosts.remove(Position.block(b.x(), b.y(), b.z()));
            melted.add(b);
        }
        if (!ghosts.isEmpty()) p.sendMultiBlockChange(ghosts);
        AreaPreview.show(plugin, p, order.bounds(), plugin.settings().previewSeconds * 20);
        UUID id = p.getUniqueId();
        Shown shown = new Shown(order.world, positions, melted, order.bounds().expand(32, 32));
        int awayLimit = plugin.settings().previewSeconds * 20;
        shown.melter = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Player online = Bukkit.getPlayer(id);
            if (online == null) {
                remove(id);
                return;
            }
            Location at = online.getLocation();
            boolean near = at.getWorld().getName().equals(shown.world) && shown.near.contains(at.getBlockX(), at.getBlockY(), at.getBlockZ());
            shown.awayTicks = near ? 0 : shown.awayTicks + 1;
            if (shown.awayTicks > awayLimit) clear(online);
            else melt(online);
        }, 1L, 1L);
        SHOWN.put(id, shown);
        return positions.size();
    }

    /** Whether the player has ghost blocks on screen. */
    public static boolean showing(Player p) {
        return SHOWN.containsKey(p.getUniqueId());
    }

    /** The cells the player's body is in, or nearly: about a block round them, from their feet to above their head. */
    private static List<BlockPos> around(Player p) {
        org.bukkit.util.BoundingBox box = p.getBoundingBox().expand(1.1, 0.6, 1.1);
        List<BlockPos> out = new ArrayList<>();
        for (int x = (int) Math.floor(box.getMinX()); x <= (int) Math.floor(box.getMaxX()); x++) {
            for (int y = (int) Math.floor(box.getMinY()); y <= (int) Math.floor(box.getMaxY()); y++) {
                for (int z = (int) Math.floor(box.getMinZ()); z <= (int) Math.floor(box.getMaxZ()); z++) out.add(new BlockPos(x, y, z));
            }
        }
        return out;
    }

    /** Swaps ghosts the player has walked into back for the real blocks, and puts back the ones they've left. */
    private static void melt(Player p) {
        Shown s = SHOWN.get(p.getUniqueId());
        if (s == null) return;
        World w = Bukkit.getWorld(s.world);
        if (w == null || !w.equals(p.getWorld())) return;
        Set<BlockPos> now = new HashSet<>();
        for (BlockPos b : around(p)) if (s.ghosts.containsKey(b)) now.add(b);
        if (now.equals(s.melted)) return;
        Map<Position, BlockData> send = new HashMap<>();
        for (BlockPos b : s.melted) if (!now.contains(b)) send.put(Position.block(b.x(), b.y(), b.z()), s.ghosts.get(b));
        for (BlockPos b : now) if (!s.melted.contains(b)) send.put(Position.block(b.x(), b.y(), b.z()), w.getBlockData(b.x(), b.y(), b.z()));
        s.melted.clear();
        s.melted.addAll(now);
        if (!send.isEmpty()) p.sendMultiBlockChange(send);
    }

    private static Shown remove(UUID id) {
        Shown s = SHOWN.remove(id);
        if (s != null && s.melter != null) s.melter.cancel();
        return s;
    }

    /** Takes one ghost away (a block left out of the build). */
    public static void forget(Player p, BlockPos b) {
        Shown s = SHOWN.get(p.getUniqueId());
        if (s == null || s.ghosts.remove(b) == null) return;
        s.melted.remove(b);
        p.sendBlockChange(new org.bukkit.Location(p.getWorld(), b.x(), b.y(), b.z()), p.getWorld().getBlockData(b.x(), b.y(), b.z()));
    }

    /** Shows one ghost again (after a click knocked it off the player's screen). */
    public static void reshow(Player p, BlockPos b) {
        Shown s = SHOWN.get(p.getUniqueId());
        if (s == null || !s.ghosts.containsKey(b) || s.melted.contains(b)) return;
        p.sendBlockChange(new org.bukkit.Location(p.getWorld(), b.x(), b.y(), b.z()), s.ghosts.get(b));
    }

    /** Puts the real blocks back on the player's screen. */
    public static void clear(Player p) {
        Shown s = remove(p.getUniqueId());
        if (s == null) return;
        World w = Bukkit.getWorld(s.world);
        if (w == null || !w.equals(p.getWorld())) return;
        Map<Position, BlockData> real = new HashMap<>();
        for (BlockPos b : s.ghosts.keySet()) {
            if (w.isChunkLoaded(b.x() >> 4, b.z() >> 4)) real.put(Position.block(b.x(), b.y(), b.z()), w.getBlockData(b.x(), b.y(), b.z()));
        }
        if (!real.isEmpty()) p.sendMultiBlockChange(real);
    }

    /** A solid cell next to air or the edge: what you'd see from outside. */
    private static boolean onSurface(Blueprint bp, int x, int y, int z) {
        int[][] d = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] o : d) {
            int nx = x + o[0], ny = y + o[1], nz = z + o[2];
            if (nx < 0 || ny < 0 || nz < 0 || nx >= bp.sx || ny >= bp.sy || nz >= bp.sz) return true;
            int idx = bp.get(nx, ny, nz);
            if (idx < 0 || !bp.palette.get(idx).getMaterial().isOccluding()) return true;
        }
        return false;
    }
}
