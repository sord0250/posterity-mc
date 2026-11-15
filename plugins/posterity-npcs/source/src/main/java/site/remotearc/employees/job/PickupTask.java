package site.remotearc.employees.job;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import site.remotearc.employees.util.WorkArea;

import java.util.List;

/**
 * Collects items lying on the ground in the work area: saplings and apples from
 * decaying leaves, crops knocked loose, overflow the employee had to drop.
 * Only the job's own produce, and never items a player threw.
 */
final class PickupTask extends WalkThenAct {

    private static final String SCAN_KEY = "pickup-scan";

    private PickupTask(Item item) {
        super(item.getLocation().toVector(), 1.3, 1.5, 1.5);
    }

    static Task find(Worker w) {
        WorkArea a = w.e.area;
        if (a == null || !a.world().equals(w.world().getName())) return null;
        if (!w.cooldownReady(SCAN_KEY, 60)) return null;
        BoundingBox box = new BoundingBox(a.minX() - 1, a.minY() - 2, a.minZ() - 1, a.maxX() + 2, a.maxY() + 3, a.maxZ() + 2);
        Item best = null;
        double bestDist = Double.MAX_VALUE;
        Location me = w.e.location;
        for (Item it : w.world().getNearbyEntitiesByType(Item.class, box.getCenter().toLocation(w.world()),
                box.getWidthX() / 2, box.getHeight() / 2, box.getWidthZ() / 2)) {
            if (!collectible(it) || !w.job.collects(it.getItemStack().getType())) continue;
            if (!box.contains(it.getLocation().toVector())) continue;
            if (w.isUnreachable(it.getLocation().toVector()) || !w.mayWork(it.getLocation())) continue;
            double d = it.getLocation().distanceSquared(me);
            if (d < bestDist) {
                bestDist = d;
                best = it;
            }
        }
        return best == null ? null : new PickupTask(best);
    }

    /** The nearest collectible item within r blocks of a spot (for a bodyguard away from its area). */
    static Task findNear(Worker w, Location center, double r) {
        if (!w.cooldownReady(SCAN_KEY, 40)) return null;
        Item best = null;
        double bestDist = Double.MAX_VALUE;
        for (Item it : w.world().getNearbyEntitiesByType(Item.class, center, r, 4, r)) {
            if (!collectible(it) || !w.job.collects(it.getItemStack().getType())) continue;
            if (w.isUnreachable(it.getLocation().toVector())) continue;
            double d = it.getLocation().distanceSquared(w.e.location);
            if (d < bestDist) {
                bestDist = d;
                best = it;
            }
        }
        return best == null ? null : new PickupTask(best);
    }

    private static boolean collectible(Item it) {
        return it.isValid() && it.isOnGround() && it.getThrower() == null && it.getOwner() == null;
    }

    @Override
    protected Result act(Worker w) {
        boolean any = false;
        for (Item it : w.world().getNearbyEntitiesByType(Item.class, w.e.location, 2.0, 1.5, 2.0)) {
            if (!collectible(it) || !w.job.collects(it.getItemStack().getType())) continue;
            ItemStack stack = it.getItemStack();
            List<ItemStack> left = site.remotearc.employees.util.Items.add(w.e.backpack, List.of(stack));
            int taken = stack.getAmount() - (left.isEmpty() ? 0 : left.getFirst().getAmount());
            if (taken <= 0) continue;
            any = true;
            if (left.isEmpty()) it.remove();
            else it.setItemStack(left.getFirst());
        }
        if (any) {
            w.playSound(w.e.location, Sound.ENTITY_ITEM_PICKUP, 0.3f, 1.4f + w.random().nextFloat() * 0.4f);
            w.plugin().manager().markDirty();
        }
        return Result.DONE;
    }

    @Override
    public String status(Worker w) {
        return "Picking up dropped items";
    }
}
