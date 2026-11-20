package site.remotearc.employees.job;

import org.bukkit.Material;

import java.util.List;
import java.util.Locale;

/** Every job an employee can be hired for. Add new jobs here and in {@link Job#create}. */
public enum JobType {
    LUMBERJACK("Lumberjack", Material.IRON_AXE, Material.OAK_SAPLING, List.of(),
            "Fells grown trees in the work area, clears their leaves and replants the saplings."),
    FARMER("Farmer", Material.IRON_HOE, Material.WHEAT, List.of(),
            "Harvests ripe crops in the work area and replants them."),
    MINER("Miner", Material.IRON_PICKAXE, Material.RAW_IRON, List.of("caver", "explorer"),
            "Explores caves and hillsides in the work area for ores (or the blocks you pick), tunnelling to reach them."),
    QUARRY("Quarry Miner", Material.IRON_PICKAXE, Material.COBBLESTONE, List.of("quarryminer", "quarry_miner", "digger"),
            "Digs out the whole work area layer by layer, leaving a staircase along the wall."),
    BUILDER("Builder", Material.IRON_PICKAXE, Material.BRICKS, List.of("constructor"),
            "Builds a schematic (.schem, .schematic, .litematic or .nbt) block by block from your materials."),
    SMELTER("Smelter", Material.COAL, Material.FURNACE, List.of("cook", "smelter_cook"),
            "Keeps the furnaces, smokers and blast furnaces in the work area loaded and fuelled, and collects the results."),
    RANCHER("Rancher", Material.IRON_SWORD, Material.LEAD, List.of("breeder", "herder"),
            "Breeds the animals in the work area up to a herd size, culls extras, shears sheep and collects eggs."),
    BODYGUARD("Guard", Material.IRON_SWORD, Material.SHIELD, List.of("guard", "knight", "defender", "security", "bodyguard", "escort", "companion", "follower"),
            "Patrols its post and fights hostile mobs that come near, picks up their drops, and (with torches) lights up dark spots;"
                    + " told to follow you instead, it goes where you go and guards you the same way."),
    TRADER("Villager Trader", Material.EMERALD, Material.EMERALD, List.of("trader", "villager", "villagertrader", "villager_trader"),
            "Works your trading hall: makes the trades you pick (e.g. 32 sticks for an emerald) whenever the villager has stock."),
    COURIER("Courier", Material.BUNDLE, Material.CHEST_MINECART, List.of("hauler", "carrier", "porter", "delivery"),
            "Carries items between chests on the routes you give it: logs to the smelter, ores to your sorting room, anything to your base.");

    private final String display;
    private final Material defaultTool;
    private final Material icon;
    private final List<String> aliases;
    private final String description;

    JobType(String display, Material defaultTool, Material icon, List<String> aliases, String description) {
        this.display = display;
        this.defaultTool = defaultTool;
        this.icon = icon;
        this.aliases = aliases;
        this.description = description;
    }

    public String display() { return display; }
    public Material defaultTool() { return defaultTool; }
    public Material icon() { return icon; }
    public String description() { return description; }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Jobs that take materials from shared storage (/employees supply). */
    public boolean usesSupply() {
        return this == BUILDER || this == SMELTER || this == RANCHER || this == LUMBERJACK || this == FARMER || this == TRADER;
    }

    /** Jobs with a per-employee block or item list (/employees filter). */
    public boolean usesFilter() {
        return this == MINER || this == QUARRY || this == SMELTER;
    }

    public static JobType parse(String s) {
        for (JobType t : values()) {
            if (t.key().equalsIgnoreCase(s) || t.display.equalsIgnoreCase(s)) return t;
            for (String a : t.aliases) if (a.equalsIgnoreCase(s)) return t;
        }
        return null;
    }
}
