package site.remotearc.employees.job;

import org.bukkit.inventory.ItemStack;
import site.remotearc.employees.Ledger;

import java.util.List;

/** Sends the backpack's surplus straight to the owner's VirtualChests chest. */
final class DeliverVirtualTask implements Task {

    @Override
    public Result tick(Worker w) {
        List<ItemStack> items = w.takeDeliverable();
        int total = count(items);
        var fromBackpack = Ledger.count(items);
        items = w.plugin().market().sellFor(w.e, items);   // anything it's set to sell goes to the market first
        if (w.e.storage.kind() != site.remotearc.employees.storage.StorageTarget.Kind.VIRTUAL) {
            w.plugin().ledger().delivered(w.e, fromBackpack, java.util.Map.of(), items);
            w.delivered(total - count(items), items);   // selling only: the rest stays in the backpack
            return Result.DONE;
        }
        var toStorage = Ledger.count(items);
        List<ItemStack> leftover = w.plugin().vchests().deposit(w.e.owner, w.e.storage.chestNumber(), items);
        // Full: spill into its overflow chests, claiming another empty one if allowed.
        for (int i = 0; !leftover.isEmpty() && i < w.e.overflow.size(); i++) {
            leftover = w.plugin().vchests().deposit(w.e.owner, w.e.overflow.get(i), leftover);
        }
        while (!leftover.isEmpty()) {
            int n = w.plugin().manager().claimOverflow(w.e);
            if (n < 0) break;
            leftover = w.plugin().vchests().deposit(w.e.owner, n, leftover);
        }
        w.plugin().ledger().delivered(w.e, fromBackpack, toStorage, leftover);
        w.delivered(total - count(leftover), leftover);
        return Result.DONE;
    }

    @Override
    public String status(Worker w) {
        return "Delivering to " + w.e.storage.describe();
    }

    static int count(List<ItemStack> items) {
        int n = 0;
        for (ItemStack s : items) n += s.getAmount();
        return n;
    }
}
