package site.remotearc.employees.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.Employee;
import site.remotearc.employees.ZonePresets;
import site.remotearc.employees.job.JobType;
import site.remotearc.employees.util.Msg;
import site.remotearc.employees.util.WorkArea;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** An employee's no-work zones: its own flown or WorldEdit boxes, and the presets it was given. */
final class ZonesMenu extends Menu {

    private static final int SHOWN = 12;

    private final Employee e;
    private final Menu back;

    ZonesMenu(ArcEmployees plugin, Employee e, Menu back) {
        super(plugin, 1, Component.empty());
        this.e = e;
        this.back = back;
    }

    private static String box(WorkArea z) {
        return z.describe() + ", y " + z.minY() + " to " + z.maxY();
    }

    @Override
    protected void draw() {}

    @Override
    public void open(Player p) {
        boolean guard = e.job == JobType.GUARD || e.job == JobType.BODYGUARD;
        List<String> lines = new ArrayList<>();
        lines.add("<gray>Places " + e.name + " leaves alone: no " + (guard ? "fighting mobs there (a golem farm's zombie)."
                : "mining, chopping, harvesting, building or picking up there.") + " Walking through is fine."
                + "\n<gray>A preset is a named zone you can give to several employees at once.");

        List<Dialogs.Button> b = new ArrayList<>();
        b.add(Dialogs.button("<green>Pick a zone from above", "<gray>Leave your body, fly over and click two corners.",
                pl -> OutOfBody.start(plugin, pl, e, OutOfBody.Goal.ZONE)));
        boolean we = Bukkit.getPluginManager().isPluginEnabled("WorldEdit");
        if (we) {
            b.add(Dialogs.button("<green>Add my WorldEdit selection", "<gray>Select two corners with //wand first.",
                    pl -> run(pl, "zone " + ref(pl, e) + " add")));
        }
        b.add(Dialogs.button("<green>Add a preset", "<gray>You have " + plugin.zonePresets().of(e.owner).size() + " preset(s).",
                pl -> new PresetsMenu(plugin, e, this).open(pl)));
        b.add(Dialogs.button("<aqua>Show them all", null, pl -> run(pl, "zone " + ref(pl, e) + " show")));
        b.add(Dialogs.button("<red>Remove all", "<gray>Its own zones and presets (the presets themselves stay).",
                pl -> new ConfirmMenu(plugin, "Remove all of " + e.name + "'s zones?", "<red>Yes, remove them all", x -> {
            run(x, "zone " + ref(x, e) + " clear");
        }, this::open).open(pl)));

        var zp = plugin.zonePresets();
        int shown = 0;
        for (int i = 0; i < e.zones.size(); i++) {
            if (shown++ >= SHOWN) {
                b.add(Dialogs.button("<gray>and " + (e.zones.size() - SHOWN) + " more…", null, pl -> {}));
                break;
            }
            WorkArea z = e.zones.get(i);
            int n = i + 1;
            b.add(Dialogs.button("<white>Zone " + n, "<gray>" + box(z) + "\n<yellow>Click to show it", pl -> {
                AreaPreview.show(plugin, pl, z, 300);
                open(pl);
            }));
            b.add(Dialogs.button("<red>Remove zone " + n, null, pl -> {
                run(pl, "zone " + ref(pl, e) + " remove " + n);
                open(pl);
            }));
        }
        shown = 0;
        for (String name : new ArrayList<>(e.zonePresets)) {
            if (shown++ >= SHOWN) {
                b.add(Dialogs.button("<gray>and " + (e.zonePresets.size() - SHOWN) + " more…", null, pl -> {}));
                break;
            }
            WorkArea z = zp.get(e.owner, name);
            b.add(Dialogs.button("<aqua>" + name + " <gray>(preset)", z == null ? "<red>Deleted" : "<gray>" + box(z) + "\n<yellow>Click to show it",
                    pl -> {
                if (z == null) {
                    run(pl, "zone " + ref(pl, e) + " remove " + name);
                } else {
                    AreaPreview.show(plugin, pl, z, 300);
                }
                open(pl);
            }));
            b.add(Dialogs.button("<red>Take off " + name, null, pl -> {
                run(pl, "zone " + ref(pl, e) + " remove " + name);
                open(pl);
            }));
        }

        Dialogs.show(plugin, p, "<gold>" + e.name + "'s no-work zones", lines, b, 2, Dialogs.back(pl -> back.open(pl)));
    }

    /** The owner's presets: click to give one to the employee; save, show or delete them. */
    static final class PresetsMenu extends Menu {

        private static final int SHOWN = 14;

        private final Employee e;
        private final Menu back;

        PresetsMenu(ArcEmployees plugin, Employee e, Menu back) {
            super(plugin, 1, Component.empty());
            this.e = e;
            this.back = back;
        }

        @Override
        protected void draw() {}

        @Override
        public void open(Player p) {
            var zp = plugin.zonePresets();
            List<String> lines = new ArrayList<>();
            List<Dialogs.Button> b = new ArrayList<>();
            int shown = 0;
            for (Map.Entry<String, WorkArea> en : zp.of(e.owner).entrySet()) {
                if (shown++ >= SHOWN) {
                    lines.add("<gray>and " + (zp.of(e.owner).size() - SHOWN) + " more preset(s) not shown.");
                    break;
                }
                String name = en.getKey();
                boolean has = e.zonePresets.stream().anyMatch(n -> n.equalsIgnoreCase(name));
                b.add(Dialogs.button(has ? "<green>" + name + " (has it)" : "<white>" + name, "<gray>" + box(en.getValue())
                        + "\n<gray>Used by " + zp.users(e.owner, name) + " employee(s)", pl -> {
                    if (!has) run(pl, "zone " + ref(pl, e) + " add " + name);
                    back.open(pl);
                }));
                b.add(Dialogs.button("<aqua>Show " + name, null, pl -> {
                    run(pl, "presets show " + name);
                    open(pl);
                }));
                b.add(Dialogs.button("<red>Delete " + name, null, pl -> new ConfirmMenu(plugin, "Delete the " + name + " preset?",
                        "<red>Yes, delete it", x -> {
                    run(x, "presets delete " + name);
                }, this::open).open(pl)));
            }
            if (Bukkit.getPluginManager().isPluginEnabled("WorldEdit")) {
                b.add(Dialogs.button("<green>Save my selection as a preset", "<gray>Select two corners with //wand, then name it"
                        + " (a name used before moves that preset for everyone).", pl -> Prompt.ask(pl, "Name for the preset (letters, numbers, - and _)?", text -> {
                    if (!ZonePresets.validName(text)) {
                        Msg.error(pl, "Use 1-24 letters, numbers, - or _.");
                        open(pl);
                        return;
                    }
                    run(pl, "presets save " + text);
                    open(pl);
                })));
            }
            if (b.isEmpty()) b.add(Dialogs.button("<gray>No presets yet", null, pl -> {}));
            Dialogs.show(plugin, p, "<gold>Zone presets", lines, b, 3, Dialogs.back(pl -> back.open(pl)));
        }
    }
}
