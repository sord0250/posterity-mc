package site.remotearc.employees.market;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.inventory.ItemStack;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.gui.Menu;
import site.remotearc.employees.util.Items;
import site.remotearc.employees.util.Msg;

import java.util.ArrayList;
import java.util.List;

/**
 * A merchant's goods: ArcShop's catalog by category, at ArcShop's prices
 * (moved by real-world markets when that's on). Undiscovered items in gated
 * categories are shown but can't be bought, as in /shop.
 */
public final class BuyMenu extends Menu {

    private final String merchant;
    private final int category;

    public BuyMenu(ArcEmployees plugin, String merchant, int category) {
        super(plugin, 6, Component.text(merchant + " sells..."));
        this.merchant = merchant;
        this.category = category;
    }

    @Override
    protected boolean live() {
        return false;
    }

    @Override
    protected void draw() {
        Market market = plugin.market();
        List<Market.Category> cats = market.categories();
        for (int s = 0; s < 54; s++) button(s, filler(), null);
        if (cats.isEmpty()) {
            button(22, icon(Material.PAPER, "<yellow>Nothing for sale", List.of("<gray>ArcShop's catalog isn't there.")), null);
            return;
        }
        int ci = Math.max(0, Math.min(category, cats.size() - 1));
        Market.Category cat = cats.get(ci);
        java.util.UUID viewer = viewerId;
        int slot = 0;
        for (Material m : cat.items()) {
            if (slot >= 45) break;
            double each = market.buyPrice(m);
            boolean ok = viewer == null || market.mayBuy(viewer, cat, m);
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Each: <gold>" + money(each));
            lore.add("<gray>A stack of " + m.getMaxStackSize() + ": <gold>" + money(each * m.getMaxStackSize()));
            RealPrices.Quote q = market.real().quoteFor(m);
            if (q != null) lore.add((q.multiplier() >= 1 ? "<red>" : "<green>") + RealPrices.describe(q));
            lore.add("");
            if (ok) {
                lore.add("<yellow>Click: buy 1  <yellow>Right-click: buy 16");
                lore.add("<yellow>Shift-click: buy a stack");
            } else {
                lore.add("<red>Not discovered yet");
                lore.add("<gray>Find or pick one up first (as with /shop).");
            }
            ItemStack it = icon(ok ? m : Material.GRAY_DYE, (ok ? "<white>" : "<dark_gray>") + Items.name(m), lore);
            button(slot++, it, ok ? (p, c) -> {
                int n = c.isShiftClick() ? m.getMaxStackSize() : c.isRightClick() ? 16 : 1;
                String err = market.buy(p, m, n);
                if (err != null) Msg.error(p, "<msg>", Msg.p("msg", err));
                else {
                    p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_YES, 0.6f, 1.1f);
                    var econ = plugin.economy();
                    Msg.send(p, "<green>Bought " + n + " " + Items.name(m) + " for " + money(market.buyPrice(m) * n) + "."
                            + (econ == null ? "" : " <gray>Balance: <white>" + Msg.money(econ.getBalance(p))));
                }
            } : null);
        }
        // Category tabs along the bottom.
        int tab = 45;
        for (int i = 0; i < cats.size() && tab < 52; i++, tab++) {
            Market.Category c = cats.get(i);
            ItemStack icon = icon(c.icon(), (i == ci ? "<green>" : "<aqua>") + c.name(), List.of("<gray>" + c.items().size() + " items", "",
                    "<yellow>Click to browse"));
            int idx = i;
            button(tab, i == ci ? glow(icon) : icon, (p, cl) -> new BuyMenu(plugin, merchant, idx).open(p));
        }
        button(53, icon(Material.EMERALD, "<green>Sell to " + merchant, List.of("", "<yellow>Click to sell your items")),
                (p, c) -> new SellMenu(plugin, p, merchant).open());
    }

    private java.util.UUID viewerId;

    @Override
    public void openFor(org.bukkit.entity.Player p) {
        viewerId = p.getUniqueId();
    }

    private static String money(double v) {
        return v >= 1 ? Msg.money(v) : "$" + String.format("%.2f", v);
    }
}
