package site.remotearc.employees.gui;

import org.bukkit.entity.Player;
import site.remotearc.employees.ArcEmployees;

import java.util.List;
import java.util.function.Consumer;

/** A yes/no prompt. */
final class ConfirmMenu {

    private final ArcEmployees plugin;
    private final String title;
    private final String yesLabel;
    private final Consumer<Player> onYes;
    private final Consumer<Player> onNo;

    /** {@code title} is shown as plain text (no MiniMessage tags are read from it); {@code yesLabel} may use them. */
    ConfirmMenu(ArcEmployees plugin, String title, String yesLabel, Consumer<Player> onYes, Consumer<Player> onNo) {
        this.plugin = plugin;
        this.title = title;
        this.yesLabel = yesLabel;
        this.onYes = onYes;
        this.onNo = onNo;
    }

    void open(Player p) {
        Dialogs.show(plugin, p, Describe.esc(title), List.of(), List.of(Dialogs.button(yesLabel, null, onYes)), 1,
                Dialogs.button("<gray>Cancel", null, onNo));
    }
}
