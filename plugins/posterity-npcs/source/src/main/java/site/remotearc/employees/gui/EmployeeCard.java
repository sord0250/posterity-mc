package site.remotearc.employees.gui;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import site.remotearc.employees.Activity;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.Employee;
import site.remotearc.employees.Food;
import site.remotearc.employees.job.JobType;
import site.remotearc.employees.storage.StorageTarget;
import site.remotearc.employees.util.Msg;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * An employee's card: what you see when you click one. Who it is, what it's
 * doing right now, how it's fed and housed, its day, and the handful of
 * things you do most (where it works, where things go, its pantry, pause,
 * fetch). Everything else is under More settings (the full menu).
 */
public final class EmployeeCard {

    private EmployeeCard() {}

    public static void show(ArcEmployees plugin, Player p, Employee e) {
        if (!plugin.manager().all().contains(e)) {
            Msg.error(p, e.name + " doesn't work for you any more.");
            return;
        }
        // Kept to just the glance info so the buttons below never need a scroll to reach;
        // everything else (food, home, day, work area, progress) is one click away in Details.
        List<String> lines = new ArrayList<>();
        lines.add(Activity.mini(plugin, e));
        lines.add(moodLine(plugin, e));

        Menu more = new EmployeeMenu(plugin, e, null);
        List<Dialogs.Button> b = new ArrayList<>();
        b.add(Dialogs.button("<white>Details", "<gray>Progress, personality, food, home and schedule.", pl -> details(plugin, pl, e)));
        if (e.job == JobType.BUILDER) {
            b.add(Dialogs.button("<aqua>Build", "<gray>Blueprints and build controls.", pl -> new BuildMenu(plugin, e, more).open(pl)));
        } else {
            b.add(Dialogs.button("<aqua>Set work area", "<gray>Leave your body and fly over " + e.name + "'s site:\n<gray>click two corners to mark the area.\n<dark_gray>(Other ways, and showing it: More settings.)",
                    pl -> OutOfBody.start(plugin, pl, e, OutOfBody.Goal.AREA)));
        }
        b.add(Dialogs.button("<aqua>Storage", "<gray>Where " + e.name + " delivers: " + Describe.esc(e.storage.describe()),
                pl -> new StorageMenu(plugin, e, more, 0).open(pl)));
        b.add(Dialogs.button("<aqua>Pantry", "<gray>Where " + e.name + " gets food: " + Describe.esc(Food.pantryName(plugin, e)),
                pl -> pantry(plugin, pl, e)));
        if (plugin.settings().homesEnabled) {
            b.add(Dialogs.button(e.house == null ? "<gold>Find a home" : "<aqua>Home", e.house == null
                    ? "<gray>Without a home they're unhappy and slower." : "<gray>" + Describe.esc(e.house.describe()),
                    pl -> new HomeMenu(plugin, e, more, 0).open(pl)));
        }
        b.add(Dialogs.button("<aqua>Backpack", "<gray>What " + e.name + " is carrying. Seeds, fuel and\n<gray>blocks put here are used first.",
                pl -> pl.openInventory(e.backpack)));
        b.add(e.paused
                ? Dialogs.button("<green>Resume work", "<gray>Back to work.", pl -> {
                    plugin.manager().setPaused(e, false);
                    show(plugin, pl, e);
                })
                : Dialogs.button("<yellow>Pause", "<gray>Stops work (and eating) until you resume.", pl -> {
                    plugin.manager().setPaused(e, true);
                    show(plugin, pl, e);
                }));
        b.add(Dialogs.button("<aqua>Bring here", e.job == JobType.BUILDER ? "<gray>Moves them to you (the build stays put)."
                : "<gray>Moves them and their work area to you,\n<gray>keeping its shape.", pl -> new ConfirmMenu(plugin,
                "Bring " + e.name + " here?", "<green>Yes, move " + e.name + (e.job == JobType.BUILDER ? "" : " and the area"), x -> {
            x.closeInventory();
            x.performCommand("employees move " + ref(x, e));
        }, x -> show(plugin, x, e)).open(pl)));
        if (plugin.avatars().has(e.id)) {
            b.add(Dialogs.button("<red>Remove avatar", "<gray>Takes away the life-size stand-in for " + e.name
                    + " in your control room.\n<dark_gray>" + e.name + " keeps working either way.", pl -> {
                plugin.avatars().remove(e.id);
                Msg.send(pl, "<gray>" + e.name + "'s avatar is gone.");
                show(plugin, pl, e);
            }));
        }
        b.add(Dialogs.button("<white>More settings…", "<gray>Everything else: filters, no-work zones, selling,\n<gray>skin, name, boards and more.",
                more::open));
        b.add(Dialogs.button("<red>Fire", "<gray>Their backpack is handed to you.", pl -> new ConfirmMenu(plugin,
                "Fire " + e.name + "?", "<red>Yes, fire " + e.name, x -> {
            plugin.manager().fire(e, x);
            x.closeInventory();
            Msg.send(x, "<gray>You fired " + e.name + ".");
        }, x -> show(plugin, x, e)).open(pl)));

        Dialogs.show(plugin, p, "<gold>" + e.name + "</gold> <gray>· " + e.job.display()
                + (e.owner.equals(p.getUniqueId()) ? "" : " <dark_gray>(" + Describe.esc(e.ownerName()) + "'s)"), lines, b, 3,
                Dialogs.close("Done"));
    }

    /** "Mood: content", or why not and what it costs. */
    private static String moodLine(ArcEmployees plugin, Employee e) {
        List<String> why = new ArrayList<>();
        if (plugin.manager().homeless(e)) why.add("no home, so they work at " + Math.round(plugin.settings().homelessWorkSpeed * 100) + "% speed");
        if (Food.enabled(plugin) && e.food <= 0) why.add("too hungry to work");
        if (why.isEmpty()) return "<gray>Mood: <green>content";
        return "<gray>Mood: <gold>unhappy</gold> <gray>(" + String.join("; ", why) + ")";
    }

    /** Everything else about the employee: progress, personality, food, home, day and work area, each in its own section. */
    private static void details(ArcEmployees plugin, Player p, Employee e) {
        if (!plugin.manager().all().contains(e)) {
            Msg.error(p, e.name + " doesn't work for you any more.");
            return;
        }
        List<String> lines = new ArrayList<>();
        List<String> progress = new ArrayList<>();
        String detail = e.worker == null ? null : e.worker.detail();
        if (detail != null) progress.add("<gray>" + Describe.esc(detail));
        if (e.personality != null && plugin.settings().personalitiesEnabled) {
            progress.add("<gray>Personality: <light_purple>" + e.personality.display + "</light_purple> <dark_gray>(" + e.personality.does + ")");
        }
        if (!progress.isEmpty()) lines.add(section("Progress", progress.toArray(String[]::new)));
        lines.add(section("Food", Describe.foodLine(plugin, e)));
        if (plugin.settings().homesEnabled) lines.add(section("Home", site.remotearc.employees.HomeSites.describe(plugin, e)));
        lines.add(section("Day", Describe.dayLine(plugin, e)));
        String where = e.job == JobType.BUILDER
                ? (e.build == null ? "<gray>Builds: <white>nothing yet (pick a blueprint)" : "<gray>Builds: <white>" + Describe.esc(e.build.blueprint))
                : e.area == null ? "<gold>No work area set." : "<gray>Works: <white>" + e.area.describe();
        lines.add(section("Work", where, "<gray>Delivers to: <white>" + Describe.esc(e.storage.describe())));
        if (!e.isLoaded()) lines.add("<dark_gray>(Not loaded right now: nobody is near and the site isn't kept loaded.)");

        Dialogs.show(plugin, p, "<gold>" + e.name + "</gold> <gray>· Details", lines, List.of(Dialogs.back(pl -> show(plugin, pl, e))), 1, null);
    }

    private static final String RULE = "<dark_gray><st>" + " ".repeat(40) + "</st>";

    /** One body entry: a title between two rules, then its lines, so each topic reads as its own section. */
    private static String section(String title, String... lines) {
        StringBuilder sb = new StringBuilder(RULE).append("\n<gold><b>").append(title).append("</b></gold>\n").append(RULE);
        for (String l : lines) if (l != null && !l.isEmpty()) sb.append('\n').append(l);
        return sb.toString();
    }

    /** Where it gets its food: pick a chest (click it), or use wherever it delivers. */
    public static void pantry(ArcEmployees plugin, Player p, Employee e) {
        List<String> lines = new ArrayList<>();
        lines.add(Describe.foodLine(plugin, e));
        lines.add("<gray>" + e.name + " takes food from the pantry when they run low. Any food will do (not rotten flesh"
                + " and the like); better food lasts longer: a bread is about a day, a cooked steak nearly two.");
        lines.add("<gray>Now: <white>" + Describe.esc(Food.pantryName(plugin, e))
                + (e.pantry.kind() == StorageTarget.Kind.NONE ? " <dark_gray>(where they deliver)" : ""));
        List<Dialogs.Button> b = new ArrayList<>();
        b.add(Dialogs.button("<aqua>Pick from above", "<gray>Leave your body, fly over and click the chest.",
                pl -> OutOfBody.start(plugin, pl, e, OutOfBody.Goal.PANTRY)));
        b.add(Dialogs.button("<aqua>Click a chest", "<gray>Then click the chest (or barrel) to use.", pl ->
                ChestPick.ask(pl, "the chest for " + e.name + "'s food", (Block block) -> {
                    if (!plugin.settings().chestsEnabled) {
                        Msg.error(pl, "Placed chests are turned off in the config.");
                        return;
                    }
                    if (!plugin.estate().mayWork(e.owner, block.getWorld(), block.getX(), block.getY(), block.getZ())) {
                        Msg.error(pl, "That chest is on someone else's land.");
                        return;
                    }
                    plugin.manager().setPantry(e, StorageTarget.chest(block));
                    Msg.send(pl, "<green>" + e.name + " will take their food from that "
                            + block.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ') + ".</green> <gray>Keep some food in it.");
                })));
        b.add(Dialogs.button("<white>Where they deliver", "<gray>Eat from the chest their work goes to.", pl -> {
            plugin.manager().setPantry(e, StorageTarget.NONE);
            show(plugin, pl, e);
        }));
        Dialogs.show(plugin, p, "<gold>" + e.name + "'s pantry", lines, b, 2, Dialogs.back(pl -> show(plugin, pl, e)));
    }

    private static String ref(Player p, Employee e) {
        return e.owner.equals(p.getUniqueId()) ? e.name : e.ownerName() + ":" + e.name;
    }
}
