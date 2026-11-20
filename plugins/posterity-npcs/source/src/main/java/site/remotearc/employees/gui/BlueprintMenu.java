package site.remotearc.employees.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.Employee;

import java.util.List;
import java.util.Locale;

/** The blueprint folder; clicking one places it where the player stands for this builder. */
final class BlueprintMenu extends Menu {

    private static final int PER_PAGE = 45;

    private final Employee e;
    private final Menu back;
    private final int page;
    private org.bukkit.entity.Player viewer;

    BlueprintMenu(ArcEmployees plugin, Employee e, Menu back, int page) {
        super(plugin, 6, Component.text("Blueprints"));
        this.e = e;
        this.back = back;
        this.page = page;
    }

    @Override
    protected boolean live() {
        return false;   // a folder listing; nothing here changes while it's open
    }

    @Override
    public void openFor(org.bukkit.entity.Player p) {
        viewer = p;
    }

    @Override
    protected void draw() {
        List<String> names = plugin.blueprints().list(viewer != null && viewer.hasPermission("employees.admin"));
        int from = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && from + i < names.size(); i++) {
            String file = names.get(from + i);
            String n = file.toLowerCase(Locale.ROOT);
            Material m = n.endsWith(".litematic") ? Material.MAP : n.endsWith(".nbt") ? Material.STRUCTURE_BLOCK : Material.PAPER;
            String base = file.contains(".") ? file.substring(0, file.lastIndexOf('.')) : file;
            button(i, icon(m, "<aqua>" + Describe.esc(base), List.of("<gray>" + Describe.esc(file), "",
                    "<yellow>Click to place it where you stand")), (p, c) -> new ConfirmMenu(plugin, "Build " + base + " here?",
                    "<green>Place it here (you can still turn it)", pl -> {
                pl.closeInventory();
                run(pl, "build " + ref(pl, e) + " " + base);
            }, this::open).open(p));
        }
        if (names.isEmpty()) {
            button(22, icon(Material.PAPER, "<yellow>No blueprints yet", List.of(
                    "<gray>Use 'Download a blueprint', or put files", "<gray>in plugins/ArcEmployees/blueprints")), null);
        }
        for (int s = 45; s < 54; s++) button(s, filler(), null);
        button(45, back(), (p, c) -> back.open(p));
        if (page > 0) button(48, icon(Material.ARROW, "<yellow>Previous page", List.of()), (p, c) -> new BlueprintMenu(plugin, e, back, page - 1).open(p));
        if (from + PER_PAGE < names.size()) button(50, icon(Material.ARROW, "<yellow>Next page", List.of()), (p, c) -> new BlueprintMenu(plugin, e, back, page + 1).open(p));
    }
}
