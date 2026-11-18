package site.remotearc.employees.market;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.util.Msg;

import java.util.ArrayList;
import java.util.List;

/**
 * A merchant's counter: the player puts items in the top five rows, sees
 * what they'd fetch, and sells. Anything not bought goes back to them when
 * the window closes.
 */
public final class SellMenu implements InventoryHolder {

    private static final int GOODS = 45;
    private static final int SELL = 49, PRICES = 47, INFO = 45, CANCEL = 51, BUY = 53;

    private final ArcEmployees plugin;
    private final Player player;
    private final String merchant;
    private final Inventory inv;

    public SellMenu(ArcEmployees plugin, Player player, String merchant) {
        this.plugin = plugin;
        this.player = player;
        this.merchant = merchant;
        this.inv = Bukkit.createInventory(this, 54, Component.text(merchant + " buys..."));
        refresh();
    }

    public void open() {
        player.openInventory(inv);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inv;
    }

    private List<ItemStack> goods() {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < GOODS; i++) {
            ItemStack s = inv.getItem(i);
            if (s != null && !s.getType().isAir()) out.add(s);
        }
        return out;
    }

    void refresh() {
        Market market = plugin.market();
        Market.Sale quote = market.sell(player.getUniqueId(), goods(), false);
        int unsold = 0;
        for (ItemStack s : quote.leftover()) unsold += s.getAmount();
        double room = market.roomToday(player.getUniqueId());
        ItemStack pane = button(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = GOODS; i < 54; i++) inv.setItem(i, pane);
        inv.setItem(INFO, button(Material.BOOK, "<gold>How selling works", List.of(
                "<gray>Put items in the space above.",
                "<gray>Prices drop the more of one thing you sell",
                "<gray>(the market fills up) and recover over a day.",
                "<gray>Earned in the last 24h: <white>" + Msg.money(market.earnedToday(player.getUniqueId()))
                        + (market.limit(player.getUniqueId()) > 0 ? " <gray>of <white>" + Msg.money(market.limit(player.getUniqueId())) : ""),
                market.limitLine(player.getUniqueId()))));
        inv.setItem(PRICES, button(Material.PAPER, "<aqua>Price list", List.of("<gray>What " + merchant + " pays today.", "", "<yellow>Click to see")));
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + quote.count() + " items for <green>" + Msg.money(quote.money()));
        if (unsold > 0) {
            lore.add("<yellow>" + unsold + " won't sell:");
            java.util.Set<String> why = new java.util.LinkedHashSet<>();
            for (ItemStack s : quote.leftover()) {
                String n = site.remotearc.employees.util.Items.name(s.getType());
                if (market.basePrice(s.getType()) <= 0) why.add("<gray>" + n + ": merchants don't buy it");
                else if (!site.remotearc.employees.util.Items.isSellable(s)) why.add("<gray>" + n + ": renamed, enchanted or used");
                else why.add("<gray>" + n + ": over today's selling limit");
            }
            why.stream().limit(5).forEach(lore::add);
        }
        if (room < Double.MAX_VALUE) {
            lore.add(market.limitLine(player.getUniqueId()));
            if (quote.money() > room && plugin.settings().marketOverRate > 0) {
                lore.add("<yellow>Part of this is over today's limit,");
                lore.add("<yellow>so merchants pay " + Math.round(plugin.settings().marketOverRate * 100) + "% for that part.");
            }
        }
        lore.add("");
        lore.add(quote.count() > 0 ? "<yellow>Click to sell" : "<gray>Nothing to sell yet");
        inv.setItem(SELL, button(quote.count() > 0 ? Material.EMERALD : Material.GRAY_DYE, "<green>Sell for " + Msg.money(quote.money()), lore));
        inv.setItem(CANCEL, button(Material.BARRIER, "<red>Close", List.of("<gray>Your items come back to you.")));
        inv.setItem(BUY, button(Material.GOLD_INGOT, "<gold>Buy from " + merchant, List.of("<gray>Everything the shop sells,", "<gray>at ArcShop's prices.", "", "<yellow>Click to browse")));
    }

    private void sell() {
        Market.Sale sale = plugin.market().sell(player.getUniqueId(), goods(), true);
        if (sale.count() == 0) {
            String why = "Nothing here that " + merchant + " buys.";
            for (ItemStack s : sale.leftover()) {
                String n = site.remotearc.employees.util.Items.name(s.getType());
                if (plugin.market().basePrice(s.getType()) <= 0) continue;
                why = !site.remotearc.employees.util.Items.isSellable(s)
                        ? merchant + " only buys ordinary " + n + " (not renamed, enchanted or used)."
                        : "You've reached today's selling limit. Prices and limits reset over the next day.";
                break;
            }
            Msg.error(player, why);
            return;
        }
        for (int i = 0; i < GOODS; i++) inv.setItem(i, null);
        int slot = 0;
        for (ItemStack s : sale.leftover()) if (slot < GOODS) inv.setItem(slot++, s);
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_YES, 0.8f, 1f);
        var econ = plugin.economy();
        Msg.send(player, "<green>Sold " + sale.count() + " items to " + merchant + " for <white>" + Msg.money(sale.money()) + "</white>."
                + (econ == null ? "" : " <gray>Balance: <white>" + Msg.money(econ.getBalance(player))));
        refresh();
    }

    private static ItemStack button(Material m, String name, List<String> lore) {
        ItemStack s = ItemStack.of(m);
        ItemMeta meta = s.getItemMeta();
        meta.displayName(Msg.item(name));
        meta.lore(Msg.lore(lore));
        meta.addItemFlags(ItemFlag.values());
        s.setItemMeta(meta);
        return s;
    }

    /** Lets items move in and out of the top rows, keeps the buttons fixed, and returns leftovers on close. */
    public static final class Listener implements org.bukkit.event.Listener {

        private final ArcEmployees plugin;

        public Listener(ArcEmployees plugin) {
            this.plugin = plugin;
        }

        @EventHandler(ignoreCancelled = true)
        public void onClick(InventoryClickEvent ev) {
            if (!(ev.getView().getTopInventory().getHolder(false) instanceof SellMenu menu)) return;
            int raw = ev.getRawSlot();
            if (raw >= GOODS && raw < 54) {
                ev.setCancelled(true);
                switch (raw) {
                    case SELL -> menu.sell();
                    case PRICES -> {
                        menu.player.closeInventory();
                        new PriceMenu(plugin, menu.player.getUniqueId(), 0).open(menu.player);
                    }
                    case CANCEL -> menu.player.closeInventory();
                    case BUY -> {
                        menu.player.closeInventory();
                        new BuyMenu(plugin, menu.merchant, 0).open(menu.player);
                    }
                    default -> { }
                }
                return;
            }
            Bukkit.getScheduler().runTask(plugin, menu::refresh);
        }

        @EventHandler(ignoreCancelled = true)
        public void onDrag(InventoryDragEvent ev) {
            if (!(ev.getView().getTopInventory().getHolder(false) instanceof SellMenu menu)) return;
            for (int raw : ev.getRawSlots()) {
                if (raw >= GOODS && raw < 54) {
                    ev.setCancelled(true);
                    return;
                }
            }
            Bukkit.getScheduler().runTask(plugin, menu::refresh);
        }

        @EventHandler
        public void onClose(InventoryCloseEvent ev) {
            if (!(ev.getInventory().getHolder(false) instanceof SellMenu menu)) return;
            List<ItemStack> back = menu.goods();
            for (int i = 0; i < GOODS; i++) menu.inv.setItem(i, null);
            Player p = menu.player;
            for (ItemStack s : p.getInventory().addItem(back.toArray(new ItemStack[0])).values()) p.getWorld().dropItem(p.getLocation(), s);
        }
    }
}
