package site.remotearc.employees.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.Employee;
import site.remotearc.employees.board.EventLog;
import site.remotearc.employees.util.Items;
import site.remotearc.employees.util.Msg;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What an employee sells: per item, a stock to keep in storage, the share
 * of the rest to sell, and optionally a daily maximum.
 */
final class SellRulesMenu extends Menu {

    private static final int[] KEEPS = {0, 64, 256, 1024, 2304};
    private static final int[] PERCENTS = {100, 75, 50, 25};

    private final Employee e;
    private final Menu back;

    SellRulesMenu(ArcEmployees plugin, Employee e, Menu back) {
        super(plugin, 6, Component.text(e.name + ": selling"));
        this.e = e;
        this.back = back;
    }

    @Override
    protected void draw() {
        for (int s = 0; s < 54; s++) button(s, filler(), null);
        double[] today = e.soldToday();
        List<String> info = new ArrayList<>(List.of(
                "<gray>Each time " + e.name + " delivers (every " + plugin.settings().vcDeliveryTicks / 20 + "s to a /chest),",
                "<gray>items it sells go to the market instead,",
                "<gray>by its rule: keep a stock in storage first,",
                "<gray>sell a share of the rest, at most n a day.",
                "<gray>Needs a merchant somewhere on the server.",
                "",
                "<gray>Last 24h: <white>" + (long) today[0] + "</white> items for <green>" + Msg.money(today[1]),
                "<gray>All time: <white>" + e.stats.itemsSold + "</white> items for <green>" + Msg.money(e.stats.earned)));
        int shown = 0;
        for (Employee.SaleRecord r : e.sales) {
            if (shown++ >= 6) break;
            info.add("<dark_gray>" + EventLog.age(r.time()) + " ago: " + Describe.esc(r.what()) + " → " + Msg.money(r.money()));
        }
        button(4, icon(Material.BOOK, "<gold>How selling works", info), null);

        Employee.SellRule all = e.sellAllRule;
        ItemStack allIcon = icon(e.sellAll ? Material.LIME_DYE : Material.GRAY_DYE,
                "<aqua>Sell everything, except what's kept: " + (e.sellAll ? "<green>on" : "<red>off"), List.of(
                        e.sellAll ? "<gray>Everything merchants buy is sold (" + all.describe() + ")," : "<gray>Off: only the items under Sells are sold.",
                        e.sellAll ? "<gray>except the items under Never sells." : "",
                        "", "<yellow>Click: on/off", "<yellow>Right-click: stock to keep of each (0/64/256/1024/2304)",
                        "<yellow>Shift-click: share to sell (100/75/50/25%)"));
        button(10, e.sellAll ? glow(allIcon) : allIcon, (p, c) -> {
            if (c.isShiftClick()) e.sellAllRule = new Employee.SellRule(all.keep(), cycle(PERCENTS, all.percent(), false), all.daily());
            else if (c.isRightClick()) e.sellAllRule = new Employee.SellRule(cycle(KEEPS, all.keep(), true), all.percent(), all.daily());
            else e.sellAll = !e.sellAll;
            if (c.isShiftClick() || c.isRightClick()) e.sellAll = true;
            plugin.manager().markDirty();
            redraw();
        });

        button(12, icon(Material.EMERALD, "<green>Add items to sell", List.of(
                "<gray>Pick from what " + e.name + " has, or search",
                "<gray>for any item. They're sold all; click one",
                "<gray>afterwards to keep a stock or sell a share.",
                "", "<yellow>Click to choose")), (p, c) -> new ItemPickerMenu(plugin, "Sell", suggestions(), null, 0,
                e.sellRules.keySet(), m -> {
                    e.sellRules.put(m, Employee.SellRule.ALL);
                    e.neverSell.remove(m);
                    plugin.manager().markDirty();
                }, this).open(p));

        button(14, icon(Material.CHEST, "<gold>Add items to never sell", List.of(
                "<gray>These are always kept, even with",
                "<gray>'sell everything' on.", "", "<yellow>Click to choose")), (p, c) -> new ItemPickerMenu(plugin, "Never sell",
                suggestions(), null, 0, e.neverSell, m -> {
                    e.neverSell.add(m);
                    e.sellRules.remove(m);
                    plugin.manager().markDirty();
                }, this).open(p));

        button(16, icon(Material.BARRIER, "<red>Stop selling", List.of("<gray>Clears both lists.", "", "<yellow>Click to clear")), (p, c) -> {
            e.sellAll = false;
            e.sellRules.clear();
            e.neverSell.clear();
            plugin.manager().markDirty();
            redraw();
        });

        button(18, icon(Material.EMERALD, "<green>Sells →", List.of("<gray>Each with its own rule.")), null);
        int slot = 19;
        for (Map.Entry<Material, Employee.SellRule> en : new ArrayList<>(e.sellRules.entrySet())) {
            if (slot > 35) break;
            Material m = en.getKey();
            Employee.SellRule r = en.getValue();
            ItemStack it = icon(m.isItem() ? m : Material.PAPER, "<white>" + Items.name(m), List.of(
                    "<gray>" + capital(r.describe()) + ".",
                    "<gray>Sold in the last 24h: <white>" + e.soldToday(m),
                    "<gray>Price now: <green>" + Msg.money(plugin.market().priceNow(e.owner, m) * 64) + "</green> <gray>a stack",
                    "", "<yellow>Click to change or remove"));
            button(slot++, it, (p, c) -> new SellItemMenu(plugin, e, m, this).open(p));
        }

        button(36, icon(Material.CHEST, "<gold>Never sells →", List.of("<gray>Always kept.")), null);
        slot = 37;
        for (Material m : new ArrayList<>(e.neverSell)) {
            if (slot > 44) break;
            button(slot++, icon(m.isItem() ? m : Material.PAPER, "<white>" + Items.name(m), List.of("<gold>Never sold.", "",
                    "<yellow>Click to take it off this list")), (p, c) -> {
                e.neverSell.remove(m);
                plugin.manager().markDirty();
                redraw();
            });
        }
        button(45, back(), (p, c) -> back.open(p));
    }

    /** What the employee has in storage and its backpack, most first: the likeliest things to sell or keep. */
    private List<Material> suggestions() {
        Map<Material, Integer> have = new java.util.EnumMap<>(Material.class);
        plugin.market().storedAll(e).forEach((m, n) -> have.merge(m, n, Integer::sum));
        for (ItemStack s : e.backpack.getStorageContents()) if (s != null && !s.getType().isAir()) have.merge(s.getType(), s.getAmount(), Integer::sum);
        List<Material> out = new ArrayList<>(have.keySet());
        out.sort((a, b) -> have.get(b) - have.get(a));
        return out;
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** The next (or previous) value in a list, wrapping round. */
    private static int cycle(int[] steps, int v, boolean up) {
        int i = 0;
        while (i < steps.length && steps[i] != v) i++;
        if (i == steps.length) return steps[0];
        return steps[Math.floorMod(i + (up ? 1 : -1), steps.length)];
    }
}
