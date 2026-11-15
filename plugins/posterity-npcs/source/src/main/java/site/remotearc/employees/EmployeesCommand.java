package site.remotearc.employees;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import site.remotearc.employees.gui.AreaPreview;
import site.remotearc.employees.gui.Describe;
import site.remotearc.employees.gui.EmployeeListMenu;
import site.remotearc.employees.gui.EmployeeMenu;
import site.remotearc.employees.job.JobType;
import site.remotearc.employees.storage.StorageTarget;
import site.remotearc.employees.build.BuildOrder;
import site.remotearc.employees.util.BlockPos;
import site.remotearc.employees.util.Msg;
import site.remotearc.employees.util.WorkArea;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** /employees (alias /emp): every player-facing action, also reachable from the menus. */
final class EmployeesCommand implements TabExecutor {

    private static final Pattern MC_NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final List<String> SUBS = List.of("plan", "help", "hire", "list", "info", "fire", "rename", "skin", "area",
            "storage", "pause", "resume", "move", "tp", "pay", "jobs", "filter", "supply", "herd", "build", "blueprints", "board", "chests",
"sell", "market", "zone", "toggle", "follow", "stay", "roam", "avatar", "avatars", "trades", "craft", "presets", "away", "report", "profit", "route", "repair", "trust", "untrust", "manage", "pauseall", "resumeall", "toggleall", "link", "unlink", "debug", "stalls", "house", "home", "homes");
    /** Subcommands that don't name an employee. */
    private static final List<String> GLOBAL = List.of("plan", "help", "hire", "list", "jobs", "blueprints", "board", "chests", "market",
            "pauseall", "resumeall", "toggleall", "link", "unlink", "menu", "avatars", "presets", "report", "profit", "trust",
            "untrust", "manage", "homes");
    private static final List<String> ADMIN_SUBS = List.of("all", "reload", "config", "unlock", "lock");

    private record Corners(UUID employee, BlockPos a, BlockPos b, String world) {}

    private final ArcEmployees plugin;
    private final JobCommands jobs;
    private final Map<UUID, Corners> corners = new HashMap<>();

    EmployeesCommand(ArcEmployees plugin) {
        this.plugin = plugin;
        this.jobs = new JobCommands(plugin);
    }

    private EmployeeManager mgr() {
        return plugin.manager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, String @NotNull [] args) {
        if (args.length == 0) {
            if (s instanceof Player p) new EmployeeListMenu(plugin, p.getUniqueId(), 0).open(p);
            else help(s, label);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help" -> help(s, label);
            case "hire" -> hire(s, args);
            case "list" -> list(s);
            case "jobs" -> jobs(s);
            case "blueprints", "blueprint", "schematics" -> jobs.blueprints(s, label, args);
            case "board", "boards" -> jobs.board(s, label, args);
            case "chests", "chest" -> jobs.chests(s, label, args);
            case "market", "shop", "merchant" -> jobs.market(s, label, args);
            case "pauseall", "resumeall", "toggleall" -> pauseAll(s, sub);
            case "plan" -> {
                if (!(s instanceof Player p)) return true;
                String what = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
                if (what.equals("done") || what.equals("stop")) {
                    String why = site.remotearc.employees.build.PlanMode.done(p, args.length > 2 ? args[2] : null);
                    if (why != null) Msg.error(p, why);
                }
                else if (what.equals("cancel")) site.remotearc.employees.build.PlanMode.cancel(p);
                else if (what.equals("hand")) {
                    String m = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "";
                    Boolean real = site.remotearc.employees.build.PlanMode.handReal(p, m.equals("real") ? Boolean.TRUE : m.equals("ghost") ? Boolean.FALSE : null);
                    if (real == null) Msg.error(p, "You're not planning.");
                    else Msg.send(p, real ? "<green>Blocks you place are real</green> <gray>(they use your items and stay) and part of the plan. WorldEdit still only plans."
                            : "<aqua>Blocks you place are ghosts</aqua> <gray>now, like WorldEdit's: free, and only in the plan.");
                }
                else if (what.isEmpty()) Msg.send(p, site.remotearc.employees.build.PlanMode.planning(p)
                        ? "<gray>You're planning. <white>/" + label + " plan done</white> keeps it, <white>/" + label + " plan cancel</white> drops the changes."
                        : "<gray>Plan a build in the world with WorldEdit or by hand: <white>/" + label + " plan \\<builder></white>");
                else if (args.length > 2 && args[2].equalsIgnoreCase("new")) {
                    return onCommand(s, cmd, label, args.length > 3 ? new String[]{"build", args[1], "plan", "new", args[3]}
                            : new String[]{"build", args[1], "plan", "new"});
                } else return onCommand(s, cmd, label, args.length > 2 ? new String[]{"build", args[1], "plan", "name", args[2]}
                        : new String[]{"build", args[1], "plan"});
            }
            case "presets", "preset" -> jobs.presets(s, label, args);
            case "homes" -> homes(s, label, args);
            case "trust", "untrust" -> {
                if (!(s instanceof Player p)) return true;
                var managers = plugin.managers();
                if (args.length < 2) {
                    var list = managers.of(p.getUniqueId()).stream().map(id -> {
                        String n = Bukkit.getOfflinePlayer(id).getName();
                        return n == null ? id.toString().substring(0, 8) : n;
                    }).toList();
                    Msg.send(p, list.isEmpty() ? "<gray>Nobody else manages your employees. <white>/" + label + " trust \\<player></white> lets a friend."
                            : "<gray>Managing your employees too: <white>" + String.join(", ", list) + "</white>. <dark_gray>/" + label + " untrust \\<player>");
                    return true;
                }
                org.bukkit.OfflinePlayer other = Bukkit.getOfflinePlayerIfCached(args[1]);
                if (other == null) other = Bukkit.getPlayerExact(args[1]);
                if (other == null || other.getUniqueId().equals(p.getUniqueId())) {
                    Msg.error(p, "No player called " + args[1] + " has been on this server.");
                    return true;
                }
                String who = other.getName() == null ? args[1] : other.getName();
                if (sub.equals("trust")) {
                    managers.add(p.getUniqueId(), other.getUniqueId());
                    Msg.send(p, "<green>" + who + " can now manage all your employees: change their work, routes, builds and settings, pause"
                            + " or fire them. Wages still come from your balance. <gray>Undo: /" + label + " untrust " + who);
                    if (other.getPlayer() != null) Msg.send(other.getPlayer(), "<green>" + p.getName() + " lets you manage their employees: <white>/"
                            + label + " manage " + p.getName());
                } else {
                    Msg.send(p, managers.remove(p.getUniqueId(), other.getUniqueId())
                            ? "<gray>" + who + " no longer manages your employees." : "<gray>" + who + " wasn't managing your employees.");
                }
            }
            case "manage" -> {
                if (!(s instanceof Player p)) return true;
                var owners = plugin.managers().ownersFor(p.getUniqueId());
                UUID owner = null;
                if (args.length > 1) {
                    for (UUID o : owners) if (args[1].equalsIgnoreCase(Bukkit.getOfflinePlayer(o).getName())) owner = o;
                } else if (owners.size() == 1) {
                    owner = owners.getFirst();
                }
                if (owner == null) {
                    Msg.error(p, owners.isEmpty() ? "Nobody has let you manage their employees (they use /" + label + " trust " + p.getName() + ")."
                            : "Whose? " + String.join(", ", owners.stream().map(o -> String.valueOf(Bukkit.getOfflinePlayer(o).getName())).toList()));
                    return true;
                }
                new EmployeeListMenu(plugin, owner, null, 0, !owner.equals(p.getUniqueId())).open(p);
            }
            case "report" -> {
                if (!(s instanceof Player p)) return true;
                int hours = 24;
                try {
                    if (args.length > 1) hours = Math.max(1, Math.min(24 * 30, Integer.parseInt(args[1])));
                } catch (NumberFormatException ex) {
                    Msg.error(s, "Usage: /" + label + " report [hours]");
                    return true;
                }
                plugin.reports().report(s, p.getUniqueId(), hours);
            }
            case "profit" -> {
                if (!(s instanceof Player p)) return true;
                int days = 7;
                try {
                    if (args.length > 1) days = Math.max(1, Math.min(30, Integer.parseInt(args[1])));
                } catch (NumberFormatException ex) {
                    Msg.error(s, "Usage: /" + label + " profit [days]");
                    return true;
                }
                plugin.reports().profit(s, p.getUniqueId(), days);
            }
            case "avatars" -> {
                if (!(s instanceof Player p)) return true;
                List<Employee> mine = mgr().ownedBy(p.getUniqueId());
                if (args.length > 1 && args[1].equalsIgnoreCase("remove")) {
                    int n = 0;
                    for (Employee e : mine) if (plugin.avatars().remove(e.id)) n++;
                    Msg.send(p, "<gray>Removed " + n + " avatar(s).");
                    return true;
                }
                // A row to your right, 1.5 blocks apart, all facing you.
                Location start = p.getLocation();
                double yaw = Math.toRadians(start.getYaw());
                double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
                int i = 0;
                for (Employee e : mine) {
                    Location at = start.clone().add(rx * 1.5 * i, 0, rz * 1.5 * i);
                    at = standOn(at);
                    at.setYaw(start.getYaw() + 180);
                    plugin.avatars().place(e, at);
                    i++;
                }
                Msg.send(p, "<green>Placed " + i + " avatar(s) in a row to your right. <gray>Right-click one for its menu,"
                        + " shift-right-click to pause or resume. Move one with <white>/" + label + " avatar \\<name></white>.");
            }
            case "menu" -> {
                if (s instanceof Player p) new EmployeeListMenu(plugin, p.getUniqueId(), 0).open(p);
            }
            case "link" -> {
                if (!(s instanceof Player p)) return true;
                if (args.length < 2) {
                    Msg.error(p, "Usage: /" + label + " link \\<action>, e.g. <white>toggleall</white>, <white>pauseall</white>, <white>chests</white>,"
                            + " <white>toggle Lumbo</white>, or any /" + label + " command. Or use the control panel in /" + label + ".");
                    return true;
                }
                String action = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
                plugin.links().arm(p, action);
                Msg.send(p, "<yellow>Now right-click the button, lever or block to link to <white>"
                        + site.remotearc.employees.board.Links.describe(action) + "</white>.");
            }
            case "unlink" -> {
                if (!(s instanceof Player p)) return true;
                plugin.links().arm(p, null);
                Msg.send(p, "<yellow>Now right-click the linked block to unlink it.");
            }
            case "all" -> {
                if (!admin(s)) return true;
                if (s instanceof Player p) new EmployeeListMenu(plugin, null, 0).open(p);
                else list(s);
            }
            case "unlock", "lock" -> {
                if (!admin(s)) return true;
                discovery(s, label, sub.equals("unlock"), args);
            }
            case "reload" -> {
                if (!admin(s)) return true;
                plugin.reloadSettings();
                Msg.send(s, "<green>Reloaded config.yml.");
            }
            case "config", "setting", "settings" -> {
                if (!admin(s)) return true;
                config(s, label, args);
            }
            default -> {
                if (!SUBS.contains(sub)) {
                    Msg.error(s, "Unknown subcommand. Try <white>/" + label + " help");
                    return true;
                }
                if (args.length < 2) {
                    Msg.error(s, "Which employee? <white>/" + label + " " + sub + " <name>");
                    return true;
                }
                Employee e = mgr().find(s, args[1]);
                if (e == null || !mgr().canManage(s, e)) {
                    Msg.error(s, "You have no employee called <white><name></white>.", Msg.p("name", args[1]));
                    return true;
                }
                switch (sub) {
                    case "info" -> info(s, e);
                    case "fire" -> fire(s, e, label, args);
                    case "rename" -> rename(s, e, args);
                    case "skin" -> skin(s, e, args);
                    case "house", "home" -> house(s, e, label, args);
                    case "area" -> area(s, e, label, args);
                    case "storage" -> storage(s, e, label, args);
                    case "pause" -> {
                        mgr().setPaused(e, true);
                        Msg.send(s, "<gray>" + e.name + " is paused. Their paid time is kept for when they resume.");
                    }
                    case "resume" -> {
                        mgr().setPaused(e, false);
                        Msg.send(s, "<green>" + e.name + " is back at work.");
                    }
                    case "move" -> move(s, e);
                    case "tp" -> {
                        if (s instanceof Player p) p.teleport(e.location.clone().add(0, 0.1, 0));
                    }
                    case "pay" -> pay(s, e);
                    case "stalls", "stall" -> jobs.stalls(s, e, label, args);
                    case "debug" -> {
                        e.debug = args.length > 2 ? args[2].equalsIgnoreCase("on") : !e.debug;
                        mgr().markDirty();
                        Msg.send(s, "<gray>Debug logging for " + e.name + " is " + (e.debug ? "on (and stays on over restarts): every task it finishes goes to the server console." : "off."));
                    }
                    case "filter" -> jobs.filter(s, e, label, args);
                    case "supply" -> jobs.supply(s, e, label, args);
                    case "herd" -> jobs.herd(s, e, label, args);
                    case "build" -> jobs.build(s, e, label, args);
                    case "sell" -> jobs.sell(s, e, label, args);
                    case "zone", "zones" -> jobs.zone(s, e, label, args);
                    case "route", "routes" -> jobs.route(s, e, label, args);
                    case "repair" -> jobs.repair(s, e, label, args);
                    case "craft", "crafting" -> jobs.craft(s, e, label, args);
                    case "away" -> {
                        String m = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : (e.worksAway ? "rest" : "work");
                        if (!m.equals("work") && !m.equals("rest")) {
                            Msg.error(s, "Usage: /" + label + " away " + e.name + " work | rest");
                            return true;
                        }
                        e.worksAway = m.equals("work");
                        mgr().markDirty();
                        Msg.send(s, e.worksAway
                                ? "<yellow>" + e.name + " keeps working while nobody's on the server, and is paid every day."
                                : "<green>" + e.name + " rests while nobody's on the server, and isn't paid for days nobody comes on.");
                    }
                    case "trades" -> {
                        if (e.job != JobType.TRADER) {
                            Msg.error(s, "Only villager traders make trades.");
                            return true;
                        }
                        if (s instanceof Player p) new site.remotearc.employees.gui.TradesMenu(plugin, e, null).open(p);
                    }
                    case "avatar" -> {
                        if (!(s instanceof Player p)) return true;
                        if (args.length > 2 && args[2].equalsIgnoreCase("remove")) {
                            Msg.send(p, plugin.avatars().remove(e.id) ? "<gray>" + e.name + "'s avatar is gone." : "<gray>" + e.name + " has no avatar.");
                            return true;
                        }
                        Location at = standOn(p.getLocation());
                        at.setYaw(p.getLocation().getYaw());
                        plugin.avatars().place(e, at);
                        Msg.send(p, "<green>" + e.name + "'s avatar stands here now. <gray>Right-click it for their menu, shift-right-click"
                                + " to pause or resume. Step back to see it.");
                    }
                    case "roam" -> {
                        if (e.job != JobType.MINER) {
                            Msg.error(s, "Only miners roam.");
                            return true;
                        }
                        String m = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : (e.roam ? "off" : "on");
                        if (m.equals("back") || m.equals("home")) {
                            if (e.roamBase != null) {
                                WorkArea base = e.roamBase;
                                e.roamStep = 0;
                                mgr().setArea(e, base);
                                Msg.send(s, "<green>" + e.name + " is going back to where they started: " + base.describe() + ".");
                            } else {
                                Msg.send(s, "<gray>" + e.name + " hasn't moved on from their first area.");
                            }
                            return true;
                        }
                        e.roam = !m.equals("off");
                        if (e.roam && e.roamBase == null) e.roamBase = e.area;
                        mgr().markDirty();
                        Msg.send(s, e.roam ? "<green>" + e.name + " will move on to new ground (up to " + plugin.settings().minerRoamDistance
                                + " blocks from where they started) once their area is mined out."
                                : "<gray>" + e.name + " stays in their current area.");
                    }
                    case "follow", "stay" -> {
                        if (e.job != JobType.BODYGUARD) {
                            Msg.error(s, "Only bodyguards follow you around.");
                            return true;
                        }
                        e.following = sub.equals("follow");
                        e.worker.interrupt();
                        mgr().markDirty();
                        Msg.send(s, e.following ? "<green>" + e.name + " is following you." : "<gray>" + e.name + " is heading back to their post"
                                + (e.area == null ? "." : " (" + e.area.describe() + ")."));
                    }
                    case "toggle" -> {
                        mgr().setPaused(e, !e.paused);
                        Msg.send(s, e.paused ? "<gray>" + e.name + " is paused." : "<green>" + e.name + " is back at work.");
                    }
                    default -> {
                        if (s instanceof Player p) new EmployeeMenu(plugin, e, null).open(p);
                    }
                }
            }
        }
        return true;
    }

    /** Pauses or resumes every employee the player owns (toggleall: pause if any are working, else resume). */
    private void pauseAll(CommandSender s, String sub) {
        if (!(s instanceof Player p)) {
            Msg.error(s, "Only players have employees.");
            return;
        }
        List<Employee> mine = mgr().ownedBy(p.getUniqueId());
        boolean pause = switch (sub) {
            case "pauseall" -> true;
            case "resumeall" -> false;
            default -> mine.stream().anyMatch(e -> !e.paused);
        };
        int n = 0;
        for (Employee e : mine) {
            if (e.paused != pause) {
                mgr().setPaused(e, pause);
                n++;
            }
        }
        Msg.send(p, pause ? "<gray>Paused " + n + " employee(s). Their paid time is kept for when they resume."
                : "<green>" + n + " employee(s) back at work.");
    }

    /**
     * /employees config <setting> [value]: shows or changes any setting in
     * config.yml (saved, with its comments, and applied straight away).
     */
    private void config(CommandSender s, String label, String[] args) {
        var cfg = plugin.getConfig();
        if (args.length < 2) {
            Msg.send(s, "<gray>Usage: <white>/" + label + " config \\<setting> [new value]</white>, e.g. <white>/" + label
                    + " config market.max-income-per-day 500</white>. Tab completes the setting names.");
            return;
        }
        String path = args[1];
        Object now = cfg.get(path);
        boolean known = cfg.isSet(path) || (cfg.getDefaults() != null && cfg.getDefaults().isSet(path));
        if (!known || cfg.isConfigurationSection(path)) {
            Msg.error(s, "There's no setting called " + path + (cfg.isConfigurationSection(path)
                    ? " (that's a group: " + String.join(", ", cfg.getConfigurationSection(path).getKeys(false)) + ")" : "") + ".");
            return;
        }
        if (args.length < 3) {
            Msg.send(s, "<gray>" + path + " = <white>" + Msg.plain(String.valueOf(now)));
            return;
        }
        String raw = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
        Object value;
        try {
            if (now instanceof Boolean) {
                String r = raw.toLowerCase(Locale.ROOT);
                if (!List.of("true", "false", "on", "off", "yes", "no").contains(r)) throw new IllegalArgumentException("true or false");
                value = r.equals("true") || r.equals("on") || r.equals("yes");
            } else if (now instanceof Integer || now instanceof Long) {
                double d = Double.parseDouble(raw.replace("$", "").replace(",", ""));
                value = d == Math.rint(d) ? (Object) (long) d : (Object) d;
                if (value instanceof Long l && l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) value = (int) (long) l;
            } else if (now instanceof Number) {
                value = Double.parseDouble(raw.replace("$", "").replace(",", ""));
            } else if (now instanceof List<?>) {
                List<String> list = new ArrayList<>();
                for (String part : raw.replace("[", "").replace("]", "").split("[,\\s]+")) if (!part.isBlank()) list.add(part.trim());
                value = list;
            } else {
                value = raw;
            }
        } catch (IllegalArgumentException ex) {
            Msg.error(s, path + " needs " + (now instanceof Boolean ? "true or false" : now instanceof Number ? "a number" : "a value") + ".");
            return;
        }
        cfg.set(path, value);
        plugin.saveConfig();
        plugin.reloadSettings();
        Msg.send(s, "<green>" + path + " is now <white>" + Msg.plain(String.valueOf(value)) + "</white> <gray>(was "
                + Msg.plain(String.valueOf(now)) + "; saved and applied).");
    }

    /** The standing spot at (or just above or below) a location. */
    private static Location standOn(Location at) {
        org.bukkit.util.Vector v = new site.remotearc.employees.util.Pathfinder(at.getWorld(),
                org.bukkit.util.BoundingBox.of(at.toVector(), 3, 6, 3)).snap(at.getX(), at.getY(), at.getZ());
        return v == null ? at.clone() : new Location(at.getWorld(), v.getX(), v.getY(), v.getZ(), at.getYaw(), 0);
    }

    /** /employees unlock|lock <player> <item ...>: what /shop (and merchants) let a player buy. */
    private void discovery(CommandSender s, String label, boolean on, String[] args) {
        if (args.length < 3) {
            Msg.error(s, "Usage: /" + label + " " + (on ? "unlock" : "lock") + " \\<player> \\<item> [item...]   (e.g. grindstone firefly_bush)");
            return;
        }
        org.bukkit.OfflinePlayer who = Bukkit.getPlayerExact(args[1]);
        if (who == null) who = Bukkit.getOfflinePlayerIfCached(args[1]);
        if (who == null) {
            Msg.error(s, "No player called " + args[1] + " has been on this server.");
            return;
        }
        List<String> done = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            org.bukkit.Material m = org.bukkit.Material.matchMaterial(args[i]);
            if (m == null || !m.isItem()) {
                Msg.error(s, "There's no item called " + args[i] + ".");
                continue;
            }
            String err = plugin.market().setDiscovered(who.getUniqueId(), m, on);
            if (err != null) {
                Msg.error(s, "<msg>", Msg.p("msg", err));
                return;
            }
            done.add(site.remotearc.employees.util.Items.name(m));
        }
        if (!done.isEmpty()) {
            Msg.send(s, (on ? "<green>Unlocked for " : "<gray>Locked again for ") + who.getName() + " in /shop: <white>" + String.join(", ", done)
                    + (on ? "" : "</white>. <gray>(Picking one up or crafting it in survival unlocks it again.)"));
        }
    }

    private boolean admin(CommandSender s) {
        if (s.hasPermission("employees.admin")) return true;
        Msg.error(s, "You don't have permission to do that.");
        return false;
    }

    private void help(CommandSender s, String label) {
        String c = "<white>/" + label;
        Msg.send(s, "<gold>Employees</gold> <gray>work for you and are paid from your balance.");
        s.sendMessage(Msg.parse(c + "</white> <gray>- open your employees menu"));
        s.sendMessage(Msg.parse(c + " hire \\<job> [name]</white> <gray>- hire the villager you're looking at (or shift-right-click one)"
                + (plugin.settings().villagersOnly ? "" : "; with no villager, someone where you stand") + " (" + EmployeeManager.jobKeyList() + ")"));
        s.sendMessage(Msg.parse(c + " list | jobs | info \\<name></white> <gray>- see who works for you"));
        s.sendMessage(Msg.parse(c + " area \\<name> worldedit | radius \\<r> | pos1 | pos2 | show | tp</white> <gray>- set, see or go to their work area"));
        s.sendMessage(Msg.parse(c + " zone \\<name> add [preset] | remove | show | clear</white> <gray>- places they leave alone (WorldEdit selection)"));
        s.sendMessage(Msg.parse(c + " presets save | delete | show \\<preset></white> <gray>- named zones to give to several employees"));
        s.sendMessage(Msg.parse(c + " storage \\<name> vc \\<n> | chest | backpack</white> <gray>- where harvests go"));
        s.sendMessage(Msg.parse(c + " filter \\<name> add | remove \\<block></white> <gray>- what a miner looks for, a smelter smelts, a quarry throws away"));
        s.sendMessage(Msg.parse(c + " supply \\<name> add \\<n> | add chest | team on|off</white> <gray>- where builders, smelters and ranchers get materials"));
        s.sendMessage(Msg.parse(c + " build \\<name> \\<blueprint> | start | rotate | flip | materials</white> <gray>- builders"));
        s.sendMessage(Msg.parse(c + " blueprints [upload | download \\<link>]</white> <gray>- schematics builders can build"));
        s.sendMessage(Msg.parse(c + " herd \\<name> \\<size></white> <gray>- how many of each animal a rancher keeps"));
        s.sendMessage(Msg.parse(c + " repair \\<builder> on | off | snapshot</white> <gray>- keep a build (or your WorldEdit selection) repaired"));
        s.sendMessage(Msg.parse(c + " route \\<courier> add \\<from> \\<to> [items] [keep n] [stock n] | remove \\<n></white> <gray>- what a courier carries"));
        s.sendMessage(Msg.parse(c + " trust | untrust \\<player></white> <gray>- let a friend manage your employees · <white>/" + label
                + " manage [owner]</white> <gray>- manage a friend's"));
        s.sendMessage(Msg.parse(c + " report [hours] | profit [days]</white> <gray>- what your employees did, and what each one earns you"));
        s.sendMessage(Msg.parse(c + " trades \\<name> | craft \\<name> on | off | never add|remove \\<item></white> <gray>- villager traders"));
        s.sendMessage(Msg.parse(c + " board [status|sales|business|employee \\<name>] | size \\<1-20> | width \\<letters> | move | remove</white> <gray>- live boards"));
        s.sendMessage(Msg.parse(c + " chests [organize | move \\<job> \\<first number>]</white> <gray>- which /chest numbers each job uses"));
        s.sendMessage(Msg.parse(c + " market [prices]</white> <gray>- sell to a nearby merchant; admins: market place [name] | remove"));
        s.sendMessage(Msg.parse(c + " sell \\<name> all | off | add \\<item></white> <gray>- an employee sells what it makes instead of storing it"));
        s.sendMessage(Msg.parse(c + " skin \\<name> \\<player> | default [skin]</white> <gray>- change their look"));
        s.sendMessage(Msg.parse(c + " house \\<name> [clear]</white> <gray>- pick a house for them from the NPC Homes catalog; looking at a bed, give them that bed instead (clear: move them out)"));
        if (s.hasPermission("employees.admin")) {
            s.sendMessage(Msg.parse(c + " homes upload catalog | add \\<blueprint> \\<id> [name] | remove \\<id> | icon \\<id> \\<item> | style \\<id> \\<style> | describe \\<id> \\<text></white> <gray>- the NPC Homes catalog"));
        }
        s.sendMessage(Msg.parse(c + " homes list | upload | mine | forget \\<name></white> <gray>- the NPC Homes catalog, and houses of your own (upload from your computer)"));
        s.sendMessage(Msg.parse(c + " rename | pause | resume | toggle | move | tp | pay | fire \\<name></white>"));
        s.sendMessage(Msg.parse(c + " pauseall | resumeall | toggleall</white> <gray>- everyone at once"));
        s.sendMessage(Msg.parse(c + " away \\<name> work | rest</white> <gray>- keep working while nobody's online (they rest by default)"));
        s.sendMessage(Msg.parse(c + " avatar \\<name> [remove] | avatars [remove]</white> <gray>- stand-ins for your control room"));
        s.sendMessage(Msg.parse(c + " follow | stay \\<bodyguard></white> <gray>- · <white>" + "/" + label + " roam \\<miner> on | off | back</white>"));
        s.sendMessage(Msg.parse(c + " link \\<action> | unlink</white> <gray>- make a button or lever run an action (then right-click it)"));
        if (s.hasPermission("employees.admin")) {
            s.sendMessage(Msg.parse(c + " config \\<setting> [value]</white> <gray>- admins: see or change any setting, e.g. market.max-income-per-day"));
        }
    }

    // ── hiring ────────────────────────────────────────────────────────────

    private void jobs(CommandSender s) {
        for (JobType t : JobType.values()) {
            Settings.JobSettings js = plugin.settings().job(t);
            s.sendMessage(Msg.parse("<gold>" + t.display() + "</gold> <gray>(" + t.key() + "): hire " + Msg.money(js.hireCost())
                    + ", wage " + Describe.wageLine(plugin, t) + "</gray>\n<dark_gray>  " + t.description()));
        }
    }

    private void hire(CommandSender s, String[] args) {
        if (!(s instanceof Player p)) {
            Msg.error(s, "Only players can hire.");
            return;
        }
        if (args.length < 2) {
            Msg.error(p, "Hire who? <white>/employees hire \\<job> [name]</white>. Jobs: " + EmployeeManager.jobKeyList());
            return;
        }
        JobType job = JobType.parse(args[1]);
        if (job == null) {
            Msg.error(p, "There is no <white><job></white> job. Jobs: " + EmployeeManager.jobKeyList(), Msg.p("job", args[1]));
            return;
        }
        // Looking at a villager: hire that villager (free). Otherwise, with villagers-only, only admins hire out of nothing.
        org.bukkit.entity.Villager villager = VillagerHiring.lookedAt(p);
        if (villager != null) {
            plugin.villagerHiring().hire(p, villager, job, args.length >= 3 ? args[2] : null);
            return;
        }
        if (plugin.settings().villagersOnly && !p.hasPermission("employees.admin")) {
            Msg.error(p, "Employees are hired from villagers: look at a grown-up villager and type <white>/employees hire "
                    + job.key() + "</white>, or shift-right-click it.");
            return;
        }
        String name = args.length >= 3 ? args[2] : mgr().randomName(p.getUniqueId());
        if (!EmployeeManager.NAME.matcher(name).matches()) {
            Msg.error(p, "Names are 1-16 letters, digits or underscores.");
            return;
        }
        if (mgr().nameTaken(p.getUniqueId(), name)) {
            Msg.error(p, "You already employ someone called <white><name></white>.", Msg.p("name", name));
            return;
        }
        int limit = plugin.settings().maxPerPlayer;
        if (!p.hasPermission("employees.admin") && mgr().ownedBy(p.getUniqueId()).size() >= limit) {
            Msg.error(p, "You can employ at most " + limit + " people.");
            return;
        }

        Settings.JobSettings js = plugin.settings().job(job);
        double cost = js.hireCost() + js.wage();
        Economy econ = plugin.economy();
        if (econ != null && cost > 0) {
            if (!econ.has(p, cost)) {
                Msg.error(p, "Hiring a " + job.display().toLowerCase() + " costs " + Msg.money(cost) + " (" + Msg.money(js.hireCost())
                        + " to hire + " + Msg.money(js.wage()) + " first wage). You have " + Msg.money(econ.getBalance(p)) + ".");
                return;
            }
            if (!econ.withdrawPlayer(p, cost).transactionSuccess()) {
                Msg.error(p, "The payment didn't go through.");
                return;
            }
        }
        Employee e = mgr().hire(p.getUniqueId(), job, name, p.getLocation());
        e.stats.wagesPaid += js.wage();
        plugin.ledger().wage(e, js.wage());
        mgr().markDirty();
        welcome(plugin, p, e, "<green>You hired <gold>" + e.name + "</gold> the " + job.display().toLowerCase() + " for " + Msg.money(cost) + ".");
    }

    /** Tells a player who just hired an employee what it does and where things go, and outlines its work area. */
    static void welcome(ArcEmployees plugin, Player p, Employee e, String hiredLine) {
        JobType job = e.job;
        Msg.send(p, hiredLine);
        if (plugin.manager().homeless(e)) {
            p.sendMessage(Msg.parse("<gold>" + e.name + " has no home yet</gold><gray>, so they're unhappy and work at "
                    + percent(plugin.settings().homelessWorkSpeed) + " speed. Give them one: look at a bed with a door near it and run"
                    + " <white>/employees house " + e.name + "</white>."));
        }
        if (e.personality != null && plugin.settings().personalitiesEnabled) {
            p.sendMessage(Msg.parse("<gray>Personality: <light_purple>" + e.personality.display + "</light_purple> <dark_gray>("
                    + e.personality.does + ")"));
        }
        if (job != JobType.BUILDER) {
            p.sendMessage(Msg.parse("<gray>Work area: <white>" + e.area.describe() + ", y " + e.area.minY() + " to " + e.area.maxY()
                    + "</white> (outlined now). Change it with <white>/employees area " + e.name + " worldedit</white> (your wand selection)."));
        }
        p.sendMessage(Msg.parse("<gray>" + (job.usesSupply() && job != JobType.SMELTER ? "Leftovers go" : "Harvests go") + " to <white>"
                + e.storage.describe() + "</white>. Wage: <white>" + Describe.wageLine(plugin, job) + "</white>, paid automatically."));
        String n = e.name;
        p.sendMessage(Msg.parse(switch (job) {
            case LUMBERJACK -> "<gray>Plant saplings in the area: " + n + " fells every grown tree there, clears its leaves and replants it.";
            case FARMER -> "<gray>" + n + " harvests ripe crops in the area and replants them from the harvest.";
            case MINER -> "<gray>" + n + " explores the area for <white>" + Describe.esc(Describe.filterText(e, 4)) + "</white> open to caves and"
                    + " hillsides, tunnelling through stone to reach them. Change it with <white>/employees filter " + n + "</white>.";
            case QUARRY -> "<gray>" + n + " digs the area out layer by layer, top down, leaving a staircase along the wall."
                    + " Pick an exact box with your WorldEdit wand and <white>/employees area " + n + " worldedit</white>.";
            case BUILDER -> "<gray>Next: stand where the build should go and run <white>/employees build " + n + " \\<blueprint></white>"
                    + " (list: <white>/employees blueprints</white>). Materials come from your other employees' storage.";
            case SMELTER -> "<gray>Put furnaces, smokers or blast furnaces in the area. " + n + " smelts <white>"
                    + Describe.esc(Describe.filterText(e, 4)) + "</white> from your employees' storage, with coal or charcoal.";
            case RANCHER -> "<gray>Fence in some animals in the area: " + n + " keeps " + plugin.settings().herdSize
                    + " of each kind, breeding and culling, and shears sheep. Feed comes from your farmer's storage.";
            case GUARD -> "<gray>" + n + " patrols the area (" + e.area.describe() + ") and fights hostile mobs that come near."
                    + " Put torches in their backpack and they'll light up dark spots too.";
            case TRADER -> "<gray>Set " + n + "'s area over your trading hall, then pick the trades to make: <white>/employees trades " + n
                    + "</white> (or the Trades button in their menu).";
            case COURIER -> "<gray>Give " + n + " routes to carry things along: <white>/employees route " + n + " add \\<from> \\<to></white>"
                    + " (a /chest number, or <white>look</white> for the chest you're looking at), or the Routes button in their menu.";
            case BODYGUARD -> "<gray>" + n + " follows you and fights mobs near you. <white>/employees stay " + n + "</white> sends them back"
                    + " to guard this spot; <white>/employees follow " + n + "</white> brings them along again.";
        }));
        if (job != JobType.BUILDER) AreaPreview.show(plugin, p, e.area, 200);
    }

    private void list(CommandSender s) {
        List<Employee> list = s instanceof Player p && !s.hasPermission("employees.admin")
                ? mgr().ownedBy(p.getUniqueId()) : new ArrayList<>(mgr().all());
        List<String> waiting = s instanceof Player p ? mgr().waitingFor(p.getUniqueId()) : List.of();
        if (!waiting.isEmpty()) {
            Msg.send(s, "<gray>Away while their world isn't loaded (they come back when it is): <white>" + Describe.esc(String.join(", ", waiting)));
        }
        if (list.isEmpty()) {
            Msg.send(s, "<gray>Nobody works here yet. Shift-right-click a grown-up villager to hire it.");
            return;
        }
        Msg.send(s, "<gray>" + list.size() + " employee(s):");
        for (Employee e : list) {
            s.sendMessage(Msg.parse("<gold>" + e.name + "</gold> <gray>" + e.job.display() + ", " + e.ownerName() + "'s: <white><status>",
                    Msg.p("status", e.status)));
        }
    }

    private void info(CommandSender s, Employee e) {
        Msg.send(s, "<gold>" + e.name + "</gold> <gray>the " + e.job.display().toLowerCase() + ", employed by " + e.ownerName()
                + (e.kingdom != null ? " (" + e.kingdom + ")" : ""));
        for (String line : Describe.lore(plugin, e)) s.sendMessage(Msg.parse("  " + line));
    }

    private void fire(CommandSender s, Employee e, String label, String[] args) {
        if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
            Msg.send(s, "<yellow>Really fire " + e.name + "? Their backpack goes to you; paid wages aren't refunded. "
                    + "<click:run_command:'/" + label + " fire " + e.name + " confirm'><red><u>[Fire " + e.name + "]</u></red></click>");
            return;
        }
        mgr().fire(e, s instanceof Player p ? p : null);
        Msg.send(s, "<gray>You fired " + e.name + ".");
    }

    private void rename(CommandSender s, Employee e, String[] args) {
        if (args.length < 3 || !EmployeeManager.NAME.matcher(args[2]).matches()) {
            Msg.error(s, "Usage: /employees rename " + e.name + " \\<new name> (1-16 letters, digits, underscores)");
            return;
        }
        if (!args[2].equalsIgnoreCase(e.name) && mgr().nameTaken(e.owner, args[2])) {
            Msg.error(s, "Someone already has that name.");
            return;
        }
        String old = e.name;
        mgr().rename(e, args[2]);
        Msg.send(s, "<gray>" + old + " is now called <gold>" + e.name + "</gold>.");
    }

    // ── looks ─────────────────────────────────────────────────────────────

    /**
     * /employees house <name>: gives the employee the bed you're looking at (with a door near it) as its home.
     * /employees house <name> clear: takes its home away again.
     */
    private void house(CommandSender s, Employee e, String label, String[] args) {
        if (!plugin.settings().homesEnabled) {
            Msg.error(s, "Homes are turned off on this server.");
            return;
        }
        String mode = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "";
        if (s instanceof Player pl) {
            switch (mode) {
                case "style" -> {
                    if (args.length < 4) {
                        new site.remotearc.employees.gui.HomeMenu(plugin, e, null, 0).open(pl);
                        return;
                    }
                    plugin.homeSites().choose(pl, e, args[3]);
                    return;
                }
                case "turn", "rotate" -> {
                    plugin.homeSites().turn(pl, e);
                    return;
                }
                case "here", "move" -> {
                    plugin.homeSites().here(pl, e);
                    return;
                }
                case "confirm", "build" -> {
                    plugin.homeSites().confirm(pl, e);
                    return;
                }
                case "cancel" -> {
                    plugin.homeSites().cancel(pl, e);
                    return;
                }
                case "preview", "show" -> {
                    BuildOrder hs = e.homeSite;
                    if (hs == null) {
                        Msg.error(pl, e.name + " has no home site. Pick a house first.");
                        return;
                    }
                    Runnable show = () -> {
                        int n = site.remotearc.employees.build.BuildPreview.show(plugin, pl, hs);
                        Msg.send(pl, n > 0 ? "<gray>Showing " + e.name + "'s home as ghost blocks (only you see them). Build where they are."
                                : "<gray>Nothing to show here: go to the home site (" + hs.world + ").");
                    };
                    if (hs.plan != null) show.run();
                    else plugin.blueprints().load(hs.blueprint).whenComplete((bp, err) -> {   // not in memory since a restart
                        if (err != null) {
                            Msg.error(pl, "Can't read the plans: " + site.remotearc.employees.build.Blueprints.reason(err));
                            return;
                        }
                        if (hs.plan == null) hs.setPlan(bp);
                        if (pl.isOnline()) show.run();
                    });
                    return;
                }
                case "self", "selfbuild" -> {
                    if (e.homeSite == null) {
                        Msg.error(pl, e.name + " has no home site to build. Pick a house first: <white>/" + label + " house " + e.name + "</white>.");
                        return;
                    }
                    e.selfBuild = args.length > 3 ? !args[3].equalsIgnoreCase("off") : !e.selfBuild;
                    mgr().markDirty();
                    mgr().refreshTickets();   // its home site is kept loaded while it builds there
                    Msg.send(pl, e.selfBuild
                            ? "<green>" + e.name + " will build their home themself:</green> <gray>a minute every "
                                + plugin.settings().homeSelfBuildBreakTicks / 1200 + " minutes, and whenever their job has nothing to do."
                                + " Materials come from their backpack and your storage. It's slow, but free."
                            : "<gray>" + e.name + " won't work on their home any more.");
                    return;
                }
                case "builder" -> {
                    homeBuilder(pl, e, label, args);
                    return;
                }
                default -> { }
            }
        }
        if (mode.equals("clear") || mode.equals("evict")) {
            if (e.house == null && e.homeSite == null) {
                Msg.send(s, "<gray>" + e.name + " has no home to leave.");
                return;
            }
            plugin.homeSites().clear(e);
            if (e.house == null) {
                Msg.send(s, "<gray>" + e.name + "'s home site is cancelled. Anything already built there stays.");
                return;
            }
            mgr().loseHouse(e, "moved out on " + s.getName() + "'s orders");
            Msg.send(s, "<yellow>" + e.name + " has moved out and is homeless again: they work at "
                    + percent(plugin.settings().homelessWorkSpeed) + " speed until they get a new home.");
            return;
        }
        if (!(s instanceof Player p)) {
            Msg.error(s, "Look at a bed in the game to give it to " + e.name + ".");
            return;
        }
        Block head = House.bedHead(p.getTargetBlockExact(6));
        if (head == null) {
            new site.remotearc.employees.gui.HomeMenu(plugin, e, null, 0).open(p);
            return;
        }
        if (!plugin.estate().mayWork(p.getUniqueId(), head.getWorld(), head.getX(), head.getY(), head.getZ())) {
            Msg.error(s, "That bed is on someone else's land.");
            return;
        }
        BlockPos bed = BlockPos.of(head);
        Employee taken = mgr().bedOwner(head.getWorld().getName(), bed);
        if (taken == e) {
            Msg.send(s, "<gray>That's already " + e.name + "'s bed.");
            return;
        }
        if (taken != null) {
            Msg.error(s, "That bed already belongs to " + taken.name + ".");
            return;
        }
        Block door = House.doorNear(head);
        if (door == null) {
            Msg.error(s, "A home needs a door: put one within " + House.DOOR_RANGE + " blocks of the bed.");
            return;
        }
        boolean wasHomeless = e.house == null;
        if (e.homeSite != null) plugin.homeSites().clear(e);   // a home by hand instead of the planned one
        mgr().giveHouse(e, new House(head.getWorld().getName(), bed, BlockPos.of(door), System.currentTimeMillis()));
        Msg.send(s, "<green>" + e.name + " has a home now: " + e.house.describe() + "."
                + (wasHomeless ? " They're happy again and back to full speed." : ""));
    }

    /** /employees homes: the NPC Homes catalog. Anyone may list it; admins add, change and remove houses. */
    private void homes(CommandSender s, String label, String[] args) {
        site.remotearc.employees.build.HomeCatalog cat = plugin.homeCatalog();
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
        if (sub.equals("list")) {
            List<site.remotearc.employees.build.HomeCatalog.Entry> all = cat.list();
            if (all.isEmpty()) {
                Msg.send(s, "<gray>The NPC Homes catalog is empty." + (s.hasPermission("employees.admin")
                        ? " Add a house with <white>/" + label + " homes add \\<blueprint> \\<id> [name]</white>." : ""));
                return;
            }
            Msg.send(s, "<gold>NPC Homes</gold> <gray>(" + all.size() + "):");
            for (var h : all) {
                s.sendMessage(Msg.parse("<white>" + h.id() + "</white> <gray>- " + Describe.esc(h.name())
                        + (h.style().isEmpty() ? "" : " <dark_gray>[" + Describe.esc(h.style()) + "]") + " <dark_gray>(" + h.file() + ")"));
            }
            return;
        }
        if (sub.equals("upload")) {
            if (!(s instanceof Player p)) {
                Msg.error(s, "Only players can upload. Put house files straight into plugins/ArcEmployees/blueprints/homes.");
                return;
            }
            boolean catalog = args.length > 2 && args[2].equalsIgnoreCase("catalog");
            if (catalog && !s.hasPermission("employees.admin")) {
                Msg.error(s, "Only admins add houses to the NPC Homes catalog. <white>/" + label + " homes upload</white> uploads one just for you.");
                return;
            }
            String url = plugin.uploads().create(p, null, catalog ? site.remotearc.employees.build.Uploads.Kind.CATALOG
                    : site.remotearc.employees.build.Uploads.Kind.PERSONAL);
            if (url == null) {
                Msg.error(s, plugin.uploads().baseUrl() == null
                        ? "Uploading needs the built-in web server (web.enabled in the config)." : "Couldn't make an upload link; see the server log.");
                return;
            }
            Msg.send(s, "<green>Your upload page is ready: <click:open_url:'" + url + "'><hover:show_text:'<gray>Opens in your browser'>"
                    + "<aqua><u>click here to open it</u></aqua></hover></click><green>.");
            Msg.send(s, catalog
                    ? "<gray>Houses you drop on it go into the NPC Homes catalog for everyone (each needs a bed and a door). The link works for 15 minutes."
                    : "<gray>Houses you drop on it are yours alone: pick them in an employee's Home menu under My houses. Each needs a bed and a door."
                    + " The link works for 15 minutes.");
            return;
        }
        if (sub.equals("mine")) {
            if (!(s instanceof Player p)) return;
            List<java.io.File> mine = cat.personal(p.getUniqueId());
            if (mine.isEmpty()) {
                Msg.send(s, "<gray>You have no houses of your own. Upload one with <white>/" + label + " homes upload</white>.");
                return;
            }
            Msg.send(s, "<gold>Your houses</gold> <gray>(" + mine.size() + " of " + site.remotearc.employees.build.HomeCatalog.MAX_PERSONAL + "):");
            for (java.io.File f : mine) s.sendMessage(Msg.parse("<white>" + Describe.esc(site.remotearc.employees.build.HomeCatalog.personalName(f.getName()))));
            return;
        }
        if (sub.equals("forget")) {
            if (!(s instanceof Player p)) return;
            if (args.length < 3) {
                Msg.error(s, "Usage: /" + label + " homes forget \\<name>");
                return;
            }
            try {
                boolean gone = cat.removePersonal(p.getUniqueId(), args[2]);
                Msg.send(s, gone ? "<gray>Removed your house <white>" + Describe.esc(args[2]) + "</white>. Homes already placed with it keep it."
                        : "<red>You have no house called " + Describe.esc(args[2]) + ".");
            } catch (java.io.IOException ex) {
                Msg.error(s, "Couldn't remove it: " + ex.getMessage());
            }
            return;
        }
        if (!s.hasPermission("employees.admin")) {
            Msg.error(s, "Only admins change the NPC Homes catalog. Upload a house just for you with <white>/" + label + " homes upload</white>.");
            return;
        }
        String id = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : null;
        try {
            switch (sub) {
                case "add" -> {
                    if (args.length < 4) {
                        Msg.error(s, "Usage: /" + label + " homes add \\<blueprint> \\<id> [display name]");
                        return;
                    }
                    String bpName = args[2];
                    String newId = args[3].toLowerCase(Locale.ROOT);
                    if (!site.remotearc.employees.build.HomeCatalog.ID.matcher(newId).matches()) {
                        Msg.error(s, "Ids are 1-32 lowercase letters, digits, - or _.");
                        return;
                    }
                    java.io.File src = plugin.blueprints().resolve(bpName);
                    if (src == null) {
                        Msg.error(s, "There's no blueprint called <white><name></white>. See <white>/" + label + " blueprints</white>.", Msg.p("name", bpName));
                        return;
                    }
                    String display = args.length > 4 ? String.join(" ", java.util.Arrays.copyOfRange(args, 4, args.length)) : null;   // null: keep / the id
                    plugin.blueprints().load(src.getName()).whenComplete((bp, err) -> {
                        if (err != null) {
                            Msg.error(s, "Can't use that blueprint: <reason>", Msg.p("reason", site.remotearc.employees.build.Blueprints.reason(err)));
                            return;
                        }
                        String problem = site.remotearc.employees.build.HomeCatalog.problem(bp);
                        if (problem != null) {
                            Msg.error(s, "That blueprint can't be a home: " + problem + ". Every NPC home needs a bed and a door.");
                            return;
                        }
                        try {
                            boolean replaced = cat.get(newId) != null;
                            var en = cat.add(newId, src, display, null, null, null);
                            Msg.send(s, "<green>" + (replaced ? "Replaced" : "Added") + " <white>" + Describe.esc(en.name()) + "</white> in the NPC Homes catalog"
                                    + " <gray>(" + bp.size() + "). Set its icon, style and description with <white>/" + label + " homes icon|style|describe " + newId + " ...</white>");
                        } catch (java.io.IOException ex) {
                            Msg.error(s, "Couldn't copy it into the catalog: " + ex.getMessage());
                        }
                    });
                }
                case "remove", "delete" -> {
                    if (id == null || !cat.remove(id)) Msg.error(s, "No house <white>" + (id == null ? "" : id) + "</white> in the catalog.");
                    else Msg.send(s, "<gray>Took <white>" + id + "</white> out of the NPC Homes catalog. Homes already placed with it keep it.");
                }
                case "icon", "style", "describe", "rename" -> {
                    var en = cat.get(id);
                    if (en == null || args.length < 4) {
                        Msg.error(s, "Usage: /" + label + " homes " + sub + " \\<id> \\<" + (sub.equals("icon") ? "item" : "text") + ">");
                        return;
                    }
                    String text = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));
                    switch (sub) {
                        case "icon" -> {
                            Material m = Material.matchMaterial(text);
                            if (m == null || !m.isItem()) {
                                Msg.error(s, "That isn't an item.");
                                return;
                            }
                            en = new site.remotearc.employees.build.HomeCatalog.Entry(en.id(), en.file(), en.name(), m, en.style(), en.description());
                        }
                        case "style" -> en = new site.remotearc.employees.build.HomeCatalog.Entry(en.id(), en.file(), en.name(), en.icon(), text, en.description());
                        case "describe" -> en = new site.remotearc.employees.build.HomeCatalog.Entry(en.id(), en.file(), en.name(), en.icon(), en.style(), text);
                        default -> en = new site.remotearc.employees.build.HomeCatalog.Entry(en.id(), en.file(), text, en.icon(), en.style(), en.description());
                    }
                    cat.update(en);
                    Msg.send(s, "<green>Updated <white>" + Describe.esc(en.name()) + "</white>.");
                }
                case "reload" -> {
                    cat.reload();
                    Msg.send(s, "<green>Reloaded the NPC Homes catalog: " + cat.list().size() + " houses.");
                }
                default -> Msg.error(s, "Usage: /" + label + " homes list | upload [catalog] | mine | forget | add | remove | icon | style | describe | rename | reload");
            }
        } catch (java.io.IOException ex) {
            Msg.error(s, "Couldn't save the catalog: " + ex.getMessage());
        }
    }

    /** /employees house <npc> builder [builder]: one of your builders builds the employee's home site. */
    private void homeBuilder(Player p, Employee e, String label, String[] args) {
        if (e.homeSite == null) {
            Msg.error(p, e.name + " has no home site. Pick a house first: <white>/" + label + " house " + e.name + "</white>.");
            return;
        }
        String home = e.owner.equals(p.getUniqueId()) ? e.name : e.ownerName() + ":" + e.name;
        if (args.length < 4) {
            List<Employee> builders = new ArrayList<>();
            for (Employee o : mgr().ownedBy(e.owner)) if (o.job == JobType.BUILDER) builders.add(o);
            if (builders.isEmpty()) {
                Msg.send(p, "<gray>You have no builders. Hire one with <white>/" + label + " hire builder</white>, or have " + e.name
                        + " build it themself: <white><click:run_command:'/" + label + " house " + home + " self on'>[Build it themself]</click>");
                return;
            }
            StringBuilder line = new StringBuilder("<gray>Which builder? ");
            for (Employee b : builders) {
                boolean busy = b.build != null && b.build.started && !b.build.finished;
                String bref = b.owner.equals(p.getUniqueId()) ? b.name : b.ownerName() + ":" + b.name;
                line.append("<white><click:run_command:'/").append(label).append(" house ").append(home).append(" builder ").append(bref)
                        .append("'>[").append(b.name).append(busy ? " (busy)" : "").append("]</click></white> ");
            }
            p.sendMessage(Msg.parse(line.toString()));
            return;
        }
        Employee b = mgr().find(p, args[3]);
        if (b == null || !mgr().canManage(p, b) || !b.owner.equals(e.owner)) {
            Msg.error(p, "You have no employee called <white><name></white>.", Msg.p("name", args[3]));
            return;
        }
        if (b.job != JobType.BUILDER) {
            Msg.error(p, b.name + " isn't a builder.");
            return;
        }
        BuildOrder cur = b.build;
        if (cur != null && !cur.finished && (cur.started || !cur.stock.isEmpty())) {
            Msg.error(p, b.name + " is busy building " + cur.blueprint + ". Let them finish, or stop it with <white>/" + label + " build " + b.name + " cancel</white>.");
            return;
        }
        BuildOrder homeOrder = e.homeSite;
        BuildOrder o = new BuildOrder(homeOrder.blueprint, homeOrder.world, homeOrder.x, homeOrder.y, homeOrder.z);
        o.rotation = homeOrder.rotation;
        o.flip = homeOrder.flip;
        o.keepGround = homeOrder.keepGround;
        o.clearAir = homeOrder.clearAir;
        o.clearAround = homeOrder.clearAround;
        Runnable go = () -> {
            if (!mgr().all().contains(b) || !mgr().all().contains(e)) return;
            b.build = o;
            String bref = b.owner.equals(p.getUniqueId()) ? b.name : b.ownerName() + ":" + b.name;
            jobs.placed(p, b, o, "/" + label + " build " + bref);
            jobs.build(p, b, label, new String[]{"build", bref, "start"});
            mgr().events().add(e.owner, b.name, "Building " + e.name + "'s home", false);
        };
        if (homeOrder.plan != null) {
            o.setPlan(homeOrder.plan);
            go.run();
        } else {
            Msg.send(p, "<gray>Reading the plans...");
            plugin.blueprints().load(homeOrder.blueprint).whenComplete((bp, err) -> {
                if (err != null) {
                    Msg.error(p, "Can't read the plans: " + site.remotearc.employees.build.Blueprints.reason(err));
                    return;
                }
                if (homeOrder.plan == null) homeOrder.setPlan(bp);
                o.setPlan(bp);
                go.run();
            });
        }
    }

    private static String percent(double d) {
        return Math.round(d * 100) + "%";
    }

    private void skin(CommandSender s, Employee e, String[] args) {
        if (args.length < 3) {
            Msg.error(s, "Usage: /employees skin " + e.name + " \\<minecraft name> | default [" + String.join("|", Skin.DEFAULT_SKINS)
                    + "] [slim] | texture \\<namespace:path> [slim]");
            return;
        }
        String kind = args[2].toLowerCase(Locale.ROOT);
        boolean slim = args.length > 4 && args[4].equalsIgnoreCase("slim");
        if (kind.equals("default")) {
            String which = args.length > 3 && Skin.DEFAULT_SKINS.contains(args[3].toLowerCase(Locale.ROOT)) ? args[3] : null;
            // Without an explicit model, Alex keeps her usual slim arms and everyone else is wide.
            Skin skin = which == null ? Skin.random(new java.util.Random())
                    : Skin.builtIn(which, args.length > 4 ? slim : which.equalsIgnoreCase("alex"));
            setSkin(s, e, skin);
            return;
        }
        if (kind.equals("texture")) {
            if (args.length < 4) {
                Msg.error(s, "Usage: /employees skin " + e.name + " texture \\<namespace:path> [slim]");
                return;
            }
            try {
                Key.key(args[3]);
            } catch (InvalidKeyException ex) {
                Msg.error(s, "That isn't a valid texture key (like <white>arc:entity/employees/bob</white>).");
                return;
            }
            setSkin(s, e, Skin.texture(args[3], slim));
            return;
        }
        String player = args[2];
        if (!MC_NAME.matcher(player).matches()) {
            Msg.error(s, "That isn't a Minecraft name.");
            return;
        }
        Msg.send(s, "<gray>Looking up <white><player></white>'s skin...", Msg.p("player", player));
        UUID id = e.id;
        PlayerProfile profile = Bukkit.createProfile(player);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean ok;
            try {
                ok = profile.complete(true);
            } catch (RuntimeException ex) {
                ok = false;
            }
            ProfileProperty textures = null;
            for (ProfileProperty pp : profile.getProperties()) if (pp.getName().equals("textures")) textures = pp;
            boolean found = ok && textures != null && textures.getSignature() != null;
            ProfileProperty tex = textures;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Employee still = null;
                for (Employee x : mgr().all()) if (x.id.equals(id)) still = x;
                if (still == null) return;
                if (!found) {
                    Msg.error(s, "Couldn't find a Minecraft account called <white><player></white>.", Msg.p("player", player));
                    return;
                }
                String name = profile.getName() != null ? profile.getName() : player;
                setSkin(s, still, Skin.player(name, tex.getValue(), tex.getSignature()));
            });
        });
    }

    private void setSkin(CommandSender s, Employee e, Skin skin) {
        e.skin = skin;
        mgr().applySkin(e);
        Msg.send(s, "<gray>" + e.name + " now wears <white><skin></white>.", Msg.p("skin", skin.describe()));
    }

    // ── work area ─────────────────────────────────────────────────────────

    private void area(CommandSender s, Employee e, String label, String[] args) {
        if (!(s instanceof Player p)) {
            Msg.error(s, "Only players can set work areas.");
            return;
        }
        String mode = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "";
        Location at = p.getLocation();
        Settings.JobSettings js = plugin.settings().job(e.job);
        int max = js.maxAreaSize();
        if (e.job == JobType.BUILDER && !mode.equals("show") && !mode.equals("tp") && !mode.equals("teleport")) {
            Msg.error(p, "A builder's work area follows its blueprint. Place one with <white>/" + label + " build " + e.name + " \\<blueprint></white>.");
            return;
        }
        switch (mode) {
            case "show" -> {
                if (e.area == null) {
                    Msg.error(p, e.name + " has no work area yet. Set one with radius or pos1/pos2.");
                    return;
                }
                AreaPreview.show(plugin, p, e.area, 400);
                Msg.send(p, "<gray>" + e.name + "'s work area: <white>" + e.area.describe() + "</white>, y " + e.area.minY() + " to " + e.area.maxY() + ".");
            }
            case "tp", "teleport", "goto" -> {
                if (e.area == null) {
                    Msg.error(p, e.name + " has no work area yet.");
                    return;
                }
                WorkArea ar = e.area;
                String where = args.length > 3 ? args[3].toLowerCase(Locale.ROOT) : "middle";
                Location to = switch (where) {
                    case "nw", "northwest" -> mgr().areaViewpoint(ar, ar.minX(), ar.minZ());
                    case "ne", "northeast" -> mgr().areaViewpoint(ar, ar.maxX(), ar.minZ());
                    case "sw", "southwest" -> mgr().areaViewpoint(ar, ar.minX(), ar.maxZ());
                    case "se", "southeast" -> mgr().areaViewpoint(ar, ar.maxX(), ar.maxZ());
                    default -> mgr().areaViewpoint(ar);
                };
                String placeName = switch (where) {
                    case "nw", "northwest" -> "the north-west corner";
                    case "ne", "northeast" -> "the north-east corner";
                    case "sw", "southwest" -> "the south-west corner";
                    case "se", "southeast" -> "the south-east corner";
                    default -> "the middle";
                };
                if (to == null) {
                    Msg.error(p, "That area's world isn't loaded.");
                    return;
                }
                to.setYaw(at.getYaw());
                to.setPitch(at.getPitch());
                p.teleportAsync(to).thenAccept(ok -> {
                    if (!ok) return;
                    AreaPreview.show(plugin, p, e.area, 400);
                    Msg.send(p, "<gray>You're at " + placeName + " of " + e.name + "'s work area (" + e.area.describe() + ", y "
                            + e.area.minY() + " to " + e.area.maxY() + "). <dark_gray>The outline shows for 20 seconds.");
                });
            }
            case "radius" -> {
                int r;
                try {
                    r = Integer.parseInt(args.length > 3 ? args[3] : "");
                } catch (NumberFormatException ex) {
                    Msg.error(p, "Usage: /" + label + " area " + e.name + " radius \\<1-" + max / 2 + ">");
                    return;
                }
                if (r < 1 || r * 2 + 1 > max) {
                    Msg.error(p, "Radius must be 1 to " + (max - 1) / 2 + ".");
                    return;
                }
                setArea(p, e, mgr().defaultArea(e.job, at, r));
            }
            case "pos1", "pos2" -> {
                Corners c = corners.get(p.getUniqueId());
                if (c == null || !c.employee().equals(e.id) || !c.world().equals(at.getWorld().getName())) {
                    c = new Corners(e.id, null, null, at.getWorld().getName());
                }
                BlockPos here = BlockPos.of(at);
                c = mode.equals("pos1") ? new Corners(e.id, here, c.b(), c.world()) : new Corners(e.id, c.a(), here, c.world());
                corners.put(p.getUniqueId(), c);
                if (c.a() == null || c.b() == null) {
                    Msg.send(p, "<gray>Corner set. Now stand on the opposite corner and run <white>/" + label + " area " + e.name + " "
                            + (c.a() == null ? "pos1" : "pos2") + "</white>.");
                    return;
                }
                int w = Math.abs(c.a().x() - c.b().x()) + 1, l = Math.abs(c.a().z() - c.b().z()) + 1;
                if (w > max || l > max) {
                    Msg.error(p, "That area is " + w + "x" + l + "; the most is " + max + "x" + max + ".");
                    return;
                }
                corners.remove(p.getUniqueId());
                int lowY = Math.min(c.a().y(), c.b().y()), highY = Math.max(c.a().y(), c.b().y());
                setArea(p, e, mgr().areaFor(e.job, at.getWorld(), c.a().x(), c.a().z(), c.b().x(), c.b().z(), lowY, highY));
            }
            case "bedrock", "deeper" -> {
                if (e.area == null) {
                    Msg.error(p, e.name + " has no work area yet.");
                    return;
                }
                World w = e.area.bukkitWorld();
                if (w == null) return;
                WorkArea a = e.area;
                int bottom = w.getMinHeight();
                if (a.maxY() - bottom + 1 > js.maxAreaHeight()) bottom = a.maxY() - js.maxAreaHeight() + 1;
                setArea(p, e, WorkArea.of(a.world(), a.minX(), bottom, a.minZ(), a.maxX(), a.maxY(), a.maxZ()));
            }
            case "worldedit", "we", "selection", "sel" -> {
                if (!Bukkit.getPluginManager().isPluginEnabled("WorldEdit")) {
                    Msg.error(p, "WorldEdit isn't installed.");
                    return;
                }
                WorkArea area;
                try {
                    area = WorldEditSelection.of(p);
                } catch (IllegalStateException ex) {
                    Msg.error(p, "<msg>", Msg.p("msg", ex.getMessage()));
                    return;
                }
                if (area.width() > max || area.length() > max) {
                    Msg.error(p, "That selection is " + area.width() + "x" + area.length() + "; the most is " + max + "x" + max + ".");
                    return;
                }
                if (area.height() > js.maxAreaHeight()) {
                    Msg.error(p, "That selection is " + area.height() + " blocks tall; the most is " + js.maxAreaHeight() + ".");
                    return;
                }
                setArea(p, e, area);
            }
            default -> {
                Msg.error(p, "Usage: /" + label + " area " + e.name + " worldedit | radius \\<r> | pos1 | pos2 | show");
                p.sendMessage(Msg.parse("<gray>Easiest: select two corners with the WorldEdit wand (<white>//wand</white>), then <white>/"
                        + label + " area " + e.name + " worldedit</white>."));
            }
        }
    }

    private void setArea(Player p, Employee e, WorkArea area) {
        mgr().setArea(e, area);
        Msg.send(p, "<green>" + e.name + " now works in <white>" + area.describe() + "</white> (y " + area.minY() + " to " + area.maxY() + ").");
        if (e.home == null || !area.containsColumn(e.home.getBlockX(), e.home.getBlockZ())) {
            p.sendMessage(Msg.parse("<gray>Tip: <white>/employees move " + e.name + "</white> brings them (and the area) to you."));
        }
        AreaPreview.show(plugin, p, area, 200);
    }

    private void move(CommandSender s, Employee e) {
        if (!(s instanceof Player p)) return;
        Location at = p.getLocation();
        if (e.job == JobType.BUILDER) {
            mgr().relocate(e, at);
            Msg.send(p, "<green>" + e.name + " moved here. (The build stays where it is; move it with /employees build " + e.name + " move.)");
            return;
        }
        WorkArea old = e.area;
        WorkArea moved;
        if (old != null && e.home != null && old.world().equals(at.getWorld().getName()) && e.home.getWorld() == at.getWorld()) {
            // Same shape, shifted by however far the employee moves.
            int dx = at.getBlockX() - e.home.getBlockX(), dy = at.getBlockY() - e.home.getBlockY(), dz = at.getBlockZ() - e.home.getBlockZ();
            moved = WorkArea.of(old.world(), old.minX() + dx, old.minY() + dy, old.minZ() + dz, old.maxX() + dx, old.maxY() + dy, old.maxZ() + dz);
            if (e.job == JobType.QUARRY) {
                // A quarry's home can be anywhere down its pit, so its height says nothing about the area's:
                // start the new pit at the floor you're standing on (as when hiring), as deep as the old one.
                World w = at.getWorld();
                int top = Math.min(w.getMaxHeight() - 1, at.getBlockY() + plugin.settings().job(e.job).areaAbove());
                int bottom = Math.max(w.getMinHeight(), top - old.height() + 1);
                moved = WorkArea.of(old.world(), moved.minX(), bottom, moved.minZ(), moved.maxX(), top, moved.maxZ());
            }
        } else {
            moved = mgr().defaultArea(e.job, at);
        }
        mgr().relocate(e, at);
        mgr().setArea(e, moved);
        Msg.send(p, "<green>" + e.name + " and their work area moved here: <white>" + moved.describe() + ", y " + moved.minY()
                + " to " + moved.maxY() + "</white>.");
        AreaPreview.show(plugin, p, moved, 200);
    }

    // ── storage and pay ───────────────────────────────────────────────────

    private void storage(CommandSender s, Employee e, String label, String[] args) {
        String mode = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "";
        switch (mode) {
            case "vc", "virtual", "virtualchest" -> {
                if (!plugin.settings().vcEnabled) {
                    Msg.error(s, "Delivering to VirtualChests is turned off in the config.");
                    return;
                }
                if (!plugin.vchests().available()) {
                    Msg.error(s, "VirtualChests isn't available, so employees can't deliver to /chest.");
                    return;
                }
                int n;
                try {
                    n = Integer.parseInt(args.length > 3 ? args[3] : "");
                } catch (NumberFormatException ex) {
                    Msg.error(s, "Usage: /" + label + " storage " + e.name + " vc \\<chest number>");
                    return;
                }
                if (n < 1 || n > 1000) {
                    Msg.error(s, "Chest numbers start at 1.");
                    return;
                }
                if (!s.hasPermission("employees.admin") && !s.hasPermission("virtualchests.open." + n)) {
                    Msg.error(s, "You don't have access to /chest " + n + ".");
                    return;
                }
                mgr().setStorage(e, StorageTarget.virtual(n));
                Msg.send(s, "<green>" + e.name + " now delivers to <white>/chest " + n + "</white>.");
            }
            case "chest" -> {
                if (!(s instanceof Player p)) return;
                if (!plugin.settings().chestsEnabled) {
                    Msg.error(p, "Delivering to placed chests is turned off in the config.");
                    return;
                }
                Block b = p.getTargetBlockExact(6);
                if (b == null || !(b.getState(false) instanceof Container)) {
                    Msg.error(p, "Look at a chest, barrel or other container within 6 blocks.");
                    return;
                }
                mgr().setStorage(e, StorageTarget.chest(b));
                Msg.send(p, "<green>" + e.name + " will carry harvests to that " + b.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ') + ".");
                if (e.area != null && (!e.area.world().equals(b.getWorld().getName())
                        || !e.area.expand(12, 8).contains(b.getX(), b.getY(), b.getZ()))) {
                    p.sendMessage(Msg.parse("<yellow>That's more than 12 blocks outside the work area; " + e.name + " may not be able to walk to it."));
                }
            }
            case "backpack", "none" -> {
                mgr().setStorage(e, StorageTarget.NONE);
                Msg.send(s, "<gray>" + e.name + " keeps everything in their backpack (36 slots).");
            }
            default -> Msg.error(s, "Usage: /" + label + " storage " + e.name + " vc \\<n> | chest | backpack");
        }
    }

    private void pay(CommandSender s, Employee e) {
        double wage = plugin.settings().job(e.job).wage();
        if (mgr().payWage(e, true)) {
            Msg.send(s, "<green>Paid " + e.name + " " + Msg.money(wage) + ". Paid up for the next " + Msg.duration(e.paidUntil - System.currentTimeMillis()) + ".");
        } else {
            Msg.error(s, e.ownerName() + " can't afford " + Msg.money(wage) + ".");
        }
    }

    // ── tab completion ────────────────────────────────────────────────────

    @Override
    public List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String alias, String @NotNull [] args) {
        List<String> out = new ArrayList<>();
        String first = args[0].toLowerCase(Locale.ROOT);
        if (first.equals("market") && args.length == 2) {
            for (String o : List.of("prices", "place", "remove")) if (o.startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(o);
            return out;
        }
        if (first.equals("sell") && args.length == 3) {
            for (String o : List.of("all", "off", "add", "remove", "list")) if (o.startsWith(args[2].toLowerCase(Locale.ROOT))) out.add(o);
            return out;
        }
        if (first.equals("chests")) {
            if (args.length == 2) out.addAll(List.of("organize", "move"));
            else if (args.length == 3 && args[1].equalsIgnoreCase("move")) for (JobType t : JobType.values()) out.add(t.key());
            else if (args.length == 4) out.addAll(List.of("100", "200", "300"));
            String last = args[args.length - 1].toLowerCase(Locale.ROOT);
            out.removeIf(o -> !o.startsWith(last));
            return out;
        }
        if (first.equals("board") && args.length == 2) {
            for (String o : List.of("status", "sales", "business", "employee", "size", "width", "move", "remove", "help")) if (o.startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(o);
            return out;
        }
        if (first.equals("board") && args.length == 3 && args[1].equalsIgnoreCase("employee") && s instanceof Player p) {
            for (Employee e : mgr().ownedBy(p.getUniqueId())) if (e.name.toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT))) out.add(e.name);
            return out;
        }
        if (first.equals("board") && args.length == 3) {
            String m = args[1].toLowerCase(Locale.ROOT);
            if (m.equals("width")) out.addAll(List.of("80", "120", "160", "200"));
            else if (!m.equals("move") && !m.equals("remove")) out.addAll(List.of("1", "2", "3", "4", "6", "10"));
            return out;
        }
        if (first.equals("blueprints") || (args.length >= 3 && List.of("filter", "supply", "herd", "build").contains(first))) {
            out.addAll(jobs.complete(s, args));
            String last = args[args.length - 1].toLowerCase(Locale.ROOT);
            out.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(last));
            return out;
        }
        if (first.equals("presets") || first.equals("preset")) {
            if (args.length == 2) out.addAll(List.of("list", "save", "delete", "show"));
            else if (args.length == 3 && s instanceof Player p && !args[1].equalsIgnoreCase("save")) out.addAll(plugin.zonePresets().of(p.getUniqueId()).keySet());
            String last = args[args.length - 1].toLowerCase(Locale.ROOT);
            out.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(last));
            return out;
        }
        if ((first.equals("unlock") || first.equals("lock")) && s.hasPermission("employees.admin") && args.length >= 2) {
            String last = args[args.length - 1].toLowerCase(Locale.ROOT);
            if (args.length == 2) for (Player pl : Bukkit.getOnlinePlayers()) out.add(pl.getName());
            else for (Material m : Material.values()) if (m.isItem() && !m.isLegacy() && m.name().toLowerCase(Locale.ROOT).startsWith(last)) out.add(m.name().toLowerCase(Locale.ROOT));
            out.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(last));
            return out.size() > 50 ? out.subList(0, 50) : out;
        }
        if (args.length == 5 && first.startsWith("craft") && args[2].equalsIgnoreCase("never")) {
            String last = args[4].toLowerCase(Locale.ROOT);
            for (Material m : Material.values()) if (m.isItem() && !m.isLegacy() && m.name().toLowerCase(Locale.ROOT).startsWith(last)) out.add(m.name().toLowerCase(Locale.ROOT));
            return out.size() > 50 ? out.subList(0, 50) : out;
        }
        if (args.length == 1) {
            out.addAll(SUBS);
            if (s.hasPermission("employees.admin")) out.addAll(ADMIN_SUBS);
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("hire")) {
                for (JobType t : JobType.values()) out.add(t.key());
            } else if (sub.equals("config") && s.hasPermission("employees.admin")) {
                for (String k : plugin.getConfig().getKeys(true)) if (!plugin.getConfig().isConfigurationSection(k)) out.add(k);
                String last = args[1].toLowerCase(Locale.ROOT);
                out.removeIf(o -> !o.toLowerCase(Locale.ROOT).contains(last));
                return out.size() > 80 ? out.subList(0, 80) : out;
            } else if (sub.equals("trust") || sub.equals("untrust")) {
                for (Player o : Bukkit.getOnlinePlayers()) if (!o.equals(s)) out.add(o.getName());
            } else if (sub.equals("manage") && s instanceof Player p) {
                for (UUID o : plugin.managers().ownersFor(p.getUniqueId())) out.add(String.valueOf(Bukkit.getOfflinePlayer(o).getName()));
            } else if (sub.equals("report")) {
                out.addAll(List.of("1", "6", "24", "72", "168"));
            } else if (sub.equals("profit")) {
                out.addAll(List.of("1", "7", "30"));
            } else if (SUBS.contains(sub) && !GLOBAL.contains(sub)) {
                List<Employee> mine = s instanceof Player p ? mgr().ownedBy(p.getUniqueId()) : List.of();
                for (Employee e : mine) out.add(e.name);
                if (s instanceof Player p) {
                    for (Employee e : mgr().all()) if (plugin.managers().trusts(e.owner, p.getUniqueId())) out.add(e.ownerName() + ":" + e.name);
                }
                if (s.hasPermission("employees.admin")) {
                    for (Employee e : mgr().all()) if (!mine.contains(e)) out.add(e.ownerName() + ":" + e.name);
                }
            }
        } else if (args.length == 3) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "area" -> out.addAll(List.of("worldedit", "radius", "pos1", "pos2", "show", "tp", "bedrock"));
                case "storage" -> out.addAll(List.of("vc", "chest", "backpack"));
                case "skin" -> {
                    out.addAll(List.of("default", "texture"));
                    for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
                }
                case "fire" -> out.add("confirm");
                case "zone", "zones" -> out.addAll(List.of("add", "remove", "show", "clear", "list"));
                case "stalls", "stall" -> out.addAll(List.of("add", "show", "job", "want", "reroll", "remove", "move", "list"));
                case "away" -> out.addAll(List.of("work", "rest"));
                case "route", "routes" -> out.addAll(List.of("list", "add", "remove", "clear"));
                case "repair" -> out.addAll(List.of("on", "off", "snapshot", "status"));
                case "craft", "crafting" -> out.addAll(List.of("on", "off", "never"));
                default -> { }
            }
        } else if (args.length == 4) {
            String sub = args[0].toLowerCase(Locale.ROOT), mode = args[2].toLowerCase(Locale.ROOT);
            if (sub.equals("skin") && mode.equals("default")) out.addAll(Skin.DEFAULT_SKINS);
            else if (sub.equals("area") && mode.equals("radius")) out.addAll(List.of("4", "8", "12", "16"));
            else if (sub.equals("area") && mode.equals("tp")) out.addAll(List.of("middle", "nw", "ne", "sw", "se"));
            else if (sub.equals("storage") && mode.equals("vc")) out.add(String.valueOf(plugin.settings().vcFirstChest));
            else if ((sub.equals("zone") || sub.equals("zones")) && (mode.equals("add") || mode.equals("remove")) && s instanceof Player p) {
                out.addAll(plugin.zonePresets().of(p.getUniqueId()).keySet());
            } else if (sub.equals("craft") && mode.equals("never")) out.addAll(List.of("add", "remove"));
            else if (sub.startsWith("route") && mode.equals("add")) out.addAll(List.of("look", "click", "10"));
        } else if (args.length == 5 && args[0].toLowerCase(Locale.ROOT).startsWith("route") && args[2].equalsIgnoreCase("add")) {
            out.addAll(List.of("look", "click", "merchant", "10"));
        } else if (args.length >= 6 && args[0].toLowerCase(Locale.ROOT).startsWith("route") && args[2].equalsIgnoreCase("add")) {
            out.addAll(List.of("keep", "stock", "all"));
        } else if (args.length == 5 && args[0].equalsIgnoreCase("skin")) {
            out.addAll(List.of("slim", "wide"));
        }
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
