package site.remotearc.employees.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.sort.Categories;
import site.remotearc.employees.sort.SortProfiles;
import site.remotearc.employees.sort.Sorter;
import site.remotearc.employees.util.Items;
import site.remotearc.employees.util.Msg;

import java.util.ArrayList;
import java.util.List;

/** One sorting category: on or off, the chests (and virtual chests) kept for it, and for your own ones, its items. */
final class SortCategoryMenu extends Menu {

    private final Sorter sorter;
    private final String key;
    private final Menu back;
    private Player viewer;

    SortCategoryMenu(ArcEmployees plugin, Sorter sorter, String key, Menu back) {
        super(plugin, 6, Component.text("Sorting: " + key.replace('_', ' ')));
        this.sorter = sorter;
        this.key = key;
        this.back = back;
    }

    @Override
    public void openFor(Player p) {
        viewer = p;
    }

    @Override
    protected boolean live() {
        return false;
    }

    @Override
    protected void draw() {
        for (int s = 0; s < 54; s++) button(s, filler(), null);
        if (viewer == null) return;
        SortProfiles.Profile prof = sorter.profiles().get(viewer.getUniqueId());
        SortProfiles.Custom custom = prof.custom.get(key);
        boolean on = prof.on(key);
        button(4, icon(prof.icon(key), "<gold>" + prof.name(key), List.of(on ? "<green>On" : "<red>Off")), null);
        if (!key.equals(Categories.MISC)) {
            button(10, icon(on ? Material.LIME_DYE : Material.GRAY_DYE, on ? "<green>On" : "<red>Off", List.of(
                    on ? "<gray>Its items get chests of their own." : "<gray>Its items go elsewhere.", "", "<yellow>Click to turn " + (on ? "off" : "on"))), (p, c) -> {
                prof.switched.put(key, !on);
                sorter.profiles().save();
                redraw();
            });
        }
        button(12, icon(Material.CHEST, "<aqua>Keep a chest for it", List.of("<gray>Click a chest or barrel in the world:", "<gray>sorting always puts this category",
                "<gray>there first (and nothing else, unless", "<gray>chests run out).", "", "<yellow>Click, then click the chest")), (p, c) ->
                ChestPick.ask(p, "the chest for " + prof.name(key), b -> {
                    Sorter.Box box = sorter.boxAt(b);
                    if (box == null) {
                        Msg.error(p, "Sorting uses chests, trapped chests and barrels.");
                        return;
                    }
                    prof.pin(key, box.key);
                    sorter.profiles().save();
                    Msg.send(p, "<green>That " + box.describe(p) + " is for " + prof.name(key) + " now.");
                    open(p);
                }));
        button(14, icon(Material.ENDER_CHEST, "<aqua>Keep a virtual chest for it", List.of("<gray>Its items go into that virtual", "<gray>chest when sorting, wherever you are.",
                "", "<yellow>Click, then type its number")), (p, c) -> Prompt.ask(p, "Which virtual chest (its number)?", t -> {
            p.performCommand("sort pin " + key + " vc " + t.trim());
            open(p);
        }));

        List<String> pins = new ArrayList<>(prof.pins.getOrDefault(key, List.of()));
        button(18, icon(Material.PAPER, "<gray>Kept for it (" + pins.size() + "):", List.of(pins.isEmpty()
                ? "<gray>None: sorting picks chests itself." : "<gray>Click one to stop keeping it.")), null);
        for (int i = 0; i < pins.size() && i < 8; i++) {
            String pin = pins.get(i);
            button(19 + i, icon(pin.startsWith("vc:") ? Material.ENDER_CHEST : Material.CHEST, "<white>" + SortMenu.pinName(pin), List.of("", "<yellow>Click to stop keeping it")), (p, c) -> {
                prof.unpin(pin);
                sorter.profiles().save();
                redraw();
            });
        }

        if (custom != null) {
            button(16, icon(Material.HOPPER, "<aqua>Add items", List.of("<gray>Click: the item in your hand", "<gray>Right-click: every kind of item in", "<gray>a chest you click",
                    "", "<yellow>Click")), (p, c) -> {
                if (c.isRightClick()) {
                    ChestPick.ask(p, "a chest whose items belong in " + custom.name, b -> {
                        Sorter.Box box = sorter.boxAt(b);
                        if (box == null || !(b.getState(false) instanceof org.bukkit.block.Container con)) return;
                        int before = custom.items.size();
                        for (ItemStack it : con.getInventory().getStorageContents()) if (it != null && !it.getType().isAir()) custom.items.add(it.getType());
                        sorter.profiles().save();
                        Msg.send(p, "<green>Added " + (custom.items.size() - before) + " kinds of item to " + custom.name + ".");
                        open(p);
                    });
                    return;
                }
                Material hand = p.getInventory().getItemInMainHand().getType();
                if (hand.isAir()) {
                    Msg.error(p, "Hold the item first.");
                    return;
                }
                custom.items.add(hand);
                sorter.profiles().save();
                redraw();
            });
            List<Material> items = new ArrayList<>(custom.items);
            button(27, icon(Material.PAPER, "<gray>Its items (" + items.size() + "):", List.of("<gray>Click one to take it out.")), null);
            for (int i = 0; i < items.size() && i < 17; i++) {
                Material m = items.get(i);
                button(28 + i, icon(m.isItem() ? m : Material.PAPER, "<white>" + Items.name(m), List.of("", "<yellow>Click to take it out")), (p, c) -> {
                    custom.items.remove(m);
                    sorter.profiles().save();
                    redraw();
                });
            }
            button(52, icon(Material.LAVA_BUCKET, "<red>Delete this category", List.of("<gray>Its items go back to their", "<gray>usual categories.", "", "<yellow>Shift-click")), (p, c) -> {
                if (!c.isShiftClick()) return;
                prof.removeCustom(key);
                sorter.profiles().save();
                (back != null ? back : new SortMenu(plugin, sorter)).open(p);
            });
        }
        button(45, back(), (p, c) -> (back != null ? back : new SortMenu(plugin, sorter)).open(p));
    }
}
