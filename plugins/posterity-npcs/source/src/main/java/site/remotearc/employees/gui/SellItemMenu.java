package site.remotearc.employees.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.Employee;
import site.remotearc.employees.util.Items;
import site.remotearc.employees.util.Msg;

import java.util.ArrayList;
import java.util.List;

/** One item an employee sells: its stock, share and daily limit, or take it off the list. */
final class SellItemMenu extends Menu {

    private static final int[] KEEPS = {0, 64, 256, 1024, 2304};
    private static final int[] PERCENTS = {100, 75, 50, 25};
    private static final int[] DAILY = {0, 64, 256, 1024};

    private final Employee e;
    private final Material m;
    private final Menu back;

    SellItemMenu(ArcEmployees plugin, Employee e, Material m, Menu back) {
        super(plugin, 1, Component.empty());
        this.e = e;
        this.m = m;
        this.back = back;
    }

    @Override
    protected void draw() {}

    @Override
    public void open(Player p) {
        Employee.SellRule r = e.sellRules.get(m);
        if (r == null) {   // taken off the list meanwhile
            back.open(p);
            return;
        }
        List<String> lines = new ArrayList<>();
        lines.add("<white>" + Items.name(m) + "</white> <gray>· " + r.describe() + "\n<gray>Sold in the last 24h: <white>"
                + e.soldToday(m) + "</white> · Price now: <green>" + Msg.money(plugin.market().priceNow(e.owner, m) * 64) + "</green> a stack");

        List<Dialogs.Button> b = new ArrayList<>();
        b.add(Dialogs.button("<aqua>Keep in storage: <white>" + (r.keep() == 0 ? "none" : r.keep()) + " <aqua>+",
                "<gray>Stock it fills up first; only sells beyond this.", pl -> set(pl, new Employee.SellRule(step(KEEPS, r.keep(), true), r.percent(), r.daily()))));
        b.add(Dialogs.button("<aqua>Keep in storage -", null, pl -> set(pl, new Employee.SellRule(step(KEEPS, r.keep(), false), r.percent(), r.daily()))));
        b.add(Dialogs.button("<aqua>Share to sell: <white>" + r.percent() + "% <aqua>+", "<gray>Of what's beyond the stock; the rest is stored.",
                pl -> set(pl, new Employee.SellRule(r.keep(), step(PERCENTS, r.percent(), true), r.daily()))));
        b.add(Dialogs.button("<aqua>Share to sell -", null, pl -> set(pl, new Employee.SellRule(r.keep(), step(PERCENTS, r.percent(), false), r.daily()))));
        b.add(Dialogs.button("<aqua>Most per day: <white>" + (r.daily() == 0 ? "no limit" : r.daily()) + " <aqua>+",
                "<gray>Never sells more than this in 24 hours.", pl -> set(pl, new Employee.SellRule(r.keep(), r.percent(), step(DAILY, r.daily(), true)))));
        b.add(Dialogs.button("<aqua>Most per day -", null, pl -> set(pl, new Employee.SellRule(r.keep(), r.percent(), step(DAILY, r.daily(), false)))));
        b.add(Dialogs.button("<green>Sell all of it", "<gray>No stock, no share, no limit.", pl -> set(pl, Employee.SellRule.ALL)));
        b.add(Dialogs.button("<red>Stop selling it", "<gray>Takes it off the Sells list.", pl -> {
            e.sellRules.remove(m);
            plugin.manager().markDirty();
            back.open(pl);
        }));
        b.add(Dialogs.button("<gold>Never sell it", "<gray>Moves it to Never sells: always kept, even with 'sell everything' on.", pl -> {
            e.sellRules.remove(m);
            e.neverSell.add(m);
            plugin.manager().markDirty();
            back.open(pl);
        }));
        Dialogs.show(plugin, p, "<gold>" + e.name + "</gold> <gray>· " + Items.name(m), lines, b, 2, Dialogs.back(pl -> back.open(pl)));
    }

    private void set(Player p, Employee.SellRule rule) {
        e.sellRules.put(m, rule);
        plugin.manager().markDirty();
        open(p);
    }

    /** The next value up (or down) a list, wrapping round. */
    private static int step(int[] steps, int v, boolean up) {
        int i = 0;
        while (i < steps.length && steps[i] != v) i++;
        if (i == steps.length) return steps[0];
        return steps[Math.floorMod(i + (up ? 1 : -1), steps.length)];
    }
}
