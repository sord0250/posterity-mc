package site.remotearc.employees.gui;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.Skin;
import site.remotearc.employees.market.Market;
import site.remotearc.employees.market.SellMenu;
import site.remotearc.employees.util.Msg;

import java.util.List;
import java.util.UUID;

/** Admin controls for a merchant: name, place, skin. */
public final class MerchantMenu extends Menu {

    private final UUID id;

    public MerchantMenu(ArcEmployees plugin, UUID id) {
        super(plugin, 4, Component.text("Merchant"));
        this.id = id;
    }

    private Market.Merchant merchant() {
        for (Market.Merchant m : plugin.market().merchants()) if (m.id().equals(id)) return m;
        return null;
    }

    @Override
    protected void draw() {
        for (int s = 0; s < 36; s++) button(s, filler(), null);
        Market.Merchant m = merchant();
        if (m == null) return;
        button(4, icon(Material.EMERALD, "<gold>" + Describe.esc(m.name()), List.of(
                "<gray>At " + (int) m.x() + ", " + (int) m.y() + ", " + (int) m.z() + (m.world().equals("world") ? "" : " in " + m.world()),
                "<gray>Skin: <white>" + Describe.esc(m.skin().describe()))), null);
        for (int i = 0; i < Skin.DEFAULT_SKINS.size(); i++) {
            String name = Skin.DEFAULT_SKINS.get(i);
            for (int row = 0; row < 2; row++) {
                boolean slim = row == 1;
                Skin skin = Skin.builtIn(name, slim);
                ItemStack head = icon(Material.PLAYER_HEAD, "<aqua>" + skin.describe(), List.of("", "<yellow>Click to wear"));
                head.setData(DataComponentTypes.PROFILE, skin.profile(id));
                if (skin.equals(m.skin())) head = glow(head);
                button(9 + row * 9 + i, head, (p, c) -> {
                    plugin.market().update(id, null, null, skin);
                    redraw();
                });
            }
        }
        button(27, icon(Material.NAME_TAG, "<aqua>Rename", List.of("", "<yellow>Click, then type the name")), (p, c) ->
                Prompt.ask(p, "New name for the merchant?", text -> {
                    plugin.market().update(id, text.length() > 32 ? text.substring(0, 32) : text, null, null);
                    Msg.send(p, "<green>Renamed.");
                }));
        button(29, icon(Material.LEAD, "<aqua>Move here", List.of("<gray>To exactly where you stand now,", "<gray>facing the way you face.", "", "<yellow>Click to move")), (p, c) -> {
            plugin.market().update(id, null, p.getLocation(), null);
            p.closeInventory();
            Msg.send(p, "<green>Moved.");
        });
        button(30, icon(Material.COMPASS, "<aqua>Move somewhere else", List.of("<gray>Walk to the new spot, then type <white>here</white>.", "", "<yellow>Click to start")), (p, c) ->
                Prompt.ask(p, "Walk to where the merchant should stand, face the way they should face, then type 'here'.", text -> {
                    plugin.market().update(id, null, p.getLocation(), null);
                    Msg.send(p, "<green>Moved.");
                }));
        button(31, icon(Material.PLAYER_HEAD, "<aqua>Copy a player's skin", List.of("", "<yellow>Click, then type the name")), (p, c) ->
                Prompt.ask(p, "Whose skin should the merchant wear?", name -> Skin.fetchPlayer(plugin, name, skin -> {
                    if (skin == null) Msg.error(p, "Couldn't find a Minecraft account called <white><n></white>.", Msg.p("n", name));
                    else {
                        plugin.market().update(id, null, null, skin);
                        Msg.send(p, "<green>The merchant now wears " + Describe.esc(skin.describe()) + ".");
                    }
                })));
        button(33, icon(Material.CHEST, "<aqua>Open the shop", List.of("<gray>What players see.", "", "<yellow>Click to open")), (p, c) ->
                new SellMenu(plugin, p, m.name()).open());
        button(35, icon(Material.BARRIER, "<red>Remove this merchant", List.of("", "<yellow>Click to remove")), (p, c) ->
                new ConfirmMenu(plugin, "Remove " + m.name() + "?", "<red>Yes, remove", pl -> {
                    pl.closeInventory();
                    plugin.market().remove(id);
                    Msg.send(pl, "<gray>" + m.name() + " closed up shop.");
                }, this::open).open(p));
    }
}
