package site.remotearc.employees.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.Employee;
import site.remotearc.employees.job.JobType;
import site.remotearc.employees.util.Items;

import java.util.List;

/** Toggle common entries of a miner's, smelter's or quarry miner's filter; anything else can be typed. */
final class FilterMenu extends Menu {

    /** A toggle: the icon, what to add or remove, and the material whose presence means "on". */
    private record Choice(Material icon, String token, Material marker) {
        Choice(Material m) {
            this(m, m.name().toLowerCase(), m);
        }
    }

    private static final List<Choice> MINER = List.of(new Choice(Material.COAL_ORE), new Choice(Material.IRON_ORE),
            new Choice(Material.COPPER_ORE), new Choice(Material.GOLD_ORE), new Choice(Material.REDSTONE_ORE),
            new Choice(Material.LAPIS_ORE), new Choice(Material.DIAMOND_ORE), new Choice(Material.EMERALD_ORE),
            new Choice(Material.NETHER_QUARTZ_ORE), new Choice(Material.NETHER_GOLD_ORE), new Choice(Material.ANCIENT_DEBRIS),
            new Choice(Material.CLAY), new Choice(Material.GRAVEL), new Choice(Material.SAND), new Choice(Material.OBSIDIAN),
            new Choice(Material.AMETHYST_CLUSTER));
    private static final List<Choice> SMELTER = List.of(new Choice(Material.RAW_IRON), new Choice(Material.RAW_GOLD),
            new Choice(Material.RAW_COPPER), new Choice(Material.BEEF), new Choice(Material.PORKCHOP), new Choice(Material.CHICKEN),
            new Choice(Material.MUTTON), new Choice(Material.RABBIT), new Choice(Material.COD), new Choice(Material.SALMON),
            new Choice(Material.POTATO), new Choice(Material.KELP), new Choice(Material.SAND), new Choice(Material.RED_SAND),
            new Choice(Material.COBBLESTONE), new Choice(Material.COBBLED_DEEPSLATE), new Choice(Material.STONE),
            new Choice(Material.CLAY_BALL), new Choice(Material.NETHERRACK), new Choice(Material.CACTUS),
            new Choice(Material.OAK_LOG, "#logs", Material.OAK_LOG), new Choice(Material.WET_SPONGE));
    private static final List<Choice> QUARRY = List.of(new Choice(Material.COBBLESTONE), new Choice(Material.COBBLED_DEEPSLATE),
            new Choice(Material.DIRT), new Choice(Material.GRAVEL), new Choice(Material.SAND), new Choice(Material.ANDESITE),
            new Choice(Material.DIORITE), new Choice(Material.GRANITE), new Choice(Material.TUFF), new Choice(Material.CALCITE),
            new Choice(Material.NETHERRACK), new Choice(Material.FLINT), new Choice(Material.BASALT), new Choice(Material.BLACKSTONE));

    private final Employee e;
    private final Menu back;

    FilterMenu(ArcEmployees plugin, Employee e, Menu back) {
        super(plugin, 5, Component.text(e.name + ": " + Describe.filterLabel(e).toLowerCase()));
        this.e = e;
        this.back = back;
    }

    @Override
    protected void draw() {
        for (int s = 0; s < 45; s++) button(s, filler(), null);
        List<Choice> choices = e.job == JobType.MINER ? MINER : e.job == JobType.SMELTER ? SMELTER : QUARRY;
        String verb = e.job == JobType.MINER ? "look for" : e.job == JobType.SMELTER ? "smelt" : "throw away";
        int slot = 0;
        for (Choice ch : choices) {
            boolean on = e.filter.contains(ch.marker());
            String name = ch.token().startsWith("#") ? "all logs" : Items.name(ch.icon());
            ItemStack it = icon(ch.icon(), (on ? "<green>" : "<gray>") + name, List.of(
                    on ? "<green>On: " + e.name + " will " + verb + " this" : "<gray>Off",
                    "", "<yellow>Click to turn " + (on ? "off" : "on")));
            button(slot++, on ? glow(it) : it, (p, c) -> {
                run(p, "filter " + ref(p, e) + " " + (on ? "remove " : "add ") + ch.token());
                redraw();
            });
        }
        button(36, back(), (p, c) -> back.open(p));
        button(38, icon(Material.WRITABLE_BOOK, "<aqua>Add something else", List.of(
                "<gray>Any block or item name, a #tag", "<gray>like #logs, or 'ores'.", "", "<yellow>Click, then type it")), (p, c) -> {
            String who = ref(p, e);
            Prompt.ask(p, "What should " + e.name + " " + verb + "? (e.g. iron_ore, #logs, ores)", text -> {
                run(p, "filter " + who + " add " + text);
                open(p);
            });
        });
        button(40, icon(Material.PAPER, "<aqua>Whole list", List.of("<white>" + Describe.esc(Describe.filterText(e, 12)))), null);
        button(42, icon(Material.LAVA_BUCKET, "<aqua>Clear", List.of("", "<yellow>Click to empty the list")), (p, c) -> {
            run(p, "filter " + ref(p, e) + " clear");
            redraw();
        });
        if (e.job != JobType.QUARRY) {
            button(44, icon(Material.CLOCK, "<aqua>Reset to default", List.of("", "<yellow>Click to reset")), (p, c) -> {
                run(p, "filter " + ref(p, e) + " reset");
                redraw();
            });
        }
    }
}
