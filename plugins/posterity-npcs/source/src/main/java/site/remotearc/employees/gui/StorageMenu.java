package site.remotearc.employees.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.Employee;
import site.remotearc.employees.storage.StorageTarget;
import site.remotearc.employees.util.Msg;

import java.util.ArrayList;
import java.util.List;

/** Choose where an employee delivers: a /chest number, the chest you look at, or its backpack. */
final class StorageMenu extends Menu {

    private final Employee e;
    private final Menu back;

    /** {@code page} is kept only so existing callers compile; the /chest number is now typed, not paged through. */
    StorageMenu(ArcEmployees plugin, Employee e, Menu back, int page) {
        super(plugin, 1, Component.empty());
        this.e = e;
        this.back = back;
    }

    @Override
    protected void draw() {}

    @Override
    public void open(Player p) {
        StorageTarget st = e.storage;
        List<String> lines = new ArrayList<>();
        lines.add("<gray>Now: <white>" + Describe.esc(st.describe()));

        List<Dialogs.Button> b = new ArrayList<>();
        b.add(Dialogs.button("<aqua>Keep in backpack", "<gray>Nothing is delivered; 36 slots.", pl -> {
            run(pl, "storage " + ref(pl, e) + " backpack");
            back.open(pl);
        }));
        if (plugin.settings().chestsEnabled) {
            b.add(Dialogs.button("<aqua>The chest you're looking at", "<gray>Look at a chest or barrel (within 6 blocks) first.", pl -> {
                run(pl, "storage " + ref(pl, e) + " chest");
                back.open(pl);
            }));
            b.add(Dialogs.button("<aqua>Pick a chest from above", "<gray>Leave your body, fly over and click the chest or barrel.",
                    pl -> OutOfBody.start(plugin, pl, e, OutOfBody.Goal.STORAGE)));
        }
        if (plugin.settings().vcEnabled && plugin.vchests().available()) {
            if (st.kind() == StorageTarget.Kind.VIRTUAL) {
                b.add(Dialogs.button("<aqua>Open /chest " + st.chestNumber(), null,
                        pl -> openThenReturn(pl, () -> plugin.vchests().open(pl, e.ownerPlayer(), st.chestNumber()))));
            }
            b.add(Dialogs.button("<aqua>Deliver to a /chest number", "<gray>Current: <white>" + (st.kind() == StorageTarget.Kind.VIRTUAL
                    ? "/chest " + st.chestNumber() : "none") + "\n<gray>See who uses which in Chests, on the previous menu.", pl ->
                    Prompt.ask(pl, "Which /chest number should " + e.name + " deliver to?", text -> {
                try {
                    int n = Integer.parseInt(text);
                    run(pl, "storage " + ref(pl, e) + " vc " + n);
                } catch (NumberFormatException ex) {
                    Msg.error(pl, "That's not a number.");
                }
                back.open(pl);
            })));
        }
        Dialogs.show(plugin, p, "<gold>Where " + e.name + " delivers", lines, b, 2, Dialogs.back(pl -> back.open(pl)));
    }
}
