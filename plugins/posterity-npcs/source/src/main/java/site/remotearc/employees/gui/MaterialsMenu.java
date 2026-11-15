package site.remotearc.employees.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.Employee;
import site.remotearc.employees.build.BuildMaterials;
import site.remotearc.employees.build.BuildOrder;
import site.remotearc.employees.util.Items;
import site.remotearc.employees.util.Msg;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Everything a build still needs, with what the builder can get and what
 * the rest would cost. Click an item to build it from something else;
 * buttons along the bottom pick a block in the world, buy what's missing,
 * and leave the ground out or in.
 */
public final class MaterialsMenu extends Menu {

    private static final int PER_PAGE = 45;

    private final Employee e;
    private final int page;
    private final Menu back;

    MaterialsMenu(ArcEmployees plugin, Employee e, int page, Menu back) {
        super(plugin, 6, Component.text("Materials: " + (e.build == null ? "" : e.build.blueprint)));
        this.e = e;
        this.page = page;
        this.back = back;
    }

    public static void open(ArcEmployees plugin, Player p, Employee e) {
        new MaterialsMenu(plugin, e, 0, new BuildMenu(plugin, e, null)).open(p);
    }

    /** The builder's build menu: blueprint, lining it up, starting, materials, taking it down. */
    public static void openBuild(ArcEmployees plugin, Player p, Employee e) {
        new BuildMenu(plugin, e, null).open(p);
    }

    /** Picking what to build for a block this server doesn't have. */
    public static void openReplace(ArcEmployees plugin, Player p, Employee e, String unknown) {
        new SwapMenu(plugin, e, Material.STRUCTURE_VOID, unknown, null, null, 0, new MaterialsMenu(plugin, e, 0, new BuildMenu(plugin, e, null))).open(p);
    }

    public static void openSwap(ArcEmployees plugin, Player p, Employee e, Material from) {
        new SwapMenu(plugin, e, from, null, null, 0, new MaterialsMenu(plugin, e, 0, new BuildMenu(plugin, e, null))).open(p);
    }

    private java.util.UUID viewerId;

    @Override
    public void openFor(Player p) {
        viewerId = p.getUniqueId();
    }

    @Override
    protected boolean live() {
        return false;   // counts what's in the chests: only on open
    }

    @Override
    protected void draw() {
        for (int s = 45; s < 54; s++) button(s, filler(), null);
        if (back != null) button(45, back(), (p, c) -> back.open(p));
        BuildOrder o = e.build;
        if (o == null || o.plan == null) {
            button(22, icon(Material.PAPER, "<yellow>" + (o == null ? "Nothing to build" : "Still reading the blueprint"), List.of()), null);
            return;
        }
        List<BuildMaterials.Line> lines = BuildMaterials.lines(plugin, e, o);
        BuildMaterials.Quote q = viewerId == null ? null : BuildMaterials.quote(plugin, e, o, viewerId, lines);
        // Blocks this server doesn't have come first: they need a decision.
        List<Map.Entry<String, Integer>> unknowns = page == 0 ? new ArrayList<>(o.plan.unknownCounts().entrySet()) : List.of();
        int slot = 0;
        for (Map.Entry<String, Integer> un : unknowns) {
            if (slot >= 18) break;
            Material r = o.replacements.get(un.getKey());
            List<String> lore = new ArrayList<>(List.of(
                    "<yellow>Minecraft " + org.bukkit.Bukkit.getMinecraftVersion() + " doesn't have this block",
                    o.plan.newer ? "<gray>(the blueprint is from a newer version)." : "<gray>(a newer version or a mod).",
                    "<gray>The blueprint has <white>" + un.getValue() + "</white> of it.",
                    r == null ? "<red>Left out until you pick a replacement" : "<green>Built as " + Items.name(r),
                    "", "<yellow>Click to pick what to build instead"));
            String name = un.getKey();
            ItemStack it = icon(r != null ? r : Material.STRUCTURE_VOID, (r == null ? "<red>" : "<green>") + Describe.esc(name.replace('_', ' ')), lore);
            button(slot++, r == null ? glow(it) : it, (p, c) -> new SwapMenu(plugin, e, Material.STRUCTURE_VOID, name, null, null, 0, this).open(p));
        }
        int uSlots = Math.min(18, o.plan.unknownCounts().size());
        int from = page * PER_PAGE - (page > 0 ? uSlots : 0), count = PER_PAGE - slot;
        for (int i = 0; i < count && from + i < lines.size(); i++) {
            BuildMaterials.Line l = lines.get(from + i);
            Material key = original(o, l.item());
            List<String> lore = new ArrayList<>();
            String color = l.short_() == 0 ? "<green>" : l.have() + l.craft() > 0 ? "<yellow>" : "<red>";
            lore.add("<gray>Still to place: <white>" + l.need());
            lore.add("<gray>In storage (and its backpack): <white>" + l.have());
            if (l.craft() > 0) lore.add("<gray>It will craft: <white>" + l.craft() + " <dark_gray>(from spare materials)");
            if (l.short_() == 0) lore.add("<green>Nothing missing");
            else {
                lore.add("<red>Missing: " + l.short_());
                Material m = l.item();
                if (q != null && q.crafted().contains(m)) lore.add("<gray>→ Buy what it's crafted from (Buy button)");
                else if (q != null && q.locked().contains(m)) lore.add("<gray>→ Not discovered yet: can't be bought");
                else if (q != null && q.notSold().contains(m)) lore.add("<gray>→ Can't be bought: swap it or leave it out");
                else {
                    String price = BuildMaterials.price(plugin, m);
                    if (price != null) lore.add("<gray>→ Buy it: <gold>" + price + " <gray>(Buy button)");
                }
            }
            if (key != l.item()) lore.add("<aqua>Instead of " + Items.name(key));
            lore.add("");
            lore.add("<yellow>Click to swap it or leave it out");
            ItemStack it = icon(l.item().isItem() ? l.item() : Material.PAPER, color + Items.name(l.item()), lore);
            it.setAmount(Math.max(1, Math.min(64, l.short_() > 0 ? l.short_() : 1)));
            button(slot + i, it, (p, c) -> new SwapMenu(plugin, e, key, null, null, 0, this).open(p));
        }
        if (lines.isEmpty() && unknowns.isEmpty()) button(22, icon(Material.LIME_DYE, "<green>Nothing left to place", List.of()), null);

        if (page > 0) button(46, icon(Material.ARROW, "<yellow>Previous page", List.of()), (p, c) -> new MaterialsMenu(plugin, e, page - 1, back).open(p));
        button(47, icon(Material.SPYGLASS, "<aqua>Click a block in the build", List.of(
                "<gray>Shows the build's ghost blocks;", "<gray>look at one and click it to swap", "<gray>what it's made of.",
                "", "<yellow>Click to start")), (p, c) -> {
            p.closeInventory();
            run(p, "build " + ref(p, e) + " pick");
        });
        List<String> swapLore = new ArrayList<>();
        if (o.swaps.isEmpty() && o.skipped.isEmpty()) swapLore.add("<gray>None: it's built as drawn.");
        if (!o.skipped.isEmpty()) swapLore.add("<white>" + o.skipped.size() + " single block(s) <gray>left out");
        for (Map.Entry<Material, Material> en : o.swaps.entrySet()) {
            swapLore.add("<white>" + Items.name(en.getKey()) + " <gray>→ <white>" + (en.getValue() == Material.AIR ? "left out" : Items.name(en.getValue())));
        }
        swapLore.add("");
        swapLore.add("<yellow>Click to see them, and undo or");
        swapLore.add("<yellow>change any one of them");
        if (!o.swaps.isEmpty()) swapLore.add("<yellow>Shift-click to undo all the swaps");
        if (!o.skipped.isEmpty()) swapLore.add("<yellow>Right-click to put the single blocks back");
        button(48, icon(Material.NAME_TAG, "<aqua>Swaps and left out (" + (o.swaps.size() + o.skipped.size()) + ")", swapLore), (p, c) -> {
            if (c.isShiftClick() && !o.swaps.isEmpty()) run(p, "build " + ref(p, e) + " swap reset");
            else if (c.isRightClick() && !o.skipped.isEmpty()) run(p, "build " + ref(p, e) + " skip reset");
            else {
                new SwapsMenu(plugin, e, 0, this).open(p);
                return;
            }
            redraw();
        });

        double bought = 0;
        for (double[] st : o.stock.values()) bought += st[1];
        String undo = bought > 0 ? "<yellow>Right-click to return the unused bought materials (" + Msg.money(bought) + ")" : "";
        Menu.Action undoAction = (p, c) -> new ConfirmMenu(plugin, "Return them?", "<green>Return them and get " + Msg.money(o.stock.values().stream().mapToDouble(st -> st[1]).sum()) + " back", pl -> {
            run(pl, "build " + ref(pl, e) + " buy undo");
            open(pl);
        }, this::open).open(p);
        if (q != null && !q.buy().isEmpty()) {
            List<String> lore = new ArrayList<>();
            lore.add("<gray>" + q.count() + " items from the merchants,");
            lore.add("<gray>handed straight to " + e.name + " for");
            lore.add("<gray>this build. What it doesn't use is");
            lore.add("<gray>refunded when the build ends.");
            if (!q.crafted().isEmpty()) lore.add("<gray>Includes what it crafts " + q.crafted().size() + " kind(s) from.");
            int crafts = lines.stream().mapToInt(BuildMaterials.Line::craft).sum();
            if (crafts > 0) lore.add("<gray>Not counted: " + crafts + " it crafts from spares.");
            if (!q.notSold().isEmpty()) lore.add("<yellow>" + q.notSold().size() + " kind(s) can't be had: swap them");
            if (!q.locked().isEmpty()) lore.add("<yellow>" + q.locked().size() + " kind(s) not discovered yet");
            lore.add("");
            lore.add("<yellow>Click to buy");
            lore.add(undo);
            button(49, glow(icon(Material.GOLD_INGOT, "<gold>Buy what's missing: " + Msg.money(q.total()), lore)), (p, c) -> {
                if (c.isRightClick() && o.stock.size() > 0) {
                    undoAction.run(p, c);
                    return;
                }
                new ConfirmMenu(plugin, "Pay " + Msg.money(q.total()) + "?", "<green>Buy " + q.count() + " items for " + Msg.money(q.total()), pl -> {
                    pl.closeInventory();
                    run(pl, "build " + ref(pl, e) + " buy confirm " + String.format(Locale.ROOT, "%.2f", q.total()));
                }, this::open).open(p);
            });
        } else if (q != null) {
            List<String> lore = new ArrayList<>();
            if (!q.notSold().isEmpty()) lore.add("<yellow>Can't be had: " + q.notSold().size() + " kind(s); swap them");
            if (!q.locked().isEmpty()) lore.add("<yellow>Not discovered yet: " + q.locked().size() + " kind(s)");
            if (lore.isEmpty()) lore.add("<green>It can get or craft everything.");
            lore.add(undo);
            button(49, icon(Material.IRON_NUGGET, "<gray>Nothing to buy", lore), bought > 0 ? undoAction : null);
        }

        int g = o.groundLayers();
        if (g > 0) {
            button(50, icon(o.keepGround ? Material.GRASS_BLOCK : Material.DIRT_PATH, "<aqua>Ground under it: " + (o.keepGround ? "<green>built" : "<red>left out"), List.of(
                    "<gray>The blueprint stands on " + g + " layer" + (g == 1 ? "" : "s") + " of", "<gray>dirt, grass or stone.",
                    o.keepGround ? "<gray>Now: built like the rest." : "<gray>Now: left out, so your own", o.keepGround ? "" : "<gray>ground is used instead.",
                    "", "<yellow>Click to " + (o.keepGround ? "leave it out" : "build it too"))), (p, c) -> {
                run(p, "build " + ref(p, e) + " ground " + (o.keepGround ? "skip" : "keep"));
                redraw();
            });
        }
        if (from + count < lines.size()) button(51, icon(Material.ARROW, "<yellow>Next page", List.of()), (p, c) -> new MaterialsMenu(plugin, e, page + 1, back).open(p));
        boolean showing = viewerId != null && org.bukkit.Bukkit.getPlayer(viewerId) != null && site.remotearc.employees.build.BuildPreview.showing(org.bukkit.Bukkit.getPlayer(viewerId));
        button(52, icon(showing ? Material.ENDER_PEARL : Material.ENDER_EYE, showing ? "<aqua>Hide the preview" : "<aqua>Show the preview", List.of(
                "<gray>Ghost blocks only you see. They stay", "<gray>while you're near the build.", "", "<yellow>Click")), (p, c) -> {
            p.closeInventory();
            run(p, "build " + ref(p, e) + " preview" + (showing ? " off" : ""));
        });
        button(53, icon(Material.WRITABLE_BOOK, "<aqua>List it in chat", List.of("", "<yellow>Click")), (p, c) -> {
            p.closeInventory();
            run(p, "build " + ref(p, e) + " materials chat");
        });
    }

    /** The blueprint's own item behind a line: what was swapped for this, or the item itself. */
    static Material original(BuildOrder o, Material item) {
        for (Map.Entry<Material, Material> en : o.swaps.entrySet()) if (en.getValue() == item) return en.getKey();
        return item;
    }
}
