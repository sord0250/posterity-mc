package site.remotearc.employees.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
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
    private static final int SHOWN = 12;

    private final Employee e;
    private final Menu back;

    SellRulesMenu(ArcEmployees plugin, Employee e, Menu back) {
        super(plugin, 1, Component.empty());
        this.e = e;
        this.back = back;
    }

    @Override
    protected void draw() {}

    @Override
    public void open(Player p) {
        double[] today = e.soldToday();
        List<String> lines = new ArrayList<>();
        lines.add("<gray>Each time " + e.name + " delivers (every " + plugin.settings().vcDeliveryTicks / 20 + "s to a /chest), items it"
                + " sells go to the market instead, by its rule: keep a stock in storage first, sell a share of the rest, at most n a"
                + " day. Needs a merchant somewhere on the server.");
        StringBuilder sales = new StringBuilder("<gray>Last 24h: <white>" + (long) today[0] + "</white> items for <green>" + Msg.money(today[1])
                + "\n<gray>All time: <white>" + e.stats.itemsSold + "</white> items for <green>" + Msg.money(e.stats.earned));
        int shown = 0;
        for (Employee.SaleRecord r : e.sales) {
            if (shown++ >= 4) break;
            sales.append("\n<dark_gray>").append(EventLog.age(r.time())).append(" ago: ").append(Describe.esc(r.what())).append(" → ").append(Msg.money(r.money()));
        }
        lines.add(sales.toString());

        List<Dialogs.Button> b = new ArrayList<>();
        Employee.SellRule all = e.sellAllRule;
        b.add(Dialogs.button("<aqua>Sell everything, except what's kept: " + (e.sellAll ? "<green>on" : "<red>off"),
                e.sellAll ? "<gray>Everything merchants buy is sold (" + all.describe() + "), except the items under Never sells."
                        : "<gray>Off: only the items under Sells are sold.", pl -> {
            e.sellAll = !e.sellAll;
            plugin.manager().markDirty();
            open(pl);
        }));
        b.add(Dialogs.button("<aqua>Stock to keep of each: <white>" + (all.keep() == 0 ? "none" : all.keep()),
                "<gray>Cycles 0 / 64 / 256 / 1024 / 2304. Turns 'sell everything' on.", pl -> {
            e.sellAllRule = new Employee.SellRule(cycle(KEEPS, all.keep(), true), all.percent(), all.daily());
            e.sellAll = true;
            plugin.manager().markDirty();
            open(pl);
        }));
        b.add(Dialogs.button("<aqua>Share to sell: <white>" + all.percent() + "%", "<gray>Cycles 100 / 75 / 50 / 25%. Turns 'sell everything' on.", pl -> {
            e.sellAllRule = new Employee.SellRule(all.keep(), cycle(PERCENTS, all.percent(), false), all.daily());
            e.sellAll = true;
            plugin.manager().markDirty();
            open(pl);
        }));

        b.add(Dialogs.button("<green>Add items to sell", "<gray>Pick from what " + e.name + " has, or search for any item. They're sold"
                + " all; click one afterward to keep a stock or sell a share.", pl -> new ItemPickerMenu(plugin, "Sell", suggestions(), null, 0,
                e.sellRules.keySet(), m -> {
            e.sellRules.put(m, Employee.SellRule.ALL);
            e.neverSell.remove(m);
            plugin.manager().markDirty();
        }, this).open(pl)));
        b.add(Dialogs.button("<gold>Add items to never sell", "<gray>These are always kept, even with 'sell everything' on.", pl ->
                new ItemPickerMenu(plugin, "Never sell", suggestions(), null, 0, e.neverSell, m -> {
            e.neverSell.add(m);
            e.sellRules.remove(m);
            plugin.manager().markDirty();
        }, this).open(pl)));
        b.add(Dialogs.button("<red>Stop selling", "<gray>Clears both lists.", pl -> {
            e.sellAll = false;
            e.sellRules.clear();
            e.neverSell.clear();
            plugin.manager().markDirty();
            open(pl);
        }));

        int shownSell = 0;
        for (Map.Entry<Material, Employee.SellRule> en : new ArrayList<>(e.sellRules.entrySet())) {
            if (shownSell++ >= SHOWN) {
                b.add(Dialogs.button("<gray>and " + (e.sellRules.size() - SHOWN) + " more…", null, pl -> {}));
                break;
            }
            Material m = en.getKey();
            Employee.SellRule r = en.getValue();
            b.add(Dialogs.button("<white>Sells: " + Items.name(m), "<gray>" + capital(r.describe()) + ".\n<gray>Sold in the last 24h: <white>"
                    + e.soldToday(m) + "\n<gray>Price now: <green>" + Msg.money(plugin.market().priceNow(e.owner, m) * 64) + "</green> a stack",
                    pl -> new SellItemMenu(plugin, e, m, this).open(pl)));
        }
        int shownNever = 0;
        for (Material m : new ArrayList<>(e.neverSell)) {
            if (shownNever++ >= SHOWN) {
                b.add(Dialogs.button("<gray>and " + (e.neverSell.size() - SHOWN) + " more…", null, pl -> {}));
                break;
            }
            b.add(Dialogs.button("<gold>Never sells: " + Items.name(m), "<gray>Always kept. <yellow>Click to take it off this list.", pl -> {
                e.neverSell.remove(m);
                plugin.manager().markDirty();
                open(pl);
            }));
        }
        Dialogs.show(plugin, p, "<gold>" + e.name + "</gold> <gray>· selling", lines, b, 3, Dialogs.back(pl -> back.open(pl)));
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
