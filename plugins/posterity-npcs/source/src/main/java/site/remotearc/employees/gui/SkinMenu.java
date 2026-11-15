package site.remotearc.employees.gui;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.Employee;
import site.remotearc.employees.Skin;

import java.util.List;

/** Pick one of the game's built-in skins, or copy a player's. */
final class SkinMenu extends Menu {

    private final Employee e;
    private final Menu back;

    SkinMenu(ArcEmployees plugin, Employee e, Menu back) {
        super(plugin, 4, Component.text(e.name + "'s skin"));
        this.e = e;
        this.back = back;
    }

    @Override
    protected void draw() {
        for (int s = 0; s < 36; s++) button(s, filler(), null);
        for (int row = 0; row < 2; row++) {
            boolean slim = row == 1;
            for (int i = 0; i < Skin.DEFAULT_SKINS.size(); i++) {
                String name = Skin.DEFAULT_SKINS.get(i);
                Skin skin = Skin.builtIn(name, slim);
                ItemStack head = icon(Material.PLAYER_HEAD, "<aqua>" + skin.describe(), List.of(
                        slim ? "<gray>Slim arms" : "<gray>Wide arms", "", "<yellow>Click to wear"));
                head.setData(DataComponentTypes.PROFILE, skin.profile(e.id));
                if (skin.equals(e.skin)) head = glow(head);
                button(row * 9 + i, head, (p, c) -> {
                    run(p, "skin " + ref(p, e) + " default " + name + " " + (slim ? "slim" : "wide"));
                    redraw();
                });
            }
        }
        button(22, icon(Material.NAME_TAG, "<aqua>Copy a player's skin", List.of(
                "<gray>Any Minecraft account name.", "", "<yellow>Click, then type the name")), (p, c) -> {
            String who = ref(p, e);
            Prompt.ask(p, "Whose skin should " + e.name + " wear?", name -> run(p, "skin " + who + " " + name));
        });
        button(27, back(), (p, c) -> back.open(p));
    }
}
