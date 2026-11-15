package site.remotearc.employees.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.Employee;
import site.remotearc.employees.util.Items;
import site.remotearc.employees.util.Msg;

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
        super(plugin, 3, Component.text(e.name + ": " + Items.name(m)));
        this.e = e;
        this.m = m;
        this.back = back;
    }

    @Override
    protected void draw() {
        for (int s = 0; s < 27; s++) button(s, filler(), null);
        Employee.SellRule r = e.sellRules.get(m);
        if (r == null) return;   // taken off the list meanwhile
        button(4, icon(m.isItem() ? m : Material.PAPER, "<white>" + Items.name(m), List.of(
                "<gray>" + r.describe() + ".",
                "<gray>Sold in the last 24h: <white>" + e.soldToday(m),
                "<gray>Price now: <green>" + Msg.money(plugin.market().priceNow(e.owner, m) * 64) + "</green> <gray>a stack")), null);
        button(10, icon(Material.CHEST, "<aqua>Keep in storage: <white>" + (r.keep() == 0 ? "none" : r.keep()), List.of(
                "<gray>Stock it fills up first; it only sells", "<gray>beyond this (and sells down any extra).",
                "", "<yellow>Left-click: more  <yellow>Right-click: less")), (p, c) -> set(new Employee.SellRule(step(KEEPS, r.keep(), !c.isRightClick()), r.percent(), r.daily())));
        button(12, icon(Material.EMERALD, "<aqua>Share to sell: <white>" + r.percent() + "%", List.of(
                "<gray>Of what's beyond the stock; the rest", "<gray>is stored.",
                "", "<yellow>Left-click: more  <yellow>Right-click: less")), (p, c) -> set(new Employee.SellRule(r.keep(), step(PERCENTS, r.percent(), c.isRightClick()), r.daily())));
        button(14, icon(Material.CLOCK, "<aqua>Most per day: <white>" + (r.daily() == 0 ? "no limit" : r.daily()), List.of(
                "<gray>Never sells more than this in 24 hours.",
                "", "<yellow>Left-click: more  <yellow>Right-click: less")), (p, c) -> set(new Employee.SellRule(r.keep(), r.percent(), step(DAILY, r.daily(), !c.isRightClick()))));
        button(16, icon(Material.LIME_DYE, "<green>Sell all of it", List.of("<gray>No stock, no share, no limit.", "", "<yellow>Click")),
                (p, c) -> set(Employee.SellRule.ALL));
        button(21, icon(Material.BARRIER, "<red>Stop selling it", List.of("<gray>Takes it off the Sells list.", "", "<yellow>Click")), (p, c) -> {
            e.sellRules.remove(m);
            plugin.manager().markDirty();
            back.open(p);
        });
        button(23, icon(Material.IRON_BARS, "<gold>Never sell it", List.of("<gray>Moves it to Never sells: always kept,", "<gray>even with 'sell everything' on.", "", "<yellow>Click")), (p, c) -> {
            e.sellRules.remove(m);
            e.neverSell.add(m);
            plugin.manager().markDirty();
            back.open(p);
        });
        button(18, back(), (p, c) -> back.open(p));
    }

    private void set(Employee.SellRule rule) {
        e.sellRules.put(m, rule);
        plugin.manager().markDirty();
        redraw();
    }

    /** The next value up (or down) a list, wrapping round. */
    private static int step(int[] steps, int v, boolean up) {
        int i = 0;
        while (i < steps.length && steps[i] != v) i++;
        if (i == steps.length) return steps[0];
        return steps[Math.floorMod(i + (up ? 1 : -1), steps.length)];
    }
}
