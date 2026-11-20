package site.remotearc.employees.job;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import site.remotearc.employees.util.Pathfinder;

/**
 * What an employee does for a living. A job only decides the next piece of
 * work; walking, delivering, picking up drops and idling are shared by every
 * job and live in {@link Worker}.
 */
public interface Job {

    /** How the employee gets around. */
    enum Movement {
        /** Walks on the ground. */
        WALK,
        /** Walks, and tunnels through the blocks its {@link #digger} allows. */
        DIG,
        /** Moves through any open space, like a player on scaffolding. */
        FLY
    }

    /** The next task, or null when there is nothing to do right now. */
    Task next(Worker w);

    /** Why the employee is idle, e.g. "Waiting for trees to grow". */
    String idleStatus(Worker w);

    /** Items kept back from deliveries (up to the job's reserve) because the job consumes them. */
    boolean isReserved(Material m);

    /** How many of a material stay in the backpack when delivering. */
    default int reserve(Worker w, Material m) {
        return isReserved(m) ? w.jobSettings().reserve() : 0;
    }

    /**
     * Items this job picks up off the ground in its work area. Only the job's
     * own produce: a player who dies in the area drops items with no thrower,
     * and those must stay where they fell.
     */
    boolean collects(Material m);

    default Movement movement() {
        return Movement.WALK;
    }

    /** What a DIG job may tunnel through. */
    default Pathfinder.Digger digger(Worker w) {
        return null;
    }

    /** A block from the backpack to pillar up or bridge with, or null if it has none. */
    default Material buildingBlock(Worker w) {
        return null;
    }

    /** Whether it walks back home when idle (a following bodyguard doesn't). */
    default boolean wandersHome(Worker w) {
        return true;
    }

    /**
     * Called before a block is dug on the way somewhere. True: the job will
     * put it back later (a builder's access tunnel), so it gives no drops.
     */
    default boolean digsTemporarily(Worker w, org.bukkit.block.Block b) {
        return false;
    }

    /** Whether to keep an item that came out of a block dug on the way somewhere. */
    default boolean keepsDug(Worker w, Material block, Material drop) {
        return true;
    }

    /** Jobs that use up their backpack (builders, smelters) keep working when it is full. */
    default boolean canWorkWhenFull() {
        return false;
    }

    /** The tool this job breaks a block with. */
    default ItemStack toolFor(Worker w, Block b) {
        return w.tool();
    }

    /** A line about the job's progress for the menu (a build's layer, a quarry's depth), or null. */
    default String detail(Worker w) {
        return null;
    }

    /** Called every tick while the employee is on shift, for background work like surveying. */
    default void tick(Worker w) {}

    /** Forget cached scans, e.g. after the work area or the filter changed. */
    default void reset(Worker w) {}

    static Job create(JobType type) {
        return switch (type) {
            case LUMBERJACK -> new LumberjackJob();
            case FARMER -> new FarmerJob();
            case MINER -> new MinerJob();
            case QUARRY -> new QuarryJob();
            case BUILDER -> new BuilderJob();
            case SMELTER -> new SmelterJob();
            case RANCHER -> new RancherJob();
            case BODYGUARD -> new BodyguardJob();
            case TRADER -> new TraderJob();
            case COURIER -> new CourierJob();
        };
    }
}
