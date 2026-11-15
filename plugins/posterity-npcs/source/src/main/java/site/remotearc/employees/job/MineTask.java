package site.remotearc.employees.job;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import site.remotearc.employees.util.BlockPos;
import site.remotearc.employees.util.Pathfinder;

import java.util.Collection;
import java.util.function.Predicate;

/**
 * Walks (tunnelling if the job digs) to where a block can be reached, breaks
 * it at player speed, then keeps breaking every other wanted block it can
 * reach and see from there (the rest of an ore vein, the rest of a quarry
 * layer) before moving on.
 */
final class MineTask implements Task {

    /** What happens to a mined block's drops. */
    interface Handler {
        void mined(Worker w, Material type, Collection<ItemStack> drops);
    }

    private final BlockPos first;
    private final Vector target;
    private final Pathfinder.Goal goal;
    private final Predicate<Block> wanted;
    private final Handler handler;
    private final String label;
    private boolean arrived;
    private BlockPos current;
    private int timer, need;
    private int left = 64;   // most blocks broken from one spot before re-planning

    MineTask(World world, BlockPos first, Predicate<Block> wanted, Handler handler, String label) {
        this.first = first;
        this.target = new Vector(first.centerX(), first.centerY(), first.centerZ());
        this.goal = Pathfinder.reachBlock(world, first, Pathfinder.REACH - 0.3, true);
        this.wanted = wanted;
        this.handler = handler;
        this.label = label;
    }

    @Override
    public Vector workTarget() {
        return target;
    }

    @Override
    public Result tick(Worker w) {
        if (!arrived) {
            switch (w.walk(goal, target)) {
                case MOVING -> {
                    return Result.RUNNING;
                }
                case FAILED -> {
                    w.markUnreachable(target);
                    return Result.FAILED;
                }
                case ARRIVED -> {
                    arrived = true;
                    current = wanted.test(first.block(w.world())) && w.mayWork(first) ? first : null;
                }
            }
        }
        World world = w.world();
        if (current == null) {
            if (left-- <= 0) return Result.DONE;
            current = next(w);
            need = 0;
            if (current == null) return Result.DONE;
        }
        Block b = current.block(world);
        if (!wanted.test(b) || !w.mayWork(current)) {
            w.crack(current, 0f);
            current = null;
            return Result.RUNNING;
        }
        if (need == 0) {
            need = w.breakTicks(b);
            timer = 0;
        }
        timer++;
        w.lookAt(current);
        if (timer % 4 == 1) w.swing();
        if (need > 2) w.crack(current, (float) timer / need);
        if (timer >= need) {
            w.crack(current, 0f);
            Material type = b.getType();
            handler.mined(w, type, w.breakBlock(b, true, true));
            current = null;
        }
        return Result.RUNNING;
    }

    /** The nearest wanted block in reach and in sight of where the employee stands. */
    private BlockPos next(Worker w) {
        World world = w.world();
        Location l = w.e.location;
        double ex = l.getX(), ey = l.getY() + Pathfinder.EYE_HEIGHT, ez = l.getZ();
        int bx = l.getBlockX(), fy = (int) Math.floor(l.getY() + 0.01), bz = l.getBlockZ();
        double reach = Pathfinder.REACH - 0.3;
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        int r = (int) Math.ceil(reach);
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r + 1; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    int x = bx + dx, y = (int) Math.floor(ey) + dy, z = bz + dz;
                    if (x == bx && z == bz && y >= fy - 1 && y <= fy + 1) continue;   // own body and the floor under it
                    double cx = x + 0.5 - ex, cy = y + 0.5 - ey, cz = z + 0.5 - ez;
                    double d = cx * cx + cy * cy + cz * cz;
                    if (d > reach * reach || d >= bestD) continue;
                    Block b = world.getBlockAt(x, y, z);
                    if (!wanted.test(b) || !w.mayWork(x, y, z)) continue;
                    BlockPos p = new BlockPos(x, y, z);
                    if (w.isUnreachable(p) || !Pathfinder.canSee(world, ex, ey, ez, p, bx, fy, bz)) continue;
                    best = p;
                    bestD = d;
                }
            }
        }
        return best;
    }

    @Override
    public void end(Worker w) {
        if (current != null) w.crack(current, 0f);
    }

    @Override
    public String status(Worker w) {
        return label;
    }
}
