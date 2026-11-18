package site.remotearc.employees.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.Employee;

import java.util.List;

/** Set an employee's work area: WorldEdit selection, a square around you, or move it here. */
final class AreaMenu extends Menu {

    private final Employee e;
    private final Menu back;

    AreaMenu(ArcEmployees plugin, Employee e, Menu back) {
        super(plugin, 3, Component.text(e.name + "'s work area"));
        this.e = e;
        this.back = back;
    }

    @Override
    protected void draw() {
        for (int s = 0; s < 27; s++) button(s, filler(), null);
        if (Bukkit.getPluginManager().isPluginEnabled("WorldEdit")) {
            button(10, icon(Material.WOODEN_AXE, "<aqua>Use my WorldEdit selection", List.of(
                    "<gray>Select two corners with //wand first.",
                    "<gray>A flat selection reaches 7 blocks up.", "", "<yellow>Click to use it")), (p, c) -> {
                p.closeInventory();
                run(p, "area " + ref(p, e) + " worldedit");
            });
        }
        int max = plugin.settings().job(e.job).maxAreaSize() / 2;
        int[] radii = max >= 48 ? new int[]{8, 16, 32, 48} : max >= 24 ? new int[]{4, 8, 16, 24} : new int[]{2, 4, 8, max};
        int slot = 11;
        for (int r : radii) {
            if (r > max || r < 1) continue;
            int side = r * 2 + 1;
            button(slot++, icon(Material.MAP, "<aqua>" + side + "x" + side + " around you", List.of(
                    "<gray>Centred where you stand, at the job's", "<gray>usual depth and height.", "", "<yellow>Click to set")), (p, c) -> {
                p.closeInventory();
                run(p, "area " + ref(p, e) + " radius " + r);
            });
        }
        button(15, icon(Material.LEAD, "<aqua>Move " + e.name + " and the area here", List.of(
                "<gray>Same size and shape, shifted to you.", "", "<yellow>Click to move")), (p, c) -> {
            p.closeInventory();
            run(p, "move " + ref(p, e));
        });
        if (e.job != site.remotearc.employees.job.JobType.BUILDER) {
            button(4, glow(icon(Material.ENDER_EYE, "<aqua>Pick it from above", List.of(
                    "<gray>Leave your body and fly over the site:",
                    "<gray>click one corner, then the other" + (e.job == site.remotearc.employees.job.JobType.QUARRY
                            || e.job == site.remotearc.employees.job.JobType.MINER ? "," : "."),
                    e.job == site.remotearc.employees.job.JobType.QUARRY || e.job == site.remotearc.employees.job.JobType.MINER
                            ? "<gray>then scroll for the depth." : "",
                    "", "<yellow>Click to leave your body"))), (p, c) -> OutOfBody.start(plugin, p, e, OutOfBody.Goal.AREA));
        }
        button(16, icon(Material.SPYGLASS, "<aqua>Show the outline", List.of(
                e.area == null ? "<gray>No area yet." : "<gray>" + e.area.describe() + ", y " + e.area.minY() + " to " + e.area.maxY(),
                "", "<yellow>Click to show for 20 seconds")), (p, c) -> {
            p.closeInventory();
            run(p, "area " + ref(p, e) + " show");
        });
        if (e.job == site.remotearc.employees.job.JobType.QUARRY || e.job == site.remotearc.employees.job.JobType.MINER) {
            button(22, icon(Material.BEDROCK, "<aqua>Reach down to bedrock", List.of(
                    e.area == null ? "<gray>No area yet." : "<gray>Now down to y " + e.area.minY(),
                    "<gray>Keeps the sides and top, moves the", "<gray>bottom to the bottom of the world.",
                    "", "<yellow>Click to extend")), (p, c) -> {
                p.closeInventory();
                run(p, "area " + ref(p, e) + " bedrock");
            });
        }
        button(18, back(), (p, c) -> back.open(p));
    }
}
