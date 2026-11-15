package site.remotearc.employees.sort;

import org.bukkit.Material;
import org.bukkit.Tag;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The built-in sorting categories: the eleven main ones every item falls into
 * (on unless turned off; turned off, their items go to misc), and finer extra
 * ones (off unless turned on; on, they take their items from the main ones).
 */
public final class Categories {

    private Categories() {}

    /** A category: key, display name, icon, on by default, and (for extras) which items it takes. */
    public record Def(String key, String name, Material icon, boolean main, Predicate<Material> takes) {}

    public static final String MISC = "misc";

    public static final List<Def> MAIN = List.of(
            new Def("tools", "Tools and weapons", Material.IRON_PICKAXE, true, null),
            new Def("armor", "Armor", Material.IRON_CHESTPLATE, true, null),
            new Def("food", "Food", Material.BREAD, true, null),
            new Def("ores", "Ores and minerals", Material.IRON_INGOT, true, null),
            new Def("wood", "Wood", Material.OAK_LOG, true, null),
            new Def("stone", "Stone", Material.COBBLESTONE, true, null),
            new Def("nature", "Nature (dirt, plants...)", Material.GRASS_BLOCK, true, null),
            new Def("redstone", "Redstone", Material.REDSTONE, true, null),
            new Def("decoration", "Decoration", Material.LANTERN, true, null),
            new Def("drops", "Mob drops", Material.BONE, true, null),
            new Def(MISC, "Everything else", Material.CHEST, true, null));

    public static final List<Def> EXTRAS = List.of(
            new Def("wool", "Wool and carpets", Material.WHITE_WOOL, false,
                    m -> Tag.WOOL.isTagged(m) || Tag.WOOL_CARPETS.isTagged(m)),
            new Def("glass", "Glass", Material.GLASS, false,
                    m -> m.name().contains("GLASS") && m != Material.GLASS_BOTTLE && m != Material.SPYGLASS),
            new Def("concrete", "Concrete and terracotta", Material.WHITE_CONCRETE, false,
                    m -> m.name().contains("CONCRETE") || m.name().contains("TERRACOTTA")),
            new Def("dyes", "Dyes", Material.RED_DYE, false, m -> m.name().endsWith("_DYE")),
            new Def("crops", "Crops and seeds", Material.WHEAT, false,
                    m -> m.name().endsWith("_SEEDS") || Set.of(Material.WHEAT, Material.SUGAR_CANE, Material.NETHER_WART, Material.COCOA_BEANS,
                            Material.PUMPKIN, Material.MELON, Material.BAMBOO, Material.CACTUS, Material.KELP).contains(m)),
            new Def("logs", "Logs only", Material.SPRUCE_LOG, false,
                    m -> Tag.LOGS.isTagged(m) || m.name().endsWith("_STEM") || m.name().endsWith("_HYPHAE")),
            new Def("cobble", "Cobblestone and deepslate", Material.COBBLED_DEEPSLATE, false,
                    m -> m == Material.COBBLESTONE || m == Material.COBBLED_DEEPSLATE || m == Material.STONE || m == Material.DEEPSLATE
                            || m == Material.ANDESITE || m == Material.DIORITE || m == Material.GRANITE || m == Material.TUFF),
            new Def("brewing", "Brewing", Material.BREWING_STAND, false,
                    m -> m.name().contains("POTION") || Set.of(Material.BREWING_STAND, Material.CAULDRON, Material.BLAZE_POWDER, Material.NETHER_WART,
                            Material.GLASS_BOTTLE, Material.FERMENTED_SPIDER_EYE, Material.GLISTERING_MELON_SLICE, Material.MAGMA_CREAM,
                            Material.GHAST_TEAR, Material.RABBIT_FOOT, Material.DRAGON_BREATH, Material.PHANTOM_MEMBRANE).contains(m)),
            new Def("enchanting", "Enchanting and books", Material.ENCHANTED_BOOK, false,
                    m -> m == Material.ENCHANTED_BOOK || m == Material.BOOK || m == Material.WRITABLE_BOOK || m == Material.WRITTEN_BOOK
                            || m == Material.EXPERIENCE_BOTTLE || m == Material.LAPIS_LAZULI || m == Material.ENCHANTING_TABLE
                            || m == Material.BOOKSHELF || m == Material.ANVIL || m == Material.GRINDSTONE),
            new Def("discs", "Music discs", Material.MUSIC_DISC_CAT, false, m -> m.name().startsWith("MUSIC_DISC") || m.name().startsWith("DISC_FRAGMENT")),
            new Def("valuables", "Valuables", Material.DIAMOND, false,
                    m -> m.name().startsWith("DIAMOND") && !m.name().endsWith("_ORE") || m.name().startsWith("NETHERITE") || m.name().startsWith("EMERALD")
                            || m == Material.ANCIENT_DEBRIS || m == Material.TOTEM_OF_UNDYING || m == Material.NETHER_STAR
                            || m == Material.ENCHANTED_GOLDEN_APPLE || m == Material.ELYTRA || m == Material.HEART_OF_THE_SEA));

    public static Def def(String key) {
        for (Def d : MAIN) if (d.key.equals(key)) return d;
        for (Def d : EXTRAS) if (d.key.equals(key)) return d;
        return null;
    }

    private static final Set<String> WOODS = Set.of("OAK", "SPRUCE", "BIRCH", "JUNGLE", "ACACIA", "DARK_OAK", "MANGROVE", "CHERRY",
            "PALE_OAK", "BAMBOO", "CRIMSON", "WARPED");

    private static boolean wooden(String n) {
        for (String w : WOODS) if (n.startsWith(w + "_")) return true;
        return n.startsWith("STRIPPED_");
    }

    private static boolean has(String n, String... parts) {
        for (String p : parts) if (n.contains(p)) return true;
        return false;
    }

    /** Which main category an item belongs to. */
    public static String main(Material m) {
        String n = m.name().toUpperCase(Locale.ROOT);
        if (n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS")
                || n.endsWith("HORSE_ARMOR") || n.equals("WOLF_ARMOR") || n.equals("ELYTRA") || n.equals("SHIELD")) return "armor";
        if (n.endsWith("_PICKAXE") || n.endsWith("_AXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE") || n.endsWith("_SWORD")
                || n.endsWith("_SPEAR") || n.endsWith("ARROW") || n.endsWith("BUCKET") && m != Material.MILK_BUCKET
                || Set.of("BOW", "CROSSBOW", "TRIDENT", "MACE", "SHEARS", "FLINT_AND_STEEL", "FISHING_ROD", "BRUSH", "SPYGLASS",
                        "COMPASS", "RECOVERY_COMPASS", "CLOCK", "LEAD", "NAME_TAG", "SADDLE", "CARROT_ON_A_STICK",
                        "WARPED_FUNGUS_ON_A_STICK", "FIRE_CHARGE", "WIND_CHARGE", "ENDER_PEARL", "TOTEM_OF_UNDYING", "BUNDLE",
                        "MAP", "FILLED_MAP").contains(n)) return "tools";
        if (has(n, "REDSTONE", "REPEATER", "COMPARATOR", "PISTON", "OBSERVER", "HOPPER", "DROPPER", "DISPENSER", "LEVER",
                "_BUTTON", "PRESSURE_PLATE", "TRIPWIRE", "DAYLIGHT", "RAIL", "MINECART", "SCULK_SENSOR", "CRAFTER", "COPPER_BULB")
                && !n.endsWith("_ORE")
                || Set.of("TNT", "NOTE_BLOCK", "TARGET", "LIGHTNING_ROD", "SLIME_BLOCK", "HONEY_BLOCK", "TRAPPED_CHEST").contains(n)) return "redstone";
        if (m.isEdible() && m != Material.ROTTEN_FLESH && m != Material.SPIDER_EYE && m != Material.POISONOUS_POTATO
                || Set.of("CAKE", "MILK_BUCKET", "SUGAR", "EGG", "BROWN_EGG", "BLUE_EGG", "PUMPKIN_PIE").contains(n)) return "food";
        if (n.endsWith("_ORE") || n.startsWith("RAW_") || n.endsWith("_INGOT") || n.endsWith("_NUGGET") || n.endsWith("_SCRAP")
                || Set.of("DIAMOND", "EMERALD", "LAPIS_LAZULI", "COAL", "CHARCOAL", "QUARTZ", "AMETHYST_SHARD", "ANCIENT_DEBRIS",
                        "IRON_BLOCK", "GOLD_BLOCK", "DIAMOND_BLOCK", "EMERALD_BLOCK", "LAPIS_BLOCK", "COAL_BLOCK", "NETHERITE_BLOCK",
                        "COPPER_BLOCK", "RAW_IRON_BLOCK", "RAW_GOLD_BLOCK", "RAW_COPPER_BLOCK", "RESIN_CLUMP", "ECHO_SHARD").contains(n)) return "ores";
        if (Set.of("ROTTEN_FLESH", "BONE", "STRING", "SPIDER_EYE", "GUNPOWDER", "SLIME_BALL", "LEATHER", "FEATHER", "INK_SAC",
                "GLOW_INK_SAC", "BLAZE_ROD", "BREEZE_ROD", "GHAST_TEAR", "PHANTOM_MEMBRANE", "RABBIT_HIDE", "RABBIT_FOOT",
                "PRISMARINE_SHARD", "PRISMARINE_CRYSTALS", "SHULKER_SHELL", "NAUTILUS_SHELL", "MAGMA_CREAM", "ENDER_EYE",
                "TURTLE_SCUTE", "ARMADILLO_SCUTE", "HONEYCOMB", "NETHER_STAR", "DRAGON_BREATH", "BLAZE_POWDER", "POISONOUS_POTATO",
                "BONE_MEAL", "SKELETON_SKULL", "WITHER_SKELETON_SKULL", "ZOMBIE_HEAD", "CREEPER_HEAD", "PIGLIN_HEAD").contains(n)) return "drops";
        if (Tag.LEAVES.isTagged(m) || Tag.SAPLINGS.isTagged(m) || Tag.FLOWERS.isTagged(m)
                || has(n, "DIRT", "GRASS", "SAND", "GRAVEL", "CLAY", "MOSS", "MUD", "PODZOL", "MYCELIUM", "VINE", "MUSHROOM",
                "FUNGUS", "ROOTS", "SEEDS", "KELP", "CORAL", "ICE", "SNOW", "CACTUS", "SUGAR_CANE", "LILY_PAD", "FERN", "BUSH",
                "PROPAGULE", "DRIPLEAF", "AZALEA", "SPORE", "SEA_PICKLE", "WART", "PUMPKIN", "MELON", "BAMBOO", "COCOA", "LICHEN",
                "SCULK", "NYLIUM", "WHEAT", "PETALS", "EGG", "HAY_BLOCK", "FROGSPAWN", "SHROOMLIGHT", "WEEPING", "TWISTING")
                && !n.contains("SANDSTONE") && !n.contains("STAINED") && !n.contains("_PANE") && !n.contains("BRICK")
                && !n.endsWith("SPAWN_EGG")) return "nature";
        if ((wooden(n) || n.endsWith("_PLANKS") || n.endsWith("_LOG") || n.endsWith("_WOOD") || n.endsWith("_STEM") || n.endsWith("_HYPHAE")
                || n.equals("STICK") || n.endsWith("_BOAT") || n.endsWith("_RAFT") || n.endsWith("_SHELF"))
                && !n.endsWith("_SIGN") && !n.endsWith("_HANGING_SIGN")) return "wood";
        if (has(n, "STONE", "COBBLE", "DEEPSLATE", "GRANITE", "DIORITE", "ANDESITE", "TUFF", "BRICK", "BLACKSTONE", "BASALT",
                "CALCITE", "PRISMARINE", "PURPUR", "NETHERRACK", "OBSIDIAN", "QUARTZ_", "DRIPSTONE", "END_STONE", "MAGMA_BLOCK", "BEDROCK")
                && !n.endsWith("_BUTTON") && !n.endsWith("PRESSURE_PLATE")) return "stone";
        if (n.contains("POTION")) return MISC;
        if (has(n, "WOOL", "CARPET", "GLASS", "CONCRETE", "TERRACOTTA", "BANNER", "_BED", "CANDLE", "POT", "PAINTING", "FRAME",
                "LANTERN", "TORCH", "SIGN", "HEAD", "SKULL", "DYE", "CHAIN", "BELL", "CAMPFIRE", "LIGHT", "FROGLIGHT", "COPPER",
                "PLATE", "AMETHYST", "SHULKER_BOX", "BARS", "FLOWER", "ARMOR_STAND", "BOOKSHELF")) return "decoration";
        return MISC;
    }
}
