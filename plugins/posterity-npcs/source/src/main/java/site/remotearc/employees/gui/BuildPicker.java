package site.remotearc.employees.gui;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.Employee;
import site.remotearc.employees.build.BuildOrder;
import site.remotearc.employees.build.BuildPreview;
import site.remotearc.employees.util.BlockPos;
import site.remotearc.employees.util.Items;
import site.remotearc.employees.util.Msg;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Picking blocks by clicking them. In a build: look at one of the preview's
 * ghost blocks (from any distance) and click to swap what it's made of. In
 * the world: click any block to build with that instead.
 */
public final class BuildPicker implements Listener {

    private enum Mode { BUILD, WORLD }

    /** {@code from}: how the swap command names the block being swapped (an item, or a block this server doesn't have). */
    private record Picking(UUID employee, Mode mode, String from, long until) {}

    private static final Map<UUID, Picking> PICKING = new ConcurrentHashMap<>();
    private static final long TIME = 90_000;

    private final ArcEmployees plugin;

    public BuildPicker(ArcEmployees plugin) {
        this.plugin = plugin;
    }

    /** Next click on a block of e's build opens the swap menu for it. */
    public static void pickBuildBlock(ArcEmployees plugin, Player p, Employee e) {
        BuildOrder o = e.build;
        if (o == null || o.plan == null) return;
        p.closeInventory();
        if (!p.getWorld().getName().equals(o.world)) {
            Msg.error(p, "The build is in another world. Use the materials list instead: <white>/employees build " + e.name + " swap");
            return;
        }
        if (!BuildPreview.showing(p)) BuildPreview.show(plugin, p, o);
        PICKING.put(p.getUniqueId(), new Picking(e.id, Mode.BUILD, null, System.currentTimeMillis() + TIME));
        Msg.send(p, "<aqua>Look at a block of the build (its ghost blocks, near or far) and click it.</aqua> <gray>Sneak and click to stop.");
    }

    /** Next click on any block makes it what e's build uses instead of {@code from}. */
    static void pickWorldBlock(ArcEmployees plugin, Player p, Employee e, String from, String label) {
        p.closeInventory();
        PICKING.put(p.getUniqueId(), new Picking(e.id, Mode.WORLD, from, System.currentTimeMillis() + TIME));
        Msg.send(p, "<aqua>Click the block to build with instead of " + label + ".</aqua> <gray>Sneak and click to stop.");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent ev) {
        Player p = ev.getPlayer();
        Picking pk = PICKING.get(p.getUniqueId());
        if (pk == null || ev.getAction() == org.bukkit.event.block.Action.PHYSICAL) return;
        ev.setCancelled(true);
        if (System.currentTimeMillis() > pk.until) {
            PICKING.remove(p.getUniqueId());
            ev.setCancelled(false);
            return;
        }
        if (ev.getHand() == org.bukkit.inventory.EquipmentSlot.OFF_HAND) return;
        if (p.isSneaking()) {
            PICKING.remove(p.getUniqueId());
            Msg.send(p, "<gray>Stopped picking.");
            return;
        }
        Employee e = plugin.manager().byId(pk.employee);
        BuildOrder o = e == null ? null : e.build;
        if (o == null || o.plan == null) {
            PICKING.remove(p.getUniqueId());
            return;
        }
        if (pk.mode == Mode.BUILD) {
            BlockPos hit = rayThroughBuild(p, o);
            if (hit == null) {
                p.sendActionBar(Msg.parse("<yellow>That isn't part of the build. Sneak and click to stop."));
                return;
            }
            PICKING.remove(p.getUniqueId());
            int[] l = o.toLocal(hit.x(), hit.y(), hit.z());
            int pi = o.plan.get(l[0], l[1], l[2]);
            Material key = BuildOrder.itemKey(o.plan.palette.get(pi));
            String unknown = o.plan.unknownStates.containsKey(pi) ? site.remotearc.employees.build.Blueprint.blockName(o.plan.unknownStates.get(pi)) : null;
            // A click on a ghost block makes the client ask for the real one: show the ghost again.
            Bukkit.getScheduler().runTaskLater(plugin, () -> BuildPreview.reshow(p, hit), 2L);
            new SwapMenu(plugin, e, key, unknown, hit, null, 0, new MaterialsMenu(plugin, e, 0, new BuildMenu(plugin, e, null))).open(p);
        } else {
            Block b = ev.getClickedBlock();
            if (b == null || b.getType().isAir()) b = p.getTargetBlockExact(96);
            if (b == null || b.getType().isAir()) {
                p.sendActionBar(Msg.parse("<yellow>Look at a block and click it. Sneak and click to stop."));
                return;
            }
            Material item = BuildOrder.itemKey(b.getBlockData());
            if (item == null || !item.isBlock() || !item.isItem()) {
                p.sendActionBar(Msg.parse("<yellow>That can't be built with. Try another block."));
                return;
            }
            PICKING.remove(p.getUniqueId());
            p.performCommand("employees build " + Menu.ref(p, e) + " swap " + pk.from + " " + Items.key(item));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent ev) {
        PICKING.remove(ev.getPlayer().getUniqueId());
    }

    /**
     * The first block of the build the player is looking at, as they see it
     * with the preview on: the ray stops at a build block, or at a solid real
     * block that's in front of it.
     */
    private static BlockPos rayThroughBuild(Player p, BuildOrder o) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize().multiply(0.05);
        Vector at = eye.toVector();
        int lx = Integer.MIN_VALUE, ly = 0, lz = 0;
        for (int i = 0; i < 128 * 20; i++) {
            at.add(dir);
            int x = at.getBlockX(), y = at.getBlockY(), z = at.getBlockZ();
            if (x == lx && y == ly && z == lz) continue;
            lx = x;
            ly = y;
            lz = z;
            int[] l = o.toLocal(x, y, z);
            if (l != null) {
                int idx = o.cell(l[0], l[1], l[2]);
                if (idx >= 0 && !o.data(idx).getMaterial().isAir() && !BuildOrder.unbuildable(o.data(idx))) return new BlockPos(x, y, z);
            }
            if (p.getWorld().getBlockAt(x, y, z).getType().isOccluding()) return null;
        }
        return null;
    }
}
