package site.remotearc.employees.job;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;
import site.remotearc.employees.util.BlockPos;
import site.remotearc.employees.util.Items;
import site.remotearc.employees.util.Pathfinder;

/** Walks to within reach of a spot and places one block there from the backpack (plugging water, a stair step). */
final class PlaceTask implements Task {

    private final BlockPos pos;
    private final Material block;
    private final Vector target;
    private final Pathfinder.Goal goal;
    private final String label;
    private boolean arrived;
    private int timer;

    PlaceTask(World world, BlockPos pos, Material block, String label) {
        this.pos = pos;
        this.block = block;
        this.target = new Vector(pos.centerX(), pos.centerY(), pos.centerZ());
        this.goal = Pathfinder.reachBlock(world, pos, Pathfinder.REACH - 0.3, false);
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
                case ARRIVED -> arrived = true;
            }
        }
        w.lookAt(pos);
        if (++timer < w.clickTicks()) return Result.RUNNING;
        Block b = pos.block(w.world());
        if (!replaceable(b)) return Result.DONE;
        if (Items.take(w.e.backpack, block, 1) == 0) return Result.FAILED;
        w.swing();
        w.plugin().blockLog().removing(w.e, b);   // the water or lava it plugs
        b.setType(block, true);
        w.plugin().blockLog().placed(w.e, b);
        w.placeEffect(b);
        w.e.stats.blocksPlaced++;
        return Result.DONE;
    }

    /** Air, liquid, or something a placed block simply replaces (grass, snow). */
    static boolean replaceable(Block b) {
        return b.getType().isAir() || b.isLiquid() || b.isReplaceable();
    }

    @Override
    public String status(Worker w) {
        return label;
    }
}
