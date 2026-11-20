package site.remotearc.employees.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.HangoutSpots;

import java.util.List;
import java.util.UUID;

/**
 * Named areas where this kingdom's off-duty employees gather - see what's set
 * and delete one, or pick a new one out of body. Reached from the kingdom
 * menu's "NPC hangout spots" button, or {@code /employees hangouts}.
 */
public final class HangoutSpotsMenu extends Menu {

    private static final int PER_PAGE = 36;

    private final UUID kingdomOwner;
    private final int page;

    public HangoutSpotsMenu(ArcEmployees plugin, UUID kingdomOwner) {
        this(plugin, kingdomOwner, 0);
    }

    public HangoutSpotsMenu(ArcEmployees plugin, UUID kingdomOwner, int page) {
        super(plugin, 5, Component.text("NPC hangout spots"));
        this.kingdomOwner = kingdomOwner;
        this.page = page;
    }

    @Override
    protected void draw() {
        for (int s = 0; s < 45; s++) button(s, filler(), null);
        List<HangoutSpots.Spot> spots = plugin.hangoutSpots().of(kingdomOwner);
        int from = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && from + i < spots.size(); i++) {
            HangoutSpots.Spot spot = spots.get(from + i);
            button(i, icon(Material.CAMPFIRE, "<gold>" + Describe.esc(spot.name()), List.of(
                    "<gray>" + spot.area().describe(), "<gray>in " + Describe.esc(spot.area().world()),
                    "", "<yellow>Right-click to remove")), (p, c) -> {
                if (!c.isRightClick()) return;
                new ConfirmMenu(plugin, "Remove the hangout spot \"" + spot.name() + "\"?", "<red>Yes, remove it", pl -> {
                    plugin.hangoutSpots().remove(kingdomOwner, spot.name());
                    open(pl);
                }, this::open).open(p);
            });
        }
        if (spots.isEmpty()) {
            button(22, icon(Material.PAPER, "<yellow>No hangout spots yet", List.of(
                    "<gray>Add one below: off-duty employees", "<gray>of yours (or anyone who trusts you)", "<gray>gather there instead of loitering",
                    "<gray>at their own post.")), null);
        }
        if (page > 0) button(36, icon(Material.ARROW, "<yellow>Previous page", List.of()), (p, c) -> new HangoutSpotsMenu(plugin, kingdomOwner, page - 1).open(p));
        if (from + PER_PAGE < spots.size()) button(44, icon(Material.ARROW, "<yellow>Next page", List.of()), (p, c) -> new HangoutSpotsMenu(plugin, kingdomOwner, page + 1).open(p));
        button(40, glow(icon(Material.ENDER_EYE, "<green>Add a hangout spot", List.of(
                "<gray>Name it, then leave your body and", "<gray>fly over the site: click one corner,", "<gray>then the other. Off-duty employees",
                "<gray>stand somewhere in it at random.", "", "<yellow>Click to name it"))), (p, c) -> {
            p.closeInventory();
            Prompt.ask(p, "Name this hangout spot (a tavern, a park...)?", name -> run(p, "hangouts add " + name));
        });
    }
}
