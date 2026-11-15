package site.remotearc.employees.job;

import org.bukkit.Sound;
import org.bukkit.block.Container;
import org.bukkit.block.Lidded;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import site.remotearc.employees.Ledger;
import site.remotearc.employees.util.BlockPos;
import site.remotearc.employees.util.Items;

import java.util.List;

/** Walks to the linked container, opens it, unloads the backpack's surplus and closes it. */
final class DeliverChestTask extends WalkThenAct {

    @Override
    public org.bukkit.util.Vector workTarget() {
        return null;   // going to storage or strolling, not working
    }


    private final BlockPos pos;
    private int timer;

    private DeliverChestTask(BlockPos pos) {
        super(new Vector(pos.centerX(), pos.centerY(), pos.centerZ()), 2.3, 2, 2.5);
        this.pos = pos;
    }

    static Task create(Worker w) {
        BlockPos p = w.e.storage.pos();
        if (p == null || !w.e.storage.world().equals(w.world().getName())) return null;
        if (w.isUnreachable(p)) return null;
        return new DeliverChestTask(p);
    }

    @Override
    protected void arrive(Worker w) {
        w.lookAt(pos);
        Container c = w.e.storage.container();
        if (c instanceof Lidded lid) lid.open();
        else if (c != null) w.playSound(c.getLocation(), Sound.BLOCK_BARREL_OPEN, 0.6f, 1f);
    }

    @Override
    protected Result act(Worker w) {
        timer++;
        if (timer == 6) {
            w.swing();
            Container c = w.e.storage.container();
            if (c == null) {
                w.deliveryBlocked("is missing");
                return Result.FAILED;
            }
            List<ItemStack> items = w.takeDeliverable();
            int total = DeliverVirtualTask.count(items);
            var fromBackpack = Ledger.count(items);
            items = w.plugin().market().sellFor(w.e, items);
            var toStorage = Ledger.count(items);
            w.plugin().blockLog().container(w.e, c.getLocation());
            List<ItemStack> leftover = Items.add(c.getInventory(), items);
            w.plugin().ledger().delivered(w.e, fromBackpack, toStorage, leftover);
            w.delivered(total - DeliverVirtualTask.count(leftover), leftover);
        }
        if (timer >= 16) {
            Container c = w.e.storage.container();
            if (c instanceof Lidded lid) lid.close();
            return Result.DONE;
        }
        return Result.RUNNING;
    }

    @Override
    public void end(Worker w) {
        Container c = w.e.storage.container();
        if (c instanceof Lidded lid && lid.isOpen()) lid.close();
    }

    @Override
    public String status(Worker w) {
        return "Delivering to the " + w.e.storage.describe();
    }
}
