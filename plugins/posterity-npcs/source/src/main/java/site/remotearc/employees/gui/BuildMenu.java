package site.remotearc.employees.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.Employee;
import site.remotearc.employees.build.BuildOrder;

import java.util.ArrayList;
import java.util.List;

/** A builder's blueprint and the controls for lining it up and running it. */
final class BuildMenu extends Menu {

    private final Employee e;
    private final Menu back;

    BuildMenu(ArcEmployees plugin, Employee e, Menu back) {
        super(plugin, 5, Component.text(e.name + "'s build"));
        this.e = e;
        this.back = back;
    }

    private org.bukkit.entity.Player viewer;

    @Override
    public void openFor(org.bukkit.entity.Player p) {
        viewer = p;
    }

    @Override
    protected void draw() {
        for (int s = 0; s < 45; s++) button(s, filler(), null);
        BuildOrder o = e.build;
        List<String> info = new ArrayList<>();
        if (o == null) {
            info.add("<gray>Nothing to build yet.");
        } else {
            info.add("<gray>Blueprint: <white>" + Describe.esc(o.blueprint));
            if (o.plan != null) {
                info.add("<gray>Size: <white>" + o.sizeX() + "x" + o.plan.sy + "x" + o.sizeZ() + ", " + o.plan.solidCount() + " blocks");
                info.add("<gray>Facing: <white>" + o.describeTurn());
            }
            String detail = e.worker == null ? null : e.worker.detail();
            if (detail != null) info.add("<gray>" + Describe.esc(detail));
            info.add("<gray>Status: <white>" + Describe.esc(e.status));
        }
        button(4, icon(Material.BRICKS, "<gold>" + (o == null ? "No blueprint" : o.blueprint), info), null);

        button(10, icon(Material.BOOKSHELF, "<aqua>Choose a blueprint", List.of(
                "<gray>Pick from the blueprint folder; it is", "<gray>placed with its bottom centre where", "<gray>you stand.",
                "", "<yellow>Click to browse")), (p, c) -> new BlueprintMenu(plugin, e, this, 0).open(p));

        boolean planning = viewer != null && e.id.equals(site.remotearc.employees.build.PlanMode.planningFor(viewer));
        var plan = icon(Material.FILLED_MAP, planning ? "<green>Planning: click when done" : "<aqua>Plan it yourself, in the world", planning
                ? List.of("<gray>Keeps the plan; " + e.name + " can start.", "", "<yellow>Click: done", "<yellow>Right-click: cancel the changes")
                : List.of("<gray>Build what you want right where it", "<gray>goes, by hand or with WorldEdit", "<gray>(//set, //walls, //paste, brushes).",
                "<gray>Nothing is real: it's only " + e.name + "'s", "<gray>plan, shown to you as ghost blocks.", "<gray>Then " + e.name + " builds it from storage.",
                "", (o != null && o.planned ? "<yellow>Click to carry on planning" : "<yellow>Click to start planning"),
                o != null && o.planned ? "<yellow>Right-click: a new plan (this one is kept)" : ""));
        button(11, planning ? glow(plan) : plan, (p, c) -> {
            p.closeInventory();
            if (planning) p.performCommand("employees plan " + (c.isRightClick() ? "cancel" : "done"));
            else run(p, "build " + ref(p, e) + " plan" + (c.isRightClick() && o != null && o.planned ? " new" : ""));
        });

        if (o != null) {
            var rep = icon(o.repair ? Material.ANVIL : Material.CHIPPED_ANVIL, "<aqua>Repair mode: " + (o.repair ? "<green>on" : "<red>off"), List.of(
                    "<gray>Once it's built, " + e.name + " looks over",
                    "<gray>it every " + plugin.settings().repairCheckSeconds + "s and puts back blocks",
                    "<gray>that went missing (creepers, fire,",
                    "<gray>griefing), from your storage. It only",
                    "<gray>fills gaps: nothing is cleared or swapped,",
                    "<gray>and grass, crops and leaves are left be.",
                    "", "<yellow>Click to turn " + (o.repair ? "off" : "on")));
            button(12, o.repair ? glow(rep) : rep, (p, c) -> {
                run(p, "repair " + ref(p, e) + " " + (o.repair ? "off" : "on"));
                redraw();
            });
        }
        if (org.bukkit.Bukkit.getPluginManager().isPluginEnabled("WorldEdit")) {
            button(14, icon(Material.WOODEN_AXE, "<aqua>Look after an existing build", List.of(
                    "<gray>Select it with //wand, then click:",
                    "<gray>it's saved as it is now, and " + e.name,
                    "<gray>repairs it from then on. Your base,",
                    "<gray>a farm, a wall... Click again after",
                    "<gray>changing it on purpose.",
                    "", "<yellow>Click to save your selection")), (p, c) -> {
                p.closeInventory();
                run(p, "repair " + ref(p, e) + " snapshot");
            });
        }

        if (o != null) {
            boolean locked = o.started && !o.finished;
            String lockNote = locked ? "<red>Started: cancel to change it" : "<gray>Only while the preview is showing";
            button(19, icon(Material.ENDER_EYE, "<aqua>Preview", List.of("<gray>Ghost blocks only you can see. They", "<gray>stay while you're near the build.",
                    "", "<yellow>Click to show", "<yellow>Right-click to hide")), (p, c) -> {
                p.closeInventory();
                run(p, "build " + ref(p, e) + " preview" + (c.isRightClick() ? " off" : ""));
            });
            button(20, icon(Material.COMPASS, "<aqua>Turn 90° clockwise", List.of(lockNote, "<yellow>Click to turn")), (p, c) -> {
                p.closeInventory();
                run(p, "build " + ref(p, e) + " rotate 90");
            });
            button(21, icon(Material.RECOVERY_COMPASS, "<aqua>Turn 90° anticlockwise", List.of(lockNote, "<yellow>Click to turn")), (p, c) -> {
                p.closeInventory();
                run(p, "build " + ref(p, e) + " rotate -90");
            });
            button(22, icon(Material.GLASS_PANE, "<aqua>Mirror", List.of(lockNote, "<yellow>Click to mirror")), (p, c) -> {
                p.closeInventory();
                run(p, "build " + ref(p, e) + " flip");
            });
            button(23, icon(Material.LEAD, "<aqua>Move it to where you stand", List.of("<gray>Any world: tried it out in the studio?",
                    "<gray>Move it to the real spot. Swaps and", "<gray>settings come along, and so does " + e.name + ".",
                    locked ? "<yellow>Started: it starts over at the new spot" : "", "<yellow>Click to move")), (p, c) -> {
                p.closeInventory();
                run(p, "build " + ref(p, e) + " move");
            });
            button(24, icon(Material.PISTON, "<aqua>Shift it", List.of("<gray>Nudge it forward, back, left,", "<gray>right, up or down: arrows as", "<gray>you're facing.", lockNote, "<yellow>Click to open")), (p, c) ->
                    new ShiftMenu(plugin, e, this).open(p));
            button(25, icon(Material.CHEST, "<aqua>Materials and swaps", List.of("<gray>What's still needed, what's missing", "<gray>and what that would cost. Swap a block",
                    "<gray>for another, buy what's missing, or", "<gray>leave out the ground it stands on.", "", "<yellow>Click to open")), (p, c) ->
                    new MaterialsMenu(plugin, e, 0, this).open(p));
            button(26, icon(o.hangoutLabel != null ? Material.CAMPFIRE : Material.OAK_SIGN,
                    "<aqua>Hangout spot: " + (o.hangoutLabel != null ? "<green>" + Describe.esc(o.hangoutLabel) : "<red>off"), List.of(
                    "<gray>Once it's finished, your off-duty", "<gray>employees (and ones from anyone", "<gray>who trusts you) may wander here",
                    "<gray>instead of loitering at their own post.", "", "<yellow>Click to name it", o.hangoutLabel != null ? "<yellow>Right-click to turn off" : "")),
                    (p, c) -> {
                if (c.isRightClick() && o.hangoutLabel != null) {
                    run(p, "build " + ref(p, e) + " label");
                    redraw();
                    return;
                }
                p.closeInventory();
                Prompt.ask(p, "Name this hangout spot (a tavern, a park...)?", text -> {
                    run(p, "build " + ref(p, e) + " label " + text);
                    open(p);
                });
            });
            button(27, icon(o.hangoutArea != null ? Material.ENDER_EYE : Material.SPYGLASS, "<aqua>Pick the hangout spot's area", List.of(
                    "<gray>Leave your body and fly over the site:", "<gray>click one corner, then the other.",
                    "<gray>Off-duty employees stand somewhere", "<gray>in it at random, instead of always",
                    "<gray>the build's own placement point.", "", "<yellow>Click to leave your body")), (p, c) ->
                    OutOfBody.start(plugin, p, e, OutOfBody.Goal.HANGOUT));
            var around = icon(o.clearAround ? Material.DIAMOND_SHOVEL : Material.GRASS_BLOCK, "<aqua>Space round the build: " + (o.clearAround ? "<yellow>dug out" : "<green>left alone"), List.of(
                    "<gray>Blueprints often come with empty space", "<gray>round the outside. Dug out, whatever's", "<gray>there goes (your ground, a neighbour's",
                    "<gray>build). Left alone, only space inside", "<gray>the build (rooms, doorways) is cleared.", "",
                    "<yellow>Click to switch (shows what gets dug)", "<yellow>Right-click: just show it"));
            var follow = icon(o.follow ? Material.WRITABLE_BOOK : Material.BOOK, "<aqua>Keep up with my changes: " + (o.follow ? "<green>on" : "<red>off"), List.of(
                    "<gray>Once it's finished, blocks you place,", "<gray>break or change with WorldEdit in it",
                    "<gray>(or 3 blocks round it) become part of", "<gray>the plan: " + e.name + " never undoes them,", "<gray>and building out grows the build.",
                    "<gray>Off: the plan stays as it is.", "", "<yellow>Click to turn " + (o.follow ? "off" : "on")));
            button(13, o.follow ? glow(follow) : follow, (p, c) -> {
                run(p, "build " + ref(p, e) + " follow " + (o.follow ? "off" : "on"));
                redraw();
            });
            button(30, around, (p, c) -> {
                p.closeInventory();
                run(p, "build " + ref(p, e) + (c.isRightClick() ? " preview digging" : " around " + (o.clearAround ? "leave" : "clear")));
            });
            button(29, icon(o.clearAir ? Material.IRON_SHOVEL : Material.WOODEN_SHOVEL, "<aqua>Clear the site: " + (o.clearAir ? "<green>on" : "<red>off"), List.of(
                    "<gray>On: breaks whatever is in the way", "<gray>where the blueprint has air.", "", "<yellow>Click to switch")), (p, c) -> {
                run(p, "build " + ref(p, e) + " clear " + (o.clearAir ? "off" : "on"));
                redraw();
            });
            button(28, icon(o.breakBlocks ? Material.NETHERITE_PICKAXE : Material.SHIELD, "<aqua>Break blocks in the way: " + (o.breakBlocks ? "<green>on" : "<red>off"), List.of(
                    "<gray>On: breaks a block that's in the way", "<gray>of one the blueprint wants there.",
                    "<gray>Off: leaves it standing and skips", "<gray>that spot instead.", "", "<yellow>Click to switch")), (p, c) -> {
                run(p, "build " + ref(p, e) + " break " + (o.breakBlocks ? "off" : "on"));
                redraw();
            });
            var craft = icon(e.craft ? Material.CRAFTING_TABLE : Material.BARREL, "<aqua>Craft missing blocks: " + (e.craft ? "<green>on" : "<red>off"), List.of(
                    "<gray>On: blocks storage doesn't have are", "<gray>crafted from spare materials (logs", "<gray>into planks and stairs...). It never",
                    "<gray>uses up what the build itself needs.", "", "<yellow>Click to turn " + (e.craft ? "off" : "on")));
            button(35, e.craft ? glow(craft) : craft, (p, c) -> {
                run(p, "craft " + ref(p, e) + " " + (e.craft ? "off" : "on"));
                redraw();
            });
            if (!o.started || o.finished) {
                button(31, glow(icon(Material.LIME_CONCRETE, o.finished ? "<green>Check it over again" : "<green>Start building", List.of(
                        "<gray>Materials come from the backpack", "<gray>and its supply.", "", "<yellow>Click to start"))), (p, c) -> {
                    p.closeInventory();
                    run(p, "build " + ref(p, e) + " start");
                });
            } else {
                button(31, icon(e.paused ? Material.LIME_DYE : Material.GRAY_DYE, e.paused ? "<green>Resume" : "<gray>Pause", List.of("", "<yellow>Click")), (p, c) -> {
                    plugin.manager().setPaused(e, !e.paused);
                    redraw();
                });
            }
            button(34, icon(Material.IRON_PICKAXE, o.dismantle ? "<yellow>Taking it down..." : "<red>Take it down", List.of(
                    "<gray>" + e.name + " breaks what it built, top", "<gray>first, and takes the blocks back to", "<gray>storage. Things added since stay,",
                    "<gray>and chests with something in them.", "", o.dismantle ? "<gray>Cancel stops it." : "<yellow>Click to take it down")), o.dismantle ? null : (p, c) ->
                    new ConfirmMenu(plugin, "Take down " + o.blueprint + "?", "<red>Yes, take it down", pl -> {
                        pl.closeInventory();
                        run(pl, "build " + ref(pl, e) + " dismantle confirm");
                    }, this::open).open(p));
            button(33, icon(Material.BARRIER, "<red>Cancel this build", List.of("<gray>What's built stays.", "<gray>Leftover materials go back", "<gray>to its storage.", "", "<yellow>Click to cancel")), (p, c) ->
                    new ConfirmMenu(plugin, "Cancel " + o.blueprint + "?", "<red>Yes, stop building", pl -> {
                        run(pl, "build " + ref(pl, e) + " cancel");
                        open(pl);
                    }, this::open).open(p));
        }
        if (plugin.uploads().baseUrl() != null) {
            button(16, icon(Material.WRITABLE_BOOK, "<aqua>Upload a blueprint from your computer", List.of(
                    "<gray>Gives you a link to a web page:", "<gray>drop .schem, .schematic, .litematic,", "<gray>.nbt or .zip files on it and",
                    "<gray>they're added here.", "<gray>Best for files from Planet Minecraft,", "<gray>Wormhole and the like.",
                    "<dark_gray>Needs employees.blueprints.download", "", "<yellow>Click for the link")), (p, c) -> {
                p.closeInventory();
                run(p, "blueprints upload");
            });
        }
        if (plugin.settings().downloadsEnabled) {
            button(17, icon(Material.HOPPER, "<aqua>Download a blueprint from a link", List.of(
                    "<gray>A link to the file, or to a page", "<gray>with a download link on it.",
                    "<gray>Dropbox, Google Drive and GitHub", "<gray>share links work too.",
                    "<dark_gray>Needs employees.blueprints.download", "", "<yellow>Click, then paste the link")), (p, c) ->
                    Prompt.ask(p, "Paste the link:", link -> run(p, "blueprints download " + link)));
        }
        button(36, back(), (p, c) -> (back != null ? back : new EmployeeMenu(plugin, e, null)).open(p));
    }
}
