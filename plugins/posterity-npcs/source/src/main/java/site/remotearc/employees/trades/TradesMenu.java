package site.remotearc.employees.trades;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Villager;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.gui.Menu;

import java.util.ArrayList;
import java.util.List;

/** Every villager near you as an icon (its job block), with all its trades; click one to be guided to it. */
final class TradesMenu extends Menu {

    private static final int PER_PAGE = 45;
    private final List<TradeBoard.Seller> all;
    private final int page;

    TradesMenu(ArcEmployees plugin, List<TradeBoard.Seller> all, Integer page) {
        super(plugin, 6, Component.text("Villager trades (" + all.size() + ")"));
        this.all = all;
        this.page = page == null ? 0 : page;
    }

    @Override
    protected boolean live() {
        return false;
    }

    @Override
    protected void draw() {
        for (int s = 0; s < 54; s++) button(s, filler(), null);
        int from = page * PER_PAGE;
        for (int i = from; i < all.size() && i < from + PER_PAGE; i++) {
            TradeBoard.Seller x = all.get(i);
            Location l = x.v().getLocation();
            List<String> lore = new ArrayList<>();
            lore.add("<gray>" + TradeBoard.level(x.level()) + (x.stall() != null ? ", " + x.stall() : "") + " <dark_gray>at " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ());
            for (TradeBoard.Offer o : x.offers()) {
                lore.add((o.sold() ? "<dark_gray><st>" : "<white>") + esc(o.result()) + (o.sold() ? "</st>" : "</white>") + " <gray>for " + esc(o.price())
                        + (o.sold() ? " <red>(sold out)" : ""));
            }
            lore.add("");
            lore.add("<yellow>Click: guide me there");
            Material m = icon(x.v().getProfession());
            boolean books = x.offers().stream().anyMatch(o -> o.result().endsWith("book"));
            var it = icon(m, "<gold>" + x.profession() + (x.v().getCustomName() != null ? " <white>" + esc(x.v().getCustomName()) : ""), lore);
            button(i - from, books ? glow(it) : it, (p, c) -> {
                p.closeInventory();
                p.performCommand("guide " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ());
            });
        }
        if (page > 0) button(45, icon(Material.ARROW, "<aqua>Previous page", List.of()), (p, c) -> new TradesMenu(plugin, all, page - 1).open(p));
        if (from + PER_PAGE < all.size()) button(53, icon(Material.ARROW, "<aqua>Next page", List.of()), (p, c) -> new TradesMenu(plugin, all, page + 1).open(p));
        button(49, icon(Material.BOOK, "<gold>Finding a trade", List.of("<white>/trades mending</white> <gray>lists the villagers", "<gray>that sell it and marks them.",
                "<white>/trades labels</white> <gray>shows trades over", "<gray>villagers' heads (only you see them).", "<gray>Glowing icons sell enchanted books.")), null);
        button(48, icon(Material.NAME_TAG, "<aqua>Labels over villagers", List.of("", "<yellow>Click to switch")), (p, c) -> {
            p.closeInventory();
            p.performCommand("trades labels");
        });
    }

    private static String esc(String s) {
        return s.replace("<", "\\<");
    }

    private static Material icon(Villager.Profession p) {
        Material m = site.remotearc.employees.job.Stalls.workstationFor(p);
        return m != null ? m : p == Villager.Profession.NITWIT ? Material.DEAD_BUSH : Material.EMERALD;
    }
}
