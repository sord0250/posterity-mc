package site.remotearc.employees.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import site.remotearc.employees.ArcEmployees;

import java.util.List;
import java.util.function.Consumer;

/** A yes/no prompt. */
final class ConfirmMenu extends Menu {

    private final String yesLabel;
    private final Consumer<Player> onYes;
    private final Consumer<Player> onNo;

    ConfirmMenu(ArcEmployees plugin, String title, String yesLabel, Consumer<Player> onYes, Consumer<Player> onNo) {
        super(plugin, 3, Component.text(title));
        this.yesLabel = yesLabel;
        this.onYes = onYes;
        this.onNo = onNo;
    }

    @Override
    protected void draw() {
        for (int s = 0; s < 27; s++) button(s, filler(), null);
        button(11, icon(Material.LIME_CONCRETE, yesLabel, List.of()), (p, c) -> onYes.accept(p));
        button(15, icon(Material.RED_CONCRETE, "<gray>Cancel", List.of()), (p, c) -> onNo.accept(p));
    }
}
