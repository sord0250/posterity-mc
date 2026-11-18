package site.remotearc.employees.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.Employee;
import site.remotearc.employees.HomeSites;
import site.remotearc.employees.build.HomeCatalog;

import java.util.ArrayList;
import java.util.List;

/** An employee's home: where it lives, and the NPC Homes catalog to pick a house from. */
public final class HomeMenu extends Menu {

    private static final int PER_PAGE = 10;

    private final Employee e;
    private final Menu back;
    private final int page;
    /** Showing the owner's own uploaded houses instead of the catalog. */
    private final boolean mine;

    public HomeMenu(ArcEmployees plugin, Employee e, Menu back, int page) {
        this(plugin, e, back, page, false);
    }

    public HomeMenu(ArcEmployees plugin, Employee e, Menu back, int page, boolean mine) {
        super(plugin, 1, Component.empty());
        this.e = e;
        this.back = back;
        this.page = page;
        this.mine = mine;
    }

    @Override
    protected void draw() {}

    @Override
    public void open(Player p) {
        if (!plugin.manager().all().contains(e)) return;
        List<String> lines = new ArrayList<>();
        lines.add(HomeSites.describe(plugin, e));
        lines.add("<gray>Pick a house below and it's placed where you stand, as ghost blocks you can turn and move."
                + " Once it's built, " + e.name + " moves in.\n<dark_gray>Built it by hand already? Look at its bed and use"
                + " /employees house " + e.name + ".");

        List<Dialogs.Button> b = new ArrayList<>();
        List<java.io.File> own = plugin.homeCatalog().personal(e.owner);
        b.add(Dialogs.button(mine ? "<white>NPC Homes catalog" : "<green>NPC Homes catalog (showing)",
                "<gray>Houses picked by the server's admins.", pl -> new HomeMenu(plugin, e, back, 0, false).open(pl)));
        b.add(Dialogs.button(mine ? "<green>My houses (" + own.size() + ") (showing)" : "<white>My houses (" + own.size() + ")",
                "<gray>Houses you uploaded yourself. Only you see them.", pl -> new HomeMenu(plugin, e, back, 0, true).open(pl)));
        b.add(Dialogs.button("<aqua>Upload your own house", "<gray>From your computer: a .schem, .litematic, .nbt or .schematic"
                + " file with a bed and a door.", pl -> {
            if (!pl.getUniqueId().equals(e.owner)) {
                pl.sendMessage(Component.text("Only " + e.ownerName() + " can add houses to their own list.", NamedTextColor.RED));
                return;
            }
            run(pl, "homes upload");
        }));

        int from = page * PER_PAGE;
        if (mine) {
            for (int i = 0; from + i < own.size() && i < PER_PAGE; i++) {
                String name = HomeCatalog.personalName(own.get(from + i).getName());
                b.add(Dialogs.button("<white>" + Describe.esc(name), "<gray>One of your own houses.", pl -> ownHouse(pl, name)));
            }
            if (own.isEmpty()) lines.add("<yellow>No houses of your own yet: Upload your own house above"
                    + " (or /employees homes upload).");
            if (page > 0) b.add(Dialogs.button("<yellow>Previous page", null, pl -> new HomeMenu(plugin, e, back, page - 1, true).open(pl)));
            if (from + PER_PAGE < own.size()) b.add(Dialogs.button("<yellow>Next page", null, pl -> new HomeMenu(plugin, e, back, page + 1, true).open(pl)));
        } else {
            List<HomeCatalog.Entry> homes = plugin.homeCatalog().list();
            for (int i = 0; from + i < homes.size() && i < PER_PAGE; i++) {
                HomeCatalog.Entry h = homes.get(from + i);
                StringBuilder hover = new StringBuilder();
                if (!h.style().isEmpty()) hover.append("<gray>Style: <white>").append(Describe.esc(h.style()));
                if (!h.description().isEmpty()) {
                    if (!hover.isEmpty()) hover.append('\n');
                    hover.append("<gray>").append(Describe.esc(h.description()));
                }
                b.add(Dialogs.button("<white>" + Describe.esc(h.name()), hover.toString(), pl -> {
                    run(pl, "house " + ref(pl, e) + " style " + h.id());
                }));
            }
            if (homes.isEmpty()) lines.add("<yellow>No houses in the catalog yet: an admin adds them with"
                    + " /employees homes upload catalog, or upload one of your own above.");
            if (page > 0) b.add(Dialogs.button("<yellow>Previous page", null, pl -> new HomeMenu(plugin, e, back, page - 1).open(pl)));
            if (from + PER_PAGE < homes.size()) b.add(Dialogs.button("<yellow>Next page", null, pl -> new HomeMenu(plugin, e, back, page + 1).open(pl)));
        }

        siteButtons(b);
        Dialogs.Button backBtn = back != null ? Dialogs.back(pl -> back.open(pl)) : Dialogs.close("Done");

        Dialogs.show(plugin, p, "<gold>" + e.name + "'s home" + (mine ? " <gray>· my houses" : ""), lines, b, 2, backBtn);
    }

    /** Placing or deleting one of the owner's own uploaded houses. */
    private void ownHouse(Player p, String name) {
        List<Dialogs.Button> b = new ArrayList<>();
        b.add(Dialogs.button("<green>Place it here", null, pl -> run(pl, "house " + ref(pl, e) + " style mine:" + name)));
        if (p.getUniqueId().equals(e.owner)) {
            b.add(Dialogs.button("<red>Delete it", null, pl -> new ConfirmMenu(plugin, "Delete " + name + "?", "<red>Yes, delete my house", x -> {
                run(x, "homes forget " + name);
            }, x -> this.open(x)).open(pl)));
        }
        Dialogs.show(plugin, p, "<gold>" + Describe.esc(name), List.of(), b, 1, Dialogs.back(this::open));
    }

    /** Who builds the home site, and moving out. */
    private void siteButtons(List<Dialogs.Button> b) {
        boolean building = e.homeSite != null && e.siteBed != null
                && (e.house == null || !e.house.bed.equals(e.siteBed));
        if (building) {
            b.add(Dialogs.button("<aqua>Build it yourself", "<gray>Shows the house as ghost blocks to build over.",
                    pl -> run(pl, "house " + ref(pl, e) + " preview")));
            b.add(Dialogs.button("<aqua>Have a builder build it", "<gray>One of your builders builds it with materials from your storage.",
                    pl -> run(pl, "house " + ref(pl, e) + " builder")));
            b.add(Dialogs.button("<aqua>" + e.name + " builds it themself: " + (e.selfBuild ? "<green>on" : "<gray>off"),
                    "<gray>A minute every few minutes, and whenever their job has nothing to do. Slow, but free.", pl -> {
                run(pl, "house " + ref(pl, e) + " self " + (e.selfBuild ? "off" : "on"));
                open(pl);
            }));
        }
        if (e.homeSite != null || e.house != null) {
            b.add(Dialogs.button("<red>Move out", "<gray>" + e.name + " leaves their home (or home site) and is homeless again.",
                    pl -> new ConfirmMenu(plugin, "Move " + e.name + " out?", "<red>Yes, move them out", x -> {
                run(x, "house " + ref(x, e) + " clear");
            }, this::open).open(pl)));
        }
    }
}
