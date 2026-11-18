package site.remotearc.employees.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.Employee;
import site.remotearc.employees.storage.StorageTarget;
import site.remotearc.employees.storage.Supply;
import site.remotearc.employees.util.Msg;

import java.util.ArrayList;
import java.util.List;

/** Where a builder, smelter or rancher takes materials from. */
final class SupplyMenu extends Menu {

    private final Employee e;
    private final Menu back;

    SupplyMenu(ArcEmployees plugin, Employee e, Menu back) {
        super(plugin, 1, Component.empty());
        this.e = e;
        this.back = back;
    }

    @Override
    protected void draw() {}

    @Override
    public void open(Player p) {
        List<String> lines = new ArrayList<>();
        lines.add("<gray>Takes materials from: <white>" + Describe.esc(Supply.describe(plugin, e))
                + "\n<gray>Only plain items (nothing renamed).");

        List<Dialogs.Button> b = new ArrayList<>();
        b.add(Dialogs.button("<aqua>Team storage: " + (e.teamSupply ? "<green>on" : "<red>off"),
                "<gray>Use what your other employees deliver (miners, lumberjacks...).", pl -> {
            run(pl, "supply " + ref(pl, e) + " team " + (e.teamSupply ? "off" : "on"));
            open(pl);
        }));
        b.add(Dialogs.button("<aqua>Add a /chest number", "<gray>E.g. the chest with your custom blocks for a build.", pl -> {
            String who = ref(pl, e);
            Prompt.ask(pl, "Which /chest number should " + e.name + " take from?", text -> {
                run(pl, "supply " + who + " add " + text.replace("/chest", "").trim());
                open(pl);
            });
        }));
        b.add(Dialogs.button("<aqua>Add a chest", "<gray>A chest, barrel or other container it should take materials from.", pl ->
                ChestPick.ask(pl, "the container " + e.name + " should take materials from", block -> {
            StorageTarget t = StorageTarget.chest(block);
            String why = t.problemFor(pl);
            if (why != null) {
                Msg.error(pl, why);
            } else if (!e.supply.contains(t)) {
                e.supply.add(t);
                plugin.manager().markDirty();
                plugin.manager().refreshTickets();
                Msg.send(pl, "<green>" + e.name + " now also takes materials from the " + Describe.esc(t.describe()) + ".");
            }
            open(pl);
        })));
        for (StorageTarget t : e.supply) {
            b.add(Dialogs.button("<aqua>" + t.describe(), "<gray>Added by you. <yellow>Click to remove.", pl -> {
                String what = t.kind() == StorageTarget.Kind.VIRTUAL ? String.valueOf(t.chestNumber()) : null;
                if (what == null) {
                    e.supply.remove(t);
                    plugin.manager().markDirty();
                } else {
                    run(pl, "supply " + ref(pl, e) + " remove " + what);
                }
                open(pl);
            }));
        }
        Dialogs.show(plugin, p, "<gold>" + e.name + "'s supply", lines, b, 2, Dialogs.back(pl -> back.open(pl)));
    }
}
