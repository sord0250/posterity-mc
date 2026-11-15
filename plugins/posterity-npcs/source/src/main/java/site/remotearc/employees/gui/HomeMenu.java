package site.remotearc.employees.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.Employee;
import site.remotearc.employees.HomeSites;
import site.remotearc.employees.build.HomeCatalog;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** An employee's home: where it lives, and the NPC Homes catalog to pick a house from. */
public final class HomeMenu extends Menu {

    private static final int PER_PAGE = 36;

    private final Employee e;
    private final Menu back;
    private final int page;
    /** Showing the owner's own uploaded houses instead of the catalog. */
    private final boolean mine;

    public HomeMenu(ArcEmployees plugin, Employee e, Menu back, int page) {
        this(plugin, e, back, page, false);
    }

    public HomeMenu(ArcEmployees plugin, Employee e, Menu back, int page, boolean mine) {
        super(plugin, 6, Component.text(e.name + "'s home" + (mine ? ": my houses" : "")));
        this.e = e;
        this.back = back;
        this.page = page;
        this.mine = mine;
    }

    @Override
    protected void draw() {
        if (!plugin.manager().all().contains(e)) return;
        for (int s = 0; s < 9; s++) button(s, filler(), null);
        for (int s = 45; s < 54; s++) button(s, filler(), null);

        List<String> about = new ArrayList<>();
        about.add(HomeSites.describe(plugin, e));
        about.add("");
        about.addAll(wrap("<gray>", "Pick a house below and it's placed where you stand, as ghost blocks"
                + " you can turn and move. Once it's built, " + e.name + " moves in.", 36));
        about.add("");
        about.addAll(wrap("<dark_gray>", "Built it by hand already? Look at its bed and use /employees house " + e.name + ".", 36));
        button(4, icon(e.house != null ? Material.RED_BED : e.homeSite != null ? Material.SCAFFOLDING : Material.WHITE_BED,
                "<gold>" + e.name + "'s home", about), null);

        // Tabs: the catalog, the owner's own houses, and uploading one.
        List<java.io.File> own = plugin.homeCatalog().personal(e.owner);
        ItemStack catTab = icon(Material.BOOKSHELF, "<aqua>NPC Homes catalog", List.of("<gray>Houses picked by the server's admins.",
                "", mine ? "<yellow>Click to see them" : "<green>Showing"));
        button(2, mine ? catTab : glow(catTab), (p, c) -> new HomeMenu(plugin, e, back, 0, false).open(p));
        ItemStack mineTab = icon(Material.CHEST, "<aqua>My houses (" + own.size() + ")", List.of("<gray>Houses you uploaded yourself.",
                "<gray>Only you see them.", "", mine ? "<green>Showing" : "<yellow>Click to see them"));
        button(6, mine ? glow(mineTab) : mineTab, (p, c) -> new HomeMenu(plugin, e, back, 0, true).open(p));
        button(8, icon(Material.WRITABLE_BOOK, "<aqua>Upload your own house", List.of("<gray>From your computer: a .schem,",
                "<gray>.litematic, .nbt or .schematic file", "<gray>with a bed and a door.", "", "<yellow>Click for an upload link")), (p, c) -> {
            if (!p.getUniqueId().equals(e.owner)) {   // a manager or admin: houses they upload would be theirs, not the owner's
                p.sendMessage(net.kyori.adventure.text.Component.text("Only " + e.ownerName() + " can add houses to their own list.",
                        net.kyori.adventure.text.format.NamedTextColor.RED));
                return;
            }
            p.closeInventory();
            run(p, "homes upload");
        });

        int from = page * PER_PAGE;
        if (mine) {
            for (int i = 0; i < PER_PAGE && from + i < own.size(); i++) {
                String name = HomeCatalog.personalName(own.get(from + i).getName());
                button(9 + i, icon(Material.PAPER, "<aqua>" + Describe.esc(name), List.of("<gray>One of your own houses.", "",
                        "<yellow>Click to place it where you stand", "<gray>Shift-click to delete it")), (p, c) -> {
                    if (c.isShiftClick()) {
                        if (!p.getUniqueId().equals(e.owner)) return;   // only the owner deletes their houses
                        new ConfirmMenu(plugin, "Delete " + name + "?", "<red>Yes, delete my house", pl -> {
                            run(pl, "homes forget " + name);
                            new HomeMenu(plugin, e, back, 0, true).open(pl);
                        }, this::open).open(p);
                        return;
                    }
                    p.closeInventory();
                    run(p, "house " + ref(p, e) + " style mine:" + name);
                });
            }
            if (own.isEmpty()) {
                button(22, icon(Material.PAPER, "<yellow>No houses of your own yet", List.of(
                        "<gray>Click <white>Upload your own house</white> above", "<gray>(or /employees homes upload).")), null);
            }
            if (back != null) button(45, back(), (p, c) -> back.open(p));
            if (page > 0) button(48, icon(Material.ARROW, "<yellow>Previous page", List.of()), (p, c) -> new HomeMenu(plugin, e, back, page - 1, true).open(p));
            if (from + PER_PAGE < own.size()) button(50, icon(Material.ARROW, "<yellow>Next page", List.of()), (p, c) -> new HomeMenu(plugin, e, back, page + 1, true).open(p));
            drawSiteButtons();
            return;
        }

        List<HomeCatalog.Entry> homes = plugin.homeCatalog().list();
        for (int i = 0; i < PER_PAGE && from + i < homes.size(); i++) {
            HomeCatalog.Entry h = homes.get(from + i);
            List<String> lore = new ArrayList<>();
            if (!h.style().isEmpty()) lore.add("<gray>Style: <white>" + Describe.esc(h.style()));
            if (!h.description().isEmpty()) lore.addAll(wrap("<gray>", Describe.esc(h.description()), 36));
            lore.add("");
            lore.add("<yellow>Click to place it where you stand");
            button(9 + i, icon(h.icon(), "<aqua>" + Describe.esc(h.name()), lore), (p, c) -> {
                p.closeInventory();
                run(p, "house " + ref(p, e) + " style " + h.id());
            });
        }
        if (homes.isEmpty()) {
            button(22, icon(Material.BARRIER, "<yellow>No houses in the catalog yet", List.of(
                    "<gray>An admin adds them with", "<white>/employees homes upload catalog", "", "<gray>Or upload one of your own above.")), null);
        }

        if (back != null) button(45, back(), (p, c) -> back.open(p));
        if (page > 0) button(48, icon(Material.ARROW, "<yellow>Previous page", List.of()), (p, c) -> new HomeMenu(plugin, e, back, page - 1).open(p));
        if (from + PER_PAGE < homes.size()) button(50, icon(Material.ARROW, "<yellow>Next page", List.of()), (p, c) -> new HomeMenu(plugin, e, back, page + 1).open(p));
        drawSiteButtons();
    }

    /** Bottom row: who builds the home site, and moving out. */
    private void drawSiteButtons() {
        boolean building = e.homeSite != null && e.siteBed != null
                && (e.house == null || !e.house.bed.equals(e.siteBed));
        if (building) {
            button(47, icon(Material.GLASS, "<aqua>Build it yourself", List.of("<gray>Shows the house as ghost blocks", "<gray>to build over.",
                    "", "<yellow>Click to show it")), (p, c) -> {
                p.closeInventory();
                run(p, "house " + ref(p, e) + " preview");
            });
            button(49, icon(Material.IRON_PICKAXE, "<aqua>Have a builder build it", List.of("<gray>One of your builders builds it",
                    "<gray>with materials from your storage.", "", "<yellow>Click to pick a builder")), (p, c) -> {
                p.closeInventory();
                run(p, "house " + ref(p, e) + " builder");
            });
            button(51, icon(e.selfBuild ? Material.LIME_DYE : Material.GRAY_DYE, "<aqua>" + e.name + " builds it themself: "
                    + (e.selfBuild ? "<green>on" : "<gray>off"), List.of("<gray>A minute every few minutes, and", "<gray>whenever their job has nothing",
                    "<gray>to do. Slow, but free.", "", "<yellow>Click to turn " + (e.selfBuild ? "off" : "on"))), (p, c) -> {
                run(p, "house " + ref(p, e) + " self " + (e.selfBuild ? "off" : "on"));
                redraw();
            });
        }
        if (e.homeSite != null || e.house != null) {
            button(53, icon(Material.TNT_MINECART, "<red>Move out", List.of("<gray>" + e.name + " leaves their home (or home site)",
                    "<gray>and is homeless again.")), (p, c) -> new ConfirmMenu(plugin, "Move " + e.name + " out?",
                    "<red>Yes, move them out", pl -> {
                pl.closeInventory();
                run(pl, "house " + ref(pl, e) + " clear");
            }, this::open).open(p));
        }
    }
}
