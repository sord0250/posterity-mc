package site.remotearc.employees;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import site.remotearc.employees.build.BuildOrder;
import site.remotearc.employees.build.BuildPreview;
import site.remotearc.employees.build.Blueprints;
import site.remotearc.employees.build.HomeCatalog;
import site.remotearc.employees.util.BlockPos;
import site.remotearc.employees.util.Msg;
import site.remotearc.employees.util.WorkArea;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Placing an employee's home from the NPC Homes catalog, and moving it in
 * once it's built. A player picks a house, sees it as ghost blocks where
 * they stand, turns or moves it, and confirms: that's the employee's home
 * site. Once the site is built (bed and door in place and
 * {@code homes.finished-percent} of its blocks), the employee moves in.
 */
public final class HomeSites {

    private record Pending(Employee e, BuildOrder order, HomeCatalog.Entry entry) {}

    private final ArcEmployees plugin;
    private final Map<UUID, Pending> pending = new HashMap<>();

    public HomeSites(ArcEmployees plugin) {
        this.plugin = plugin;
    }

    private static String ref(Player p, Employee e) {
        return e.owner.equals(p.getUniqueId()) ? e.name : e.ownerName() + ":" + e.name;
    }

    // ── choosing and placing ──────────────────────────────────────────────

    /** Puts a catalog house where the player stands, as a preview, for them to turn, move and confirm. */
    public void choose(Player p, Employee e, String id) {
        HomeCatalog.Entry entry;
        String blueprint;
        if (id.startsWith("mine:")) {
            // One of the owner's own uploaded houses.
            java.io.File f = plugin.homeCatalog().personalFile(e.owner, id.substring(5));
            entry = f == null ? null : new HomeCatalog.Entry(id, f.getName(), HomeCatalog.personalName(f.getName()),
                    org.bukkit.Material.PAPER, "your own", "");
            blueprint = f == null ? null : HomeCatalog.personalBlueprintName(f);
        } else {
            entry = plugin.homeCatalog().get(id);
            blueprint = entry == null ? null : HomeCatalog.blueprintName(entry);
        }
        if (entry == null) {
            Msg.error(p, "There's no house called <white><id></white> in the NPC Homes catalog.", Msg.p("id", id));
            return;
        }
        Location at = p.getLocation();
        Msg.send(p, "<gray>Reading the plans for <white><name></white>...", Msg.p("name", entry.name()));
        plugin.blueprints().load(blueprint).whenComplete((bp, err) -> {
            if (!plugin.manager().all().contains(e) || !p.isOnline()) return;
            if (err != null) {
                Msg.error(p, "That house can't be used: <reason>", Msg.p("reason", Blueprints.reason(err)));
                return;
            }
            String problem = HomeCatalog.problem(bp);
            if (problem != null) {
                Msg.error(p, "That house can't be a home: " + problem + ".");
                return;
            }
            BuildOrder o = new BuildOrder(blueprint, at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ());
            o.setPlan(bp);
            o.y -= o.groundLayers();   // a house saved on a slab of ground sits on yours
            pending.put(p.getUniqueId(), new Pending(e, o, entry));
            show(p, e, o, entry);
        });
    }

    private void show(Player p, Employee e, BuildOrder o, HomeCatalog.Entry entry) {
        BuildPreview.show(plugin, p, o);
        WorkArea b = o.bounds();
        String c = "/employees house " + ref(p, e) + " ";
        Msg.send(p, "<green>" + site.remotearc.employees.gui.Describe.esc(entry.name()) + "</green> <gray>for " + e.name + ": " + b.width() + "x" + o.plan.sy + "x" + b.length()
                + ", " + o.describeTurn() + ". The ghost blocks show where it goes.");
        p.sendMessage(Msg.parse("<yellow><click:run_command:'" + c + "turn'>[Turn 90°]</click></yellow>  "
                + "<yellow><click:run_command:'" + c + "here'>[Move here]</click></yellow>  "
                + "<green><click:run_command:'" + c + "confirm'>[Build it here]</click></green>  "
                + "<red><click:run_command:'" + c + "cancel'>[Cancel]</click></red>"));
    }

    private Pending pendingFor(Player p, Employee e) {
        Pending pd = pending.get(p.getUniqueId());
        if (pd == null || pd.e() != e) {
            Msg.error(p, "Pick a house for " + e.name + " first: <white>/employees house " + ref(p, e) + "</white>.");
            return null;
        }
        return pd;
    }

    public void turn(Player p, Employee e) {
        Pending pd = pendingFor(p, e);
        if (pd == null) return;
        pd.order().rotation = (pd.order().rotation + 1) % 4;
        pd.order().turnedChanged();
        show(p, e, pd.order(), pd.entry());
    }

    public void here(Player p, Employee e) {
        Pending pd = pendingFor(p, e);
        if (pd == null) return;
        Location at = p.getLocation();
        BuildOrder o = pd.order();
        o.world = at.getWorld().getName();
        o.x = at.getBlockX();
        o.z = at.getBlockZ();
        o.y = at.getBlockY() - o.groundLayers();
        show(p, e, o, pd.entry());
    }

    public void cancel(Player p, Employee e) {
        if (pending.remove(p.getUniqueId()) != null) {
            BuildPreview.clear(p);
            Msg.send(p, "<gray>Cancelled. " + e.name + " is still " + (e.house == null ? "homeless." : "living at their " + e.house.describe() + "."));
        }
    }

    /** Makes the previewed house the employee's home site. */
    public void confirm(Player p, Employee e) {
        Pending pd = pendingFor(p, e);
        if (pd == null) return;
        BuildOrder o = pd.order();
        World w = Bukkit.getWorld(o.world);
        if (w == null) return;
        WorkArea b = o.bounds();
        for (Employee o2 : plugin.manager().all()) {
            if (o2 == e || o2.homeSite == null || !o2.homeSite.world.equals(o.world)) continue;
            WorkArea theirs = o2.siteArea != null ? o2.siteArea : o2.homeSite.plan != null ? o2.homeSite.bounds() : null;
            if (theirs != null && overlaps(b, theirs)) {
                Msg.error(p, "That overlaps " + o2.name + "'s home. Move it with [Move here].");
                return;
            }
        }
        BlockPos bed = null, door = null;
        for (int y = 0; y < o.plan.sy && (bed == null || door == null); y++) {
            for (int z = 0; z < o.plan.sz; z++) {
                for (int x = 0; x < o.plan.sx; x++) {
                    int idx = o.cell(x, y, z);
                    if (idx < 0) continue;
                    BlockData d = o.data(idx);
                    if (bed == null && d instanceof org.bukkit.block.data.type.Bed bd && bd.getPart() == org.bukkit.block.data.type.Bed.Part.HEAD) {
                        bed = o.toWorld(x, y, z);
                    } else if (door == null && Tag.DOORS.isTagged(d.getMaterial()) && d instanceof org.bukkit.block.data.Bisected h
                            && h.getHalf() == org.bukkit.block.data.Bisected.Half.BOTTOM) {
                        door = o.toWorld(x, y, z);
                    }
                }
            }
        }
        if (bed == null || door == null) {
            Msg.error(p, "That house can't be a home: its " + (bed == null ? "bed" : "door") + " is left out of the build.");
            return;
        }
        // The home is built from its own copy of the house, so changing or removing the
        // catalog house (or the player's upload) later doesn't touch homes already placed.
        java.io.File src = plugin.blueprints().resolve(o.blueprint);
        if (src == null) {
            Msg.error(p, "That house's file is gone (removed from the catalog?). Pick it again.");
            return;
        }
        String n = src.getName();
        String copy = Blueprints.SITES + e.id.toString().substring(0, 8) + "-" + HomeCatalog.personalName(n).replaceAll("[^A-Za-z0-9_-]", "_")
                + n.substring(n.lastIndexOf('.')).toLowerCase(java.util.Locale.ROOT);
        try {
            java.io.File target = new java.io.File(plugin.blueprints().dir(), copy);
            java.nio.file.Files.createDirectories(target.getParentFile().toPath());
            java.nio.file.Files.copy(src.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException ex) {
            Msg.error(p, "Couldn't keep a copy of the plans: " + ex.getMessage());
            return;
        }
        if (e.homeSite != null && !e.homeSite.blueprint.equals(copy)) deleteCopy(e, e.homeSite.blueprint);
        o.blueprint = copy;
        pending.remove(p.getUniqueId());
        e.homeSite = o;
        e.siteArea = b;
        e.siteBed = bed;
        e.siteDoor = door;
        e.siteBlocks = Math.max(1, total(o, null));
        e.siteProgress = -1;
        plugin.manager().markDirty();
        plugin.manager().refreshTickets();   // keeps the site loaded while it's built (see EmployeeManager.refreshTickets)
        plugin.manager().events().add(e.owner, e.name, "Has a home site: " + pd.entry().name() + " at " + b.minX() + " " + b.minY() + " " + b.minZ(), false);
        Msg.send(p, "<green>" + e.name + "'s home will be the " + site.remotearc.employees.gui.Describe.esc(pd.entry().name()) + " here.</green> <gray>" + e.name
                + " moves in once the bed and door are in and " + plugin.settings().homeFinishedPercent
                + "% of it is built. Who builds it?");
        String c = "/employees house " + ref(p, e) + " ";
        p.sendMessage(Msg.parse("<white><click:run_command:'" + c + "preview'>[I'll build it]</click></white>  "
                + "<white><click:run_command:'" + c + "builder'>[A builder]</click></white>  "
                + "<white><click:run_command:'" + c + "self on'>[" + e.name + " builds it themself]</click></white>"));
    }

    private static boolean overlaps(WorkArea a, WorkArea b) {
        return a.minX() <= b.maxX() && b.minX() <= a.maxX() && a.minY() <= b.maxY() && b.minY() <= a.maxY()
                && a.minZ() <= b.maxZ() && b.minZ() <= a.maxZ();
    }

    /** Blocks still to place (in w), or all of them (w null). */
    private static int total(BuildOrder o, World w) {
        int n = 0;
        for (int c : o.remaining(w).values()) n += c;
        return n;
    }

    /** Deletes a home site's copy of its plans, unless a builder is still building from it. */
    private void deleteCopy(Employee owner, String blueprint) {
        if (blueprint == null || !blueprint.startsWith(Blueprints.SITES)) return;
        for (Employee o : plugin.manager().all()) {
            if (o != owner && o.homeSite != null && blueprint.equals(o.homeSite.blueprint)) return;
            if (o.build != null && !o.build.finished && blueprint.equals(o.build.blueprint)) return;
        }
        java.io.File f = new java.io.File(plugin.blueprints().dir(), blueprint);
        if (!f.delete() && f.exists()) plugin.getLogger().warning("Could not delete " + f);
    }

    /** Forgets the employee's home site (it was fired, moved out, or given a bed by hand). */
    public void clear(Employee e) {
        if (e.homeSite != null) deleteCopy(e, e.homeSite.blueprint);
        e.homeSite = null;
        e.siteArea = null;
        e.siteBed = null;
        e.siteDoor = null;
        e.siteBlocks = 0;
        e.siteProgress = -1;
        e.selfBuild = false;
        plugin.manager().markDirty();
    }

    // ── moving in ─────────────────────────────────────────────────────────

    /** Checked by the worker every half minute: how far along the home site is, and whether it's ready to move into. */
    public void check(Employee e) {
        BuildOrder o = e.homeSite;
        if (o == null || e.siteBed == null || e.siteDoor == null) return;
        if (e.house != null && e.house.world.equals(o.world) && e.house.bed.equals(e.siteBed)) return;   // already living there
        World w = Bukkit.getWorld(o.world);
        if (w == null || !w.isChunkLoaded(e.siteBed.x() >> 4, e.siteBed.z() >> 4)) return;
        if (o.plan == null) {
            if (o.loadError == null) plugin.blueprints().load(o.blueprint).whenComplete((bp, err) -> {
                if (err != null) o.loadError = Blueprints.reason(err);
                else if (o.plan == null) o.setPlan(bp);
            });
            return;
        }
        int left = total(o, w);
        e.siteProgress = (int) Math.floor(100.0 * Math.max(0, e.siteBlocks - left) / e.siteBlocks);
        if (e.siteProgress < plugin.settings().homeFinishedPercent) return;
        Block head = House.bedHead(e.siteBed.block(w));
        if (head == null || !BlockPos.of(head).equals(e.siteBed)) return;
        if (!Tag.DOORS.isTagged(e.siteDoor.block(w).getType())) return;
        Employee taken = plugin.manager().bedOwner(o.world, e.siteBed);
        if (taken != null && taken != e) return;
        plugin.manager().giveHouse(e, new House(o.world, e.siteBed, e.siteDoor, System.currentTimeMillis()));
        Player owner = Bukkit.getPlayer(e.owner);
        if (owner != null) {
            Msg.send(owner, "<green>" + e.name + " has moved into their new home!</green> <gray>They're happy again and back to full speed.");
        }
    }

    /** One line about the home for menus. */
    public static String describe(ArcEmployees plugin, Employee e) {
        if (e.house != null) return "<gray>Home: <white>" + e.house.describe();
        String slow = "<dark_gray>(unhappy: works at " + Math.round(plugin.settings().homelessWorkSpeed * 100) + "% speed)";
        if (e.homeSite != null) {
            return "<gray>Home: <yellow>being built" + (e.siteProgress >= 0 ? " (" + e.siteProgress + "%)" : "") + "</yellow> " + slow;
        }
        return "<gray>Home: <gold>none</gold> " + slow;
    }
}
