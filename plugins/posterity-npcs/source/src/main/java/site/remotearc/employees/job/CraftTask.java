package site.remotearc.employees.job;

import org.bukkit.Sound;
import site.remotearc.employees.util.Items;

import java.util.List;

/** Crafts a plan step by step in the backpack, a moment each, like a player at a crafting table. */
final class CraftTask implements Task {

    private final List<Crafting.Step> steps;
    private final String why;
    private int index, timer;

    CraftTask(List<Crafting.Step> steps, String why) {
        this.steps = steps;
        this.why = why;
    }

    @Override
    public Result tick(Worker w) {
        if (index >= steps.size()) return Result.DONE;
        Crafting.Step st = steps.get(index);
        // Smelting takes a while (a few seconds a stack, with a little furnace's smoke); crafting a moment.
        int takes = st.smelted() ? 20 + 10 * Math.min(12, (st.amount() + 7) / 8) : 10;
        if (st.smelted() && timer % 20 == 0) {
            w.world().spawnParticle(org.bukkit.Particle.SMOKE, w.e.location.clone().add(0, 1.0, 0), 3, 0.2, 0.2, 0.2, 0.01);
            w.playSound(w.e.location, Sound.BLOCK_FURNACE_FIRE_CRACKLE, 0.5f, 1f);
        }
        if (++timer < takes) return Result.RUNNING;
        timer = 0;
        if (!Crafting.craft(w, steps.get(index++))) return Result.FAILED;
        w.swing();
        w.playSound(w.e.location, st.smelted() ? Sound.BLOCK_BLASTFURNACE_FIRE_CRACKLE : Sound.BLOCK_CRAFTER_CRAFT, 0.5f, 1f);
        return index >= steps.size() ? Result.DONE : Result.RUNNING;
    }

    @Override
    public String status(Worker w) {
        Crafting.Step st = steps.get(Math.min(index, steps.size() - 1));
        return (st.smelted() ? "Smelting " : "Crafting ") + Items.name(st.result()) + " (" + st.amount() + ") " + why;
    }
}
