package site.remotearc.employees.job;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import site.remotearc.employees.util.Items;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Works out how to craft an item from what's on hand, using the server's own
 * crafting recipes: sticks from planks, planks from logs, and so on, up to
 * three steps deep. Stonecutting counts (stone into bricks, stairs one for
 * one), and so does smelting (cobblestone into stone into smooth stone,
 * sand into glass, raw iron into ingots), which uses fuel: coal or charcoal
 * (8 items each), or logs and planks (1.5 each). Recipes that leave something behind (milk buckets) or
 * need a particular item (a named one) are skipped, and so is anything on the
 * employee's never-craft-with list.
 */
public final class Crafting {

    /** One crafting step: these go in, {@code amount} of {@code result} come out. */
    public record Step(Map<Material, Integer> uses, Material result, int amount, boolean smelted) {
        public Step(Map<Material, Integer> uses, Material result, int amount) {
            this(uses, result, amount, false);
        }
    }

    /** The steps in order, and what they use up of what was on hand. */
    public record Plan(List<Step> steps, Map<Material, Integer> raw) {
        /** How many of an item the plan ends up making. */
        public int makes(Material m) {
            int n = 0;
            for (Step st : steps) if (st.result() == m) n += st.amount();
            for (Step st : steps) n -= st.uses().getOrDefault(m, 0);
            return n;
        }
    }

    /** A recipe as slots, each slot a list of the materials that fit in it. */
    private record Simple(List<List<Material>> slots, int makes, boolean smelt) {
        Simple(List<List<Material>> slots, int makes) {
            this(slots, makes, false);
        }
    }

    private static final int MAX_DEPTH = 3;
    private static final Map<Material, List<Simple>> RECIPES = new EnumMap<>(Material.class);

    private Crafting() {}

    /**
     * Plans crafting at least {@code amount} of {@code target} from {@code stock}
     * (which is left untouched), or as much as possible down to {@code least}.
     * Null if not even that much can be made.
     */
    public static Plan plan(Material target, int amount, int least, Map<Material, Integer> stock, Set<Material> never) {
        return plan(target, amount, least, stock, never, MAX_DEPTH);
    }

    /** The same, crafting up to {@code depth} steps deep (a lectern from sugar cane is four). */
    public static Plan plan(Material target, int amount, int least, Map<Material, Integer> stock, Set<Material> never, int depth) {
        int lo = Math.max(1, Math.min(least, amount));
        for (int n = Math.max(lo, amount); ; n = Math.max(lo, n / 2)) {
            Plan p = plan(target, n, stock, new Ctx(never, depth, new EnumMap<>(Material.class)));
            if (p != null) return p;
            if (n <= lo) return null;
        }
    }

    /** What a plan may use and how deep it may go, and the items already found impossible from some depth on. */
    private record Ctx(Set<Material> never, int maxDepth, Map<Material, Integer> failed) {}

    private static Plan plan(Material target, int amount, Map<Material, Integer> stock, Ctx ctx) {
        Map<Material, Integer> s = new EnumMap<>(Material.class);
        stock.forEach((m, n) -> {
            if (!ctx.never().contains(m) && n > 0) s.put(m, n);
        });
        Map<Material, Integer> before = new EnumMap<>(s);
        List<Step> steps = new ArrayList<>();
        if (!make(target, amount, s, ctx, 0, steps, new HashSet<>())) return null;
        Map<Material, Integer> raw = new EnumMap<>(Material.class);
        before.forEach((m, n) -> {
            int used = n - s.getOrDefault(m, 0);
            if (used > 0) raw.put(m, used);
        });
        return new Plan(steps, raw);
    }

    /** Adds at least {@code need} more of {@code target} to {@code s} by crafting, recording the steps. */
    private static boolean make(Material target, int need, Map<Material, Integer> s, Ctx ctx, int depth,
                                List<Step> steps, Set<Material> making) {
        if (need <= 0) return true;
        Integer failedAt = ctx.failed().get(target);
        if (failedAt != null && depth >= failedAt) return false;   // couldn't with more steps to spare
        if (depth >= ctx.maxDepth() || !making.add(target)) return false;
        Set<Material> never = ctx.never();
        try {
            for (Simple r : recipes(target)) {
                int crafts = (need + r.makes() - 1) / r.makes();
                Map<List<Material>, Integer> grouped = new LinkedHashMap<>();
                for (List<Material> slot : r.slots()) grouped.merge(slot, 1, Integer::sum);
                Map<Material, Integer> trial = new EnumMap<>(s);
                List<Step> trialSteps = new ArrayList<>();
                Map<Material, Integer> uses = new EnumMap<>(Material.class);
                boolean ok = true;
                for (Map.Entry<List<Material>, Integer> slot : grouped.entrySet()) {
                    int want = crafts * slot.getValue();
                    Material pick = null;
                    for (Material m : slot.getKey()) {
                        if (!never.contains(m) && trial.getOrDefault(m, 0) >= want) {
                            pick = m;
                            break;
                        }
                    }
                    if (pick == null) {
                        for (Material m : slot.getKey()) {
                            if (never.contains(m)) continue;
                            Map<Material, Integer> t2 = new EnumMap<>(trial);
                            List<Step> s2 = new ArrayList<>();
                            if (make(m, want - t2.getOrDefault(m, 0), t2, ctx, depth + 1, s2, making)) {
                                trial = t2;
                                trialSteps.addAll(s2);
                                pick = m;
                                break;
                            }
                        }
                    }
                    if (pick == null) {
                        ok = false;
                        break;
                    }
                    trial.merge(pick, -want, Integer::sum);
                    uses.merge(pick, want, Integer::sum);
                }
                if (!ok) continue;
                if (r.smelt() && !fuel(crafts, trial, uses, trialSteps, ctx, depth, making)) continue;
                trial.merge(target, crafts * r.makes(), Integer::sum);
                trialSteps.add(new Step(uses, target, crafts * r.makes(), r.smelt()));
                s.clear();
                s.putAll(trial);
                steps.addAll(trialSteps);
                return true;
            }
            ctx.failed().merge(target, depth, Math::min);
            return false;
        } finally {
            making.remove(target);
        }
    }

    /** Fuel for smelting this many: coal or charcoal on hand, then logs or planks, then charcoal made from logs. */
    private static boolean fuel(int items, Map<Material, Integer> trial, Map<Material, Integer> uses, List<Step> steps, Ctx ctx, int depth,
                                Set<Material> making) {
        int coal = (items + 7) / 8;
        for (Material m : List.of(Material.COAL, Material.CHARCOAL)) {
            if (!ctx.never().contains(m) && trial.getOrDefault(m, 0) >= coal) {
                trial.merge(m, -coal, Integer::sum);
                uses.merge(m, coal, Integer::sum);
                return true;
            }
        }
        int wood = (int) Math.ceil(items / 1.5);
        for (Map.Entry<Material, Integer> en : new ArrayList<>(trial.entrySet())) {
            Material m = en.getKey();
            if (ctx.never().contains(m) || en.getValue() < wood) continue;
            if (!org.bukkit.Tag.LOGS_THAT_BURN.isTagged(m) && !org.bukkit.Tag.PLANKS.isTagged(m)) continue;
            trial.merge(m, -wood, Integer::sum);
            uses.merge(m, wood, Integer::sum);
            return true;
        }
        if (ctx.never().contains(Material.CHARCOAL) || making.contains(Material.CHARCOAL)) return false;
        Map<Material, Integer> t2 = new EnumMap<>(trial);
        List<Step> s2 = new ArrayList<>();
        if (!make(Material.CHARCOAL, coal - t2.getOrDefault(Material.CHARCOAL, 0), t2, ctx, depth + 1, s2, making)) return false;
        trial.clear();
        trial.putAll(t2);
        steps.addAll(s2);
        trial.merge(Material.CHARCOAL, -coal, Integer::sum);
        uses.merge(Material.CHARCOAL, coal, Integer::sum);
        return true;
    }

    /** The plain crafting-table recipes for an item (cached). */
    private static List<Simple> recipes(Material target) {
        synchronized (RECIPES) {
            List<Simple> cached = RECIPES.get(target);
            if (cached != null) return cached;
        }
        List<Simple> out = new ArrayList<>();
        List<Simple> cut = new ArrayList<>(), smelt = new ArrayList<>();
        for (Recipe r : Bukkit.getRecipesFor(new ItemStack(target))) {
            if (r.getResult().getType() != target || r.getResult().getAmount() < 1) continue;
            // A stonecutter (one block in, the same or more out) and a furnace: one item each, no table needed.
            if (r instanceof org.bukkit.inventory.StonecuttingRecipe sc || r instanceof org.bukkit.inventory.FurnaceRecipe) {
                RecipeChoice in = r instanceof org.bukkit.inventory.StonecuttingRecipe s1 ? s1.getInputChoice()
                        : ((org.bukkit.inventory.FurnaceRecipe) r).getInputChoice();
                if (!(in instanceof RecipeChoice.MaterialChoice mc)) continue;
                List<Material> mats = new ArrayList<>(mc.getChoices());
                mats.removeIf(m -> m == target);
                if (mats.isEmpty()) continue;
                if (r instanceof org.bukkit.inventory.FurnaceRecipe) smelt.add(new Simple(List.of(List.copyOf(mats)), r.getResult().getAmount(), true));
                else cut.add(new Simple(List.of(List.copyOf(mats)), r.getResult().getAmount()));
                continue;
            }
            List<RecipeChoice> choices = new ArrayList<>();
            if (r instanceof ShapedRecipe sr) {
                for (String row : sr.getShape()) {
                    for (char c : row.toCharArray()) {
                        RecipeChoice ch = sr.getChoiceMap().get(c);
                        if (c != ' ' && ch != null) choices.add(ch);
                    }
                }
            } else if (r instanceof ShapelessRecipe sl) {
                choices.addAll(sl.getChoiceList());
            } else {
                continue;
            }
            List<List<Material>> slots = new ArrayList<>();
            boolean ok = !choices.isEmpty();
            for (RecipeChoice ch : choices) {
                if (!(ch instanceof RecipeChoice.MaterialChoice mc)) {
                    ok = false;
                    break;
                }
                List<Material> mats = new ArrayList<>();
                for (Material m : mc.getChoices()) {
                    Material rest = m.getCraftingRemainingItem();
                    if (rest == null || rest.isAir()) mats.add(m);
                }
                if (mats.isEmpty()) {
                    ok = false;
                    break;
                }
                slots.add(List.copyOf(mats));
            }
            if (ok) out.add(new Simple(slots, r.getResult().getAmount()));
        }
        // The stonecutter first (it wastes less: a block a stair), the furnace last (it takes fuel).
        out.addAll(0, cut);
        out.addAll(smelt);
        // Stripped logs and wood: not a crafting recipe but an axe on the log, one for one. Builders have axes.
        if (target.name().startsWith("STRIPPED_")) {
            Material base = Material.matchMaterial(target.name().substring("STRIPPED_".length()));
            if (base != null && base.isItem()) out.add(new Simple(List.of(List.of(base)), 1));
        }
        synchronized (RECIPES) {
            RECIPES.put(target, out);
        }
        return out;
    }

    /** Does a plan's steps in the backpack; false (with nothing lost) if something's missing. */
    static boolean craft(Worker w, Step step) {
        for (Map.Entry<Material, Integer> u : step.uses().entrySet()) {
            if (Items.count(w.e.backpack, u.getKey()) < u.getValue()) return false;
        }
        step.uses().forEach((m, n) -> Items.take(w.e.backpack, m, n));
        List<ItemStack> made = new ArrayList<>();
        int left = step.amount(), max = step.result().getMaxStackSize();
        while (left > 0) {
            int n = Math.min(max, left);
            made.add(new ItemStack(step.result(), n));
            left -= n;
        }
        w.stash(made);
        return true;
    }
}
