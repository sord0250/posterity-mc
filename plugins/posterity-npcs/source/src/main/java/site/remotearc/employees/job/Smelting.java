package site.remotearc.employees.job;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.SmokingRecipe;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** Which items each kind of furnace accepts, read once from the server's recipes. */
public final class Smelting {

    enum Kind {
        FURNACE(Material.FURNACE), SMOKER(Material.SMOKER), BLAST(Material.BLAST_FURNACE);

        final Material block;

        Kind(Material block) {
            this.block = block;
        }

        static Kind of(Material block) {
            for (Kind k : values()) if (k.block == block) return k;
            return null;
        }
    }

    private static Map<Kind, Set<Material>> inputs;

    private Smelting() {}

    private static Map<Kind, Set<Material>> inputs() {
        if (inputs != null) return inputs;
        Map<Kind, Set<Material>> map = new EnumMap<>(Kind.class);
        for (Kind k : Kind.values()) map.put(k, EnumSet.noneOf(Material.class));
        for (Iterator<Recipe> it = Bukkit.recipeIterator(); it.hasNext(); ) {
            Recipe r;
            try {
                r = it.next();
            } catch (RuntimeException ex) {
                continue;
            }
            Kind k = r instanceof BlastingRecipe ? Kind.BLAST : r instanceof SmokingRecipe ? Kind.SMOKER
                    : r instanceof FurnaceRecipe ? Kind.FURNACE : null;
            if (k == null || !(r instanceof CookingRecipe<?> cr)) continue;
            RecipeChoice choice = cr.getInputChoice();
            if (choice instanceof RecipeChoice.MaterialChoice mc) map.get(k).addAll(mc.getChoices());
            else if (choice instanceof RecipeChoice.ExactChoice ec) for (ItemStack s : ec.getChoices()) map.get(k).add(s.getType());
        }
        inputs = map;
        return map;
    }

    static boolean accepts(Kind k, Material m) {
        return inputs().get(k).contains(m);
    }

    public static boolean smeltable(Material m) {
        for (Kind k : Kind.values()) if (accepts(k, m)) return true;
        return false;
    }
}
