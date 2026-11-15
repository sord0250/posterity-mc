package site.remotearc.employees.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.Employee;
import site.remotearc.employees.build.BuildOrder;
import site.remotearc.employees.util.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Every swap and left-out block of a build, each one undone or changed on its own. */
final class SwapsMenu extends Menu {

    private static final int PER_PAGE = 45;

    private final Employee e;
    private final int page;
    private final Menu back;

    SwapsMenu(ArcEmployees plugin, Employee e, int page, Menu back) {
        super(plugin, 6, Component.text("Swaps and left out"));
        this.e = e;
        this.page = page;
        this.back = back;
    }

    @Override
    protected boolean live() {
        return false;
    }

    @Override
    protected void draw() {
        for (int s = 0; s < 54; s++) button(s, filler(), null);
        BuildOrder o = e.build;
        if (o == null || o.plan == null) {
            button(22, icon(Material.PAPER, "<yellow>Nothing to build", List.of()), null);
            return;
        }
        List<Map.Entry<Material, Material>> swaps = new ArrayList<>(o.swaps.entrySet());
        List<Map.Entry<String, Material>> unknown = new ArrayList<>(o.replacements.entrySet());
        int total = swaps.size() + unknown.size() + (o.skipped.isEmpty() ? 0 : 1);
        if (total == 0) button(22, icon(Material.PAPER, "<gray>None: it's built as drawn", List.of()), null);
        int start = page * PER_PAGE, slot = 0, i = 0;
        for (Map.Entry<Material, Material> en : swaps) {
            if (i++ < start || slot >= PER_PAGE) continue;
            Material from = en.getKey(), to = en.getValue();
            boolean out = to == Material.AIR;
            button(slot++, icon(from.isItem() ? from : Material.PAPER, (out ? "<red>" : "<aqua>") + Items.name(from), List.of(
                    out ? "<gray>Left out everywhere" : "<gray>Built from <white>" + Items.name(to), "",
                    "<yellow>Click to bring it back as drawn", "<yellow>Right-click to pick something else")), (p, c) -> {
                if (c.isRightClick()) {
                    new SwapMenu(plugin, e, from, null, null, 0, this).open(p);
                    return;
                }
                run(p, "build " + ref(p, e) + " swap " + Items.key(from) + " back");
                redraw();
            });
        }
        for (Map.Entry<String, Material> en : unknown) {
            if (i++ < start || slot >= PER_PAGE) continue;
            String name = en.getKey();
            button(slot++, icon(Material.STRUCTURE_VOID, "<aqua>" + name.replace('_', ' '), List.of(
                    "<gray>Not in this Minecraft version;", "<gray>built from <white>" + Items.name(en.getValue()), "",
                    "<yellow>Click to leave it out instead", "<yellow>Right-click to pick something else")), (p, c) -> {
                if (c.isRightClick()) {
                    new SwapMenu(plugin, e, Material.STRUCTURE_VOID, name, null, null, 0, this).open(p);
                    return;
                }
                run(p, "build " + ref(p, e) + " swap " + name + " none");
                redraw();
            });
        }
        if (!o.skipped.isEmpty() && i >= start && slot < PER_PAGE) {
            button(slot, icon(Material.BARRIER, "<red>" + o.skipped.size() + " single block(s) left out", List.of(
                    "<gray>Ones you left out one at a time.", "", "<yellow>Click to put them all back")), (p, c) -> {
                run(p, "build " + ref(p, e) + " skip reset");
                redraw();
            });
        }
        button(45, back(), (p, c) -> (back != null ? back : new MaterialsMenu(plugin, e, 0, new BuildMenu(plugin, e, null))).open(p));
        if (page > 0) button(46, icon(Material.ARROW, "<yellow>Previous page", List.of()), (p, c) -> new SwapsMenu(plugin, e, page - 1, back).open(p));
        if (start + PER_PAGE < total) button(53, icon(Material.ARROW, "<yellow>Next page", List.of()), (p, c) -> new SwapsMenu(plugin, e, page + 1, back).open(p));
    }
}
