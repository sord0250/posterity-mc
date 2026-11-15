package site.remotearc.employees;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import site.remotearc.employees.build.Blueprint;
import site.remotearc.employees.build.Blueprints;
import site.remotearc.employees.build.BuildMaterials;
import site.remotearc.employees.build.BuildOrder;
import site.remotearc.employees.build.BuildPreview;
import site.remotearc.employees.gui.AreaPreview;
import site.remotearc.employees.gui.Describe;
import site.remotearc.employees.job.JobType;
import site.remotearc.employees.job.Smelting;
import site.remotearc.employees.storage.StorageTarget;
import site.remotearc.employees.storage.Supply;
import site.remotearc.employees.util.BlockPos;
import site.remotearc.employees.util.Items;
import site.remotearc.employees.util.MaterialList;
import site.remotearc.employees.util.Msg;
import site.remotearc.employees.util.Pathfinder;
import site.remotearc.employees.util.WorkArea;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** The job-specific subcommands: filter, supply, herd, build and blueprints. */
final class JobCommands {

    static final List<String> BUILD_ACTIONS = List.of("rename", "around", "follow", "plan", "start", "preview", "rotate", "flip", "move", "shift", "materials",
            "clear", "cancel", "info", "swap", "swaps", "pick", "ground", "buy", "skip", "dismantle", "menu");

    private final ArcEmployees plugin;

    JobCommands(ArcEmployees plugin) {
        this.plugin = plugin;
    }

    private EmployeeManager mgr() {
        return plugin.manager();
    }

    // ── filter ────────────────────────────────────────────────────────────

    void filter(CommandSender s, Employee e, String label, String[] args) {
        if (!e.job.usesFilter()) {
            Msg.error(s, e.name + " is a " + e.job.display().toLowerCase(Locale.ROOT) + "; only miners, quarry miners and smelters have a filter.");
            return;
        }
        String mode = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "list";
        String usage = "/" + label + " filter " + e.name + " add|remove \\<block...> | clear | reset";
        switch (mode) {
            case "list" -> {
                Msg.send(s, "<gold>" + e.name + "</gold> <gray>" + Describe.filterLabel(e).toLowerCase(Locale.ROOT) + ": <white>"
                        + Describe.esc(Describe.filterText(e, 40)));
                s.sendMessage(Msg.parse("<dark_gray>" + usage + "  (names, #tags like #logs, or 'ores')"));
            }
            case "add", "remove" -> {
                if (args.length < 4) {
                    Msg.error(s, "Usage: " + usage);
                    return;
                }
                Set<Material> changed = EnumSet.noneOf(Material.class);
                for (int i = 3; i < args.length; i++) {
                    Set<Material> one = MaterialList.parseOne(args[i]);
                    if (one == null) {
                        Msg.error(s, "<white><what></white> isn't a block, item, #tag or 'ores'.", Msg.p("what", args[i]));
                        return;
                    }
                    for (Material m : one) {
                        changed.add(m);
                        // "iron_ore" also means its deepslate twin.
                        Material twin = Material.matchMaterial("DEEPSLATE_" + m.name());
                        if (twin != null && m.name().endsWith("_ORE")) changed.add(twin);
                    }
                }
                if (mode.equals("add")) {
                    String bad = check(e.job, changed);
                    if (bad != null) {
                        Msg.error(s, "<msg>", Msg.p("msg", bad));
                        return;
                    }
                    e.filter.addAll(changed);
                } else {
                    e.filter.removeAll(changed);
                }
                filterChanged(s, e);
            }
            case "clear" -> {
                e.filter.clear();
                filterChanged(s, e);
            }
            case "reset" -> {
                e.filter.clear();
                if (e.job == JobType.MINER) e.filter.addAll(plugin.settings().minerTargets);
                if (e.job == JobType.SMELTER) e.filter.addAll(plugin.settings().smelterInputs);
                filterChanged(s, e);
            }
            default -> Msg.error(s, "Usage: " + usage);
        }
    }

    /** Why these can't go in this job's filter, or null if they can. */
    private static String check(JobType job, Set<Material> list) {
        for (Material m : list) {
            if (job == JobType.MINER && !m.isBlock()) return Items.name(m) + " isn't a block (miners look for blocks, e.g. iron_ore)";
            if (job == JobType.SMELTER && !Smelting.smeltable(m)) return Items.name(m) + " can't be smelted or cooked";
        }
        return null;
    }

    private void filterChanged(CommandSender s, Employee e) {
        mgr().markDirty();
        e.worker.interrupt();
        Msg.send(s, "<green>" + e.name + " " + Describe.filterLabel(e).toLowerCase(Locale.ROOT) + ": <white>"
                + Describe.esc(Describe.filterText(e, 12)));
    }

    // ── supply ────────────────────────────────────────────────────────────

    void supply(CommandSender s, Employee e, String label, String[] args) {
        if (!e.job.usesSupply()) {
            Msg.error(s, "Only builders, smelters and ranchers take materials from storage.");
            return;
        }
        String mode = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "list";
        String usage = "/" + label + " supply " + e.name + " add \\<chest number> | add chest | remove \\<n> | remove chest | team on|off";
        switch (mode) {
            case "list" -> {
                Msg.send(s, "<gold>" + e.name + "</gold> <gray>takes materials from: <white>" + Describe.esc(Supply.describe(plugin, e)));
                s.sendMessage(Msg.parse("<gray>Team storage (where your other employees deliver) is <white>"
                        + (e.teamSupply ? "on" : "off") + "</white>. Only plain items are taken (nothing renamed)."));
                s.sendMessage(Msg.parse("<dark_gray>" + usage));
            }
            case "add", "remove" -> {
                boolean add = mode.equals("add");
                String what = args.length > 3 ? args[3].toLowerCase(Locale.ROOT) : "";
                StorageTarget t;
                if (what.equals("chest")) {
                    if (!(s instanceof Player p)) return;
                    Block b = p.getTargetBlockExact(6);
                    if (b == null || !(b.getState(false) instanceof Container)) {
                        Msg.error(p, "Look at a chest, barrel or other container within 6 blocks.");
                        return;
                    }
                    t = StorageTarget.chest(b);
                } else {
                    int n;
                    try {
                        n = Integer.parseInt(what.startsWith("vc") ? args.length > 4 ? args[4] : "" : what);
                    } catch (NumberFormatException ex) {
                        Msg.error(s, "Usage: " + usage);
                        return;
                    }
                    if (add && !s.hasPermission("employees.admin") && !s.hasPermission("virtualchests.open." + n)) {
                        Msg.error(s, "You don't have access to /chest " + n + ".");
                        return;
                    }
                    t = StorageTarget.virtual(n);
                }
                if (add) {
                    if (!e.supply.contains(t)) e.supply.add(t);
                } else if (!e.supply.remove(t)) {
                    Msg.error(s, e.name + " wasn't taking from the " + t.describe() + " (team storage is switched with 'team on|off').");
                    return;
                }
                mgr().markDirty();
                Msg.send(s, "<green>" + e.name + " now takes materials from: <white>" + Describe.esc(Supply.describe(plugin, e)));
            }
            case "team" -> {
                boolean on = args.length < 4 || !args[3].equalsIgnoreCase("off");
                e.teamSupply = on;
                mgr().markDirty();
                Msg.send(s, "<green>Team storage " + (on ? "on" : "off") + ". " + e.name + " takes materials from: <white>"
                        + Describe.esc(Supply.describe(plugin, e)));
            }
            default -> Msg.error(s, "Usage: " + usage);
        }
    }

    // ── builder repair mode ───────────────────────────────────────────────

    void repair(CommandSender s, Employee e, String label, String[] args) {
        if (e.job != JobType.BUILDER) {
            Msg.error(s, "Only builders repair builds.");
            return;
        }
        String mode = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "status";
        String usage = "/" + label + " repair " + e.name + " on | off | snapshot";
        BuildOrder o = e.build;
        switch (mode) {
            case "status" -> {
                if (o == null) Msg.send(s, "<gray>" + e.name + " has no build to look after. <white>" + usage);
                else Msg.send(s, "<gray>Repair mode for " + Describe.esc(o.blueprint) + " is <white>" + (o.repair ? "on" : "off")
                        + "</white>. <dark_gray>" + usage);
            }
            case "on", "off" -> {
                if (o == null) {
                    Msg.error(s, e.name + " has no build. Give them one, or save what's there with <white>/" + label + " repair " + e.name
                            + " snapshot</white> (select it with //wand first).");
                    return;
                }
                o.repair = mode.equals("on");
                mgr().markDirty();
                e.worker.interrupt();
                Msg.send(s, o.repair
                        ? "<green>" + e.name + " will look after " + Describe.esc(o.blueprint) + " once it's built, putting back blocks that go missing"
                                + " (every " + plugin.settings().repairCheckSeconds + "s). Only gaps are filled: nothing is cleared or swapped."
                        : "<gray>" + e.name + " no longer repairs " + Describe.esc(o.blueprint) + ".");
            }
            case "snapshot", "here", "save" -> {
                if (!(s instanceof Player p)) return;
                if (!Bukkit.getPluginManager().isPluginEnabled("WorldEdit")) {
                    Msg.error(p, "Snapshots need WorldEdit.");
                    return;
                }
                if (o != null && o.started && !o.finished && !o.repair) {
                    Msg.error(p, e.name + " is still building " + Describe.esc(o.blueprint) + ". Cancel that first with <white>/" + label
                            + " build " + e.name + " cancel</white>.");
                    return;
                }
                WorkArea box;
                try {
                    box = WorldEditSelection.exact(p);
                } catch (IllegalStateException ex) {
                    Msg.error(p, ex.getMessage());
                    return;
                }
                long volume = (long) box.width() * box.length() * (box.maxY() - box.minY() + 1);
                if (volume > plugin.settings().builderMaxVolume) {
                    Msg.error(p, "That's " + volume + " blocks; builders handle up to " + plugin.settings().builderMaxVolume
                            + " (jobs.builder.max-volume).");
                    return;
                }
                World w = Bukkit.getWorld(box.world());
                String file = "repair-" + e.name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "") + ".schem";
                try {
                    site.remotearc.employees.build.WorldEditFormats.write(w, box, new java.io.File(plugin.blueprints().dir(), file));
                } catch (java.io.IOException | RuntimeException ex) {
                    Msg.error(p, "Couldn't save the snapshot: " + ex.getMessage());
                    return;
                }
                int sx = box.width(), sz = box.length();
                BuildOrder n = new BuildOrder(file, box.world(), box.minX() + sx / 2, box.minY(), box.minZ() + sz / 2);
                n.clearAir = false;
                n.started = true;
                n.finished = true;
                n.repair = true;
                n.layer = box.maxY() - box.minY() + 1;
                e.build = n;
                mgr().setArea(e, box.expand(2, 2));
                e.worker.interrupt();
                mgr().markDirty();
                Msg.send(p, "<green>Saved " + box.describe() + " as it is now (" + volume + " blocks). " + e.name
                        + " will put back anything that goes missing from it, from your storage, checking every "
                        + plugin.settings().repairCheckSeconds + "s. <gray>Changed something on purpose? Run this again to update the snapshot.");
            }
            default -> Msg.error(s, "Usage: " + usage);
        }
    }

    // ── courier routes ────────────────────────────────────────────────────

    /** A route end from a word: 12 (a /chest number), look (the container you look at), or x,y,z. */
    private static StorageTarget parseEnd(CommandSender s, String word) {
        String w = word.toLowerCase(Locale.ROOT).replace("/chest", "").replace("vc", "");
        if (w.equals("look") || w.equals("chest") || w.equals("here")) {
            if (!(s instanceof Player p)) return null;
            Block b = p.getTargetBlockExact(6);
            return b != null && b.getState(false) instanceof Container ? StorageTarget.chest(b) : null;
        }
        try {
            return StorageTarget.virtual(Integer.parseInt(w));
        } catch (NumberFormatException ignored) {
            // not a number
        }
        String world = s instanceof Player p ? p.getWorld().getName() : "world";
        int at = word.lastIndexOf(':');
        if (at > 0) {
            world = word.substring(0, at);
            word = word.substring(at + 1);
        }
        try {
            BlockPos pos = BlockPos.parse(word);
            org.bukkit.World wd = org.bukkit.Bukkit.getWorld(world);
            if (wd == null || !(pos.block(wd).getState(false) instanceof Container)) return null;
            return new StorageTarget(StorageTarget.Kind.CHEST, 0, world, pos);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    void route(CommandSender s, Employee e, String label, String[] args) {
        if (e.job != JobType.COURIER) {
            Msg.error(s, "Only couriers carry things along routes.");
            return;
        }
        String mode = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "list";
        String usage = "/" + label + " route " + e.name + " add \\<from> \\<to|merchant> [items...] [keep \\<n>] [stock \\<n>] | remove \\<n> | clear";
        switch (mode) {
            case "list" -> {
                if (e.routes.isEmpty()) Msg.send(s, "<gray>" + e.name + " has no routes yet.");
                for (int i = 0; i < e.routes.size(); i++) {
                    Employee.Route r = e.routes.get(i);
                    Msg.send(s, "<gold>" + (i + 1) + ".</gold> <white>" + Describe.esc(r.describe()) + "</white> <gray>" + Describe.esc(r.rules()));
                }
                s.sendMessage(Msg.parse("<dark_gray>" + usage + ". From and to: a /chest number, <white>look</white> (the chest you're looking at), <white>click</white> (click it next)"
                        + " or x,y,z; to can also be <white>merchant</white> (carry it there and sell it)."));
            }
            case "add" -> {
                if (args.length < 5) {
                    Msg.error(s, "Usage: " + usage);
                    return;
                }
                // "click": pick the chest by clicking it, then run this again with its position.
                for (int k = 3; k <= 4; k++) {
                    if (!args[k].equalsIgnoreCase("click") || !(s instanceof Player p)) continue;
                    int slot = k;
                    site.remotearc.employees.gui.ChestPick.ask(p, k == 3 ? "the container " + e.name + " should take from"
                            : "the container " + e.name + " should bring things to", b -> {
                        String[] again = args.clone();
                        again[slot] = b.getWorld().getName() + ":" + b.getX() + "," + b.getY() + "," + b.getZ();
                        p.performCommand(label + " " + String.join(" ", again));
                    });
                    return;
                }
                boolean sell = List.of("merchant", "sell", "market").contains(args[4].toLowerCase(Locale.ROOT));
                StorageTarget from = parseEnd(s, args[3]), to = sell ? StorageTarget.NONE : parseEnd(s, args[4]);
                if (sell && (!plugin.settings().marketEnabled || !plugin.market().hasMerchants())) {
                    Msg.error(s, "There's no merchant to sell to yet (an admin places one with /" + label + " market place).");
                    return;
                }
                if (from == null || to == null) {
                    Msg.error(s, "\"" + (from == null ? args[3] : args[4]) + "\" isn't a /chest number or a chest, barrel or other container"
                            + " (use look while looking at one, or x,y,z).");
                    return;
                }
                if (from.equals(to)) {
                    Msg.error(s, "A route has to go from one place to another.");
                    return;
                }
                for (StorageTarget t : sell ? List.of(from) : List.of(from, to)) {
                    String why = t.problemFor(s);
                    if (why != null) {
                        Msg.error(s, why);
                        return;
                    }
                }
                Set<Material> items = java.util.EnumSet.noneOf(Material.class);
                int keep = 0, stock = 0;
                for (int i = 5; i < args.length; i++) {
                    String a = args[i].toLowerCase(Locale.ROOT);
                    if ((a.equals("keep") || a.equals("stock")) && i + 1 < args.length) {
                        int n;
                        try {
                            n = Math.max(0, Integer.parseInt(args[++i]));
                        } catch (NumberFormatException ex) {
                            Msg.error(s, a + " needs a number.");
                            return;
                        }
                        if (a.equals("keep")) keep = n;
                        else stock = n;
                        continue;
                    }
                    if (a.equals("all") || a.equals("everything")) continue;
                    for (String part : a.split(",")) {
                        if (part.isEmpty()) continue;
                        Set<Material> got = site.remotearc.employees.util.MaterialList.parseOne(part);
                        if (got != null) got.removeIf(m -> !m.isItem());
                        if (got == null || got.isEmpty()) {
                            Msg.error(s, "Unknown item: " + part);
                            return;
                        }
                        items.addAll(got);
                    }
                }
                e.routes.add(new Employee.Route(from, to, items, keep, stock));
                routesChanged(e);
                Msg.send(s, "<green>Route " + e.routes.size() + ": <white>" + Describe.esc(e.routes.getLast().describe())
                        + "</white> <gray>(" + Describe.esc(e.routes.getLast().rules()) + ")");
            }
            case "remove" -> {
                int n;
                try {
                    n = Integer.parseInt(args.length > 3 ? args[3] : "");
                } catch (NumberFormatException ex) {
                    Msg.error(s, "Usage: " + usage);
                    return;
                }
                if (n < 1 || n > e.routes.size()) {
                    Msg.error(s, e.name + " has " + e.routes.size() + " route(s).");
                    return;
                }
                Employee.Route r = e.routes.remove(n - 1);
                routesChanged(e);
                Msg.send(s, "<gray>Removed the route " + Describe.esc(r.describe()) + ".");
            }
            case "clear" -> {
                e.routes.clear();
                routesChanged(e);
                Msg.send(s, "<gray>" + e.name + " has no routes now.");
            }
            default -> Msg.error(s, "Usage: " + usage);
        }
    }

    void routesChanged(Employee e) {
        mgr().markDirty();
        mgr().refreshTickets();
        e.worker.interrupt();
    }

    // ── herd ──────────────────────────────────────────────────────────────

    void herd(CommandSender s, Employee e, String label, String[] args) {
        if (e.job != JobType.RANCHER) {
            Msg.error(s, "Only ranchers keep a herd.");
            return;
        }
        int n;
        try {
            n = Integer.parseInt(args.length > 2 ? args[2] : "");
        } catch (NumberFormatException ex) {
            Msg.send(s, "<gray>" + e.name + " keeps <white>" + (e.herdSize > 0 ? e.herdSize : plugin.settings().herdSize)
                    + "</white> of each kind of animal. Change it: <white>/" + label + " herd " + e.name + " \\<2-200>");
            return;
        }
        if (n < 2 || n > 200) {
            Msg.error(s, "Herd size must be 2 to 200.");
            return;
        }
        e.herdSize = n;
        mgr().markDirty();
        e.worker.interrupt();
        Msg.send(s, "<green>" + e.name + " now keeps " + n + " of each kind: breeding up to that, culling above it.");
    }

    // ── control boards ────────────────────────────────────────────────────

    /**
     * /employees board [status|sales] [size]   put one up where you look
     * /employees board size <1-20>             how big the nearest board is (2 = a normal wall board)
     * /employees board width <letters>         how much text fits on a line (e.g. 120)
     * /employees board move | remove
     */
    void board(CommandSender s, String label, String[] args) {
        if (!(s instanceof Player p)) {
            Msg.error(s, "Only players can put up a board.");
            return;
        }
        var boards = plugin.boards();
        String mode = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "status";
        String usage = "<gray>Boards: <white>/" + label + " board [status|sales|business|employee \\<name>] [size]</white> puts one up where you look;"
                + " <white>board size \\<1-20></white> and <white>board width \\<letters a line></white> change the nearest one;"
                + " <white>board move</white> brings your nearest one here; <white>board remove</white>.";
        switch (mode) {
            case "remove" -> {
                boolean ok = boards.removeNear(p.getUniqueId(), p.getLocation(), 8, p.hasPermission("employees.admin"));
                if (ok) Msg.send(p, "<gray>Board removed.");
                else Msg.error(p, "There's no board of yours within 8 blocks.");
            }
            case "move" -> {
                var old = boards.nearest(p.getUniqueId(), p.getLocation(), 100_000);
                if (old == null) {
                    Msg.error(p, "You have no board in this world.");
                    return;
                }
                Placement pl = placement(p);
                boards.replace(old, old.movedTo(pl.at, pl.yaw, pl.wall));
                Msg.send(p, "<green>Moved your nearest " + old.kind() + " board " + (pl.wall ? "onto this wall." : "here."));
            }
            case "size", "resize", "width" -> {
                var old = boards.nearest(p.getUniqueId(), p.getLocation(), 32);
                if (old == null) {
                    Msg.error(p, "There's no board of yours within 32 blocks.");
                    return;
                }
                double size = old.size();
                int letters = old.letters();
                try {
                    if (mode.equals("width")) letters = Math.max(30, Math.min(600, Integer.parseInt(args[2])));
                    else size = Math.max(0.5, Math.min(20, parseSize(args[2])));
                } catch (RuntimeException ex) {
                    Msg.error(p, mode.equals("width") ? "Usage: /" + label + " board width \\<letters a line, e.g. 120>"
                            : "Usage: /" + label + " board size \\<1-20>  (now " + old.size() + ")");
                    return;
                }
                boards.replace(old, old.resized(size, letters));
                Msg.send(p, "<green>Board: size <white>" + size + "</white>, <white>" + letters + "</white> letters a line. <gray>Bigger size = bigger"
                        + " text; more letters = wider lines.");
            }
            case "help" -> Msg.send(p, usage);
            default -> {
                String kind = switch (mode) {
                    case "sales", "business", "employee" -> mode;
                    default -> "status";
                };
                UUID employee = null;
                int sizeAt = 2;
                if (kind.equals("employee")) {
                    Employee e = args.length > 2 ? mgr().find(p, args[2]) : null;
                    if (e == null || !mgr().canManage(p, e)) {
                        Msg.error(p, "Usage: /" + label + " board employee \\<name> [size]");
                        return;
                    }
                    employee = e.id;
                    sizeAt = 3;
                }
                String sizeArg = mode.equals(kind) ? (args.length > sizeAt ? args[sizeAt] : "2") : mode;
                double size;
                try {
                    size = Math.max(0.5, Math.min(20, parseSize(sizeArg)));
                } catch (RuntimeException ex) {
                    Msg.send(p, usage);
                    return;
                }
                if (boards.count(p.getUniqueId()) >= 20 && !p.hasPermission("employees.admin")) {
                    Msg.error(p, "You have 20 boards already. Remove one with <white>/" + label + " board remove</white> near it.");
                    return;
                }
                Placement pl = placement(p);
                boards.create(p.getUniqueId(), pl.at, pl.yaw, (float) (size / 4), 105 * 6, pl.wall, kind, employee);
                Msg.send(p, "<green>" + Character.toUpperCase(kind.charAt(0)) + kind.substring(1) + " board up"
                        + (pl.wall ? " on the wall" : " (it turns to face you)") + ", size " + size + ". " + usage);
            }
        }
    }

    /** A size number, or one of the old names. */
    private static double parseSize(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "small" -> 1.5;
            case "medium" -> 2;
            case "large" -> 2.5;
            case "huge" -> 3.5;
            case "giant" -> 6;
            default -> Double.parseDouble(s);
        };
    }

    private record Placement(Location at, float yaw, boolean wall) {}

    /** Flat on the wall the player looks at (facing out of it), or free-standing in front of them. */
    private static Placement placement(Player p) {
        float yaw = p.getLocation().getYaw() + 180;
        Block target = p.getTargetBlockExact(8);
        org.bukkit.block.BlockFace face = p.getTargetBlockFace(8);
        if (target != null && face != null && face != org.bukkit.block.BlockFace.UP && face != org.bukkit.block.BlockFace.DOWN) {
            // Its top edge at the top of the block you look at; it hangs down from there.
            Location at = target.getLocation().add(0.5 + face.getModX() * 0.52, 0.98, 0.5 + face.getModZ() * 0.52);
            yaw = switch (face) {
                case NORTH -> 180;
                case SOUTH -> 0;
                case WEST -> 90;
                case EAST -> -90;
                default -> yaw;
            };
            at.setYaw(yaw);
            return new Placement(at, yaw, true);
        }
        Location at = p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(2.5)).add(0, 2.6, 0);
        at.setYaw(yaw);
        return new Placement(at, yaw, false);
    }

    // ── no-work zones ─────────────────────────────────────────────────────

    /** Your WorldEdit selection as a box, or null (after telling the player why). */
    private WorkArea selection(Player p) {
        if (!Bukkit.getPluginManager().isPluginEnabled("WorldEdit")) {
            Msg.error(p, "WorldEdit isn't installed.");
            return null;
        }
        try {
            return WorldEditSelection.of(p);
        } catch (IllegalStateException ex) {
            Msg.error(p, "<msg>", Msg.p("msg", ex.getMessage()));
            return null;
        }
    }

    private static String box(WorkArea z) {
        return z.describe() + " (y " + z.minY() + " to " + z.maxY() + ")";
    }

    void zone(CommandSender s, Employee e, String label, String[] args) {
        String mode = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "list";
        var zp = plugin.zonePresets();
        String what = e.job == JobType.GUARD || e.job == JobType.BODYGUARD ? "leaves mobs alone" : "won't work";
        switch (mode) {
            case "add" -> {
                if (args.length > 3) {
                    String name = args[3];
                    if (zp.get(e.owner, name) == null) {
                        Msg.error(s, "There's no preset called <white><n></white>. Save one with <white>/" + label + " presets save <n></white>.",
                                Msg.p("n", name));
                        return;
                    }
                    if (e.zonePresets.stream().anyMatch(n -> n.equalsIgnoreCase(name))) {
                        Msg.send(s, "<gray>" + e.name + " already has the <white>" + name + "</white> zone.");
                        return;
                    }
                    e.zonePresets.add(name.toLowerCase(Locale.ROOT));
                    zp.changed();
                    mgr().markDirty();
                    e.worker.interrupt();
                    Msg.send(s, "<green>" + e.name + " " + what + " in <white>" + name + "</white> now: " + box(zp.get(e.owner, name)) + ".");
                    if (s instanceof Player p) AreaPreview.show(plugin, p, zp.get(e.owner, name), 200);
                    return;
                }
                if (!(s instanceof Player p)) return;
                WorkArea z = selection(p);
                if (z == null) return;
                e.zones.add(z);
                zp.changed();
                mgr().markDirty();
                e.worker.interrupt();
                Msg.send(p, "<green>" + e.name + " " + what + " in " + box(z) + " now.");
                AreaPreview.show(plugin, p, z, 200);
            }
            case "remove", "delete" -> {
                if (args.length < 4) {
                    Msg.error(s, "Usage: /" + label + " zone " + e.name + " remove \\<preset name or number from the list>");
                    return;
                }
                String which = args[3];
                if (e.zonePresets.removeIf(n -> n.equalsIgnoreCase(which))) {
                    Msg.send(s, "<gray>" + e.name + " no longer has the <white>" + which + "</white> zone.");
                } else {
                    int i;
                    try {
                        i = Integer.parseInt(which) - 1;
                    } catch (NumberFormatException ex) {
                        i = -1;
                    }
                    if (i < 0 || i >= e.zones.size()) {
                        Msg.error(s, e.name + " has no zone <white><z></white>.", Msg.p("z", which));
                        return;
                    }
                    WorkArea z = e.zones.remove(i);
                    Msg.send(s, "<gray>Removed " + e.name + "'s zone " + box(z) + ".");
                }
                zp.changed();
                mgr().markDirty();
            }
            case "clear" -> {
                e.zones.clear();
                e.zonePresets.clear();
                zp.changed();
                mgr().markDirty();
                Msg.send(s, "<gray>" + e.name + " has no no-work zones now.");
            }
            case "show" -> {
                if (s instanceof Player p) for (WorkArea z : zp.boxes(e)) AreaPreview.show(plugin, p, z, 300);
            }
            default -> {
                List<String> lines = new ArrayList<>();
                for (int i = 0; i < e.zones.size(); i++) lines.add("<white>" + (i + 1) + "</white> " + box(e.zones.get(i)));
                for (String n : e.zonePresets) {
                    WorkArea z = zp.get(e.owner, n);
                    lines.add("<white>" + n + "</white> " + (z == null ? "<red>(preset deleted)" : box(z)));
                }
                Msg.send(s, "<gold>" + e.name + "</gold> <gray>" + what + " in " + lines.size() + " zone(s)" + (lines.isEmpty() ? "." : ":"));
                for (String l : lines) s.sendMessage(Msg.parse("<gray> - " + l));
                s.sendMessage(Msg.parse("<dark_gray>/" + label + " zone " + e.name + " add [preset] (no preset: your WorldEdit selection)"
                        + " | remove \\<preset or number> | show | clear. Presets: /" + label + " presets."
                        + (e.job == JobType.GUARD || e.job == JobType.BODYGUARD ? " Guards also ignore name-tagged, leashed and boat/minecart mobs." : "")));
            }
        }
    }

    /** /employees presets [list | save <name> | delete <name> | show <name>]: named no-work zones to hand to employees. */
    void presets(CommandSender s, String label, String[] args) {
        if (!(s instanceof Player p)) {
            Msg.error(s, "Only players have zone presets.");
            return;
        }
        var zp = plugin.zonePresets();
        UUID me = p.getUniqueId();
        String mode = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
        String name = args.length > 2 ? args[2] : null;
        switch (mode) {
            case "save", "set", "create" -> {
                if (name == null || !ZonePresets.validName(name)) {
                    Msg.error(p, "Usage: /" + label + " presets save \\<name> (letters, numbers, - and _). It saves your WorldEdit selection.");
                    return;
                }
                WorkArea z = selection(p);
                if (z == null) return;
                boolean existed = zp.get(me, name) != null;
                zp.put(me, name, z);
                for (Employee e : mgr().ownedBy(me)) if (e.zonePresets.stream().anyMatch(n -> n.equalsIgnoreCase(name))) e.worker.interrupt();
                Msg.send(p, "<green>" + (existed ? "Moved" : "Saved") + " the <white>" + name + "</white> zone: " + box(z) + "."
                        + " <gray>Give it to an employee with <white>/" + label + " zone \\<employee> add " + name + "</white> or in their menu.");
                AreaPreview.show(plugin, p, z, 200);
            }
            case "delete", "remove" -> {
                if (name == null || !zp.remove(me, name)) {
                    Msg.error(p, "You have no preset called <white><n></white>.", Msg.p("n", name == null ? "" : name));
                    return;
                }
                Msg.send(p, "<gray>Deleted the <white>" + name + "</white> preset (and took it off your employees).");
            }
            case "show" -> {
                WorkArea z = name == null ? null : zp.get(me, name);
                if (z == null) {
                    Msg.error(p, "You have no preset called <white><n></white>.", Msg.p("n", name == null ? "" : name));
                    return;
                }
                AreaPreview.show(plugin, p, z, 400);
                Msg.send(p, "<gray>" + name + ": " + box(z));
            }
            default -> {
                var all = zp.of(me);
                Msg.send(p, "<gold>Your zone presets</gold> <gray>(" + all.size() + ")" + (all.isEmpty() ? "." : ":"));
                all.forEach((n, z) -> p.sendMessage(Msg.parse("<gray> - <white>" + n + "</white> " + box(z) + ", used by " + zp.users(me, n))));
                p.sendMessage(Msg.parse("<dark_gray>/" + label + " presets save \\<name> (your WorldEdit selection) | delete \\<name> | show \\<name>."
                        + " Then /" + label + " zone \\<employee> add \\<name>."));
            }
        }
    }

    // ── trader crafting ───────────────────────────────────────────────────

    /** /employees craft <trader> [on | off | never [add | remove <item>]]. */
    void craft(CommandSender s, Employee e, String label, String[] args) {
        if (e.job != JobType.TRADER && e.job != JobType.BUILDER) {
            Msg.error(s, "Only villager traders and builders craft.");
            return;
        }
        String mode = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "";
        switch (mode) {
            case "on", "off" -> {
                e.craft = mode.equals("on");
                mgr().markDirty();
                e.worker.interrupt();
                Msg.send(s, e.job == JobType.BUILDER
                        ? (e.craft ? "<green>" + e.name + " crafts blocks the build is missing from spare materials in storage (logs into stairs...)."
                            : "<gray>" + e.name + " only uses blocks as they are in storage.")
                        : e.craft ? "<green>" + e.name + " crafts what its trades cost from what's in your storage (logs into sticks...)."
                        : "<gray>" + e.name + " only uses trade goods as they are now.");
            }
            case "never" -> {
                String op = args.length > 3 ? args[3].toLowerCase(Locale.ROOT) : "list";
                Material m = args.length > 4 ? Material.matchMaterial(args[4]) : null;
                if ((op.equals("add") || op.equals("remove")) && (m == null || !m.isItem())) {
                    Msg.error(s, "Usage: /" + label + " craft " + e.name + " never add|remove \\<item>");
                    return;
                }
                if (op.equals("add")) {
                    e.craftNever.add(m);
                    Msg.send(s, "<green>" + e.name + " never crafts with " + Items.name(m) + ".");
                } else if (op.equals("remove")) {
                    e.craftNever.remove(m);
                    Msg.send(s, "<green>" + e.name + " may craft with " + Items.name(m) + " again.");
                } else {
                    Msg.send(s, "<gold>" + e.name + "</gold> <gray>never crafts with: <white>"
                            + (e.craftNever.isEmpty() ? "nothing" : String.join(", ", e.craftNever.stream().map(Items::name).toList())));
                    return;
                }
                mgr().markDirty();
                e.worker.interrupt();
            }
            default -> {
                Msg.send(s, "<gold>" + e.name + "</gold> <gray>crafting is " + (e.craft ? "<green>on" : "<red>off") + "<gray>; never crafts with "
                        + e.craftNever.size() + " kind(s) of item.");
                s.sendMessage(Msg.parse("<dark_gray>/" + label + " craft " + e.name + " on | off | never [add | remove \\<item>]"));
            }
        }
    }

    // ── market ────────────────────────────────────────────────────────────

    void market(CommandSender s, String label, String[] args) {
        var market = plugin.market();
        String mode = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        Player p = s instanceof Player pl ? pl : null;
        switch (mode) {
            case "limit", "overlimit", "over-limit" -> {
                String key = mode.equals("limit") ? "market.max-income-per-day" : "market.over-limit-rate";
                if (args.length < 3 || !s.hasPermission("employees.admin")) {
                    Msg.send(s, mode.equals("limit")
                            ? "<gray>Daily selling limit: <white>" + Msg.money(plugin.settings().marketDailyCap) + "</white> at full price"
                                    + (plugin.settings().marketWagesRaiseCap ? ", plus a day of wages" : "")
                                    + (s.hasPermission("employees.admin") ? ". Change it: <white>/" + label + " market limit \\<amount></white> (0 = none)" : ".")
                            : "<gray>Past the limit merchants pay <white>" + Math.round(plugin.settings().marketOverRate * 100) + "%</white>"
                                    + (s.hasPermission("employees.admin") ? ". Change it: <white>/" + label + " market overlimit \\<percent></white> (0 = stop buying)" : "."));
                    return;
                }
                String v = args[2].replace("%", "").replace("$", "");
                if (!mode.equals("limit")) {
                    try {
                        v = String.valueOf(Math.max(0, Math.min(100, Double.parseDouble(v))) / 100.0);
                    } catch (NumberFormatException ex) {
                        Msg.error(s, "Give a percentage, e.g. 25.");
                        return;
                    }
                }
                plugin.getServer().dispatchCommand(s, label + " config " + key + " " + v);
            }
            case "place" -> {
                if (p == null || !s.hasPermission("employees.admin")) {
                    Msg.error(s, "Only admins can place merchants.");
                    return;
                }
                String name = args.length > 2 ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)) : "Marlow the Merchant";
                if (name.length() > 32) name = name.substring(0, 32);
                market.place(p.getLocation(), name);
                Msg.send(p, "<gray>(Shift-right-click them to rename, move or reskin them.)");
                Msg.send(p, "<green>" + name + " opens shop here. Players right-click them to buy and sell; employees set to sell"
                        + " (<white>/" + label + " sell \\<name> all</white>) sell to them from anywhere.");
            }
            case "remove" -> {
                if (p == null || !s.hasPermission("employees.admin")) {
                    Msg.error(s, "Only admins can remove merchants.");
                    return;
                }
                String gone = market.removeNear(p.getLocation());
                if (gone == null) Msg.error(p, "There's no merchant within 8 blocks.");
                else Msg.send(p, "<gray>" + gone + " closed up shop.");
            }
            case "edit", "move", "rename", "skin" -> {
                if (p == null || !s.hasPermission("employees.admin")) {
                    Msg.error(s, "Only admins can change merchants.");
                    return;
                }
                var m = market.nearest(p.getLocation());
                if (m == null) {
                    Msg.error(p, "Stand within 8 blocks of the merchant (or shift-right-click them).");
                    return;
                }
                new site.remotearc.employees.gui.MerchantMenu(plugin, m.id()).open(p);
            }
            case "prices" -> {
                if (p != null) new site.remotearc.employees.market.PriceMenu(plugin, p.getUniqueId(), 0).open(p);
            }
            default -> {
                if (p == null) return;
                if (!market.hasMerchants()) {
                    Msg.error(p, "There's no merchant yet. An admin places one with <white>/" + label + " market place [name]</white>.");
                    return;
                }
                if (!market.nearMerchant(p)) {
                    StringBuilder where = new StringBuilder();
                    for (var m : market.merchants()) where.append(" ").append(m.name()).append(" (").append((int) m.x()).append(", ")
                            .append((int) m.z()).append(m.world().equals("world") ? "" : " in " + m.world()).append(")");
                    Msg.error(p, "Walk up to a merchant to sell:<where>", Msg.p("where", where.toString()));
                    return;
                }
                new site.remotearc.employees.market.SellMenu(plugin, p, "The merchant").open();
            }
        }
    }

    /**
     * /employees sell <name> all | off | add <item...> | remove <item...> | list. "all" and "add"
     * take any of: keep <n> (stock to keep), sell <n>% / keep <n>% (share of the rest to sell),
     * sell <n> (most per day); none = sell all of it.
     */
    void sell(CommandSender s, Employee e, String label, String[] args) {
        String mode = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "list";
        List<String> items = new ArrayList<>();
        int keep = 0, percent = 100, daily = 0;
        List<String> rest = new ArrayList<>(List.of(args).subList(Math.min(args.length, 3), args.length));
        for (int i = 0; i < rest.size(); i++) {
            String t = rest.get(i).toLowerCase(Locale.ROOT);
            if ((t.equals("keep") || t.equals("sell")) && i + 1 < rest.size()) {
                String raw = rest.get(++i);
                boolean pct = raw.endsWith("%");
                int n;
                try {
                    n = Math.max(0, Integer.parseInt(raw.replace("%", "")));
                } catch (NumberFormatException ex) {
                    Msg.error(s, "<white><v></white> isn't a number.", Msg.p("v", raw));
                    return;
                }
                if (pct && t.equals("keep")) percent = 100 - Math.min(100, n);   // keep 50% = sell the other 50%
                else if (pct) percent = Math.min(100, n);                          // sell 25%
                else if (t.equals("keep")) keep = n;                               // keep 256 in storage
                else daily = n;                                                    // sell up to 256 a day
            } else {
                items.add(rest.get(i));
            }
        }
        Employee.SellRule rule = new Employee.SellRule(keep, percent, daily);
        switch (mode) {
            case "all", "on" -> {
                e.sellAll = true;
                e.sellAllRule = rule;
            }
            case "off", "none" -> {
                e.sellAll = false;
                e.sellRules.clear();
                e.neverSell.clear();
            }
            case "add", "remove", "never" -> {
                if (items.isEmpty()) {
                    Msg.error(s, "Usage: /" + label + " sell " + e.name + " add \\<item...> [keep 256] [sell 50%] [sell 256] | never \\<item...>");
                    return;
                }
                for (String token : items) {
                    java.util.Set<Material> one = MaterialList.parseOne(token);
                    if (one == null) {
                        Msg.error(s, "<white><what></white> isn't an item or #tag.", Msg.p("what", token));
                        return;
                    }
                    for (Material m : one) {
                        switch (mode) {
                            case "add" -> {
                                e.sellRules.put(m, rule);
                                e.neverSell.remove(m);
                            }
                            case "never" -> {
                                e.neverSell.add(m);
                                e.sellRules.remove(m);
                            }
                            default -> {
                                e.sellRules.remove(m);
                                e.neverSell.remove(m);
                            }
                        }
                    }
                }
            }
            case "list" -> { }
            default -> {
                Msg.error(s, "Usage: /" + label + " sell " + e.name + " all | off | add \\<item...> | remove \\<item...>"
                        + " [keep 256] [sell 50%] [sell 256]");
                return;
            }
        }
        if (!mode.equals("list")) plugin.manager().markDirty();
        Msg.send(s, "<gold>" + e.name + "</gold> <gray>sells: <white>" + Describe.esc(sellText(e)) + (plugin.market().hasMerchants() ? ""
                : " <yellow>(no merchant placed yet, so nothing sells until an admin runs /" + label + " market place)"));
        double[] today = e.soldToday();
        if (today[0] > 0) s.sendMessage(Msg.parse("<gray>Last 24h: <white>" + (long) today[0] + "</white> items for <green>" + Msg.money(today[1])));
    }

    static String sellText(Employee e) {
        List<String> parts = new ArrayList<>();
        if (e.sellAll) parts.add("everything merchants buy (" + e.sellAllRule.describe() + ")");
        for (Map.Entry<Material, Employee.SellRule> en : e.sellRules.entrySet()) {
            if (parts.size() >= 8) {
                parts.add("and more");
                break;
            }
            parts.add(Items.name(en.getKey()) + " (" + en.getValue().describe() + ")");
        }
        if (!e.neverSell.isEmpty()) {
            parts.add("never " + String.join(", ", e.neverSell.stream().limit(6).map(Items::name).toList()) + (e.neverSell.size() > 6 ? "..." : ""));
        }
        return parts.isEmpty() ? "nothing (keeps it all)" : String.join(", ", parts);
    }

    // ── chest groups ──────────────────────────────────────────────────────

    void chests(CommandSender s, String label, String[] args) {
        if (!(s instanceof Player p)) {
            Msg.error(s, "Only players have chests.");
            return;
        }
        var g = plugin.chestGroups();
        String mode = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (mode) {
            case "organize", "sort" -> {
                for (String line : g.organize(p.getUniqueId())) Msg.send(p, line);
            }
            case "move" -> {
                JobType job = args.length > 2 ? JobType.parse(args[2]) : null;
                int start;
                try {
                    start = Integer.parseInt(args.length > 3 ? args[3] : "");
                } catch (NumberFormatException ex) {
                    start = -1;
                }
                if (job == null || start < 1) {
                    Msg.error(p, "Usage: /" + label + " chests move \\<job> \\<first chest number>");
                    return;
                }
                String err = g.move(p.getUniqueId(), job, start);
                if (err != null) Msg.error(p, "<msg>", Msg.p("msg", err));
                else Msg.send(p, "<green>The " + job.display().toLowerCase() + " group is now /chest " + g.range(p.getUniqueId(), job) + ".");
            }
            case "list" -> {
                for (JobType t : JobType.values()) Msg.send(p, "<gold>" + t.display() + "s</gold> <gray>/chest " + g.range(p.getUniqueId(), t));
            }
            default -> new site.remotearc.employees.gui.ChestsMenu(plugin, p.getUniqueId(), null).open(p);
        }
    }

    // ── blueprints ────────────────────────────────────────────────────────

    void blueprints(CommandSender s, String label, String[] args) {
        if (args.length > 1 && args[1].equalsIgnoreCase("save")) {
            saveSelection(s, label, args);
            return;
        }
        if (args.length > 1 && args[1].equalsIgnoreCase("upload")) {
            if (!(s instanceof Player p)) {
                Msg.error(s, "Only players can upload (put files straight into plugins/ArcEmployees/blueprints).");
                return;
            }
            if (!s.hasPermission("employees.blueprints.download")) {
                Msg.error(s, "You don't have permission to add blueprints.");
                return;
            }
            if (plugin.uploads().baseUrl() == null) {
                Msg.error(s, "Uploading needs the built-in web server (web.enabled) or blueprints.upload-url in the config.");
                return;
            }
            String name = args.length > 2 ? args[2] : null;
            if (name != null && !EmployeeManager.NAME.matcher(name.replace("-", "_")).matches()) {
                Msg.error(s, "Blueprint names are letters, digits, - and _.");
                return;
            }
            String url = plugin.uploads().create(p, name);
            if (url == null) {
                Msg.error(s, "Couldn't make an upload link; see the server log.");
                return;
            }
            Msg.send(s, "<green>Your upload page is ready: <click:open_url:'" + url + "'><hover:show_text:'<gray>Opens in your browser'>"
                    + "<aqua><u>click here to open it</u></aqua></hover></click><green>.");
            Msg.send(s, "<gray>Drop .schem, .schematic, .litematic, .nbt or .zip files on it. The link works for 15 minutes; "
                    + "you'll get a message here as each file arrives.");
            return;
        }
        if (args.length > 1 && args[1].equalsIgnoreCase("download")) {
            if (!s.hasPermission("employees.blueprints.download")) {
                Msg.error(s, "You don't have permission to download blueprints.");
                return;
            }
            if (!plugin.settings().downloadsEnabled) {
                Msg.error(s, "Downloading blueprints is turned off in the config.");
                return;
            }
            if (args.length < 3) {
                Msg.error(s, "Usage: /" + label + " blueprints download \\<link> [name]");
                return;
            }
            String name = args.length > 3 ? args[3] : null;
            if (name != null && !EmployeeManager.NAME.matcher(name.replace("-", "_")).matches()) {
                Msg.error(s, "Blueprint names are letters, digits, - and _.");
                return;
            }
            plugin.blueprints().download(s, args[2], name);
            return;
        }
        List<String> names = plugin.blueprints().list();
        if (names.isEmpty()) {
            Msg.send(s, "<gray>No blueprints yet. Upload one from your computer with <white>/" + label + " blueprints upload</white>, download one with <white>/" + label + " blueprints download \\<link></white>, "
                    + "or put .schem / .schematic / .litematic / .nbt files in <white>plugins/ArcEmployees/blueprints</white>.");
            return;
        }
        Msg.send(s, "<gray>" + names.size() + " blueprint(s): <white><list>", Msg.p("list", String.join(", ", names)));
        s.sendMessage(Msg.parse("<gray>Build one: stand where it goes and run <white>/" + label + " build \\<builder> \\<blueprint>"));
    }

    /** /emp stalls <trader> [add | remove <n> | want <n> <trade> | reroll <n> | list]: the trading hall's villager spots. */
    void stalls(CommandSender s, Employee e, String label, String[] args) {
        if (e.job != JobType.TRADER) {
            Msg.error(s, e.name + " isn't a villager trader. Hire one with <white>/" + label + " hire trader");
            return;
        }
        String c = "/" + label + " stalls " + e.name;
        String sub = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "list";
        org.bukkit.World w = e.location == null ? null : e.location.getWorld();
        switch (sub) {
            case "add" -> {
                if (!(s instanceof Player p)) return;
                org.bukkit.entity.Villager.Profession job = args.length > 3 ? site.remotearc.employees.job.Stalls.byName(args[3]) : null;
                if (args.length > 3 && job == null) {
                    Msg.error(p, "No villager job called " + args[3] + " (librarian, armorer, cleric, farmer, fletcher...).");
                    return;
                }
                site.remotearc.employees.gui.BlockPick.ask(p, "the top of the floor block the villager will stand on", floor -> {
                    BlockPos stand = BlockPos.of(floor.getRelative(org.bukkit.block.BlockFace.UP));
                    site.remotearc.employees.gui.BlockPick.askFace(p, "where its job block goes: the face of the block it'll sit on or against"
                            + " (or the job block itself, if it's there), or the stall's floor again for none", (b1, f1) -> {
                        BlockPos station = b1.equals(floor) ? null : site.remotearc.employees.job.Stalls.WORKSTATIONS.containsKey(b1.getType())
                                ? BlockPos.of(b1) : BlockPos.of(b1.getRelative(f1));
                        site.remotearc.employees.gui.BlockPick.askFace(p, "where the trapdoor goes under the roof: the underside of the roof block"
                                + " above (or the trapdoor itself), or the stall's floor again for none", (b2, f2) -> {
                            BlockPos cap = b2.equals(floor) ? null : org.bukkit.Tag.TRAPDOORS.isTagged(b2.getType()) ? BlockPos.of(b2) : BlockPos.of(b2.getRelative(f2));
                            Employee.Stall st = new Employee.Stall(stand, station, null);
                            st.door = cap;
                            if (job != null) st.profession = job.getKey().getKey();
                            e.stalls.add(st);
                            mgr().markDirty();
                            e.worker.interrupt();
                            int n = e.stalls.size();
                            org.bukkit.entity.Villager.Profession known = job != null ? job
                                    : station != null ? site.remotearc.employees.job.Stalls.WORKSTATIONS.get(station.block(p.getWorld()).getType()) : null;
                            Msg.send(p, "<green>Stall " + n + " set up.</green> <gray>" + e.name + " walks a villager in"
                                    + (known != null ? ", makes it a " + known.getKey().getKey() : "")
                                    + (station != null ? ", puts its job block down" : "") + (cap != null ? " and puts a trapdoor under the roof" : "") + "."
                                    + (station != null && known == null ? " <yellow>Which job is it for? <white><click:suggest_command:'" + c + " job " + n + " '>[Set its job]</click>" : "")
                                    + " <white><click:suggest_command:'" + c + " want " + n + " '>[Reroll it for a trade]</click> "
                                    + "<click:run_command:'" + c + " add'>[Add another]</click>");
                        });
                    });
                });
            }
            case "job", "profession" -> {
                int n = stallNumber(s, e, args, 3);
                if (n < 0) return;
                org.bukkit.entity.Villager.Profession job = args.length > 4 ? site.remotearc.employees.job.Stalls.byName(args[4]) : null;
                if (job == null) {
                    Msg.error(s, "Usage: " + c + " job " + (n + 1) + " <librarian|armorer|butcher|cartographer|cleric|farmer|fisherman|fletcher|leatherworker|mason|shepherd|toolsmith|weaponsmith>");
                    return;
                }
                e.stalls.get(n).profession = job.getKey().getKey();
                mgr().markDirty();
                e.worker.interrupt();
                Msg.send(s, "<green>Stall " + (n + 1) + " is for a " + job.getKey().getKey() + ".");
            }
            case "move", "herd" -> {
                if (!(s instanceof Player p)) return;
                int r = 8;
                if (args.length > 3) {
                    try {
                        r = Math.max(1, Math.min(32, Integer.parseInt(args[3])));
                    } catch (NumberFormatException ex) {
                        Msg.error(p, "Usage: " + c + " move [radius]   (villagers round where you stand)");
                        return;
                    }
                }
                java.util.Set<java.util.UUID> inStalls = new java.util.HashSet<>();
                for (Employee.Stall st : e.stalls) {
                    org.bukkit.entity.Villager in = site.remotearc.employees.job.Stalls.inStallPublic(p.getWorld(), st);
                    if (in != null) inStalls.add(in.getUniqueId());
                }
                List<java.util.UUID> pick = new ArrayList<>();
                for (org.bukkit.entity.Entity en : p.getNearbyEntities(r, 6, r)) {
                    if (en instanceof org.bukkit.entity.Villager v && v.customName() == null && !inStalls.contains(v.getUniqueId())) pick.add(v.getUniqueId());
                }
                if (pick.isEmpty()) {
                    Msg.error(p, "No villagers within " + r + " blocks of you (named ones and ones in stalls are left alone).");
                    return;
                }
                site.remotearc.employees.gui.BlockPick.ask(p, "where the " + pick.size() + " villager" + (pick.size() == 1 ? "" : "s") + " should go (the top of a block)", to -> {
                    e.moveTo = to.getRelative(org.bukkit.block.BlockFace.UP).getLocation().add(0.5, 0, 0.5);
                    e.moveQueue.clear();
                    e.moveQueue.addAll(pick);
                    e.worker.interrupt();
                    Msg.send(p, "<green>" + e.name + " will walk " + pick.size() + " villager" + (pick.size() == 1 ? "" : "s") + " there, one at a time.</green> "
                            + "<gray>One stuck in a hole, or that can't get there on foot, gets pulled along.");
                });
            }
            case "remove", "delete" -> {
                int n = stallNumber(s, e, args, 3);
                if (n < 0) return;
                e.stalls.remove(n);
                mgr().markDirty();
                Msg.send(s, "<gray>Stall " + (n + 1) + " removed (the villager stays where it is).");
            }
            case "want", "find", "cycle" -> {
                int n = stallNumber(s, e, args, 3);
                if (n < 0) return;
                Employee.Stall st = e.stalls.get(n);
                if (args.length < 5 || args[4].equalsIgnoreCase("none") || args[4].equalsIgnoreCase("off")) {
                    st.want = null;
                    Msg.send(s, "<gray>Stall " + (n + 1) + " isn't looking for a trade any more.");
                } else {
                    st.want = String.join(" ", java.util.Arrays.copyOfRange(args, 4, args.length)).toLowerCase(Locale.ROOT);
                    Msg.send(s, "<green>" + e.name + " rerolls stall " + (n + 1) + "'s villager until it offers " + st.want + ".</green> <gray>"
                            + "Only villagers nobody has traded with can be rerolled. An enchantment (mending, protection 4) or an item (emerald, bell).");
                }
                mgr().markDirty();
                e.worker.interrupt();
            }
            case "reroll" -> {
                int n = stallNumber(s, e, args, 3);
                if (n < 0 || w == null) return;
                org.bukkit.entity.Villager v = site.remotearc.employees.job.Stalls.inStallPublic(w, e.stalls.get(n));
                if (v == null) {
                    Msg.error(s, "There's no villager in stall " + (n + 1) + " yet.");
                    return;
                }
                if (v.getVillagerExperience() > 0) {
                    Msg.error(s, "That villager has been traded with, so its trades are locked.");
                    return;
                }
                v.setRecipes(new ArrayList<>());
                v.addTrades(2);
                Msg.send(s, "<green>Stall " + (n + 1) + " rerolled.</green> <white><click:run_command:'" + c + " reroll " + (n + 1) + "'>[Again]</click>");
            }
            case "show" -> {
                if (!(s instanceof Player p)) return;
                if (args.length > 3 && args[3].equalsIgnoreCase("off")) {
                    site.remotearc.employees.job.StallView.hide(p);
                    Msg.send(p, "<gray>Stalls hidden.");
                    return;
                }
                if (e.stalls.isEmpty() || w == null || !w.equals(p.getWorld())) {
                    Msg.error(p, e.stalls.isEmpty() ? e.name + " has no stalls yet." : e.name + "'s stalls are in another world.");
                    return;
                }
                site.remotearc.employees.job.StallView.show(plugin, p, e, 90);
                Msg.send(p, "<green>Showing " + e.name + "'s " + e.stalls.size() + " stalls for a minute and a half</green> <gray>(only you see them): a label over each, "
                        + "<green>green</green> round the villager's spot, <gold>orange</gold> round the job block, <blue>blue</blue> round the trapdoor. "
                        + "<white><click:run_command:'" + c + " show off'>[Hide]</click>");
            }
            default -> {
                if (e.stalls.isEmpty()) {
                    Msg.send(s, "<gray>No stalls yet. A stall is a spot in your trading hall for one villager: " + e.name + " brings villagers to them, "
                            + "gives each the stall's workstation's job, and can reroll trades. <white><click:run_command:'" + c + " add'>[Add a stall]</click>");
                    return;
                }
                Msg.send(s, "<gold>" + e.name + "'s stalls:");
                for (int i = 0; i < e.stalls.size(); i++) {
                    s.sendMessage(Msg.parse("<gray>" + site.remotearc.employees.job.Stalls.describe(w, e.stalls.get(i), i + 1)
                            + " <white><click:suggest_command:'" + c + " want " + (i + 1) + " '>[want]</click> <click:run_command:'" + c + " reroll " + (i + 1)
                            + "'>[reroll]</click> <click:run_command:'" + c + " remove " + (i + 1) + "'>[remove]</click>"));
                }
                s.sendMessage(Msg.parse("<white><click:run_command:'" + c + " show'>[Show them]</click> <click:run_command:'" + c + " add'>[Add a stall]</click> <click:suggest_command:'" + c + " move 8'>[Move villagers round me]</click>"));
                if (s instanceof Player p && w != null && w.equals(p.getWorld())) site.remotearc.employees.job.StallView.show(plugin, p, e, 90);
            }
        }
    }

    private static int stallNumber(CommandSender s, Employee e, String[] args, int at) {
        try {
            int n = Integer.parseInt(args[at]) - 1;
            if (n >= 0 && n < e.stalls.size()) return n;
        } catch (RuntimeException ignored) {
            // falls through
        }
        Msg.error(s, "Which stall? 1 to " + e.stalls.size() + " (see /employees stalls " + e.name + ").");
        return -1;
    }

    /** /emp blueprints save <name> [replace]: the WorldEdit selection, as it stands, becomes a blueprint. */
    private void saveSelection(CommandSender s, String label, String[] args) {
        if (!(s instanceof Player p)) {
            Msg.error(s, "Only players have a WorldEdit selection.");
            return;
        }
        if (args.length < 3 || !EmployeeManager.NAME.matcher(args[2].replace("-", "_")).matches()) {
            Msg.error(s, "Usage: /" + label + " blueprints save \\<name> [replace]   (select it first with the WorldEdit wand)");
            return;
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("WorldEdit")) {
            Msg.error(s, "Saving a selection needs WorldEdit.");
            return;
        }
        WorkArea box;
        try {
            box = WorldEditSelection.exact(p);
        } catch (IllegalStateException ex) {
            Msg.error(p, ex.getMessage());
            return;
        }
        java.io.File f = new java.io.File(plugin.blueprints().dir(), args[2] + ".schem");
        if ((f.exists() || plugin.blueprints().resolve(args[2]) != null) && !(args.length > 3 && args[3].equalsIgnoreCase("replace"))) {
            Msg.error(p, "There's already a blueprint called " + args[2] + ". Pick another name, or add <white>replace</white>.");
            return;
        }
        World w = Bukkit.getWorld(box.world());
        try {
            site.remotearc.employees.build.Plans.Built b = site.remotearc.employees.build.Plans.fromWorld(w, box, f.getName(), plugin.settings().builderMaxVolume);
            site.remotearc.employees.build.Plans.write(f, b.bp());
            Msg.send(p, "<green>Saved " + b.bp().size() + " (" + b.bp().solidCount() + " blocks) as blueprint <white>" + args[2] + "</white>.</green> "
                    + "<gray>Stand where it should go and run <white>/" + label + " build \\<builder> " + args[2] + "</white>.");
        } catch (java.io.IOException ex) {
            Msg.error(p, "Couldn't save it: " + ex.getMessage());
        }
    }

    /** /emp build <name> plan [done|cancel]: plan a build in the world itself. */
    private void plan(CommandSender s, Employee e, String c, String[] args) {
        if (!(s instanceof Player p)) {
            Msg.error(s, "Only players can plan in the world.");
            return;
        }
        String how = args.length > 3 ? args[3].toLowerCase(Locale.ROOT) : "";
        if (how.equals("done") || how.equals("stop")) {
            site.remotearc.employees.build.PlanMode.done(p);
            return;
        }
        if (how.equals("cancel")) {
            site.remotearc.employees.build.PlanMode.cancel(p);
            return;
        }
        if (how.equals("name") && args.length > 4 && site.remotearc.employees.build.PlanMode.planningFor(p) != null
                && e.id.equals(site.remotearc.employees.build.PlanMode.planningFor(p))) {
            String why = site.remotearc.employees.build.PlanMode.rename(e, args[4]);
            if (why != null) Msg.error(p, why);
            else Msg.send(p, "<green>The plan is called " + args[4] + " now.");
            return;
        }
        boolean fresh = how.equals("new");
        String named = how.equals("name") && args.length > 4 ? args[4] : fresh && args.length > 4 ? args[4] : null;
        String kept = fresh && e.build != null && e.build.planned ? e.build.blueprint : null;
        String why = site.remotearc.employees.build.PlanMode.enter(p, e, named, fresh);
        if (why == null && kept != null) Msg.send(p, "<gray>The old plan is kept as blueprint <white>" + kept + "</white>: give it to a builder any time with <white>"
                + c + " " + kept.replace(".schem", "") + "</white>.");
        if (why != null) {
            Msg.error(p, why);
            return;
        }
        Msg.send(p, "<aqua>Planning for " + e.name + ".</aqua> <gray>WorldEdit (//set, //walls, //paste, brushes, //undo...) now only plans: "
                + "its blocks go into " + e.name + "'s plan as ghost blocks only you see. Blocks you place by hand are real, and part of the plan too, "
                + "so build some yourself and let " + e.name + " do the rest. Break a ghost to take it out; nothing else changes.");
        p.sendMessage(Msg.parse("<green><click:run_command:'/employees plan done'>[Done planning]</click></green> "
                + "<green><click:suggest_command:'/employees plan done '>[Done, and name it]</click></green> "
                + "<aqua><click:run_command:'/employees plan hand ghost'>[Place ghosts by hand instead]</click></aqua> "
                + "<red><click:run_command:'/employees plan cancel'>[Cancel]</click></red> <dark_gray>(/emp plan done)"));
    }

    // ── build ─────────────────────────────────────────────────────────────

    void build(CommandSender s, Employee e, String label, String[] args) {
        if (e.job != JobType.BUILDER) {
            Msg.error(s, e.name + " isn't a builder. Hire one with <white>/" + label + " hire builder");
            return;
        }
        String c = "/" + label + " build " + e.name;
        String sub = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : s instanceof Player ? "menu" : "info";
        if (sub.equals("menu")) {
            if (s instanceof Player p) site.remotearc.employees.gui.MaterialsMenu.openBuild(plugin, p, e);
            return;
        }
        if (sub.equals("plan")) {
            plan(s, e, c, args);
            return;
        }
        BuildOrder o = e.build;
        if (!BUILD_ACTIONS.contains(sub)) {
            newBuild(s, e, args[2], c, args.length > 3 && args[3].equalsIgnoreCase("here"));
            return;
        }
        if (o == null) {
            Msg.error(s, e.name + " has nothing to build. Stand where it should go and run <white>" + c + " \\<blueprint>"
                    + "</white> (see <white>/" + label + " blueprints</white>).");
            return;
        }
        Player p = s instanceof Player pl ? pl : null;
        switch (sub) {
            case "info" -> info(s, e, o, c);
            case "preview" -> {
                if (p == null || !ready(s, o)) return;
                if (args.length > 3 && List.of("digging", "dig", "clearing").contains(args[3].toLowerCase(Locale.ROOT))) {
                    int n = BuildPreview.show(plugin, p, o, true);
                    Msg.send(p, n == 0 ? "<gray>Nothing needs digging out." : "<gray>Showing what gets dug out: <red>red</red> round the build"
                            + (o.clearAround ? "" : " (off: it's left alone)") + ", <gold>orange</gold> inside it (" + n + " blocks). "
                            + "<white><click:run_command:'" + c + " around " + (o.clearAround ? "leave" : "clear") + "'>[" + (o.clearAround ? "Leave the space round it" : "Dig round it too") + "]</click>");
                    return;
                }
                if (args.length > 3 && List.of("off", "hide", "clear").contains(args[3].toLowerCase(Locale.ROOT))) {
                    BuildPreview.clear(p);
                    Msg.send(p, "<gray>Preview hidden.");
                    return;
                }
                int n = BuildPreview.show(plugin, p, o);
                if (n == 0) {
                    Msg.error(p, !p.getWorld().getName().equals(o.world) ? "The build is in " + o.world + "; go there to see it."
                            : "Nothing to show: the blocks are all left out, or too far away to be loaded.");
                    return;
                }
                Msg.send(p, "<gray>Showing " + o.blueprint + " (" + n + " ghost blocks, only you see them) while you're near it; it goes "
                        + plugin.settings().previewSeconds + "s after you leave. <white><click:run_command:'" + c + " preview off'>[Hide it]</click>");
            }
            case "rotate", "flip", "move", "shift" -> {
                if (!ready(s, o)) return;
                boolean moving = sub.equals("move");
                if (moving && o.started && !o.finished && !o.dismantle && (args.length < 4 || !args[3].equalsIgnoreCase("confirm"))) {
                    Msg.send(s, "<yellow>" + e.name + " has started " + o.blueprint + " where it is now. Move it to where you're standing? What's built in "
                            + "the old spot stays; " + e.name + " comes here and starts over when you press Start. Swaps, left-out blocks, the ground "
                            + "setting and bought materials come along. <green><click:run_command:'" + c + " move confirm'>[Move it here]</click>");
                    return;
                }
                if (!moving && p != null && !BuildPreview.showing(p)) {
                    // Only while you can see where it goes, so a stray click can't turn a finished build without you noticing.
                    int n = BuildPreview.show(plugin, p, o);
                    Msg.send(p, n > 0 ? "<yellow>Turning, mirroring and shifting work while the preview is showing.</yellow> <gray>It's showing now: "
                            + (sub.equals("rotate") ? "turn" : sub.equals("flip") ? "mirror" : "shift") + " it again to do it."
                            : "<yellow>Turning, mirroring and shifting work while the preview is showing:</yellow> <white><click:run_command:'" + c + " preview'>[Preview]</click>");
                    return;
                }
                if (!moving && o.started && !o.finished) {
                    Msg.error(s, e.name + " has already started. Turning or moving it now would leave the built part the old way. "
                            + "Pause with <white>/" + label + " pause " + e.name + "</white>, or start over with <white>" + c + " cancel</white>.");
                    return;
                }
                if (sub.equals("rotate")) {
                    int deg = 90;
                    if (args.length > 3) {
                        try {
                            deg = Integer.parseInt(args[3].replace("°", ""));
                        } catch (NumberFormatException ex) {
                            Msg.error(s, "Usage: " + c + " rotate [90|180|270|-90]");
                            return;
                        }
                    }
                    if (deg % 90 != 0) {
                        Msg.error(s, "Builds turn in steps of 90 degrees.");
                        return;
                    }
                    o.rotation = Math.floorMod(o.rotation + deg / 90, 4);
                } else if (sub.equals("flip")) {
                    o.flip = !o.flip;
                } else if (sub.equals("move")) {
                    if (p == null) return;
                    Location at = p.getLocation();
                    o.world = at.getWorld().getName();
                    o.x = at.getBlockX();
                    o.y = at.getBlockY() - (o.keepGround ? 0 : o.groundLayers());   // its ground goes where yours is
                    o.z = at.getBlockZ();
                } else if (args.length >= 4 && direction(args[3]) != null) {
                    // forward / back / left / right / up / down, as the player is facing.
                    int n = 1;
                    if (args.length > 4) {
                        try {
                            n = Integer.parseInt(args[4]);
                        } catch (NumberFormatException ex) {
                            Msg.error(s, "Usage: " + c + " shift forward|back|left|right|up|down [blocks]");
                            return;
                        }
                    }
                    int[] d = direction(args[3]);
                    int fx = 0, fz = 1;   // facing south unless a player says otherwise
                    if (p != null) {
                        switch (Math.floorMod(Math.round(p.getLocation().getYaw() / 90f), 4)) {
                            case 1 -> { fx = -1; fz = 0; }
                            case 2 -> { fx = 0; fz = -1; }
                            case 3 -> { fx = 1; fz = 0; }
                            default -> { }
                        }
                    }
                    // d = {forward, right, up}; right of facing south (+z) is west (-x).
                    o.x += n * (d[0] * fx - d[1] * fz);
                    o.z += n * (d[0] * fz + d[1] * fx);
                    o.y += n * d[2];
                    o.turnedChanged();
                    o.finished = false;
                    o.started = false;
                    o.dismantle = false;
                    o.layer = 0;
                    e.worker.interrupt();
                    mgr().setArea(e, o.bounds().expand(2, 2));
                    mgr().markDirty();
                    if (p != null) {
                        BuildPreview.show(plugin, p, o);
                        p.sendActionBar(Msg.parse("<gray>Moved " + o.blueprint + " " + n + " " + args[3].toLowerCase(java.util.Locale.ROOT)
                                + " <dark_gray>(corner at " + o.bounds().minX() + " " + o.bounds().minY() + " " + o.bounds().minZ() + ")"));
                    } else {
                        Msg.send(s, "<gray>Moved " + o.blueprint + " " + n + " " + args[3]);
                    }
                    return;
                } else {
                    if (args.length < 6) {
                        Msg.error(s, "Usage: " + c + " shift forward|back|left|right|up|down [blocks]   (or x y z)");
                        return;
                    }
                    try {
                        o.x += Integer.parseInt(args[3]);
                        o.y += Integer.parseInt(args[4]);
                        o.z += Integer.parseInt(args[5]);
                    } catch (NumberFormatException ex) {
                        Msg.error(s, "Usage: " + c + " shift \\<x> \\<y> \\<z>");
                        return;
                    }
                }
                o.turnedChanged();
                // A new placement: nothing happens until it is started again.
                o.finished = false;
                o.started = false;
                o.dismantle = false;
                o.layer = 0;
                e.worker.interrupt();
                if (moving) bringToSite(e, o, p);   // another world, or far away: the builder comes along
                placed(s, e, o, c);
            }
            case "start" -> {
                if (!ready(s, o)) return;
                if (o.finished) {
                    Msg.send(s, "<gray>" + e.name + " already finished " + o.blueprint + ". Checking it over again.");
                    o.finished = false;
                    o.layer = 0;
                }
                o.started = true;
                if (p != null) BuildPreview.clear(p);
                bringToSite(e, o, p);
                mgr().markDirty();
                e.worker.interrupt();
                Msg.send(s, "<green>" + e.name + " is building " + o.blueprint + ". Materials come from the backpack and: <white>"
                        + Describe.esc(Supply.describe(plugin, e)) + "</white>. Check with <white>" + c + " materials</white>.");
            }
            case "clear" -> {
                boolean on = args.length < 4 || !args[3].equalsIgnoreCase("off");
                o.clearAir = on;
                mgr().markDirty();
                e.worker.interrupt();
                Msg.send(s, on ? "<green>" + e.name + " clears whatever is in the way where the blueprint has air (grass, dirt, trees)."
                        : "<green>" + e.name + " only places blocks and leaves the rest of the site as it is.");
            }
            case "materials" -> {
                if (p != null && (args.length < 4 || !args[3].equalsIgnoreCase("chat"))) {
                    if (ready(s, o)) site.remotearc.employees.gui.MaterialsMenu.open(plugin, p, e);
                } else materials(s, e, o, c);
            }
            case "dismantle" -> {
                if (!ready(s, o)) return;
                if (args.length < 4 || !args[3].equalsIgnoreCase("confirm")) {
                    Msg.send(s, "<yellow>Take " + o.blueprint + " down? " + e.name + " breaks every block that's still what the blueprint has there, "
                            + "top first, and takes them back to " + (e.storage.kind() == StorageTarget.Kind.NONE ? "its backpack" : e.storage.describe())
                            + ". Things added since stay, and so do chests with something in them. What it dug out to make room isn't put back"
                            + (plugin.settings().coreProtect ? " (CoreProtect can do that: <white>/co rollback u:" + plugin.settings().coreProtectPrefix + e.name + " t:\\<time></white> after it's done)" : "")
                            + ". <red><click:run_command:'" + c + " dismantle confirm'>[Take it down]</click>");
                    return;
                }
                if (p != null) BuildPreview.clear(p);
                double refund = refund(e, o);
                o.dismantle = true;
                o.started = true;
                o.finished = false;
                o.repair = false;
                o.layer = 0;
                if (e.paused) mgr().setPaused(e, false);
                bringToSite(e, o, p);
                mgr().markDirty();
                e.worker.interrupt();
                Msg.send(s, "<green>" + e.name + " is taking down " + o.blueprint + ", top first."
                        + (refund > 0 ? " <gray>Refunded " + Msg.money(refund) + " for bought materials it hadn't used." : "")
                        + " <gray>Stop with <white><click:run_command:'" + c + " cancel'>[Cancel]</click></white>.");
            }
            case "swap" -> swap(s, e, o, c, args);
            case "swaps" -> swaps(s, o, c);
            case "pick" -> {
                if (p == null || !ready(s, o)) return;
                site.remotearc.employees.gui.BuildPicker.pickBuildBlock(plugin, p, e);
            }
            case "ground" -> ground(s, e, o, c, args);
            case "rename", "name" -> {
                if (args.length < 4) {
                    Msg.error(s, "Usage: " + c + " rename \\<name>  (builds planned in the world)");
                    return;
                }
                String why = site.remotearc.employees.build.PlanMode.rename(e, args[3]);
                if (why != null) Msg.error(s, why);
                else Msg.send(s, "<green>" + e.name + "'s build is called " + args[3] + " now (blueprint " + o.blueprint + ").");
            }
            case "follow" -> {
                String m = args.length > 3 ? args[3].toLowerCase(Locale.ROOT) : (o.follow ? "off" : "on");
                if (m.equals("now")) {
                    if (!o.follow) {
                        Msg.error(s, "Turn it on first: " + c + " follow on");
                        return;
                    }
                    int n = site.remotearc.employees.build.FollowChanges.catchUp(e);
                    Msg.send(s, n < 0 ? "<gray>" + e.name + " is building it; changes are taken in once it's finished."
                            : n == 0 ? "<green>" + e.name + "'s plan already matches the build." : "<green>Took " + n + " changed blocks into " + e.name
                            + "'s plan.</green> <gray>Gaps aren't taken in (they could be creeper holes): put a block in one and break it again and the gap stays.");
                    return;
                }
                if (!m.equals("on") && !m.equals("off")) {
                    Msg.error(s, "Usage: " + c + " follow on|off|now");
                    return;
                }
                o.follow = m.equals("on");
                mgr().markDirty();
                Msg.send(s, o.follow
                        ? "<green>Once it's finished, " + e.name + "'s build keeps up with you:</green> <gray>blocks you place, break or change with WorldEdit in it "
                        + "or up to 3 blocks round it become part of the plan, so " + e.name + " never undoes them (repairs, checking it over, plan edits)."
                        : "<yellow>" + e.name + "'s build no longer takes your changes in:</yellow> <gray>the plan stays as it is, and checking it over or repairs put it back that way.");
            }
            case "around" -> {
                String m = args.length > 3 ? args[3].toLowerCase(Locale.ROOT) : (o.clearAround ? "leave" : "clear");
                if (!m.equals("clear") && !m.equals("leave")) {
                    Msg.error(s, "Usage: " + c + " around clear|leave");
                    return;
                }
                o.clearAround = m.equals("clear");
                o.turnedChanged();
                o.setPlan(o.plan);
                e.worker.interrupt();
                mgr().markDirty();
                Msg.send(s, o.clearAround
                        ? "<green>" + e.name + " digs out the blueprint's empty space round the build as well as inside it."
                        : "<green>" + e.name + " leaves whatever is round the build alone</green> <gray>and only clears space inside it (rooms, doorways). "
                        + "Neighbouring builds and ground are left be.");
                if (p != null) {
                    int n = BuildPreview.show(plugin, p, o, true);
                    Msg.send(p, n == 0 ? "<gray>Nothing needs digging out now." : "<gray>Showing what gets dug out: <red>red</red> round the build, "
                            + "<gold>orange</gold> inside it (" + n + " blocks). <white><click:run_command:'" + c + " preview'>[Show the build again]</click>");
                }
            }
            case "skip" -> skip(s, e, o, c, args);
            case "buy" -> buy(s, e, o, c, args);
            case "cancel" -> {
                if (p != null) BuildPreview.clear(p);
                e.build = null;
                double refund = refund(e, o);
                mgr().markDirty();
                e.worker.interrupt();
                Msg.send(s, "<gray>" + e.name + " stopped " + (o.dismantle ? "taking down " : "building ") + o.blueprint + ". What's built stays; "
                        + (e.storage.kind() == site.remotearc.employees.storage.StorageTarget.Kind.NONE
                            ? "the leftover materials stay in its backpack."
                            : "it takes the leftover materials back to " + e.storage.describe() + " now.")
                        + (refund > 0 ? " The bought materials it didn't use were refunded: <white>" + Msg.money(refund) + "</white>." : "")
                        + (plugin.settings().coreProtect ? " To take the build down too: <white>/co rollback u:"
                            + plugin.settings().coreProtectPrefix + e.name + " t:\\<time></white>." : ""));
            }
            default -> { }
        }
    }

    private boolean ready(CommandSender s, BuildOrder o) {
        if (o.plan != null) return true;
        if (o.loadError == null) {
            // Finished builds aren't kept in memory; read it again for whoever asks.
            plugin.blueprints().load(o.blueprint).whenComplete((bp, err) -> {
                if (err != null) o.loadError = Blueprints.reason(err);
                else if (o.plan == null) {
                    o.setPlan(bp);
                    site.remotearc.employees.build.Plans.rememberOrigin(plugin, o, bp);
                }
            });
            if (o.plan != null) return true;   // it was cached
        }
        Msg.error(s, o.loadError != null ? "The blueprint can't be loaded: " + o.loadError : "Still reading the blueprint; try again in a moment.");
        return false;
    }

    private void newBuild(CommandSender s, Employee e, String name, String c, boolean here) {
        if (!(s instanceof Player p)) {
            Msg.error(s, "Only players can place a build.");
            return;
        }
        if (e.build != null && e.build.started && !e.build.finished) {
            Msg.error(p, e.name + " is still building " + e.build.blueprint + ". Stop that first with <white>" + c + " cancel</white>.");
            return;
        }
        // NPC homes (homes/...) are built through /employees house, not by name: that also keeps players' private houses private.
        if (name.contains("/") || plugin.blueprints().resolve(name) == null) {
            Msg.error(p, "There's no blueprint called <white><name></white>. See <white>/employees blueprints</white>.", Msg.p("name", name));
            return;
        }
        Location at = p.getLocation();
        Msg.send(p, "<gray>Reading <white><name></white>...", Msg.p("name", name));
        plugin.blueprints().load(name).whenComplete((bp, err) -> {
            if (!mgr().all().contains(e)) return;
            if (err != null) {
                Msg.error(p, "Can't use that blueprint: <reason>", Msg.p("reason", Blueprints.reason(err)));
                return;
            }
            String fileName = plugin.blueprints().resolve(name).getName();
            BuildOrder o = new BuildOrder(fileName, at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ());
            o.setPlan(bp);
            // A blueprint saved standing on a slab of dirt and grass: leave that out and sink it so its ground is yours.
            boolean back = !here && bp.originWorld != null && Bukkit.getWorld(bp.originWorld) != null;
            if (back) {
                // Planned (or copied) in the game: back where it was, exactly as it was.
                o.world = bp.originWorld;
                o.x = bp.originX + bp.sx / 2;
                o.y = bp.originY;
                o.z = bp.originZ + bp.sz / 2;
                o.keepGround = true;
                o.planned = fileName.startsWith("plan-") || bp.format.startsWith("planned");
            }
            int ground = back ? 0 : o.groundLayers();
            o.y -= ground;
            if (e.build != null) {
                double refund = refund(e, e.build);
                if (refund > 0) Msg.send(p, "<gray>Refunded " + Msg.money(refund) + " for materials bought for " + e.build.blueprint + " and not used.");
            }
            e.build = o;
            placed(p, e, o, c);
            if (back) {
                p.sendMessage(Msg.parse("<gray>It was made in the game, so it's back where it was made" + (o.world.equals(p.getWorld().getName()) ? "" : " (in " + o.world + ")")
                        + ": anything already built there counts as done. <white><click:run_command:'" + c + " " + name + " here'>[Put it where I stand instead]</click>"));
            }
            if (ground > 0) {
                p.sendMessage(Msg.parse("<gray>It was saved standing on " + ground + " layer" + (ground == 1 ? "" : "s")
                        + " of ground (dirt, grass, stone): " + e.name + " leaves " + (ground == 1 ? "it" : "them") + " out, and it's sunk "
                        + ground + " so it sits on your ground. <white><click:run_command:'" + c + " ground keep'>[Build the ground too]</click>"));
            }
            Msg.send(p, "<gray>" + bp.format + ", " + bp.size() + ", " + bp.solidCount() + " blocks.");
            unknownNote(p, o, c);
        });
    }

    /** A shift direction as {forward, right, up}, or null. */
    private static int[] direction(String word) {
        return switch (word.toLowerCase(java.util.Locale.ROOT)) {
            case "forward", "forwards", "f" -> new int[]{1, 0, 0};
            case "back", "backward", "backwards", "b" -> new int[]{-1, 0, 0};
            case "right", "r" -> new int[]{0, 1, 0};
            case "left", "l" -> new int[]{0, -1, 0};
            case "up", "u" -> new int[]{0, 0, 1};
            case "down", "d" -> new int[]{0, 0, -1};
            default -> null;
        };
    }

    /** After placing, moving or turning a build: update the work area, show it, and say what's next. */
    void placed(CommandSender s, Employee e, BuildOrder o, String c) {
        WorkArea bounds = o.bounds();
        mgr().setArea(e, bounds.expand(2, 2));
        mgr().markDirty();
        if (!(s instanceof Player)) {
            Msg.send(s, "<green>" + o.blueprint + "</green> <gray>placed, " + o.describeTurn() + ", corner at " + bounds.minX() + " "
                    + bounds.minY() + " " + bounds.minZ() + ". Start it with <white>" + c + " start</white>.");
        }
        if (s instanceof Player p) {
            int n = BuildPreview.show(plugin, p, o);
            Msg.send(p, "<green>" + o.blueprint + "</green> <gray>is placed here, " + o.describeTurn() + " (" + bounds.width() + "x"
                    + o.plan.sy + "x" + bounds.length() + ", from " + bounds.minX() + " " + bounds.minY() + " " + bounds.minZ()
                    + "). Showing " + n + " ghost blocks to you.");
            p.sendMessage(Msg.parse("<gray>Wrong way round? <white><click:run_command:'" + c + " rotate'>[Turn 90°]</click> "
                    + "<click:run_command:'" + c + " flip'>[Mirror]</click> <click:suggest_command:'" + c + " shift down 1'>[Shift]</click> "
                    + "<click:run_command:'" + c + " move'>[Move here]</click></white>"));
            p.sendMessage(Msg.parse("<gray>Happy with it? <green><click:run_command:'" + c + " start'>[Start building]</click></green> "
                    + "<gray>or check <white><click:run_command:'" + c + " materials'>[materials]</click></white> first."));
        }
    }

    /** Puts the builder at the edge of the site if it's far away, and makes that its home. */
    private void bringToSite(Employee e, BuildOrder o, Player p) {
        WorkArea b = o.bounds();
        World w = Bukkit.getWorld(o.world);
        if (w == null) return;
        Location spot;
        if (p != null && p.getWorld().equals(w) && !b.expand(1, 1).contains(p.getLocation().getBlockX(), p.getLocation().getBlockY(), p.getLocation().getBlockZ())
                && p.getLocation().distanceSquared(new Location(w, b.centerX(), b.minY(), b.centerZ())) < 64 * 64) {
            spot = p.getLocation();
        } else {
            spot = new Location(w, b.minX() - 1.5, b.minY(), b.minZ() - 1.5);
            Vector v = new Pathfinder(w, org.bukkit.util.BoundingBox.of(spot.toVector(), 4, 8, 4)).snap(spot.getX(), spot.getY(), spot.getZ());
            if (v != null) spot = new Location(w, v.getX(), v.getY(), v.getZ());
        }
        if (e.location.getWorld() != w || e.location.distanceSquared(spot) > 48 * 48
                || !b.expand(8, 8).contains(e.location.getBlockX(), e.location.getBlockY(), e.location.getBlockZ())) {
            mgr().relocate(e, spot);
        } else {
            e.home = spot.clone();
        }
    }

    private void info(CommandSender s, Employee e, BuildOrder o, String c) {
        Msg.send(s, "<gold>" + e.name + "</gold> <gray>is building <white><bp></white>", Msg.p("bp", o.blueprint));
        if (o.plan == null) {
            s.sendMessage(Msg.parse("<gray>  " + (o.loadError != null ? "Can't load: " + Describe.esc(o.loadError) : "Reading the blueprint...")));
            return;
        }
        WorkArea b = o.bounds();
        s.sendMessage(Msg.parse("<gray>  Size " + b.width() + "x" + o.plan.sy + "x" + b.length() + ", " + o.describeTurn()
                + ", corner at " + b.minX() + " " + b.minY() + " " + b.minZ() + (o.world.equals("world") ? "" : " in " + Describe.esc(o.world))));
        String state = o.finished ? "finished" : !o.started ? "not started" : e.paused ? "paused" : "layer " + (o.layer + 1) + " of " + o.plan.sy;
        s.sendMessage(Msg.parse("<gray>  Progress: <white>" + state + "</white>. Clears the site: <white>" + (o.clearAir ? "yes" : "no")));
        s.sendMessage(Msg.parse("<gray>  Status: <white>" + Describe.esc(e.status)));
        if (!o.swaps.isEmpty()) s.sendMessage(Msg.parse("<gray>  Swaps: <white>" + o.swaps.size() + "</white> <click:run_command:'" + c + " swaps'>[list]</click>"));
        if (o.groundLayers() > 0) s.sendMessage(Msg.parse("<gray>  Ground under it (" + o.groundLayers() + " layers): <white>" + (o.keepGround ? "built" : "left out")));
        s.sendMessage(Msg.parse("<dark_gray>  " + c + " start | preview | rotate | flip | move | shift | materials | swap | pick | buy | ground keep|skip | clear on|off | cancel"));
    }

    private void materials(CommandSender s, Employee e, BuildOrder o, String c) {
        if (!ready(s, o)) return;
        List<BuildMaterials.Line> lines = BuildMaterials.lines(plugin, e, o);
        if (lines.isEmpty()) {
            Msg.send(s, "<green>Nothing left to place.");
            return;
        }
        int total = 0, short_ = 0;
        for (BuildMaterials.Line l : lines) {
            total += l.need();
            short_ += l.short_();
        }
        Msg.send(s, "<gray>" + e.name + " still needs <white>" + total + "</white> items for " + o.blueprint + "; <white>"
                + (short_ == 0 ? "everything is in storage" : short_ + " are missing") + "</white>:");
        int shown = 0;
        for (BuildMaterials.Line l : lines) {
            if (shown++ >= 16) {
                s.sendMessage(Msg.parse("<dark_gray>  ...and " + (lines.size() - 16) + " more kinds"));
                break;
            }
            String color = l.short_() == 0 ? "<green>" : l.have() + l.craft() > 0 ? "<yellow>" : "<red>";
            String swap = s instanceof Player && l.short_() > 0
                    ? " <click:run_command:'" + c + " swap " + Items.key(l.item()) + "'><hover:show_text:'<gray>Build it from something else'><aqua>[swap]</aqua></hover></click>" : "";
            s.sendMessage(Msg.parse("  " + color + l.need() + " " + Items.name(l.item()) + "</" + color.substring(1) + " <dark_gray>(have " + l.have()
                    + (l.craft() > 0 ? ", can craft " + l.craft() : "") + ")" + swap));
        }
        s.sendMessage(Msg.parse("<dark_gray>Counted: its backpack, " + Describe.esc(Supply.describe(plugin, e))
                + (o.stock.isEmpty() ? "" : " and materials bought for this build")));
        if (!o.swaps.isEmpty()) s.sendMessage(Msg.parse("<gray>" + o.swaps.size() + " swap(s): <white><click:run_command:'" + c + " swaps'>[list]</click>"));
        unknownNote(s, o, c);
        if (s instanceof Player p && short_ > 0) {
            BuildMaterials.Quote q = BuildMaterials.quote(plugin, e, o, p.getUniqueId(), lines);
            if (!q.buy().isEmpty()) {
                p.sendMessage(Msg.parse("<gray>Buy what's missing (" + q.count() + " items) for <gold>" + Msg.money(q.total()) + "</gold>: "
                        + "<green><click:run_command:'" + c + " buy'>[see what that buys]</click>"));
            }
        }
        if (s instanceof Player) s.sendMessage(Msg.parse("<gray>Or <white><click:run_command:'" + c + " pick'>[click a block in the build]</click></white> to swap it, "
                + "or <white><click:run_command:'" + c + " materials'>[open the list]</click></white>."));
    }

    /** Says which blocks of the blueprint this server doesn't have, and how to deal with them. */
    void unknownNote(CommandSender s, BuildOrder o, String c) {
        Map<String, Integer> unknowns = o.plan.unknownCounts();
        if (unknowns.isEmpty()) return;
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> en : unknowns.entrySet()) {
            Material r = o.replacements.get(en.getKey());
            parts.add(en.getKey().replace('_', ' ') + " (" + en.getValue() + (r != null ? ", built as " + Items.name(r) : "") + ")");
        }
        long open = unknowns.keySet().stream().filter(n -> !o.replacements.containsKey(n)).count();
        s.sendMessage(Msg.parse("<yellow>" + unknowns.size() + " kind(s) of block in it don't exist in Minecraft " + Bukkit.getMinecraftVersion()
                + (o.plan.newer ? " (the file was made in a newer version)" : " (newer versions or mods)") + ": <white>" + Describe.esc(String.join(", ", parts))
                + "</white>." + (open == 0 ? "" : " <yellow>They're left out unless you pick what to build instead: ")
                + "<white><click:run_command:'" + c + " materials'>[Pick replacements]</click> "
                + "<click:run_command:'" + c + " swap unknown auto'>[Use the closest match for each]</click>"));
    }

    /** The blueprint's own items (what swaps are keyed by), from what was typed: its name, or what it was swapped for. */
    private Material blueprintItem(CommandSender s, BuildOrder o, String typed) {
        Set<Material> own = EnumSet.noneOf(Material.class);
        for (var d : o.plan.palette) {
            Material k = BuildOrder.itemKey(d);
            if (k != null && !BuildOrder.unbuildable(d)) own.add(k);
        }
        String q = typed.toLowerCase(Locale.ROOT).replace("minecraft:", "").replace(' ', '_');
        Material exact = Material.matchMaterial(q);
        if (exact != null && own.contains(exact)) return exact;
        if (exact != null) for (Map.Entry<Material, Material> en : o.swaps.entrySet()) if (en.getValue() == exact) return en.getKey();
        List<Material> hits = own.stream().filter(m -> m.name().toLowerCase(Locale.ROOT).contains(q)).toList();
        if (hits.size() == 1) return hits.get(0);
        Msg.error(s, hits.isEmpty() ? "The blueprint has no block called " + Describe.esc(typed) + "."
                : "Which one? " + Describe.esc(String.join(", ", hits.stream().limit(10).map(Items::key).toList())));
        return null;
    }

    /** A block item from what was typed: an exact name, or the one name containing it. */
    private Material blockItem(CommandSender s, String typed, String retry) {
        String q = typed.toLowerCase(Locale.ROOT).replace("minecraft:", "").replace(' ', '_');
        Material exact = Material.matchMaterial(q);
        if (exact != null && exact.isBlock() && exact.isItem() && !exact.isAir()) return exact;
        List<Material> hits = new ArrayList<>();
        for (Material m : Material.values()) {
            if (!m.isLegacy() && m.isBlock() && m.isItem() && !m.isAir() && m.name().toLowerCase(Locale.ROOT).contains(q)) hits.add(m);
        }
        if (hits.size() == 1) return hits.get(0);
        if (hits.isEmpty()) {
            Msg.error(s, "No block's name contains " + Describe.esc(typed) + ".");
            return null;
        }
        StringBuilder sb = new StringBuilder("<gray>Which one? ");
        for (Material m : hits.stream().limit(24).toList()) {
            sb.append("<white><click:run_command:'").append(retry).append(Items.key(m)).append("'>[").append(Items.name(m)).append("]</click> ");
        }
        if (hits.size() > 24) sb.append("<dark_gray>and ").append(hits.size() - 24).append(" more: type more of the name");
        s.sendMessage(Msg.parse(sb.toString()));
        return null;
    }

    private void swap(CommandSender s, Employee e, BuildOrder o, String c, String[] args) {
        if (!ready(s, o)) return;
        if (args.length < 4) {
            if (s instanceof Player p) site.remotearc.employees.gui.MaterialsMenu.open(plugin, p, e);
            else Msg.error(s, "Usage: " + c + " swap \\<block> \\<new block | none | back>   or   " + c + " swap reset");
            return;
        }
        if (args[3].equalsIgnoreCase("reset")) {
            o.swaps.clear();
            o.turnedChanged();
            e.worker.interrupt();
            mgr().markDirty();
            Msg.send(s, "<green>" + e.name + " builds " + o.blueprint + " exactly as drawn again.");
            return;
        }
        String unknown = args[3].toLowerCase(Locale.ROOT).replace("minecraft:", "");
        Map<String, Integer> unknowns = o.plan.unknownCounts();
        if (unknown.equals("unknown") && args.length > 4 && args[4].equalsIgnoreCase("auto")) {
            Map<Material, Integer> have = BuildMaterials.have(plugin, e, o);
            List<String> done = new ArrayList<>();
            for (String n : unknowns.keySet()) {
                Material m = BuildOrder.closest(n, have);
                if (m == null) continue;
                o.replace(n, m);
                done.add(Describe.esc(n.replace('_', ' ')) + " → " + Items.name(m));
            }
            e.worker.interrupt();
            mgr().markDirty();
            Msg.send(s, done.isEmpty() ? "<yellow>Nothing close to those blocks here: pick replacements yourself."
                    : "<green>Using: <white>" + String.join(", ", done) + "</white>. <gray>Change any in <white><click:run_command:'" + c + " materials'>[Materials]</click></white>.");
            if (s instanceof Player p && BuildPreview.showing(p)) BuildPreview.show(plugin, p, o);
            return;
        }
        if (unknowns.containsKey(unknown)) {
            if (args.length < 5) {
                if (s instanceof Player p) site.remotearc.employees.gui.MaterialsMenu.openReplace(plugin, p, e, unknown);
                else Msg.error(s, "Usage: " + c + " swap " + unknown + " \\<block | none>");
                return;
            }
            String t = args[4].toLowerCase(Locale.ROOT);
            Material to = null;
            if (!List.of("none", "nothing", "skip", "leave", "air", "back").contains(t)) {
                to = blockItem(s, args[4], c + " swap " + unknown + " ");
                if (to == null) return;
            }
            o.replace(unknown, to);
            e.worker.interrupt();
            mgr().markDirty();
            Msg.send(s, to == null ? "<gray>" + Describe.esc(unknown.replace('_', ' ')) + " is left out again."
                    : "<green>" + e.name + " builds <white>" + Items.name(to) + "</white> where the blueprint has " + Describe.esc(unknown.replace('_', ' '))
                        + " (" + unknowns.get(unknown) + " blocks), keeping its facing and shape where it can.");
            if (s instanceof Player p && BuildPreview.showing(p)) BuildPreview.show(plugin, p, o);
            return;
        }
        Material from = blueprintItem(s, o, args[3]);
        if (from == null) return;
        if (args.length < 5) {
            if (s instanceof Player p) site.remotearc.employees.gui.MaterialsMenu.openSwap(plugin, p, e, from);
            else Msg.error(s, "Usage: " + c + " swap " + Items.key(from) + " \\<new block | none | back>");
            return;
        }
        String t = args[4].toLowerCase(Locale.ROOT);
        Material to;
        if (List.of("none", "nothing", "skip", "leave", "air", "remove").contains(t)) to = Material.AIR;
        else if (List.of("back", "undo", "original", "reset").contains(t)) to = null;
        else {
            to = blockItem(s, args[4], c + " swap " + Items.key(from) + " ");
            if (to == null) return;
        }
        o.swap(from, to);
        e.worker.interrupt();
        mgr().markDirty();
        int n = 0;
        Map<Material, Integer> left = o.remaining(Bukkit.getWorld(o.world));
        if (to != null && to != Material.AIR) n = left.getOrDefault(to, 0);
        Msg.send(s, to == null ? "<green>" + Items.name(from) + " is built as drawn again."
                : to == Material.AIR ? "<green>" + e.name + " leaves out the " + Items.name(from) + " (blocks already placed stay)."
                : "<green>" + e.name + " uses <white>" + Items.name(to) + "</white> wherever the blueprint has " + Items.name(from)
                    + (n > 0 ? " (" + n + " still to place)" : "") + ". <gray>Facing and shape carry over where the new block has them."
                    + " <white><click:run_command:'" + c + " swap " + Items.key(from) + " back'>[Undo]</click>");
        if (s instanceof Player p && BuildPreview.showing(p)) BuildPreview.show(plugin, p, o);
    }

    private void swaps(CommandSender s, BuildOrder o, String c) {
        if (!o.skipped.isEmpty()) s.sendMessage(Msg.parse("<gray>" + o.skipped.size() + " single block(s) left out. <white><click:run_command:'" + c + " skip reset'>[Put them back]</click>"));
        if (o.swaps.isEmpty()) {
            Msg.send(s, "<gray>No swaps: it's built as drawn. Swap a block with <white>" + c + " swap \\<block> \\<new block></white>.");
            return;
        }
        Msg.send(s, "<gray>Swapped in " + o.blueprint + ":");
        for (Map.Entry<Material, Material> en : o.swaps.entrySet()) {
            s.sendMessage(Msg.parse("  <white>" + Items.name(en.getKey()) + "</white> <gray>→ <white>"
                    + (en.getValue() == Material.AIR ? "left out" : Items.name(en.getValue())) + "</white> "
                    + "<yellow><click:run_command:'" + c + " swap " + Items.key(en.getKey()) + " back'>[undo]</click>"));
        }
        s.sendMessage(Msg.parse("<gray><click:run_command:'" + c + " swap reset'>[Undo them all]</click>"));
    }

    /** skip <x> <y> <z> [back]: leaves the one block there out of the build (or puts it back); skip reset puts them all back. */
    private void skip(CommandSender s, Employee e, BuildOrder o, String c, String[] args) {
        if (!ready(s, o)) return;
        if (args.length > 3 && args[3].equalsIgnoreCase("reset")) {
            int n = o.skipped.size();
            o.skipped.clear();
            e.worker.interrupt();
            mgr().markDirty();
            Msg.send(s, "<green>Put back the " + n + " single block(s) left out of " + o.blueprint + ".");
            if (s instanceof Player p && BuildPreview.showing(p)) BuildPreview.show(plugin, p, o);
            return;
        }
        if (args.length < 6) {
            Msg.error(s, "Usage: " + c + " skip \\<x> \\<y> \\<z> [back]   or   " + c + " skip reset. Easier: " + c + " pick, then click the block.");
            return;
        }
        int x, y, z;
        try {
            x = Integer.parseInt(args[3]);
            y = Integer.parseInt(args[4]);
            z = Integer.parseInt(args[5]);
        } catch (NumberFormatException ex) {
            Msg.error(s, "Usage: " + c + " skip \\<x> \\<y> \\<z> [back]");
            return;
        }
        boolean back = args.length > 6 && args[6].equalsIgnoreCase("back");
        if (!o.skip(x, y, z, !back)) {
            Msg.error(s, x + " " + y + " " + z + " isn't part of the build.");
            return;
        }
        e.worker.interrupt();
        mgr().markDirty();
        if (s instanceof Player p) {
            if (back) BuildPreview.show(plugin, p, o);
            else BuildPreview.forget(p, new BlockPos(x, y, z));
        }
        Msg.send(s, back ? "<green>The block at " + x + " " + y + " " + z + " is built again."
                : "<green>Left out the block at " + x + " " + y + " " + z + ". <white><click:run_command:'" + c + " skip " + x + " " + y + " " + z
                    + " back'>[Undo]</click></white> <gray>(" + o.skipped.size() + " left out; <click:run_command:'" + c + " skip reset'>[put them all back]</click>)");
    }

    private void ground(CommandSender s, Employee e, BuildOrder o, String c, String[] args) {
        if (!ready(s, o)) return;
        int n = o.groundLayers();
        if (n == 0) {
            Msg.send(s, "<gray>" + o.blueprint + " doesn't stand on a slab of ground: every block in it is built.");
            return;
        }
        boolean keep = args.length > 3 ? List.of("keep", "on", "build", "yes").contains(args[3].toLowerCase(Locale.ROOT)) : !o.keepGround;
        o.keepGround = keep;
        e.worker.interrupt();
        mgr().markDirty();
        Msg.send(s, keep ? "<green>" + e.name + " builds the " + n + " layer" + (n == 1 ? "" : "s") + " of ground under " + o.blueprint
                        + " too. <gray>(It may need shifting up " + n + " to stand on top of your ground: <white><click:suggest_command:'"
                        + c + " shift 0 " + n + " 0'>[Shift up]</click></white>)"
                : "<green>" + e.name + " leaves out the dirt, grass and stone in the bottom " + n + " layer" + (n == 1 ? "" : "s")
                        + " of " + o.blueprint + " (and the grass growing on it); anything else in them is still built.");
        if (s instanceof Player p && BuildPreview.showing(p)) BuildPreview.show(plugin, p, o);
    }

    private void buy(CommandSender s, Employee e, BuildOrder o, String c, String[] args) {
        if (!(s instanceof Player p)) {
            Msg.error(s, "Only players can buy materials.");
            return;
        }
        if (!ready(s, o)) return;
        if (args.length > 3 && List.of("undo", "refund", "return").contains(args[3].toLowerCase(Locale.ROOT))) {
            if (o.stock.isEmpty()) {
                Msg.send(p, "<gray>" + e.name + " has none of the materials bought for this build left.");
                return;
            }
            double v = refund(e, o);
            mgr().markDirty();
            e.worker.interrupt();
            Msg.send(p, "<green>Returned the unused bought materials; " + Msg.money(v) + " went back to " + e.ownerName() + ".");
            return;
        }
        List<BuildMaterials.Line> lines = BuildMaterials.lines(plugin, e, o);
        BuildMaterials.Quote q = BuildMaterials.quote(plugin, e, o, p.getUniqueId(), lines);
        if (args.length > 3 && args[3].equalsIgnoreCase("confirm")) {
            double expected = Double.MAX_VALUE;
            try {
                if (args.length > 4) expected = Double.parseDouble(args[4]);
            } catch (NumberFormatException ignored) {
                // no price given: take today's
            }
            if (q.total() > expected + 0.01) {
                Msg.error(p, "The price went up to " + Msg.money(q.total()) + ". <white><click:run_command:'" + c + " buy'>[See it again]</click>");
                return;
            }
            String err = BuildMaterials.buy(plugin, p, e, o, q);
            if (err != null) {
                Msg.error(p, "<msg>", Msg.p("msg", err));
                return;
            }
            e.worker.interrupt();
            var econ = plugin.economy();
            Msg.send(p, "<green>Bought " + q.count() + " items for " + Msg.money(q.total()) + "; " + e.name + " has them now."
                    + (econ == null ? "" : " <gray>Balance: <white>" + Msg.money(econ.getBalance(p)))
                    + " <gray>Whatever it doesn't use is refunded when the build ends. <white><click:run_command:'" + c + " buy undo'>[Undo]</click>");
            return;
        }
        if (q.buy().isEmpty() && q.notSold().isEmpty() && q.locked().isEmpty()) {
            int crafts = lines.stream().mapToInt(BuildMaterials.Line::craft).sum();
            Msg.send(p, "<green>Nothing is missing: " + e.name + " can get everything from storage"
                    + (crafts > 0 ? " or craft it from what's spare (" + crafts + " items)" : "") + ".");
            if (!o.stock.isEmpty()) p.sendMessage(Msg.parse("<gray>Bought materials it still has can be returned: <white><click:run_command:'" + c + " buy undo'>[Undo the purchase]</click>"));
            return;
        }
        if (!q.buy().isEmpty()) {
            Msg.send(p, "<gray>Buying what " + e.name + " is missing for " + o.blueprint + " costs <gold>" + Msg.money(q.total()) + "</gold>:");
            int shown = 0;
            for (Map.Entry<Material, Integer> en : q.buy().entrySet()) {
                if (shown++ >= 12) {
                    p.sendMessage(Msg.parse("<dark_gray>  ...and " + (q.buy().size() - 12) + " more kinds"));
                    break;
                }
                double each = plugin.market().buyPrice(en.getKey());
                p.sendMessage(Msg.parse("  <white>" + en.getValue() + " " + Items.name(en.getKey()) + "</white> <dark_gray>"
                        + Msg.money(each) + " each = </dark_gray><gold>" + Msg.money(each * en.getValue())));
            }
        }
        if (!q.crafted().isEmpty()) {
            p.sendMessage(Msg.parse("<gray>Merchants don't sell " + Describe.esc(String.join(", ", q.crafted().stream().map(Items::name).toList()))
                    + ": that's what " + e.name + " crafts them from."));
        }
        int crafts = lines.stream().mapToInt(BuildMaterials.Line::craft).sum();
        if (crafts > 0) p.sendMessage(Msg.parse("<gray>Not counted: " + crafts + " items it crafts from spare materials in storage."));
        if (!q.notSold().isEmpty()) {
            p.sendMessage(Msg.parse("<yellow>Merchants don't sell: <white>" + Describe.esc(String.join(", ", q.notSold().stream().map(Items::name).toList()))
                    + "</white>. <gray>Swap them for something else: <white><click:run_command:'" + c + " swap " + Items.key(q.notSold().get(0)) + "'>[swap]</click>"));
        }
        if (!q.locked().isEmpty()) {
            p.sendMessage(Msg.parse("<yellow>Not discovered yet (find or pick one up first, as with /shop): <white>"
                    + Describe.esc(String.join(", ", q.locked().stream().map(Items::name).toList()))));
        }
        if (!q.buy().isEmpty()) {
            p.sendMessage(Msg.parse("<green><click:run_command:'" + c + " buy confirm " + String.format(Locale.ROOT, "%.2f", q.total()) + "'>"
                    + "[Buy them for " + Msg.money(q.total()) + "]</click> <gray>They go straight to " + e.name + " for this build."));
        }
        if (!o.stock.isEmpty()) p.sendMessage(Msg.parse("<gray>Already bought for it: " + o.stock.size() + " kind(s). <white><click:run_command:'" + c + " buy undo'>[Undo the purchase]</click>"));
    }

    /** Gives the owner back what unused bought materials cost; returns the amount. */
    private double refund(Employee e, BuildOrder o) {
        double v = o.takeStockValue();
        var econ = plugin.economy();
        if (v <= 0 || econ == null) return 0;
        econ.depositPlayer(Bukkit.getOfflinePlayer(e.owner), v);
        return v;
    }

    // ── tab completion ────────────────────────────────────────────────────

    List<String> complete(CommandSender s, String[] args) {
        String sub = args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (sub.equals("blueprints")) {
            if (args.length == 2) out.addAll(List.of("list", "upload", "download"));
            return out;
        }
        if (args.length == 3) {
            switch (sub) {
                case "filter" -> out.addAll(List.of("list", "add", "remove", "clear", "reset"));
                case "supply" -> out.addAll(List.of("list", "add", "remove", "team"));
                case "herd" -> out.addAll(List.of("6", "10", "16", "24"));
                case "build" -> {
                    out.addAll(BUILD_ACTIONS);
                    for (String n : plugin.blueprints().list()) {
                        int dot = n.lastIndexOf('.');
                        out.add(dot > 0 ? n.substring(0, dot) : n);
                    }
                }
                default -> { }
            }
        } else if (args.length >= 4) {
            String mode = args[2].toLowerCase(Locale.ROOT);
            if (sub.equals("filter") && (mode.equals("add") || mode.equals("remove"))) {
                out.add("ores");
                for (String o : List.of("coal_ore", "iron_ore", "copper_ore", "gold_ore", "redstone_ore", "lapis_ore", "diamond_ore",
                        "emerald_ore", "ancient_debris", "nether_quartz_ore", "raw_iron", "raw_gold", "raw_copper", "cobblestone",
                        "cobbled_deepslate", "dirt", "gravel", "sand", "#logs", "beef", "porkchop", "chicken", "mutton", "potato", "kelp")) out.add(o);
            } else if (sub.equals("supply") && args.length == 4) {
                if (mode.equals("add") || mode.equals("remove")) out.addAll(List.of("chest", String.valueOf(plugin.settings().vcFirstChest)));
                if (mode.equals("team")) out.addAll(List.of("on", "off"));
            } else if (sub.equals("build") && args.length == 4) {
                if (mode.equals("rotate")) out.addAll(List.of("90", "180", "270", "-90"));
                if (mode.equals("clear")) out.addAll(List.of("on", "off"));
                if (mode.equals("shift")) out.addAll(List.of("forward", "back", "left", "right", "up", "down"));
                if (mode.equals("ground")) out.addAll(List.of("keep", "skip"));
                if (mode.equals("buy")) out.addAll(List.of("confirm", "undo"));
                if (mode.equals("materials")) out.add("chat");
                if (mode.equals("preview")) out.addAll(List.of("off", "digging"));
                if (mode.equals("around")) out.addAll(List.of("clear", "leave"));
                if (mode.equals("follow")) out.addAll(List.of("on", "off", "now"));
                if (mode.equals("skip")) out.add("reset");
                if (mode.equals("swap")) {
                    out.add("reset");
                    Employee e = plugin.manager().find(s, args[1]);
                    if (e != null && e.build != null && e.build.plan != null) {
                        Set<String> own = new java.util.TreeSet<>();
                        for (var d : e.build.plan.palette) {
                            Material k = BuildOrder.itemKey(d);
                            if (k != null && !BuildOrder.unbuildable(d)) own.add(Items.key(k));
                        }
                        out.addAll(own);
                        out.addAll(e.build.plan.unknownCounts().keySet());
                        if (!e.build.plan.unknownStates.isEmpty()) out.add("unknown");
                    }
                }
            } else if (sub.equals("build") && mode.equals("shift") && args.length <= 6) {
                out.add(direction(args[3]) != null ? "1" : "0");
            } else if (sub.equals("build") && mode.equals("swap") && args.length == 5) {
                out.addAll(List.of("none", "back"));
                if (args[3].equalsIgnoreCase("unknown")) out.add("auto");
                String q = args[4].toLowerCase(Locale.ROOT);
                for (Material m : Material.values()) {
                    if (!m.isLegacy() && m.isBlock() && m.isItem() && !m.isAir() && Items.key(m).contains(q)) out.add(Items.key(m));
                }
            }
        }
        return out;
    }
}
