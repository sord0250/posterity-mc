package site.remotearc.employees.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.Employee;
import site.remotearc.employees.build.BuildMaterials;
import site.remotearc.employees.build.BuildOrder;
import site.remotearc.employees.util.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pick what a build uses instead of one of its blocks: similar blocks
 * first (the same kind, then the same shape), the ones the builder can get
 * enough of at the top; or search, use what's in your hand, click a block
 * in the world, or leave it out.
 */
final class SwapMenu extends Menu {

    private static final int PER_PAGE = 36;

    private final Employee e;
    private final Material from;
    private final String search;
    /** The block clicked in the build, if it was opened that way: it can be left out on its own. */
    private final site.remotearc.employees.util.BlockPos at;
    private final int page;
    private final Menu back;

    /** A block this server doesn't have (by name), when that's what's being replaced; null otherwise. */
    private final String unknown;

    SwapMenu(ArcEmployees plugin, Employee e, Material from, site.remotearc.employees.util.BlockPos at, String search, int page, Menu back) {
        this(plugin, e, from, null, at, search, page, back);
    }

    SwapMenu(ArcEmployees plugin, Employee e, Material from, String unknown, site.remotearc.employees.util.BlockPos at, String search, int page, Menu back) {
        super(plugin, 6, Component.text(search != null ? "Blocks like: " + search
                : "Build " + (unknown != null ? unknown.replace('_', ' ') : Items.name(from)) + " from..."));
        this.e = e;
        this.from = from;
        this.unknown = unknown;
        this.at = at;
        this.search = search;
        this.page = page;
        this.back = back;
    }

    @Override
    protected boolean live() {
        return false;
    }

    @Override
    protected void draw() {
        for (int s = 0; s < 54; s++) button(s, filler(), null);
        BuildOrder o = e.build;
        if (o == null || o.plan == null) {
            button(22, icon(Material.PAPER, "<yellow>Nothing to build", List.of()), null);
            return;
        }
        boolean unk = unknown != null;
        Material now = unk ? o.replacements.get(unknown) : o.swaps.get(from);
        Map<Material, Integer> left = o.remaining(org.bukkit.Bukkit.getWorld(o.world));
        int need = unk ? o.plan.unknownCounts().getOrDefault(unknown, 0)
                : now == Material.AIR ? 0 : left.getOrDefault(now == null ? from : now, 0);
        Map<Material, Integer> have = BuildMaterials.have(plugin, e, o);
        Map<Material, Integer> spare = BuildMaterials.spare(have, left, e);

        List<String> info = new ArrayList<>();
        if (unk) {
            info.add("<yellow>Minecraft " + org.bukkit.Bukkit.getMinecraftVersion() + " doesn't have this block");
            info.add(o.plan.newer ? "<gray>(the blueprint is from a newer version)." : "<gray>(a newer version or a mod).");
            info.add("<gray>The blueprint has <white>" + need + "</white> of it.");
            info.add("<gray>Now: <white>" + (now == null ? "left out" : "built as " + Items.name(now)));
        } else {
            info.add("<gray>In the blueprint: <white>" + Items.name(from));
            info.add("<gray>Built from: <white>" + (now == null ? "itself" : now == Material.AIR ? "nothing (left out)" : Items.name(now)));
            if (now != Material.AIR) {
                info.add("<gray>Still to place: <white>" + need);
                info.add("<gray>In storage: <white>" + have.getOrDefault(now == null ? from : now, 0));
            }
        }
        info.add("");
        info.add("<gray>Pick a block below. Facing, half and");
        info.add("<gray>shape carry over where they can.");
        button(4, icon(unk ? Material.STRUCTURE_VOID : from.isItem() ? from : Material.PAPER,
                "<gold>" + (unk ? unknown.replace('_', ' ') : Items.name(from)), info), null);

        List<Material> list = search == null ? (unk ? found(lastWordOf(unknown), have) : suggestions(from, have)) : found(search, have);
        int start = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && start + i < list.size(); i++) {
            Material m = list.get(start + i);
            int h = have.getOrDefault(m, 0);
            int craft = 0;
            if (e.craft && need > h) {
                site.remotearc.employees.job.Crafting.Plan plan = site.remotearc.employees.job.Crafting.plan(m, need - h, 1, spare, e.craftNever, BuildMaterials.DEPTH);
                if (plan != null) craft = Math.min(need - h, plan.makes(m));
            }
            List<String> lore = new ArrayList<>();
            lore.add("<gray>In storage: <white>" + h);
            if (craft > 0) lore.add("<gray>Can craft: <white>" + craft + " <dark_gray>(from spare materials)");
            if (need > 0) {
                if (h + craft >= need) lore.add("<green>Enough for the " + need + " it needs");
                else lore.add("<yellow>Short by " + (need - h - craft) + " of " + need);
            }
            String price = BuildMaterials.price(plugin, m);
            lore.add(price == null ? "<dark_gray>Merchants don't sell it" : "<gray>Price: <gold>" + price);
            lore.add("");
            lore.add("<yellow>Click to build with this instead");
            ItemStack it = icon(m, (m == now ? "<green>" : "<white>") + Items.name(m), lore);
            button(9 + i, m == now ? glow(it) : it, (p, cl) -> choose(p, Items.key(m)));
        }
        if (list.isEmpty()) {
            button(22, icon(Material.PAPER, "<yellow>Nothing here", List.of(search == null ? "<gray>Use Search to find any block." : "<gray>No block names contain that.")), null);
        }

        button(45, back(), (p, cl) -> (back != null ? back : new MaterialsMenu(plugin, e, 0, new BuildMenu(plugin, e, null))).open(p));
        if (page > 0) button(46, icon(Material.ARROW, "<yellow>Previous page", List.of()),
                (p, cl) -> new SwapMenu(plugin, e, from, unknown, at, search, page - 1, back).open(p));
        button(47, icon(Material.BRICKS, "<aqua>Use the block in your hand", List.of("<gray>Hold the block you want in your", "<gray>main hand, then click here.", "", "<yellow>Click")), (p, cl) -> {
            Material held = p.getInventory().getItemInMainHand().getType();
            if (held.isAir() || !held.isBlock()) {
                site.remotearc.employees.util.Msg.error(p, "Hold a block in your main hand first.");
                return;
            }
            choose(p, Items.key(held));
        });
        button(48, icon(Material.SPYGLASS, "<aqua>Click a block in the world", List.of("<gray>Closes this; then click any block", "<gray>(near or far) to build with that.", "", "<yellow>Click to start")), (p, cl) ->
                BuildPicker.pickWorldBlock(plugin, p, e, token(), unk ? unknown.replace('_', ' ') : Items.name(from)));
        button(49, icon(Material.OAK_SIGN, "<aqua>Search", List.of("<gray>Type part of a name, e.g. <white>spruce", "<gray>or <white>stone brick</white>.", "", "<yellow>Click, then type it")), (p, cl) ->
                Prompt.ask(p, "Search for a block (part of its name):", text -> new SwapMenu(plugin, e, from, unknown, at, text, 0, back).open(p)));
        if (search != null) button(50, icon(Material.CHEST, "<aqua>Back to suggestions", List.of()), (p, cl) -> new SwapMenu(plugin, e, from, unknown, at, null, 0, back).open(p));
        else if (unk) {
            if (now != null) button(50, icon(Material.BARRIER, "<red>Leave it out", List.of("<gray>Nothing is built where the", "<gray>blueprint has it (the default).", "", "<yellow>Click")), (p, cl) -> choose(p, "none"));
        } else button(50, icon(Material.BARRIER, "<red>Leave out all " + Items.name(from), List.of("<gray>Nothing is built anywhere the", "<gray>blueprint has " + Items.name(from) + ".", "", "<yellow>Click")), (p, cl) -> choose(p, "none"));
        if (at != null) {
            button(52, icon(Material.STRUCTURE_VOID, "<red>Leave out just this block", List.of("<gray>Only the one you clicked, at", "<gray>" + at.x() + " " + at.y() + " " + at.z() + ". Then click",
                    "<gray>more blocks to leave them out too", "<gray>(sneak and click to stop).", "", "<yellow>Click")), (p, cl) -> {
                run(p, "build " + ref(p, e) + " skip " + at.x() + " " + at.y() + " " + at.z());
                BuildPicker.pickBuildBlock(plugin, p, e);
            });
        }
        if (now != null && !unk) button(51, icon(Material.LIME_DYE, "<green>Undo: build it as drawn", List.of("<gray>Back to " + Items.name(from) + ".", "", "<yellow>Click")), (p, cl) -> choose(p, "back"));
        if (start + PER_PAGE < list.size()) button(53, icon(Material.ARROW, "<yellow>Next page", List.of()),
                (p, cl) -> new SwapMenu(plugin, e, from, unknown, at, search, page + 1, back).open(p));
    }

    private void choose(org.bukkit.entity.Player p, String to) {
        run(p, "build " + ref(p, e) + " swap " + token() + " " + to);
        (back != null ? back : new MaterialsMenu(plugin, e, 0, new BuildMenu(plugin, e, null))).open(p);
    }

    /** How the swap command names what's being swapped. */
    private String token() {
        return unknown != null ? unknown : Items.key(from);
    }

    private static String lastWordOf(String name) {
        return name.substring(name.lastIndexOf('_') + 1);
    }

    private static boolean usable(Material m) {
        return !m.isLegacy() && m.isBlock() && m.isItem() && !m.isAir() && !BuildOrder.unbuildable(m.createBlockData());
    }

    private static String lastWord(Material m) {
        String n = m.name();
        return n.substring(n.lastIndexOf('_') + 1);
    }

    /**
     * Blocks like this one: the same kind by name (planks for planks,
     * stairs for stairs), then the same shape the builder can get; the ones
     * it has enough of first.
     */
    static List<Material> suggestions(Material from, Map<Material, Integer> have) {
        if (!from.isBlock()) return found(lastWord(from), have);
        Class<?> shape = from.createBlockData().getClass();
        boolean cube = from.isOccluding();
        String word = lastWord(from);
        List<Material> out = new ArrayList<>();
        for (Material m : Material.values()) {
            if (m == from || !usable(m)) continue;
            boolean kind = lastWord(m).equals(word);
            boolean sameShape = m.createBlockData().getClass() == shape && (!cube || m.isOccluding());
            if (kind || (sameShape && have.getOrDefault(m, 0) > 0)) out.add(m);
        }
        out.sort(Comparator.<Material>comparingInt(m -> -have.getOrDefault(m, 0))
                .thenComparing(m -> !lastWord(m).equals(word))
                .thenComparing(Material::name));
        return out;
    }

    static List<Material> found(String search, Map<Material, Integer> have) {
        String q = search.toLowerCase(Locale.ROOT).trim().replace(' ', '_');
        List<Material> out = new ArrayList<>();
        for (Material m : Material.values()) if (usable(m) && m.name().toLowerCase(Locale.ROOT).contains(q)) out.add(m);
        out.sort(Comparator.<Material>comparingInt(m -> -have.getOrDefault(m, 0)).thenComparing(Material::name));
        return out;
    }
}
