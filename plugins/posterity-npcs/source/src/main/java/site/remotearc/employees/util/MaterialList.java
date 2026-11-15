package site.remotearc.employees.util;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/** Lists of materials written by people: names, "#tags" and a few group words. */
public final class MaterialList {

    /** Every ore block, stone and deepslate variants, plus the nether ores and ancient debris. */
    public static final Set<Material> ORES = EnumSet.of(
            Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE, Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE,
            Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE, Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE,
            Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE, Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE,
            Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE, Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE,
            Material.NETHER_GOLD_ORE, Material.NETHER_QUARTZ_ORE, Material.ANCIENT_DEBRIS);

    private MaterialList() {}

    /** Parses a config list; unknown entries are logged and skipped. */
    public static Set<Material> parse(Collection<String> entries, Logger log, String where) {
        Set<Material> out = EnumSet.noneOf(Material.class);
        for (String entry : entries) {
            Set<Material> one = parseOne(entry);
            if (one == null) log.warning(where + ": '" + entry + "' is not a material, tag or group; skipped");
            else out.addAll(one);
        }
        return out;
    }

    /**
     * One entry: a material ("iron_ore"), an item or block tag ("#logs",
     * "#minecraft:wool") or "ores". Returns null if it means nothing.
     */
    public static Set<Material> parseOne(String entry) {
        String s = entry.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        if (s.equals("ores") || s.equals("all_ores")) return EnumSet.copyOf(ORES);
        if (s.startsWith("#")) {
            NamespacedKey key = NamespacedKey.fromString(s.substring(1));
            if (key == null) return null;
            Tag<Material> tag = Bukkit.getTag(Tag.REGISTRY_ITEMS, key, Material.class);
            if (tag == null) tag = Bukkit.getTag(Tag.REGISTRY_BLOCKS, key, Material.class);
            if (tag == null || tag.getValues().isEmpty()) return null;
            return EnumSet.copyOf(tag.getValues());
        }
        Material m = Material.matchMaterial(s);
        if (m == null || m.isAir() || m.isLegacy()) return null;
        return EnumSet.of(m);
    }
}
