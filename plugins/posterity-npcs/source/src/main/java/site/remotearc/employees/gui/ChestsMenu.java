package site.remotearc.employees.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.job.JobType;
import site.remotearc.employees.storage.ChestGroups;
import site.remotearc.employees.util.Msg;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Every chest group at a glance: which /chest numbers each job's employees use. */
public final class ChestsMenu extends Menu {

    private final UUID owner;
    private final Menu back;

    public ChestsMenu(ArcEmployees plugin, UUID owner, Menu back) {
        super(plugin, 4, Component.text("Employee chests"));
        this.owner = owner;
        this.back = back != null ? back : new EmployeeListMenu(plugin, owner, 0);
    }

    @Override
    protected boolean live() {
        return false;
    }

    @Override
    protected void draw() {
        for (int s = 0; s < 36; s++) button(s, filler(), null);
        ChestGroups g = plugin.chestGroups();
        Map<Integer, List<String>> uses = g.uses(owner);
        int slot = 10;
        for (JobType t : JobType.values()) {
            List<String> lore = new ArrayList<>();
            int start = g.start(owner, t), inUse = 0;
            for (int n = start; n < start + g.size(); n++) {
                List<String> u = uses.get(n);
                if (u == null) continue;
                inUse++;
                lore.add("<white>/chest " + n + " <gray>" + Describe.esc(String.join(", ", u)));
            }
            if (lore.isEmpty()) lore.add("<gray>None in use yet.");
            lore.addFirst("<gray>" + inUse + " of " + g.size() + " in use:");
            lore.add("");
            lore.add("<yellow>Click to see and open them");
            JobType job = t;
            button(slot++, icon(t.icon(), "<gold>" + t.display() + "s <gray>/chest " + g.range(owner, t), lore),
                    (p, c) -> new ChestGroupMenu(plugin, owner, job, null, this).open(p));
        }
        List<Integer> outside = new ArrayList<>();
        for (int n : uses.keySet()) if (g.groupOf(owner, n) == null) outside.add(n);
        outside.sort(Integer::compare);
        List<String> lore = new ArrayList<>();
        for (int n : outside) lore.add("<white>/chest " + n + " <gray>" + Describe.esc(String.join(", ", uses.get(n))));
        if (lore.isEmpty()) lore.add("<gray>None: everything is in its group.");
        else {
            lore.add("");
            lore.add("<yellow>Click to see them");
        }
        button(22, icon(Material.CHEST, "<aqua>Outside the groups", lore), outside.isEmpty() ? null
                : (p, c) -> new ChestGroupMenu(plugin, owner, null, outside, this).open(p));

        button(30, icon(Material.HOPPER, "<aqua>Sort into groups", List.of(
                "<gray>Moves each employee's chest (and its",
                "<gray>overflow) into its job's group, items",
                "<gray>and all. Numbers holding your own",
                "<gray>things are skipped, never overwritten.", "", "<yellow>Click to sort")), (p, c) -> new ConfirmMenu(plugin,
                "Sort chests into groups?", "<green>Yes, move them", pl -> {
            pl.closeInventory();
            for (String line : plugin.chestGroups().organize(owner)) Msg.send(pl, line);
        }, this::open).open(p));
        button(32, icon(Material.BOOK, "<aqua>How groups work", List.of(
                "<gray>Each job has a block of " + g.size() + " numbers.",
                "<gray>New employees, and overflow when a",
                "<gray>chest fills, take the first free one",
                "<gray>in their job's block. A full block can",
                "<gray>be moved to new numbers from its page.")), null);
        button(27, back(), (p, c) -> back.open(p));
    }
}
