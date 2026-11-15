package site.remotearc.employees.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.Employee;
import site.remotearc.employees.ZonePresets;
import site.remotearc.employees.job.JobType;
import site.remotearc.employees.util.WorkArea;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** An employee's no-work zones: its own WorldEdit boxes and the presets it was given. */
final class ZonesMenu extends Menu {

    private final Employee e;
    private final Menu back;

    ZonesMenu(ArcEmployees plugin, Employee e, Menu back) {
        super(plugin, 6, Component.text(e.name + "'s no-work zones"));
        this.e = e;
        this.back = back;
    }

    private static String box(WorkArea z) {
        return z.describe() + ", y " + z.minY() + " to " + z.maxY();
    }

    @Override
    protected void draw() {
        for (int s = 0; s < 54; s++) button(s, filler(), null);
        boolean guard = e.job == JobType.GUARD || e.job == JobType.BODYGUARD;
        button(4, icon(Material.BOOK, "<gold>No-work zones", List.of(
                "<gray>Places " + e.name + " leaves alone: no",
                guard ? "<gray>fighting mobs there (a golem farm's" : "<gray>mining, chopping, harvesting, building",
                guard ? "<gray>zombie). Walking through is fine." : "<gray>or picking up there. Walking through is fine.",
                "<gray>A preset is a named zone you can give",
                "<gray>to several employees at once.")), null);

        var zp = plugin.zonePresets();
        int slot = 9;
        for (int i = 0; i < e.zones.size() && slot < 45; i++) {
            WorkArea z = e.zones.get(i);
            int n = i + 1;
            button(slot++, icon(Material.PAPER, "<white>Zone " + n, List.of("<gray>" + box(z), "",
                    "<yellow>Left-click to show it", "<yellow>Shift-click to remove it")), (p, c) -> {
                if (c.isShiftClick()) {
                    run(p, "zone " + ref(p, e) + " remove " + n);
                    redraw();
                } else {
                    p.closeInventory();
                    AreaPreview.show(plugin, p, z, 300);
                }
            });
        }
        for (String name : new ArrayList<>(e.zonePresets)) {
            if (slot >= 45) break;
            WorkArea z = zp.get(e.owner, name);
            button(slot++, glow(icon(Material.FILLED_MAP, "<aqua>" + name + " <gray>(preset)", List.of(
                    z == null ? "<red>Deleted" : "<gray>" + box(z), "",
                    "<yellow>Left-click to show it", "<yellow>Shift-click to take it off " + e.name))), (p, c) -> {
                if (c.isShiftClick() || z == null) {
                    run(p, "zone " + ref(p, e) + " remove " + name);
                    redraw();
                } else {
                    p.closeInventory();
                    AreaPreview.show(plugin, p, z, 300);
                }
            });
        }

        boolean we = Bukkit.getPluginManager().isPluginEnabled("WorldEdit");
        if (we) {
            button(47, icon(Material.WOODEN_AXE, "<green>Add my WorldEdit selection", List.of(
                    "<gray>Select two corners with //wand first.", "", "<yellow>Click to add")), (p, c) -> {
                p.closeInventory();
                run(p, "zone " + ref(p, e) + " add");
            });
        }
        button(48, icon(Material.MAP, "<green>Add a preset", List.of(
                "<gray>You have " + zp.of(e.owner).size() + " preset(s).", "", "<yellow>Click to choose")),
                (p, c) -> new PresetsMenu(plugin, e, this).open(p));
        button(50, icon(Material.SPYGLASS, "<aqua>Show them all", List.of("", "<yellow>Click to outline every zone")), (p, c) -> {
            p.closeInventory();
            run(p, "zone " + ref(p, e) + " show");
        });
        button(51, icon(Material.BARRIER, "<red>Remove all", List.of("<gray>Its own zones and presets", "<gray>(the presets themselves stay).",
                "", "<yellow>Shift-click to clear")), (p, c) -> {
            if (!c.isShiftClick()) return;
            run(p, "zone " + ref(p, e) + " clear");
            redraw();
        });
        button(45, back(), (p, c) -> back.open(p));
    }

    /** The owner's presets: click to give one to the employee; save, show or delete them. */
    static final class PresetsMenu extends Menu {

        private final Employee e;
        private final Menu back;

        PresetsMenu(ArcEmployees plugin, Employee e, Menu back) {
            super(plugin, 6, Component.text("Zone presets"));
            this.e = e;
            this.back = back;
        }

        @Override
        protected void draw() {
            for (int s = 0; s < 54; s++) button(s, filler(), null);
            var zp = plugin.zonePresets();
            int slot = 0;
            for (Map.Entry<String, WorkArea> en : zp.of(e.owner).entrySet()) {
                if (slot >= 45) break;
                String name = en.getKey();
                boolean has = e.zonePresets.stream().anyMatch(n -> n.equalsIgnoreCase(name));
                var it = icon(has ? Material.FILLED_MAP : Material.MAP, "<aqua>" + name, List.of(
                        "<gray>" + box(en.getValue()),
                        "<gray>Used by " + zp.users(e.owner, name) + " employee(s)",
                        "", has ? "<green>" + e.name + " has it" : "<yellow>Click to give it to " + e.name,
                        "<yellow>Right-click to show it", "<yellow>Shift-right-click to delete the preset"));
                button(slot++, has ? glow(it) : it, (p, c) -> {
                    if (c.isShiftClick() && c.isRightClick()) {
                        run(p, "presets delete " + name);
                        redraw();
                    } else if (c.isRightClick()) {
                        p.closeInventory();
                        run(p, "presets show " + name);
                    } else if (!has) {
                        run(p, "zone " + ref(p, e) + " add " + name);
                        back.open(p);
                    }
                });
            }
            if (Bukkit.getPluginManager().isPluginEnabled("WorldEdit")) {
                button(49, icon(Material.WRITABLE_BOOK, "<green>Save my selection as a preset", List.of(
                        "<gray>Select two corners with //wand,", "<gray>then name it (a name used before",
                        "<gray>moves that preset for everyone).", "", "<yellow>Click, then type a name")), (p, c) ->
                        Prompt.ask(p, "Name for the preset (letters, numbers, - and _)?", text -> {
                            if (!ZonePresets.validName(text)) {
                                site.remotearc.employees.util.Msg.error(p, "Use 1-24 letters, numbers, - or _.");
                                return;
                            }
                            run(p, "presets save " + text);
                            open(p);
                        }));
            }
            button(45, back(), (p, c) -> back.open(p));
        }
    }
}
