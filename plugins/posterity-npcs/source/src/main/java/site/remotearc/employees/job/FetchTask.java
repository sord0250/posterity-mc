package site.remotearc.employees.job;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Container;
import org.bukkit.block.Lidded;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import site.remotearc.employees.storage.StorageTarget;
import site.remotearc.employees.storage.Supply;
import site.remotearc.employees.util.Items;
import site.remotearc.employees.util.Pathfinder;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Fetches materials from the employee's supply: VirtualChests chests are
 * reached remotely (a moment's pause, like opening /chest), then, if
 * something is still missing, the nearest placed supply chest that has it
 * is walked to.
 */
final class FetchTask implements Task {

    private final Map<Material, Integer> want;
    private final String what;
    private int timer;
    private int got;
    private StorageTarget chest;
    private Vector chestAt;
    private Pathfinder.Goal goal;
    private int openTimer = -1;

    /** What was asked for, before taking shrank {@link #want}. */
    private final Map<Material, Integer> asked;

    private FetchTask(Map<Material, Integer> want, String what) {
        this.want = want;
        this.asked = new EnumMap<>(want);
        this.what = what;
    }

    /**
     * A fetch for up to these amounts, trimmed to what fits in the backpack
     * (keeping two slots free). Null if nothing would fit.
     */
    static FetchTask create(Worker w, Map<Material, Integer> wanted, String what) {
        Map<Material, Integer> want = new EnumMap<>(Material.class);
        int slots = Items.freeSlots(w.e.backpack) - 2;
        for (Map.Entry<Material, Integer> e : wanted.entrySet()) {
            if (slots <= 0) break;
            int amount = Math.min(e.getValue(), Items.room(w.e.backpack, e.getKey()));
            int stacks = (amount + e.getKey().getMaxStackSize() - 1) / e.getKey().getMaxStackSize();
            if (stacks > slots) amount = slots * e.getKey().getMaxStackSize();
            if (amount <= 0) continue;
            want.put(e.getKey(), amount);
            slots -= (amount + e.getKey().getMaxStackSize() - 1) / e.getKey().getMaxStackSize();
        }
        return want.isEmpty() ? null : new FetchTask(want, what);
    }

    @Override
    public Result tick(Worker w) {
        if (timer++ == 0) {
            List<ItemStack> items = Supply.takeRemote(w.plugin(), w.e, want);
            w.plugin().ledger().taken(w.e, items, true);
            got += DeliverVirtualTask.count(items);
            w.stash(items);
        }
        if (chest == null) {
            if (timer < 10) return Result.RUNNING;
            if (!want.isEmpty()) {
                for (StorageTarget t : Supply.sources(w.plugin(), w.e)) {
                    if (t.kind() != StorageTarget.Kind.CHEST || !t.world().equals(w.world().getName())) continue;
                    if (w.isUnreachable(t.pos()) || !Supply.chestHas(t, want)) continue;
                    chest = t;
                    chestAt = new Vector(t.pos().centerX(), t.pos().centerY(), t.pos().centerZ());
                    goal = Pathfinder.near(chestAt.getX(), chestAt.getY(), chestAt.getZ(), 2.3, 2, 2.5);
                    break;
                }
            }
            if (chest == null) return finish(w);
        }
        if (openTimer < 0) {
            switch (w.walk(goal, chestAt)) {
                case MOVING -> {
                    return Result.RUNNING;
                }
                case FAILED -> {
                    w.markUnreachable(chestAt);
                    return finish(w);
                }
                case ARRIVED -> {
                    openTimer = 0;
                    w.lookAt(chest.pos());
                    Container c = chest.container();
                    if (c instanceof Lidded lid) lid.open();
                    else if (c != null) w.playSound(c.getLocation(), Sound.BLOCK_BARREL_OPEN, 0.6f, 1f);
                }
            }
        }
        openTimer++;
        if (openTimer == 6) {
            w.swing();
            Container c = chest.container();
            if (c != null) {
                w.plugin().blockLog().container(w.e, c.getLocation());
                List<ItemStack> items = Supply.takeFrom(c, want);
                w.plugin().ledger().taken(w.e, items, true);
                got += DeliverVirtualTask.count(items);
                w.stash(items);
            }
        }
        if (openTimer >= 16) {
            close(chest);
            return finish(w);
        }
        return Result.RUNNING;
    }

    /** Done; what it couldn't get at all isn't counted on for a while. */
    private Result finish(Worker w) {
        List<Material> none = new java.util.ArrayList<>();
        for (Map.Entry<Material, Integer> e : want.entrySet()) if (e.getValue() >= asked.getOrDefault(e.getKey(), 0)) none.add(e.getKey());
        if (!none.isEmpty()) w.fetchMissed(none);
        return got > 0 ? Result.DONE : Result.FAILED;
    }

    private static void close(StorageTarget t) {
        if (t != null && t.container() instanceof Lidded lid && lid.isOpen()) lid.close();
    }

    @Override
    public void end(Worker w) {
        close(chest);
    }

    @Override
    public String status(Worker w) {
        return "Fetching " + what + (chest != null ? " from the " + chest.describe() : "");
    }
}
