package site.remotearc.employees.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.Employee;
import site.remotearc.employees.storage.StorageTarget;
import site.remotearc.employees.storage.Supply;

import java.util.List;

/** Where a builder, smelter or rancher takes materials from. */
final class SupplyMenu extends Menu {

    private final Employee e;
    private final Menu back;

    SupplyMenu(ArcEmployees plugin, Employee e, Menu back) {
        super(plugin, 4, Component.text(e.name + "'s supply"));
        this.e = e;
        this.back = back;
    }

    @Override
    protected void draw() {
        for (int s = 0; s < 36; s++) button(s, filler(), null);
        button(4, icon(Material.PAPER, "<aqua>Takes materials from", concat(wrap("<white>", Describe.esc(Supply.describe(plugin, e)), 40),
                "<gray>Only plain items (nothing renamed).")), null);
        button(10, icon(e.teamSupply ? Material.LIME_DYE : Material.GRAY_DYE, "<aqua>Team storage: " + (e.teamSupply ? "<green>on" : "<red>off"),
                List.of("<gray>Use what your other employees", "<gray>deliver (miners, lumberjacks...).", "",
                        "<yellow>Click to turn " + (e.teamSupply ? "off" : "on"))), (p, c) -> {
            run(p, "supply " + ref(p, e) + " team " + (e.teamSupply ? "off" : "on"));
            redraw();
        });
        button(12, icon(Material.ENDER_CHEST, "<aqua>Add a /chest number", List.of(
                "<gray>E.g. the chest with your custom", "<gray>blocks for a build.", "", "<yellow>Click, then type the number")), (p, c) -> {
            String who = ref(p, e);
            Prompt.ask(p, "Which /chest number should " + e.name + " take from?", text -> {
                run(p, "supply " + who + " add " + text.replace("/chest", "").trim());
                open(p);
            });
        });
        button(14, icon(Material.CHEST, "<aqua>Add a chest", List.of(
                "<gray>A chest, barrel or other container", "<gray>it should take materials from.",
                "", "<yellow>Click, then click the chest in the world")), (p, c) ->
                ChestPick.ask(p, "the container " + e.name + " should take materials from", b -> {
                    StorageTarget t = StorageTarget.chest(b);
                    String why = t.problemFor(p);
                    if (why != null) {
                        site.remotearc.employees.util.Msg.error(p, why);
                    } else if (!e.supply.contains(t)) {
                        e.supply.add(t);
                        plugin.manager().markDirty();
                        plugin.manager().refreshTickets();
                        site.remotearc.employees.util.Msg.send(p, "<green>" + e.name + " now also takes materials from the " + Describe.esc(t.describe()) + ".");
                    }
                    open(p);
                }));
        int slot = 18;
        for (StorageTarget t : e.supply) {
            if (slot >= 27) break;
            button(slot++, icon(t.kind() == StorageTarget.Kind.VIRTUAL ? Material.ENDER_CHEST : Material.CHEST, "<aqua>" + t.describe(),
                    List.of("<gray>Added by you.", "", "<yellow>Click to remove")), (p, c) -> {
                String what = t.kind() == StorageTarget.Kind.VIRTUAL ? String.valueOf(t.chestNumber()) : null;
                if (what == null) {
                    e.supply.remove(t);
                    plugin.manager().markDirty();
                } else {
                    run(p, "supply " + ref(p, e) + " remove " + what);
                }
                redraw();
            });
        }
        button(27, back(), (p, c) -> back.open(p));
    }
}
