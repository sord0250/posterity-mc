package site.remotearc.employees.sort;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.util.Msg;
import site.remotearc.employees.util.WorkArea;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * /sort: organize nearby chests (and virtual chests) by category, showing
 * which chest gets what first; quick-stack, tidy and undo; pick categories
 * and the chests each one goes into. Takes over ArcShop's /sort (still there
 * as /arcshop:sort).
 */
public final class SortCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of("organize", "confirm", "deposit", "tidy", "undo", "pin", "unpin", "category",
            "categories", "labels", "radius", "virtual", "scan", "help");

    private final ArcEmployees plugin;
    private final Sorter sorter;
    /** The last plan shown: whether it used the WorldEdit selection. */
    private final Map<UUID, Boolean> pending = new HashMap<>();

    public SortCommand(ArcEmployees plugin, Sorter sorter) {
        this.plugin = plugin;
        this.sorter = sorter;
    }

    private SortProfiles.Profile prof(Player p) {
        return sorter.profiles().get(p.getUniqueId());
    }

    private List<Sorter.Box> boxes(Player p, boolean sel) {
        WorkArea area = null;
        if (sel) {
            try {
                area = site.remotearc.employees.WorldEditSelection.exact(p);
            } catch (IllegalStateException ex) {
                Msg.error(p, ex.getMessage());
                return null;
            } catch (NoClassDefFoundError ex) {
                Msg.error(p, "Sorting a selection needs WorldEdit.");
                return null;
            }
        }
        return sorter.boxes(p, area);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, String @NotNull [] args) {
        if (!(s instanceof Player p)) {
            Msg.error(s, "Only players can sort.");
            return true;
        }
        if (args.length == 0) {
            new site.remotearc.employees.gui.SortMenu(plugin, sorter).open(p);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        boolean sel = List.of(args).stream().anyMatch(a -> a.equalsIgnoreCase("sel"));
        switch (sub) {
            case "organize", "organise", "plan", "preview" -> {
                List<Sorter.Box> boxes = boxes(p, sel);
                if (boxes == null) return true;
                if (boxes.isEmpty()) {
                    Msg.error(p, "No chests or barrels within " + prof(p).radius + " blocks" + (sel ? " in your selection" : "") + ", and no virtual chests added.");
                    return true;
                }
                Sorter.Plan plan = sorter.plan(p, boxes);
                sorter.tell(p, plan);
                sorter.label(p, plan);
                pending.put(p.getUniqueId(), sel);
            }
            case "confirm" -> {
                Boolean wasSel = pending.remove(p.getUniqueId());
                if (wasSel == null) {
                    Msg.error(p, "Look at the plan first: /sort organize");
                    return true;
                }
                List<Sorter.Box> boxes = boxes(p, wasSel);
                if (boxes == null || boxes.isEmpty()) return true;
                Sorter.Plan plan = sorter.plan(p, boxes);
                String why = sorter.apply(p, plan);
                sorter.clearLabels(p);
                if (why != null) {
                    Msg.error(p, why);
                    return true;
                }
                int items = plan.items().values().stream().mapToInt(Integer::intValue).sum();
                Msg.send(p, "<green>Sorted " + items + " items into " + plan.where().size() + " categories across " + plan.boxes().size()
                        + " containers.</green> <gray><click:run_command:'/sort undo'>[Undo]</click>");
            }
            case "deposit", "stack", "quickstack" -> {
                List<Sorter.Box> boxes = boxes(p, sel);
                if (boxes == null) return true;
                boolean hotbar = List.of(args).stream().anyMatch(a -> a.equalsIgnoreCase("hotbar"));
                int[] r = sorter.deposit(p, boxes, hotbar);
                if (r[0] == 0) Msg.send(p, "<gray>Nothing to put away: no container here has those items or their category.");
                else Msg.send(p, "<green>Put away " + r[0] + " items into " + r[1] + " containers.</green> <gray><click:run_command:'/sort undo'>[Undo]</click>");
            }
            case "tidy" -> {
                List<Sorter.Box> boxes = boxes(p, sel);
                if (boxes == null) return true;
                int n = sorter.tidy(p, boxes);
                Msg.send(p, n == 0 ? "<gray>Already tidy." : "<green>Tidied " + n + " containers (stacks merged and sorted, nothing moved between them).</green>"
                        + " <gray><click:run_command:'/sort undo'>[Undo]</click>");
            }
            case "undo" -> {
                int[] r = sorter.undo(p);
                if (r == null) Msg.error(p, "Nothing to undo (undo lasts until the server restarts).");
                else Msg.send(p, "<green>Put back " + r[0] + " containers.</green>" + (r[1] > 0 ? " <yellow>" + r[1] + " changed since, so they were left as they are." : ""));
            }
            case "pin" -> {
                if (args.length < 2 || !known(p, args[1])) {
                    Msg.error(p, "Usage: /sort pin <category> [vc <number>]   then click the chest. Categories: " + String.join(", ", prof(p).active()));
                    return true;
                }
                String cat = args[1].toLowerCase(Locale.ROOT);
                if (args.length >= 4 && args[2].equalsIgnoreCase("vc")) {
                    int n = number(p, args[3]);
                    if (n < 0) return true;
                    prof(p).pin(cat, "vc:" + n);
                    sorter.profiles().save();
                    Msg.send(p, "<green>Virtual chest " + n + " is for " + prof(p).name(cat) + " now.");
                    return true;
                }
                site.remotearc.employees.gui.ChestPick.ask(p, "the chest for " + prof(p).name(cat), b -> {
                    Sorter.Box box = sorter.boxAt(b);
                    if (box == null) {
                        Msg.error(p, "Sorting uses chests, trapped chests and barrels.");
                        return;
                    }
                    prof(p).pin(cat, box.key);
                    sorter.profiles().save();
                    Msg.send(p, "<green>That " + box.describe(p) + " is for " + prof(p).name(cat) + " now.");
                });
            }
            case "unpin" -> {
                if (args.length >= 3 && args[1].equalsIgnoreCase("vc")) {
                    int n = number(p, args[2]);
                    if (n < 0) return true;
                    prof(p).unpin("vc:" + n);
                    sorter.profiles().save();
                    Msg.send(p, "<gray>Virtual chest " + n + " isn't kept for anything now.");
                    return true;
                }
                site.remotearc.employees.gui.ChestPick.ask(p, "the chest to free up", b -> {
                    Sorter.Box box = sorter.boxAt(b);
                    if (box == null) return;
                    prof(p).unpin(box.key);
                    sorter.profiles().save();
                    Msg.send(p, "<gray>That chest isn't kept for anything now; sorting fills it with whatever needs room.");
                });
            }
            case "category", "cat" -> {
                if (args.length < 3 || !known(p, args[1]) || !List.of("on", "off").contains(args[2].toLowerCase(Locale.ROOT))) {
                    Msg.error(p, "Usage: /sort category <name> on|off");
                    return true;
                }
                String cat = args[1].toLowerCase(Locale.ROOT);
                if (cat.equals(Categories.MISC)) {
                    Msg.error(p, "Everything else always has somewhere to go.");
                    return true;
                }
                prof(p).switched.put(cat, args[2].equalsIgnoreCase("on"));
                sorter.profiles().save();
                Msg.send(p, "<green>" + prof(p).name(cat) + " is " + args[2].toLowerCase(Locale.ROOT) + ".");
            }
            case "categories" -> new site.remotearc.employees.gui.SortMenu(plugin, sorter).open(p);
            case "labels", "label" -> {
                SortProfiles.Profile pr = prof(p);
                String want = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
                pr.labels = want.equals("on") || (!want.equals("off") && !pr.labels);
                sorter.profiles().save();
                Msg.send(p, pr.labels ? "<green>Labels on:</green> <gray>chests and barrels near you show what's in them (only you see them). <white>/sort labels</white> again turns them off."
                        : "<gray>Labels off.");
            }
            case "radius" -> {
                int n = args.length > 1 ? number(p, args[1]) : -1;
                if (n < 1) {
                    Msg.send(p, "<gray>Sorting looks " + prof(p).radius + " blocks round you (up to " + Sorter.MAX_RADIUS + "): /sort radius <blocks>");
                    return true;
                }
                prof(p).radius = Math.min(Sorter.MAX_RADIUS, n);
                sorter.profiles().save();
                Msg.send(p, "<green>Sorting looks " + prof(p).radius + " blocks round you.");
            }
            case "virtual", "vc" -> {
                if (args.length < 3 || !List.of("add", "remove").contains(args[1].toLowerCase(Locale.ROOT))) {
                    Msg.send(p, "<gray>Virtual chests sorted too: <white>" + (prof(p).virtual.isEmpty() ? "none" : prof(p).virtual)
                            + "</white>. /sort virtual add|remove <number>");
                    return true;
                }
                int n = number(p, args[2]);
                if (n < 0) return true;
                if (args[1].equalsIgnoreCase("add")) prof(p).virtual.add(n);
                else {
                    prof(p).virtual.remove(n);
                    prof(p).unpin("vc:" + n);
                }
                sorter.profiles().save();
                Msg.send(p, "<green>Virtual chests sorted too: " + prof(p).virtual);
            }
            case "scan" -> {
                List<Sorter.Box> boxes = boxes(p, sel);
                if (boxes == null) return true;
                Msg.send(p, "<gold>" + boxes.size() + " containers</gold> <gray>within " + prof(p).radius + " blocks" + (sel ? " (selection)" : "") + ":");
                for (Sorter.Box b : boxes) {
                    String pin = prof(p).pinnedTo(b.key);
                    p.sendMessage(Msg.parse("<gray>• " + b.describe(p) + (pin != null ? " <aqua>(kept for " + prof(p).name(pin) + ")" : "")));
                }
            }
            default -> help(p);
        }
        return true;
    }

    private boolean known(Player p, String cat) {
        String c = cat.toLowerCase(Locale.ROOT);
        return Categories.def(c) != null || prof(p).custom.containsKey(c);
    }

    private static int number(Player p, String s) {
        try {
            int n = Integer.parseInt(s);
            if (n >= 0) return n;
        } catch (NumberFormatException ignored) {
        }
        Msg.error(p, "That isn't a number: " + s);
        return -1;
    }

    private static void help(Player p) {
        Msg.send(p, "<gold>/sort</gold> <gray>opens the sorting menu: categories, the chests for each, virtual chests.");
        p.sendMessage(Msg.parse("<gray>/sort organize [sel] <dark_gray>- the plan: which chest gets what (labels over them), then [Sort it]"));
        p.sendMessage(Msg.parse("<gray>/sort deposit [hotbar] <dark_gray>- put your items away where they belong"));
        p.sendMessage(Msg.parse("<gray>/sort tidy <dark_gray>- merge and sort inside each chest  <gray>/sort undo"));
        p.sendMessage(Msg.parse("<gray>/sort pin <category> [vc <n>] | unpin <dark_gray>- keep a chest for a category"));
        p.sendMessage(Msg.parse("<gray>/sort labels <dark_gray>- labels on nearby chests showing what's in them, on or off"));
        p.sendMessage(Msg.parse("<gray>/sort category <name> on|off | radius <n> | virtual add|remove <n> | scan"));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, String @NotNull [] args) {
        List<String> out = new ArrayList<>();
        if (!(s instanceof Player p)) return out;
        if (args.length == 1) out.addAll(SUBS);
        else if (args.length == 2 && List.of("pin", "category", "cat").contains(args[0].toLowerCase(Locale.ROOT))) {
            for (Categories.Def d : Categories.MAIN) out.add(d.key());
            for (Categories.Def d : Categories.EXTRAS) out.add(d.key());
            out.addAll(prof(p).custom.keySet());
        } else if (args.length == 2 && List.of("virtual", "vc").contains(args[0].toLowerCase(Locale.ROOT))) out.addAll(List.of("add", "remove"));
        else if (args.length == 3 && List.of("category", "cat").contains(args[0].toLowerCase(Locale.ROOT))) out.addAll(List.of("on", "off"));
        else if (args.length == 2 && List.of("organize", "deposit", "tidy", "scan").contains(args[0].toLowerCase(Locale.ROOT))) out.addAll(List.of("sel", "hotbar"));
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }

    /** Takes /sort from ArcShop (which keeps /arcshop:sort). */
    public static void takeOver(ArcEmployees plugin) {
        org.bukkit.command.PluginCommand pc = plugin.getCommand("sort");
        if (pc == null) return;
        Map<String, Command> known = Bukkit.getCommandMap().getKnownCommands();
        known.put("sort", pc);
        try {
            Bukkit.getServer().getClass().getMethod("syncCommands").invoke(Bukkit.getServer());
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Older servers: the next restart or join picks it up.
        }
        for (Player p : Bukkit.getOnlinePlayers()) p.updateCommands();
    }
}
