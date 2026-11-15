package site.remotearc.employees.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.Employee;
import site.remotearc.employees.build.BuildOrder;
import site.remotearc.employees.util.WorkArea;

import java.util.List;

/**
 * Nudges a build with arrow buttons, as the player is facing: no x and z to
 * work out. The ghost blocks move while the menu is open.
 */
final class ShiftMenu extends Menu {

    private final Employee e;
    private final Menu back;

    ShiftMenu(ArcEmployees plugin, Employee e, Menu back) {
        super(plugin, 4, Component.text("Shift " + e.name + "'s build"));
        this.e = e;
        this.back = back;
    }

    @Override
    protected boolean live() {
        return false;
    }

    @Override
    protected void draw() {
        for (int s = 0; s < 36; s++) button(s, filler(), null);
        BuildOrder o = e.build;
        if (o == null || o.plan == null) {
            button(13, icon(Material.PAPER, "<yellow>Nothing to shift", List.of()), null);
            return;
        }
        WorkArea b = o.bounds();
        button(4, icon(Material.COMPASS, "<gold>" + o.blueprint, List.of(
                "<gray>Directions are as you're facing", "<gray>(close this and turn to change it).",
                "<gray>Corner at <white>" + b.minX() + " " + b.minY() + " " + b.minZ(), "",
                "<gray>Click: 1 block. Shift-click: 5.")), null);
        arrow(13, Material.ARROW, "Forward", "forward");
        arrow(21, Material.ARROW, "Left", "left");
        arrow(23, Material.ARROW, "Right", "right");
        arrow(31, Material.ARROW, "Back (toward you)", "back");
        arrow(16, Material.FEATHER, "Up", "up");
        arrow(34, Material.ANVIL, "Down", "down");
        button(22, icon(Material.ARMOR_STAND, "<gray>You", List.of("<gray>Facing the way the arrows point.")), null);
        button(27, back(), (p, c) -> (back != null ? back : new BuildMenu(plugin, e, null)).open(p));
    }

    private void arrow(int slot, Material icon, String label, String dir) {
        button(slot, icon(icon, "<aqua>" + label, List.of("", "<yellow>Click: 1 block", "<yellow>Shift-click: 5 blocks")), (p, c) -> {
            int n = c == ClickType.SHIFT_LEFT || c == ClickType.SHIFT_RIGHT ? 5 : 1;
            run(p, "build " + ref(p, e) + " shift " + dir + " " + n);
            redraw();
        });
    }
}
