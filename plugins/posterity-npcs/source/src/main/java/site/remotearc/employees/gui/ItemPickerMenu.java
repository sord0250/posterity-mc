package site.remotearc.employees.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.util.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Pick items by clicking them instead of typing their names: suggestions
 * first (what an employee has), or every item whose name contains a search.
 * Picked items glow; Done goes back.
 */
final class ItemPickerMenu extends Menu {

    private static final int PER_PAGE = 45;

    private final String title;
    private final List<Material> suggestions;
    private final String search;   // null = suggestions
    private final int page;
    private final Set<Material> picked;
    private final Consumer<Material> onPick;
    private final Menu back;

    ItemPickerMenu(ArcEmployees plugin, String title, List<Material> suggestions, String search, int page,
                   Set<Material> picked, Consumer<Material> onPick, Menu back) {
        super(plugin, 6, Component.text(search == null ? title : title + ": " + search));
        this.title = title;
        this.suggestions = suggestions;
        this.search = search;
        this.page = page;
        this.picked = picked;
        this.onPick = onPick;
        this.back = back;
    }

    private List<Material> choices() {
        if (search == null) return suggestions;
        String q = search.toLowerCase(Locale.ROOT).trim().replace(' ', '_');
        List<Material> out = new ArrayList<>();
        for (Material m : Material.values()) {
            if (m.isLegacy() || !m.isItem() || m.isAir()) continue;
            if (m.name().toLowerCase(Locale.ROOT).contains(q)) out.add(m);
        }
        return out;
    }

    @Override
    protected void draw() {
        List<Material> list = choices();
        int from = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE; i++) {
            if (from + i >= list.size()) {
                button(i, ItemStack.of(Material.AIR), null);
                continue;
            }
            Material m = list.get(from + i);
            boolean on = picked.contains(m);
            ItemStack it = icon(m, (on ? "<green>" : "<white>") + Items.name(m), List.of(on ? "<green>Added" : "", "<yellow>Click to add"));
            button(i, on ? glow(it) : it, (p, c) -> {
                onPick.accept(m);
                redraw();
            });
        }
        if (list.isEmpty()) {
            button(22, icon(Material.PAPER, "<yellow>Nothing here", List.of(search == null ? "<gray>Use Search to find any item." : "<gray>No item names contain that.")), null);
        }
        for (int s = 45; s < 54; s++) button(s, filler(), null);
        button(45, back(), (p, c) -> back.open(p));
        if (page > 0) button(47, icon(Material.ARROW, "<yellow>Previous page", List.of()),
                (p, c) -> new ItemPickerMenu(plugin, title, suggestions, search, page - 1, picked, onPick, back).open(p));
        button(49, icon(Material.SPYGLASS, "<aqua>Search", List.of("<gray>Type part of a name, e.g. <white>spruce", "<gray>or <white>raw iron</white>.",
                "", "<yellow>Click, then type it")), (p, c) -> Prompt.ask(p, "Search for an item (part of its name):",
                text -> new ItemPickerMenu(plugin, title, suggestions, text, 0, picked, onPick, back).open(p)));
        if (search != null) button(50, icon(Material.CHEST, "<aqua>Back to suggestions", List.of()),
                (p, c) -> new ItemPickerMenu(plugin, title, suggestions, null, 0, picked, onPick, back).open(p));
        if (from + PER_PAGE < list.size()) button(51, icon(Material.ARROW, "<yellow>Next page", List.of()),
                (p, c) -> new ItemPickerMenu(plugin, title, suggestions, search, page + 1, picked, onPick, back).open(p));
        button(53, icon(Material.LIME_DYE, "<green>Done", List.of()), (p, c) -> back.open(p));
    }
}
