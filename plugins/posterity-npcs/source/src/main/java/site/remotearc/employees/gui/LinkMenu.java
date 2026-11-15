package site.remotearc.employees.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.Employee;
import site.remotearc.employees.util.Msg;

import java.util.List;

/** Pick an action for a button or lever, then click the block in the world. */
final class LinkMenu extends Menu {

    private final Menu back;

    LinkMenu(ArcEmployees plugin, Menu back) {
        super(plugin, 4, Component.text("Control panel"));
        this.back = back;
    }

    @Override
    protected void draw() {
        for (int s = 0; s < 36; s++) button(s, filler(), null);
        button(4, icon(Material.BOOK, "<gold>How it works", List.of(
                "<gray>Choose what a control should do, then",
                "<gray>right-click the button, lever or block.",
                "<gray>A lever linked to pause/resume follows",
                "<gray>its position: on = working, off = paused.",
                "<gray>Only you (and admins) can use your controls.",
                "<gray>Break the block, or use Unlink, to remove one.")), null);
        option(10, Material.LEVER, "Pause / resume everyone", "toggleall");
        option(11, Material.GRAY_DYE, "Pause everyone", "pauseall");
        option(12, Material.LIME_DYE, "Put everyone back to work", "resumeall");
        option(13, Material.PLAYER_HEAD, "Open the employees menu", "");
        option(14, Material.ENDER_CHEST, "Open the chests menu", "chests");
        option(15, Material.PAPER, "Open the merchant price list", "market prices");
        option(16, Material.SPYGLASS, "List everyone in chat", "list");
        button(31, icon(Material.ARMOR_STAND, "<aqua>Place avatars of everyone", List.of(
                "<gray>Life-size stand-ins of all your employees",
                "<gray>in a row to your right, facing you.",
                "<gray>Right-click one for its menu.", "", "<yellow>Click to place them")), (p, c) -> {
            p.closeInventory();
            run(p, "avatars");
        });
        int slot = 19;
        if (back instanceof EmployeeListMenu) {
            for (Employee e : plugin.manager().ownedBy(owner())) {
                if (slot > 25) break;
                option(slot++, Material.LEVER, "Pause / resume " + e.name, "toggle " + e.name);
            }
        }
        button(29, icon(Material.WRITABLE_BOOK, "<aqua>Any /employees command", List.of("<gray>e.g. <white>board move</white> or <white>pay Bram</white>.",
                "", "<yellow>Click, then type it")), (p, c) -> Prompt.ask(p, "Which /employees command should it run? (without /employees)",
                text -> arm(p, text.replaceFirst("^/?(employees|emp)\\s+", ""))));
        button(33, icon(Material.BARRIER, "<red>Unlink a control", List.of("", "<yellow>Click, then right-click the block")), (p, c) -> {
            p.closeInventory();
            run(p, "unlink");
        });
        button(27, back(), (p, c) -> back.open(p));
    }

    private java.util.UUID viewer;

    private java.util.UUID owner() {
        return viewer;
    }

    @Override
    public void openFor(org.bukkit.entity.Player p) {
        viewer = p.getUniqueId();
    }

    private void option(int slot, Material icon, String label, String action) {
        button(slot, icon(icon, "<aqua>" + label, List.of("", "<yellow>Click, then right-click the block")), (p, c) -> arm(p, action));
    }

    private static void arm(org.bukkit.entity.Player p, String action) {
        p.closeInventory();
        if (action.isEmpty()) {
            p.performCommand("employees link menu");
            return;
        }
        p.performCommand("employees link " + action);
        Msg.send(p, "<gray>(Right-click the button or lever now.)");
    }
}
