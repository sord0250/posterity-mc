package site.remotearc.employees.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.job.JobType;
import site.remotearc.employees.storage.ChestGroups;
import site.remotearc.employees.util.Items;
import site.remotearc.employees.util.Msg;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The chests of one job group (or a given list): who uses each, what's in it; click to open. */
final class ChestGroupMenu extends Menu {

    private final UUID owner;
    private final JobType job;
    private final List<Integer> numbers;
    private final Menu back;
    private int page;

    ChestGroupMenu(ArcEmployees plugin, UUID owner, JobType job, List<Integer> numbers, Menu back) {
        super(plugin, 6, Component.text(job == null ? "Other chests" : job.display() + " chests"));
        this.owner = owner;
        this.job = job;
        this.numbers = numbers;
        this.back = back;
    }

    @Override
    protected boolean live() {
        return false;
    }

    @Override
    protected void draw() {
        for (int s = 0; s < 54; s++) button(s, filler(), null);
        ChestGroups g = plugin.chestGroups();
        List<Integer> list = new ArrayList<>();
        if (job != null) for (int n = g.start(owner, job); n < g.start(owner, job) + g.size(); n++) list.add(n);
        else list.addAll(numbers);
        Map<Integer, List<String>> uses = g.uses(owner);
        int slot = 0;
        int pages = Math.max(1, (list.size() + 44) / 45);
        page = Math.min(page, pages - 1);
        for (int n : list.subList(page * 45, Math.min(list.size(), page * 45 + 45))) {
            ItemStack[] contents = plugin.vchests().contents(owner, n);
            Map<Material, Integer> count = new EnumMap<>(Material.class);
            int stacks = 0;
            for (ItemStack s : contents) {
                if (s == null || s.getType().isAir()) continue;
                stacks++;
                count.merge(s.getType(), s.getAmount(), Integer::sum);
            }
            List<String> lore = new ArrayList<>();
            List<String> u = uses.get(n);
            if (u != null) lore.add("<gray>Used by <white>" + Describe.esc(String.join(", ", u)));
            lore.add("<gray>" + stacks + " of " + plugin.vchests().size() + " slots full");
            count.entrySet().stream().sorted(Map.Entry.<Material, Integer>comparingByValue(Comparator.reverseOrder())).limit(5)
                    .forEach(en -> lore.add("<dark_gray> " + en.getValue() + " " + Items.name(en.getKey())));
            lore.add("");
            lore.add("<yellow>Click to open <gray>(close it to come back)");
            Material icon = u != null ? Material.ENDER_CHEST : stacks > 0 ? Material.CHEST : Material.GLASS;
            String color = stacks >= plugin.vchests().size() ? "<red>" : u != null ? "<aqua>" : "<gray>";
            ItemStack it = icon(icon, color + "/chest " + n + (stacks >= plugin.vchests().size() ? " (full)" : u == null && stacks == 0 ? " (free)" : ""), lore);
            // The stack size shows its place in the group (1, 2, 3...): chest numbers go past what a stack can show.
            int place = job != null ? n - g.start(owner, job) + 1 : list.indexOf(n) + 1;
            it.setData(io.papermc.paper.datacomponent.DataComponentTypes.MAX_STACK_SIZE, 99);
            it.setAmount(Math.max(1, Math.min(99, place)));
            button(slot++, it, (p, c) -> openThenReturn(p, () -> {
                if (!plugin.vchests().open(p, Bukkit.getOfflinePlayer(owner), n)) Msg.error(p, "VirtualChests is not available right now.");
            }));
        }
        button(45, back(), (p, c) -> back.open(p));
        if (page > 0) button(47, icon(Material.ARROW, "<yellow>Previous page", List.of("<gray>Page " + page + " of " + pages)), (p, c) -> {
            page--;
            redraw();
        });
        if (page < pages - 1) button(51, icon(Material.ARROW, "<yellow>Next page", List.of("<gray>Page " + (page + 2) + " of " + pages)), (p, c) -> {
            page++;
            redraw();
        });
        if (job != null) {
            button(49, icon(Material.MINECART, "<aqua>Move this group", List.of(
                    "<gray>Now: /chest " + g.range(owner, job),
                    "<gray>Moves every chest in it (items and",
                    "<gray>all) to the same place in a new block,",
                    "<gray>and repoints your employees.", "", "<yellow>Click, then type the new first number")), (p, c) ->
                    Prompt.ask(p, "New first chest number for the " + job.display().toLowerCase() + " group (e.g. 100)?", text -> {
                        try {
                            String err = plugin.chestGroups().move(owner, job, Integer.parseInt(text.replace("/chest", "").trim()));
                            if (err != null) Msg.error(p, "<msg>", Msg.p("msg", err));
                            else Msg.send(p, "<green>The " + job.display().toLowerCase() + " group is now /chest " + plugin.chestGroups().range(owner, job) + ".");
                        } catch (NumberFormatException ex) {
                            Msg.error(p, "That isn't a number.");
                        }
                    }));
        }
    }
}
