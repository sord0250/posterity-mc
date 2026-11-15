package site.remotearc.employees.job;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.Employee;
import site.remotearc.employees.Settings;
import site.remotearc.employees.board.EventLog;
import site.remotearc.employees.storage.StorageTarget;
import site.remotearc.employees.util.BlockPos;
import site.remotearc.employees.util.Items;
import site.remotearc.employees.util.Msg;
import site.remotearc.employees.util.Pathfinder;
import site.remotearc.employees.util.WorkArea;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.logging.Level;

/**
 * The runtime brain of one employee: picks tasks, walks, and moves the
 * Mannequin. The manager ticks it every server tick while the employee's
 * entity is loaded; it never runs off the main thread.
 */
public final class Worker {

    public enum Move { MOVING, ARRIVED, FAILED }

    /** Coarse state shown under the employee's name. */
    public enum State { WORKING, IDLE, FULL, PAUSED, STRIKE, RESTING }

    private static final int UNREACHABLE_TICKS = 1200;
    private static final int MAX_WALK_TICKS = 20 * 45;
    private static final int PATH_NODE_BUDGET = 3000;

    private final ArcEmployees plugin;
    public final Employee e;
    public final Job job;
    private final Random random = new Random();
    private final Map<Long, Long> unreachable = new HashMap<>();
    private final Map<String, Long> throttles = new HashMap<>();

    private Task task;
    private int cooldown;
    private long now;
    private List<Pathfinder.Waypoint> path;
    private int pathIndex;
    private Pathfinder.Goal pathGoal;
    /** The current path only gets part of the way; how many times it has been re-planned for this goal. */
    private boolean partialPath;
    private Pathfinder.Goal replanFor;
    private int replans;
    private int walkTicks;
    /** Block being dug out of the way on the current path, with its progress. */
    private BlockPos digging;
    private int digTimer, digNeed;
    private int placeTimer;
    /** Tasks that failed in a row; a long streak means the employee is stuck somewhere. */
    private int failures;
    private long lastDelivery = -1_000_000;
    /** Why the last delivery left items behind ("is full", "is missing"), or null. */
    private String storageProblem;
    private State state;
    /** Where it faces now, and where it's turning to: heads turn over a few ticks, as a player's would. */
    private float yaw, pitch, wantYaw, wantPitch;
    private long lastMove = -1;
    private final double offX, offZ, pace;
    /** No personality distractions before this tick (see Distractions). */
    long calmUntil;
    /** Building its own home: the current break ends at this tick (0 = not on one), and the next starts at nextHomeBreak. */
    long homeSessionUntil, nextHomeBreak;
    /** Materials it fetched for its home (see HomeWork.reserves): kept in the backpack, not delivered. */
    final java.util.Set<Material> homeMaterials = java.util.EnumSet.noneOf(Material.class);

    /** How many of a material stay in the backpack when delivering: what its job keeps, and all it fetched to build its home. */
    int reserved(Material m) {
        return HomeWork.reserves(this, m) ? Integer.MAX_VALUE : job.reserve(this, m);
    }

    public Worker(ArcEmployees plugin, Employee e) {
        this.plugin = plugin;
        this.e = e;
        this.job = Job.create(e.job);
        this.yaw = e.location.getYaw();
        this.wantYaw = yaw;
        // Each walks a little off the block centres and at its own pace, so two
        // employees on the same path don't move as one.
        long bits = e.id.getLeastSignificantBits();
        this.offX = ((bits & 0xFF) / 255.0 - 0.5) * 0.3;
        this.offZ = (((bits >> 8) & 0xFF) / 255.0 - 0.5) * 0.3;
        this.pace = 0.92 + ((bits >> 16) & 0xFF) / 255.0 * 0.16;
        // Settles in to work first after being hired or loaded.
        this.calmUntil = 20 * 60 + random.nextInt(20 * 120);
        this.nextHomeBreak = 20 * 60 + random.nextInt(20 * 60);
    }

    /** After a distraction: stays focused for a while (two minutes if it gave up straight away, e.g. couldn't reach a penned sheep). */
    void calmDown(boolean soon) {
        int calm = settings().personalityCalmTicks;
        calmUntil = now + (soon ? 20 * 120 : (long) (calm * (0.7 + random.nextDouble() * 0.6)));
    }

    public ArcEmployees plugin() { return plugin; }
    public Settings settings() { return plugin.settings(); }
    public Settings.JobSettings jobSettings() { return settings().job(e.job); }
    public long now() { return now; }
    public Random random() { return random; }
    public World world() { return e.location.getWorld(); }
    public State state() { return state; }
    public String storageProblem() { return storageProblem; }

    public ItemStack tool() {
        return new ItemStack(jobSettings().tool());
    }

    /** The tool used on a block (a builder picks the right one from its toolbox). */
    public ItemStack toolFor(Block b) {
        return job.toolFor(this, b);
    }

    /**
     * How fast it works compared to a survival player: the job's work-speed,
     * slowed while it's homeless (unhappy).
     */
    public double workSpeed() {
        double s = jobSettings().workSpeed();
        return plugin.manager().homeless(e) ? s * settings().homelessWorkSpeed : s;
    }

    /** A survival player can right-click (place, plant, pick) about once every 4 ticks. */
    private static final double CLICK_TICKS = 4;
    /** Survival players wait 5 ticks after breaking a block before the next one starts to break. */
    private static final double BREAK_PAUSE_TICKS = 5;

    /**
     * Ticks to break a block as a survival player holding this employee's tool
     * would (vanilla mining formula, no enchantments or effects), scaled by the
     * job's work-speed.
     */
    public int breakTicks(Block b) {
        BlockData data = b.getBlockData();
        float hardness = b.getType().getHardness();
        double ticks;
        if (hardness <= 0) {
            ticks = CLICK_TICKS;   // instant-break blocks: limited by how fast a player clicks
        } else {
            ItemStack tool = toolFor(b);
            float speed = data.getDestroySpeed(tool, false);
            boolean harvestable = !data.requiresCorrectToolForDrops() || data.isPreferredTool(tool);
            double progressPerTick = speed / hardness / (harvestable ? 30.0 : 100.0);
            ticks = Math.ceil(1.0 / progressPerTick) + BREAK_PAUSE_TICKS;
        }
        return Math.max(1, (int) Math.round(ticks / workSpeed()));
    }

    /** Ticks for one right-click action (planting, picking berries), scaled by work-speed. */
    public int clickTicks() {
        return Math.max(1, (int) Math.round(CLICK_TICKS / workSpeed()));
    }

    /** The job's progress line for menus, or null. */
    public String detail() {
        return job.detail(this);
    }

    public Vector position() {
        return e.location.toVector();
    }

    // ── main loop ─────────────────────────────────────────────────────────

    public void tick() {
        boolean turned = turn();
        tickWork();
        if (turned && lastMove != now) moveTo(e.location.getX(), e.location.getY(), e.location.getZ());
    }

    /**
     * Turns the head and body a step toward where it wants to look: quick for
     * a big turn, easing in at the end. True if it turned.
     */
    private boolean turn() {
        float dy = wrap(wantYaw - yaw), dp = wantPitch - pitch;
        if (Math.abs(dy) < 0.5f && Math.abs(dp) < 0.5f) return false;
        float stepY = Math.max(6f, Math.min(40f, Math.abs(dy) * 0.45f));
        float stepP = Math.max(3f, Math.min(20f, Math.abs(dp) * 0.45f));
        yaw = wrap(yaw + Math.max(-stepY, Math.min(stepY, dy)));
        pitch += Math.max(-stepP, Math.min(stepP, dp));
        return true;
    }

    private static float wrap(float deg) {
        deg %= 360f;
        if (deg >= 180f) deg -= 360f;
        if (deg < -180f) deg += 360f;
        return deg;
    }

    private void tickWork() {
        now++;
        if ((now & 1023) == 0) unreachable.values().removeIf(until -> until <= now);

        if (e.paused) {
            show("Paused", State.PAUSED);
            return;
        }
        if (e.onStrike) {
            show("On strike: " + e.ownerName() + " couldn't pay the " + Msg.money(jobSettings().wage()) + " wage", State.STRIKE);
            return;
        }
        if (plugin.manager().resting(e)) {
            if (state != State.RESTING) interrupt();
            show("Resting: nobody's on the server", State.RESTING);
            return;
        }
        if (e.knockedOutUntil > 0) {
            long left = e.knockedOutUntil - System.currentTimeMillis();
            if (left > 0) {
                if (e.isLoaded() && e.entity.getPose() != org.bukkit.entity.Pose.SLEEPING
                        && org.bukkit.entity.Mannequin.validPoses().contains(org.bukkit.entity.Pose.SLEEPING)) {
                    e.entity.setPose(org.bukkit.entity.Pose.SLEEPING, true);
                }
                show("Knocked out by " + (e.knockedOutBy == null ? "a mob" : e.knockedOutBy) + ": back up in " + (left / 1000 + 1) + "s", State.IDLE);
                return;
            }
            e.knockedOutUntil = 0;
            e.knockedOutBy = null;
            if (e.isLoaded()) {
                e.entity.setPose(org.bukkit.entity.Pose.STANDING, false);
                var max = e.entity.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
                e.entity.setHealth(max == null ? 20 : max.getValue());
            }
            plugin.manager().markDirty();
        }
        if (cooldownReady("house-check", 600)) {
            if (e.house != null && !e.house.bedStanding()) plugin.manager().loseHouse(e, "their bed is gone");
            if (e.homeSite != null) plugin.homeSites().check(e);
        }
        try {
            job.tick(this);
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING, e.name + " hit an error in the background", ex);
        }
        if (task == null) {
            if (cooldown > 0) {
                cooldown--;
                if (random.nextInt(80) == 0) glanceAround();
                return;
            }
            if (job.movement() != Job.Movement.FLY && cooldownReady("settle", 100)) settle();
            task = choose();
            if (task == null) {
                cooldown = 40;
                if (e.debug && cooldownReady("debug-idle", 100)) plugin.getLogger().info("[debug " + e.name + "] idle: " + idleReason()
                        + (job instanceof BuilderJob b ? " | " + b.debugState() : ""));
                show(idleReason(), idleState());
                return;
            }
        }
        show(task.status(this), task.idle() ? idleState() : State.WORKING);
        Task.Result result;
        try {
            result = task.tick(this);
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING, e.name + " hit an error while working", ex);
            result = Task.Result.FAILED;
        }
        if (result != Task.Result.RUNNING) {
            Task finished = task;
            task = null;
            finished.end(this);
            clearPath();
            cooldown = result == Task.Result.DONE ? 1 : 20;
            failures = result == Task.Result.FAILED ? failures + 1 : 0;
            if (e.debug) {
                String st;
                try {
                    st = finished.status(this);
                } catch (RuntimeException ex) {
                    st = "?";
                }
                plugin.getLogger().info("[debug " + e.name + "] " + finished.getClass().getSimpleName() + " " + result + ": " + st
                        + (finished.workTarget() != null ? " at " + finished.workTarget().getBlockX() + " " + finished.workTarget().getBlockY() + " " + finished.workTarget().getBlockZ() : "")
                        + " (from " + e.location.getBlockX() + " " + e.location.getBlockY() + " " + e.location.getBlockZ() + ", failures " + failures + ")");
            }
            if (failures >= 8) {
                if (e.debug) plugin.getLogger().info("[debug " + e.name + "] 8 failures in a row: going home");
                rescue();
            }
        }
    }

    /**
     * After many failed tasks in a row the employee is probably boxed in (a
     * miner's tunnel caved in, a builder walled itself in): it climbs out to
     * its home spot, as a player would with a few blocks and a pickaxe.
     */
    private long lastRescue = -1_000_000;

    private void rescue() {
        failures = 0;
        if (!job.wandersHome(this) || job.movement() == Job.Movement.DIG) return;   // diggers dig their own way out
        if (now - lastRescue < 20 * 120) {
            // Stuck again right after going home: home itself is the trap (inside a building). Use open ground.
            Location open = plugin.manager().openSpot(e);
            if (open != null) {
                e.home = open;
                plugin.manager().events().add(e.owner, e.name, "Was boxed in at its home spot; moved home to open ground", true);
                plugin.manager().markDirty();
            }
        }
        lastRescue = now;
        Location home = e.home;
        if (home == null || home.getWorld() != world() || home.distanceSquared(e.location) < 9) return;
        if (!world().isChunkLoaded(home.getBlockX() >> 4, home.getBlockZ() >> 4)) return;
        world().spawnParticle(Particle.CLOUD, e.location.clone().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.02);
        moveTo(home.getX(), home.getY(), home.getZ());
        unreachable.clear();
    }

    /** Abandons the current task, e.g. after the work area or storage changes. */
    public void interrupt() {
        if (task != null) {
            Task t = task;
            task = null;
            t.end(this);
        }
        clearPath();
        cooldown = 0;
        failures = 0;
        storageProblem = null;
        lastDelivery = -1_000_000;
        unreachable.clear();
        job.reset(this);
    }

    private Task choose() {
        Task distraction = Distractions.check(this);
        if (distraction != null) return distraction;
        Task homeWork = HomeWork.check(this);
        if (homeWork != null) return homeWork;
        // A following bodyguard stays with its owner: it never walks back to a chest at its post to
        // deliver, or to pick things up there. Its loot waits in the backpack until it's sent back.
        boolean away = !job.wandersHome(this);
        boolean walksToDeliver = e.storage.kind() == StorageTarget.Kind.CHEST;
        if (wantsDelivery(false) && !(away && walksToDeliver)) {
            Task t = deliveryTask();
            if (t != null) return t;
        }
        if (!backpackFull() || job.canWorkWhenFull() || away) {
            Task t = job.next(this);
            if (t != null && t.workTarget() != null && !mayWork(t.workTarget())) {
                // A job that didn't check its no-work zones: skip the spot for a good while.
                unreachable.put(BlockPos.pack(t.workTarget().getBlockX(), t.workTarget().getBlockY(), t.workTarget().getBlockZ()), now + 20 * 600);
                t = null;
            }
            if (t != null) return t;
            if (!away) t = PickupTask.find(this);
            if (t != null) return t;
            t = HomeWork.idle(this);   // nothing to do at work: free time for its home
            if (t != null) return t;
        }
        if (away && walksToDeliver) return idleTask();
        if (wantsDelivery(true)) {
            Task t = deliveryTask();
            if (t != null) return t;
        }
        return idleTask();
    }

    private void show(String status, State s) {
        if (!status.equals(e.status) && s != State.WORKING && s != State.PAUSED && EventLog.isProblem(status)) {
            plugin.manager().events().add(e.owner, e.name, status, true);
        }
        e.status = status;
        if (s != state) {
            state = s;
            plugin.manager().updateLabel(e);
        }
    }

    private String idleReason() {
        if (backpackFull()) {
            return storageProblem != null ? "Backpack full, and " + e.storage.describe() + " " + storageProblem : "Backpack full";
        }
        return job.idleStatus(this);
    }

    private State idleState() {
        return backpackFull() ? State.FULL : State.IDLE;
    }

    public boolean backpackFull() {
        return Items.freeSlots(e.backpack) < 3;
    }

    // ── delivery ──────────────────────────────────────────────────────────

    private boolean wantsDelivery(boolean idle) {
        StorageTarget st = e.storage;
        boolean selling = e.selling() && settings().marketEnabled && plugin.market().hasMerchants();
        if (st.kind() == StorageTarget.Kind.NONE && !selling) return false;
        if (st.kind() == StorageTarget.Kind.VIRTUAL && (!settings().vcEnabled || !plugin.vchests().available())) return false;
        if (st.kind() == StorageTarget.Kind.CHEST && !settings().chestsEnabled) return false;
        if (Items.countDeliverable(e.backpack, this::reserved) == 0) return false;
        int free = Items.freeSlots(e.backpack);
        long since = now - lastDelivery;
        if (st.kind() == StorageTarget.Kind.VIRTUAL || st.kind() == StorageTarget.Kind.NONE) {
            return since >= settings().vcDeliveryTicks || (free < 8 && since >= 100);
        }
        return (free < 12 && since >= 200) || (idle && since >= 1200);
    }

    private Task deliveryTask() {
        return switch (e.storage.kind()) {
            case VIRTUAL -> new DeliverVirtualTask();
            case CHEST -> DeliverChestTask.create(this);
            case NONE -> new DeliverVirtualTask();   // only reached when selling
        };
    }

    /** Takes everything deliverable out of the backpack (reserves stay behind). */
    public List<ItemStack> takeDeliverable() {
        return Items.takeAllExcept(e.backpack, this::reserved);
    }

    /** Records a delivery attempt; leftovers go back into the backpack. */
    public void delivered(int amount, List<ItemStack> leftover) {
        lastDelivery = now;
        storageProblem = leftover.isEmpty() ? null : "is full";
        e.stats.itemsDelivered += amount;
        stash(leftover);
        plugin.manager().markDirty();
    }

    /** A delivery could not happen at all; try again after the usual interval. */
    public void deliveryBlocked(String problem) {
        lastDelivery = now;
        storageProblem = problem;
    }

    // ── idling ────────────────────────────────────────────────────────────

    private Task idleTask() {
        if (!job.wandersHome(this)) return null;
        Location home = e.home;
        boolean inArea = e.area != null && e.area.containsColumn(e.location.getBlockX(), e.location.getBlockZ());
        if (home != null && home.getWorld() != null && home.getWorld().isChunkLoaded(home.getBlockX() >> 4, home.getBlockZ() >> 4)) {
            boolean otherWorld = home.getWorld() != world();
            double d2 = otherWorld ? Double.MAX_VALUE : home.distanceSquared(e.location);
            // Inside its own area it's at work: never sent home (a quarry miner deep in its pit is meant to be there).
            if (inArea && !otherWorld) {
                if (job.movement() == Job.Movement.WALK && d2 > 24 * 24 && !isUnreachable(home.toVector())) {
                    return new IdleWalkTask(home.toVector(), "Heading back to work");
                }
                home = null;
            }
            if (home == null) {
                // fall through to strolling
            } else if (otherWorld || d2 > 160 * 160 || isUnreachable(home.toVector())) {
                // Another world, very far, or the walk back just failed: go straight there.
                world().spawnParticle(Particle.CLOUD, e.location.clone().add(0, 1, 0), 10, 0.3, 0.5, 0.3, 0.02);
                plugin.manager().teleport(e, home);
                unreachable.clear();
                return null;
            }
            else return new IdleWalkTask(home.toVector(), "Heading back to work");
        }
        if (e.area != null && random.nextInt(6) == 0) {
            Vector spot = randomSpot(e.area);
            if (spot != null && !isUnreachable(spot)) return new IdleWalkTask(spot, null);
        }
        return null;
    }

    private Vector randomSpot(WorkArea a) {
        Pathfinder pf = new Pathfinder(world(), limits(null));
        int x = a.minX() + random.nextInt(a.width());
        int z = a.minZ() + random.nextInt(a.length());
        if (!world().isChunkLoaded(x >> 4, z >> 4)) return null;
        return pf.snap(x + 0.5, e.home != null ? e.home.getY() : e.location.getY(), z + 0.5);
    }

    /** Looks round now and then while idle: often at a player close by, as people do. */
    private void glanceAround() {
        Player near = null;
        double best = 8 * 8;
        for (Player p : world().getPlayers()) {
            double d = p.getLocation().distanceSquared(e.location);
            if (d < best && p.getGameMode() != org.bukkit.GameMode.SPECTATOR) {
                best = d;
                near = p;
            }
        }
        if (near != null && random.nextInt(3) != 0) {
            Location eyes = near.getEyeLocation();
            lookAt(eyes.getX(), eyes.getY(), eyes.getZ());
            return;
        }
        wantYaw = wrap(yaw + (random.nextFloat() - 0.5f) * 90f);
        wantPitch = (random.nextFloat() - 0.5f) * 30f;
    }

    /**
     * Drops or lifts the employee onto the nearest floor if the ground changed
     * under it, and if it was dug out from under them altogether, falls to the
     * next floor below (taking its home spot along if that's gone too).
     */
    private void settle() {
        Location l = e.location;
        int bx = l.getBlockX(), bz = l.getBlockZ(), fy = (int) Math.floor(l.getY() + 0.01);
        if (!world().isChunkLoaded(bx >> 4, bz >> 4)) return;
        Block feet = world().getBlockAt(bx, fy, bz);
        if ((isWet(feet) || isWet(feet.getRelative(0, 1, 0))) && swimOut()) return;
        double feetTop = Pathfinder.collisionTop(feet);
        if (feetTop > 0.5 + 1e-6) {
            // Inside a block (something was built where it stands): up onto the nearest floor.
            Pathfinder pf = new Pathfinder(world(), BoundingBox.of(l.toVector(), 4, 8, 4));
            for (int dy = 1; dy <= 8; dy++) {
                double f = pf.floor(bx, fy + dy, bz);
                if (!Double.isNaN(f)) {
                    moveTo(l.getX(), f, l.getZ());
                    break;
                }
            }
        } else {
            double base = feetTop > 0 ? fy + feetTop : fy - 1 + Pathfinder.collisionTop(world().getBlockAt(bx, fy - 1, bz));
            boolean supported = feetTop > 0 || Pathfinder.collisionTop(world().getBlockAt(bx, fy - 1, bz)) >= 0.5 - 1e-6;
            if (supported) {
                if (Math.abs(base - l.getY()) > 0.05 && Math.abs(base - l.getY()) < 1.0) moveTo(l.getX(), base, l.getZ());   // settle onto a slab etc.
            } else {
                // Nothing underneath any more (dug out): fall to whatever is below, water or not.
                for (int y = fy - 1; y > world().getMinHeight(); y--) {
                    double t = Pathfinder.collisionTop(world().getBlockAt(bx, y - 1, bz));
                    if (t >= 0.5 - 1e-6) {
                        moveTo(l.getX(), y - 1 + t, l.getZ());
                        break;
                    }
                }
            }
        }
        if (e.home != null && e.home.getWorld() == world()) {
            Pathfinder hp = new Pathfinder(world(), BoundingBox.of(e.home.toVector(), 8, 400, 8));
            if (hp.snap(e.home.getX(), e.home.getY(), e.home.getZ()) == null) {
                Vector h = fall(hp, e.home.getX(), e.home.getY(), e.home.getZ());
                if (h != null) {
                    e.home = new Location(world(), h.getX(), h.getY(), h.getZ(), e.home.getYaw(), 0);
                    plugin.manager().markDirty();
                }
            }
        }
    }

    private static boolean isWet(Block b) {
        return b.isLiquid() || (b.getBlockData() instanceof org.bukkit.block.data.Waterlogged wl && wl.isWaterlogged());
    }

    /**
     * Standing in water (fell into a flooded hole, water flowed into its
     * tunnel): it swims to the nearest dry spot it could climb out onto, as a
     * player would. Paths never start or end in water, so without this it
     * would stay there however its task changed.
     */
    private boolean swimOut() {
        Location l = e.location;
        int bx = l.getBlockX(), bz = l.getBlockZ(), fy = (int) Math.floor(l.getY() + 0.01);
        Pathfinder pf = new Pathfinder(world(), BoundingBox.of(l.toVector(), 8, 8, 8)).leavesOpen(job instanceof LumberjackJob).gates(this::mayOpenGate);
        Vector best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                if (!world().isChunkLoaded((bx + dx) >> 4, (bz + dz) >> 4)) continue;
                for (int dy = -1; dy <= 4; dy++) {
                    double f = pf.floor(bx + dx, fy + dy, bz + dz);
                    if (Double.isNaN(f)) continue;
                    double d = dx * dx + dz * dz + dy * dy * 0.5;
                    if (d < bestD) {
                        bestD = d;
                        best = new Vector(bx + dx + 0.5, f, bz + dz + 0.5);
                    }
                }
            }
        }
        if (best == null) return false;
        world().spawnParticle(Particle.SPLASH, l.clone().add(0, 1, 0), 20, 0.3, 0.3, 0.3, 0.1);
        playSound(l, Sound.ENTITY_PLAYER_SWIM, 0.5f, 1f);
        moveTo(best.getX(), best.getY(), best.getZ());
        clearPath();
        return true;
    }

    /** The first floor straight down from a point, or null if there is none above the void. */
    private Vector fall(Pathfinder pf, double x, double y, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (!world().isChunkLoaded(bx >> 4, bz >> 4)) return null;
        for (int fy = (int) Math.floor(y) - 1; fy > world().getMinHeight(); fy--) {
            double f = pf.floor(bx, fy, bz);
            if (!Double.isNaN(f)) return new Vector(bx + 0.5, f, bz + 0.5);
        }
        return null;
    }

    // ── movement ──────────────────────────────────────────────────────────

    /**
     * Walks toward a goal, planning a path on the first call for that goal.
     * Call every tick with the same goal object until it stops returning MOVING.
     */
    /** Set by a task that needs to move differently from its job for a while (a trader walking a villager, not flying). */
    public Job.Movement moveAs;

    public Move walk(Pathfinder.Goal goal, Vector target) {
        if (pathGoal != goal) {
            if (target != null && isUnreachable(target)) return Move.FAILED;
            Pathfinder pf = new Pathfinder(world(), limits(target)).leavesOpen(job instanceof LumberjackJob).gates(this::mayOpenGate);
            List<Pathfinder.Waypoint> found = switch (moveAs != null ? moveAs : job.movement()) {
                case WALK -> pf.find(position(), goal, PATH_NODE_BUDGET);
                case DIG -> pf.digging(digger()).find(position(), goal, PATH_NODE_BUDGET * 4);
                case FLY -> {
                    Pathfinder.Digger d = digger();
                    yield (d != null ? pf.flyDigging(d) : pf).findFlying(position(), goal, PATH_NODE_BUDGET * 2);
                }
            };
            if (found == null) return Move.FAILED;
            if (goal != replanFor) {
                replanFor = goal;
                replans = 0;
            }
            clearPath();
            partialPath = pf.partial();
            path = found;
            pathIndex = 0;
            pathGoal = goal;
            walkTicks = 0;
        }
        if (pathIndex >= path.size()) return endOfPath();
        Pathfinder.Waypoint wp = path.get(pathIndex);
        if (!wp.dig().isEmpty()) {
            switch (digThrough(wp)) {
                case MOVING -> {
                    return Move.MOVING;   // still breaking; digging time doesn't count as walking
                }
                case FAILED -> {
                    return Move.FAILED;
                }
                default -> { }
            }
        }
        if (!wp.place().isEmpty()) {
            switch (buildUp(wp)) {
                case MOVING -> {
                    return Move.MOVING;
                }
                case FAILED -> {
                    return Move.FAILED;
                }
                default -> { }
            }
        }
        if (++walkTicks > MAX_WALK_TICKS) return Move.FAILED;
        stepAlong();
        return pathIndex >= path.size() ? endOfPath() : Move.MOVING;
    }

    /** The job's digger, kept out of no-work zones. */
    private Pathfinder.Digger digger() {
        Pathfinder.Digger d = job.digger(this);
        if (d == null) return null;
        return new Pathfinder.Digger() {
            @Override
            public int digTicks(Block b) {
                return mayWork(b.getX(), b.getY(), b.getZ()) ? d.digTicks(b) : -1;
            }

            @Override
            public boolean mayPlace(int x, int y, int z) {
                return mayWork(x, y, z) && d.mayPlace(x, y, z);
            }
        };
    }

    /** At the end of a path: arrived, or (for a part-way path) plan the next stretch. */
    private Move endOfPath() {
        if (!partialPath) return Move.ARRIVED;
        if (++replans > 12 || path.size() < 3) return Move.FAILED;   // not getting any closer: give it up
        pathGoal = null;   // the next walk() call plans again from here
        return Move.MOVING;
    }

    /**
     * Breaks the blocks standing between the employee and the next waypoint,
     * one at a time and as slowly as a player would. ARRIVED = the way is open.
     */
    private Move digThrough(Pathfinder.Waypoint wp) {
        for (BlockPos p : wp.dig()) {
            Block b = p.block(world());
            if (Pathfinder.isClear(b)) continue;
            Pathfinder.Digger digger = digger();
            if (digger == null || digger.digTicks(b) < 0 || b.isLiquid()) return Move.FAILED;   // the world changed
            if (!p.equals(digging)) {
                if (digging != null) crack(digging, 0f);
                digging = p;
                digNeed = breakTicks(b);
                digTimer = 0;
            }
            digTimer++;
            lookAt(p);
            if (digTimer % 4 == 1) swing();
            if (digNeed > 2) crack(p, (float) digTimer / digNeed);
            if (digTimer >= digNeed) {
                crack(p, 0f);
                Material type = b.getType();
                boolean temporary = job.digsTemporarily(this, b);
                List<ItemStack> drops = new java.util.ArrayList<>(breakBlock(b, true, true));
                if (temporary) drops.clear();   // it goes back as it was later
                e.stats.blocksMined++;
                drops.removeIf(d -> !job.keepsDug(this, type, d.getType()));
                stash(drops);
                digging = null;
            }
            return Move.MOVING;
        }
        return Move.ARRIVED;
    }

    /**
     * Puts down the blocks a path needs (a pillar under the feet, a bridge over
     * a gap) from the backpack, one per right-click. ARRIVED = all in place.
     */
    private Move buildUp(Pathfinder.Waypoint wp) {
        for (BlockPos p : wp.place()) {
            Block b = p.block(world());
            if (!b.getType().isAir() && !b.isReplaceable()) continue;   // already there (water can be built into, like a player does)
            if (++placeTimer < clickTicks()) return Move.MOVING;
            placeTimer = 0;
            Material m = job.buildingBlock(this);
            if (m == null || Items.take(e.backpack, m, 1) == 0) return Move.FAILED;
            int fy = (int) Math.floor(e.location.getY() + 0.01);
            if (p.x() == e.location.getBlockX() && p.z() == e.location.getBlockZ() && p.y() == fy) {
                moveTo(e.location.getX(), fy + 1, e.location.getZ());   // jump, then place underneath
            }
            lookAt(p);
            swing();
            plugin.blockLog().removing(e, b);   // water it built into
            b.setType(m, true);
            plugin.blockLog().placed(e, b);
            placeEffect(b);
            return Move.MOVING;
        }
        return Move.ARRIVED;
    }

    private void clearPath() {
        closeBehind(false);
        if (digging != null) crack(digging, 0f);
        digging = null;
        path = null;
        pathGoal = null;
    }

    /** Doors and gates it opened on the way, to close again once it's through. */
    private final List<BlockPos> opened = new java.util.ArrayList<>();

    /** Opens a closed wooden door or fence gate in the way of the next step. */
    private void openWay(Vector wp) {
        int x = wp.getBlockX(), z = wp.getBlockZ(), y = (int) Math.floor(wp.getY() + 0.01);
        for (int dy = 0; dy <= 1; dy++) {
            Block b = world().getBlockAt(x, y + dy, z);
            if (Pathfinder.opensItself(b.getType()) && b.getBlockData() instanceof org.bukkit.block.data.Openable o && !o.isOpen()) {
                if (Tag.FENCE_GATES.isTagged(b.getType()) && !mayOpenGate(b)) {
                    pathGoal = null;   // animals came up to it meanwhile: find another way, or wait
                    return;
                }
                o.setOpen(true);
                b.setBlockData(o, true);   // the other half of a door follows
                playSound(b.getLocation(), Tag.FENCE_GATES.isTagged(b.getType()) ? Sound.BLOCK_FENCE_GATE_OPEN : Sound.BLOCK_WOODEN_DOOR_OPEN, 0.7f, 1f);
                swing();
                opened.add(BlockPos.of(b));
            }
        }
    }

    /**
     * Only a rancher goes through a fence gate with animals close to it: anyone
     * else would let one slip out (guards wandering into a pen). For them the
     * gate is a fence until the animals have moved off.
     */
    private boolean mayOpenGate(Block gate) {
        if (job instanceof RancherJob) return true;
        return world().getNearbyEntities(gate.getLocation().add(0.5, 0.5, 0.5), 6, 3, 6,
                en -> en instanceof org.bukkit.entity.Animals || en instanceof org.bukkit.entity.AbstractHorse).isEmpty();
    }

    /** Closes doors and gates it opened once it's clear of them (gates as soon as it's through, before an animal follows). */
    private void closeBehind(boolean all) {
        if (opened.isEmpty()) return;
        java.util.Iterator<BlockPos> it = opened.iterator();
        while (it.hasNext()) {
            BlockPos p = it.next();
            Block b = p.block(world());
            double clear = Tag.FENCE_GATES.isTagged(b.getType()) ? 1.3 : 2.0;
            if (!all && p.horizontalDistanceSq(e.location.getX(), e.location.getZ()) < clear * clear) continue;
            if (b.getBlockData() instanceof org.bukkit.block.data.Openable o && o.isOpen()) {
                o.setOpen(false);
                b.setBlockData(o, true);
                playSound(b.getLocation(), Tag.FENCE_GATES.isTagged(b.getType()) ? Sound.BLOCK_FENCE_GATE_CLOSE : Sound.BLOCK_WOODEN_DOOR_CLOSE, 0.7f, 1f);
            }
            it.remove();
        }
    }

    /** Sprinting (a player's 1.3 times walking speed) until this tick: chasing a mob, catching up with its owner. */
    private long sprintUntil;

    /** Sprint for the next moment; call each tick it should keep sprinting. */
    public void sprint() {
        sprintUntil = now + 2;
        if (e.isLoaded() && cooldownReady("sprint-dust", 4) && e.entity.isOnGround()) {
            world().spawnParticle(Particle.BLOCK, e.location.clone().add(0, 0.1, 0), 2, 0.15, 0, 0.15,
                    e.location.clone().add(0, -0.5, 0).getBlock().getBlockData());
        }
    }

    private void stepAlong() {
        Vector wp = path.get(pathIndex).pos();
        if (wp.distanceSquared(e.location.toVector()) < 2.5 * 2.5) openWay(wp);
        closeBehind(false);
        if (job.movement() == Job.Movement.WALK) wp = new Vector(wp.getX() + offX, wp.getY(), wp.getZ() + offZ);
        Location l = e.location;
        double dx = wp.getX() - l.getX(), dz = wp.getZ() - l.getZ();
        double h = Math.sqrt(dx * dx + dz * dz);
        // Picks up speed over the first few steps, and slows into sharp turns.
        double speed = settings().walkSpeed * pace * Math.min(1.0, 0.45 + walkTicks * 0.11) * (sprintUntil > now ? 1.3 : 1.0);
        if (h > 0.05 && Math.abs(wrap((float) Math.toDegrees(Math.atan2(-dx, dz)) - yaw)) > 60f) speed *= 0.6;
        double nx, nz;
        if (h <= speed) {
            nx = wp.getX();
            nz = wp.getZ();
        } else {
            nx = l.getX() + dx / h * speed;
            nz = l.getZ() + dz / h * speed;
        }
        double ny = l.getY() + Math.max(-0.5, Math.min(0.42, wp.getY() - l.getY()));
        if (h > 0.05) {
            wantYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            wantPitch = 5f;
        }
        if (h <= speed && Math.abs(wp.getY() - ny) < 1e-3) pathIndex++;
        moveTo(nx, ny, nz);
    }

    /** Puts the employee (and its entity, if loaded) at a feet position. */
    public void moveTo(double x, double y, double z) {
        e.location = new Location(world(), x, y, z, yaw, pitch);
        lastMove = now;
        if (e.isLoaded()) e.entity.teleport(e.location);
    }

    public void lookAt(double x, double y, double z) {
        Location l = e.location;
        double dx = x - l.getX(), dy = y - (l.getY() + 1.62), dz = z - l.getZ();
        double h = Math.sqrt(dx * dx + dz * dz);
        wantYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        wantPitch = (float) Math.toDegrees(-Math.atan2(dy, h));
    }

    public void lookAt(BlockPos p) {
        lookAt(p.centerX(), p.centerY(), p.centerZ());
    }

    private BoundingBox limits(Vector target) {
        Vector pos = position();
        BoundingBox box;
        WorkArea a = e.area;
        if (a != null && a.world().equals(world().getName())) {
            int m = 16;   // room to go round: a builder heading down into a cellar from a door outside its site
            box = new BoundingBox(a.minX() - m, a.minY() - 12, a.minZ() - m, a.maxX() + m + 1, a.maxY() + 13, a.maxZ() + m + 1);
        } else {
            box = BoundingBox.of(pos, 24, 12, 24);
        }
        box.union(BoundingBox.of(pos, 8, 6, 8));
        if (target != null) box.union(BoundingBox.of(target, 6, 6, 6));
        return box;
    }

    /** True at most once every {@code ticks} ticks per key: throttles scans. */
    public boolean cooldownReady(String key, int ticks) {
        Long next = throttles.get(key);
        if (next != null && next > now) return false;
        throttles.put(key, now + ticks);
        return true;
    }

    // ── no-work zones ─────────────────────────────────────────────────────

    private List<WorkArea> zones = List.of();
    private int zonesVersion = -1;

    /** False inside one of its no-work zones: it doesn't break, place, harvest, fight or pick up there. */
    public boolean mayWork(int x, int y, int z) {
        var zp = plugin.zonePresets();
        if (zonesVersion != zp.version()) {
            zones = zp.boxes(e);
            zonesVersion = zp.version();
        }
        return (zones.isEmpty() || site.remotearc.employees.ZonePresets.outside(zones, world().getName(), x, y, z))
                && plugin.estate().mayWork(e.owner, world(), x, y, z);   // not on someone else's land (ArcEstate)
    }

    /** Outside its no-work zones (fighting a mob on a friend's land is fine; changing blocks there isn't). */
    public boolean outsideZones(Location l) {
        var zp = plugin.zonePresets();
        if (zonesVersion != zp.version()) {
            zones = zp.boxes(e);
            zonesVersion = zp.version();
        }
        return zones.isEmpty() || site.remotearc.employees.ZonePresets.outside(zones, l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    public boolean mayWork(BlockPos p) {
        return mayWork(p.x(), p.y(), p.z());
    }

    public boolean mayWork(Vector v) {
        return mayWork(v.getBlockX(), v.getBlockY(), v.getBlockZ());
    }

    public boolean mayWork(Location l) {
        return mayWork(l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    /** The mob that last hurt it (a guard turns on it), while it's still around. */
    private org.bukkit.entity.LivingEntity attacker;

    /**
     * Whether a guard or bodyguard would fight this mob back. Mobs it leaves
     * alone (one kept in a boat, named, leashed, swimming, out of reach) may
     * not go for it either, so it's never beaten by something it ignores.
     */
    public boolean fightsBack(org.bukkit.entity.LivingEntity mob) {
        return job instanceof GuardJob && GuardJob.fightable(this, mob) && !isUnreachable(mob.getLocation().toVector());
    }

    /** Hurt by a mob: drop whatever it was doing (following, picking up, a torch) and turn on it. */
    public void attackedBy(org.bukkit.entity.LivingEntity mob) {
        attacker = mob;
        if (task != null && !(task instanceof GuardJob.FightTask)) {
            Task t = task;
            task = null;
            t.end(this);
            clearPath();
        }
        cooldown = 0;
    }

    /** Who to fight back, if they hurt it lately and are still near and fightable. */
    org.bukkit.entity.LivingEntity attacker() {
        org.bukkit.entity.LivingEntity a = attacker;
        if (a == null) return null;
        if (!a.isValid() || a.getWorld() != world() || a.getLocation().distanceSquared(e.location) > 16 * 16 || !fightsBack(a)) {
            attacker = null;
            return null;
        }
        return a;
    }

    /** Forgets which spots it couldn't get to (after moving somewhere new). */
    public void forgetUnreachable() {
        unreachable.clear();
        clearPath();
    }

    /** Materials a fetch came back without, and until when they're not looked for again. */
    private final Map<Material, Long> missedFetch = new java.util.EnumMap<>(Material.class);

    /** A fetch didn't get these: don't count on fetching them for a minute. */
    void fetchMissed(java.util.Collection<Material> items) {
        for (Material m : items) missedFetch.put(m, now + 20 * 60);
    }

    /** Whether a recent fetch came back without this. */
    public boolean recentlyMissed(Material m) {
        Long until = missedFetch.get(m);
        return until != null && until > now;
    }

    public void markUnreachable(Vector target) {
        unreachable.put(BlockPos.pack(target.getBlockX(), target.getBlockY(), target.getBlockZ()), now + UNREACHABLE_TICKS);
    }

    public boolean isUnreachable(Vector target) {
        return isUnreachable(BlockPos.pack(target.getBlockX(), target.getBlockY(), target.getBlockZ()));
    }

    public boolean isUnreachable(BlockPos p) {
        return isUnreachable(p.key());
    }

    private boolean isUnreachable(long key) {
        Long until = unreachable.get(key);
        return until != null && until > now;
    }

    // ── actions ───────────────────────────────────────────────────────────

    public void swing() {
        if (e.isLoaded()) e.entity.swingMainHand();
    }

    /** Shows (or with 0, clears) crack progress on a block to nearby players. */
    public void crack(BlockPos p, float progress) {
        if (!e.isLoaded()) return;
        Location at = new Location(world(), p.x(), p.y(), p.z());
        for (Player pl : world().getPlayers()) {
            if (pl.getLocation().distanceSquared(at) < 48 * 48) {
                pl.sendBlockDamage(at, Math.max(0f, Math.min(1f, progress)), e.entity.getEntityId());
            }
        }
    }

    /** Breaks a block as the employee, adding its drops to the backpack. */
    public Collection<ItemStack> breakBlock(Block b, boolean effects, boolean sound) {
        BlockData data = b.getBlockData();
        Collection<ItemStack> drops = b.getDrops(toolFor(b), e.entity);
        plugin.blockLog().removing(e, b);
        b.setType(Material.AIR, true);
        if (effects) breakEffect(b, data, sound);
        return drops;
    }

    public void breakEffect(Block b, BlockData data, boolean sound) {
        Location c = b.getLocation().add(0.5, 0.5, 0.5);
        world().spawnParticle(Particle.BLOCK, c, 10, 0.25, 0.25, 0.25, 0, data);
        if (sound) world().playSound(c, data.getSoundGroup().getBreakSound(), SoundCategory.BLOCKS, 0.8f, 0.9f);
    }

    public void placeEffect(Block b) {
        BlockData data = b.getBlockData();
        world().playSound(b.getLocation().add(0.5, 0.5, 0.5), data.getSoundGroup().getPlaceSound(), SoundCategory.BLOCKS, 0.8f, 0.9f);
    }

    public void playSound(Location at, Sound sound, float volume, float pitch) {
        world().playSound(at, sound, SoundCategory.NEUTRAL, volume, pitch);
    }

    /** Puts items in the backpack; anything that does not fit is dropped at the employee's feet. */
    public void stash(Collection<ItemStack> items) {
        if (items.isEmpty()) return;
        List<ItemStack> left = Items.add(e.backpack, items);
        if (!left.isEmpty() && e.storage.kind() == StorageTarget.Kind.VIRTUAL && plugin.vchests().available()
                && Items.countDeliverable(e.backpack, this::reserved) > 0) {
            // Backpack full (a builder digging out dirt, a miner deep in a vein): send what it doesn't
            // need to its chest on the spot and keep the rest, rather than leave it lying about.
            new DeliverVirtualTask().tick(this);
            left = Items.add(e.backpack, left);
        }
        if (!left.isEmpty() && e.storage.kind() == StorageTarget.Kind.VIRTUAL && plugin.vchests().available()) {
            left = plugin.vchests().deposit(e.owner, e.storage.chestNumber(), left);
        }
        for (ItemStack s : left) world().dropItem(e.location, s);
        plugin.manager().markDirty();
    }
}
