package site.remotearc.employees.market;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.gui.Menu;
import site.remotearc.employees.util.Msg;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** What merchants pay, item by item, for this player right now (after the demand they've used up). */
public final class PriceMenu extends Menu {

    private static final int PER_PAGE = 45;

    private final UUID player;
    private final int page;

    public PriceMenu(ArcEmployees plugin, UUID player, int page) {
        super(plugin, 6, Component.text("Merchant prices"));
        this.player = player;
        this.page = page;
    }

    @Override
    protected void draw() {
        Market market = plugin.market();
        List<Map.Entry<Material, Double>> list = new ArrayList<>(market.catalog().entrySet());
        list.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        int from = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && from + i < list.size(); i++) {
            Material m = list.get(from + i).getKey();
            double base = list.get(from + i).getValue(), now = market.priceNow(player, m);
            if (!m.isItem()) continue;
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Each: <green>" + money(now) + (now < base * 0.99 ? " <dark_gray>(normally " + money(base) + ")" : ""));
            lore.add("<gray>A stack of " + m.getMaxStackSize() + ": about <green>" + money(now * m.getMaxStackSize()));
            if (now < base * 0.99) lore.add("<yellow>Lower: you've sold a lot of this lately.");
            RealPrices.Quote q = market.real().quoteFor(m);
            if (q != null) lore.add((q.multiplier() >= 1 ? "<green>" : "<red>") + RealPrices.describe(q));
            button(i, icon(m, "<white>" + Market.name(m), lore), null);
        }
        for (int s = 45; s < 54; s++) button(s, filler(), null);
        List<String> markets = new ArrayList<>();
        for (RealPrices.Quote q : market.real().quotes().values()) markets.add((q.multiplier() >= 1 ? "<green>" : "<red>") + RealPrices.describe(q));
        if (market.real().enabled()) {
            if (markets.isEmpty()) markets.add("<gray>Fetching prices...");
            button(47, icon(Material.CLOCK, "<aqua>Real-world markets", markets), null);
        }
        button(49, icon(Material.BOOK, "<gold>Today", List.of("<gray>Earned in the last 24h: <white>" + Msg.money(market.earnedToday(player)),
                market.limit(player) > 0 ? "<gray>Limit: <white>" + Msg.money(market.limit(player)) : "<gray>No daily limit",
                market.limitLine(player))), null);
        if (page > 0) button(45, icon(Material.ARROW, "<yellow>Previous page", List.of()), (p, c) -> new PriceMenu(plugin, player, page - 1).open(p));
        if (from + PER_PAGE < list.size()) button(53, icon(Material.ARROW, "<yellow>Next page", List.of()), (p, c) -> new PriceMenu(plugin, player, page + 1).open(p));
    }

    private static String money(double v) {
        return v >= 1 ? Msg.money(v) : "$" + String.format("%.3f", v);
    }
}
