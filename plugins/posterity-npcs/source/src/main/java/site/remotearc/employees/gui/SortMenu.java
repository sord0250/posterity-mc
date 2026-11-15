package site.remotearc.employees.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.sort.Categories;
import site.remotearc.employees.sort.SortProfiles;
import site.remotearc.employees.sort.Sorter;
import site.remotearc.employees.util.Msg;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /sort: sort, put away, tidy, undo; and every category, on or off, with the chests kept for it. */
public final class SortMenu extends Menu {

    private final Sorter sorter;
    private Player viewer;

    public SortMenu(ArcEmployees plugin, Sorter sorter) {
        super(plugin, 6, Component.text("Sorting"));
        this.sorter = sorter;
    }

    @Override
    public void openFor(Player p) {
        viewer = p;
    }

    @Override
    protected boolean live() {
        return false;
    }

    private static void sort(Player p, String args) {
        p.closeInventory();
        p.performCommand("sort " + args);
    }

    @Override
    protected void draw() {
        for (int s = 0; s < 54; s++) button(s, filler(), null);
        if (viewer == null) return;
        SortProfiles.Profile prof = sorter.profiles().get(viewer.getUniqueId());

        button(0, glow(icon(Material.HOPPER, "<green>Sort the chests round me", List.of(
                "<gray>Shows the plan first: which chest", "<gray>gets which category, with labels", "<gray>over the chests. Then [Sort it].",
                "", "<yellow>Click"))), (p, c) -> sort(p, "organize"));
        button(1, icon(Material.WOODEN_AXE, "<aqua>Sort my WorldEdit selection", List.of(
                "<gray>The same, for the containers in", "<gray>your wand selection instead.", "", "<yellow>Click")), (p, c) -> sort(p, "organize sel"));
        button(2, icon(Material.CHEST_MINECART, "<aqua>Put my things away", List.of(
                "<gray>Your inventory (not the hotbar) into", "<gray>chests that have the same items or", "<gray>are kept for their category.",
                "", "<yellow>Click", "<yellow>Right-click: hotbar too")), (p, c) -> sort(p, c.isRightClick() ? "deposit hotbar" : "deposit"));
        button(3, icon(Material.BRUSH, "<aqua>Tidy each chest", List.of("<gray>Merges and orders stacks inside", "<gray>each chest; nothing changes chest.",
                "", "<yellow>Click")), (p, c) -> sort(p, "tidy"));
        button(4, icon(Material.CLOCK, "<aqua>Undo the last sort", List.of("", "<yellow>Click")), (p, c) -> sort(p, "undo"));

        var lab = icon(Material.OAK_SIGN, "<aqua>Labels on chests: " + (prof.labels ? "<green>on" : "<red>off"), List.of(
                "<gray>On: chests and barrels near you show", "<gray>what's in them on their front (only", "<gray>you see them).", "",
                "<yellow>Click to turn " + (prof.labels ? "off" : "on")));
        button(5, prof.labels ? glow(lab) : lab, (p, c) -> {
            p.performCommand("sort labels " + (prof.labels ? "off" : "on"));
            redraw();
        });
        button(6, icon(Material.SPYGLASS, "<aqua>Looks " + prof.radius + " blocks round you", List.of(
                "<gray>Chests kept for a category count", "<gray>from further off too (48 blocks).", "",
                "<yellow>Click: +2   Right-click: -2")), (p, c) -> {
            prof.radius = Math.max(2, Math.min(Sorter.MAX_RADIUS, prof.radius + (c.isRightClick() ? -2 : 2)));
            sorter.profiles().save();
            redraw();
        });
        List<String> vl = new ArrayList<>();
        vl.add(prof.virtual.isEmpty() ? "<gray>None yet." : "<gray>Sorted too: <white>" + prof.virtual);
        vl.add("<gray>Their contents are sorted along with");
        vl.add("<gray>the chests round you, and they take");
        vl.add("<gray>whatever needs room. Keep one for a");
        vl.add("<gray>category in that category's menu.");
        vl.add("");
        vl.add("<yellow>Click: add one   Right-click: remove one");
        button(7, icon(Material.ENDER_CHEST, "<aqua>Virtual chests", vl), (p, c) -> {
            if (!plugin.vchests().available()) {
                Msg.error(p, "VirtualChests isn't available.");
                return;
            }
            Prompt.ask(p, (c.isRightClick() ? "Stop sorting" : "Sort") + " which virtual chest (its number)?",
                    t -> {
                        p.performCommand("sort virtual " + (c.isRightClick() ? "remove " : "add ") + t.trim());
                        new SortMenu(plugin, sorter).open(p);
                    });
        });
        button(8, icon(Material.NAME_TAG, "<aqua>New category", List.of("<gray>Your own: name it, then add items", "<gray>(from your hand or a whole chest).",
                "<gray>It takes them from the others.", "", "<yellow>Click")), (p, c) -> Prompt.ask(p, "Name the new category:", text -> {
            String key = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
            if (key.isEmpty() || Categories.def(key) != null || prof.custom.containsKey(key)) {
                Msg.error(p, "Pick another name: that one's taken or empty.");
                return;
            }
            Material hand = p.getInventory().getItemInMainHand().getType();
            SortProfiles.Custom cu = prof.newCustom(key, text.trim(), hand.isAir() || !hand.isItem() ? Material.NAME_TAG : hand);
            if (!hand.isAir()) cu.items.add(hand);
            sorter.profiles().save();
            new SortCategoryMenu(plugin, sorter, key, this).open(p);
        }));

        List<String> keys = new ArrayList<>(prof.custom.keySet());
        for (Categories.Def d : Categories.MAIN) keys.add(d.key());
        for (Categories.Def d : Categories.EXTRAS) keys.add(d.key());
        int slot = 9;
        for (String key : keys) {
            if (slot > 44) break;
            boolean on = prof.on(key);
            List<String> lore = new ArrayList<>();
            Categories.Def d = Categories.def(key);
            lore.add(prof.custom.containsKey(key) ? "<gray>Your own category" : d.main() ? "<gray>Main category" : "<gray>Extra: takes its items from the main ones");
            lore.add(on ? "<green>On" : "<red>Off" + (d != null && d.main() ? "<gray>: its items go to Everything else" : ""));
            List<String> pins = prof.pins.getOrDefault(key, List.of());
            if (pins.isEmpty()) lore.add("<gray>Chests: picked when sorting");
            else {
                lore.add("<gray>Kept for it:");
                for (int i = 0; i < pins.size() && i < 4; i++) lore.add("<white> " + pinName(pins.get(i)));
                if (pins.size() > 4) lore.add("<gray> and " + (pins.size() - 4) + " more");
            }
            lore.add("");
            lore.add("<yellow>Click: its chests" + (prof.custom.containsKey(key) ? " and items" : ""));
            if (!key.equals(Categories.MISC)) lore.add("<yellow>Right-click: turn " + (on ? "off" : "on"));
            ItemStack it = icon(prof.icon(key), (on ? "<green>" : "<gray>") + prof.name(key), lore);
            button(slot++, on ? glow(it) : it, (p, c) -> {
                if (c.isRightClick()) {
                    if (key.equals(Categories.MISC)) return;
                    prof.switched.put(key, !prof.on(key));
                    sorter.profiles().save();
                    redraw();
                    return;
                }
                new SortCategoryMenu(plugin, sorter, key, this).open(p);
            });
        }
        button(49, icon(Material.BOOK, "<gold>How it works", List.of(
                "<gray>Every item has a category. Sorting",
                "<gray>gives each category its own chests:",
                "<gray>the ones you keep for it first, then",
                "<gray>the ones already holding most of it.",
                "<gray>Small ones share if chests run out.",
                "<gray>Turn categories on to split things",
                "<gray>finer, or off to lump them together.")), null);
    }

    static String pinName(String key) {
        if (key.startsWith("vc:")) return "virtual chest " + key.substring(3);
        String[] a = key.split(";");
        return a.length == 4 ? "chest at " + a[1] + " " + a[2] + " " + a[3] + (a[0].equals("world") ? "" : " (" + a[0] + ")") : key;
    }
}
