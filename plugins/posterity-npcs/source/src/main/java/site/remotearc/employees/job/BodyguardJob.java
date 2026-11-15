package site.remotearc.employees.job;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import site.remotearc.employees.Employee;
import site.remotearc.employees.util.BlockPos;
import site.remotearc.employees.util.Items;
import site.remotearc.employees.util.Pathfinder;
import site.remotearc.employees.util.WorkArea;

/**
 * A guard that goes with its owner: while following it stays a few blocks
 * away, fights hostile mobs that come near the owner, picks up what they
 * drop and (with torches) lights dark ground around the owner. If the owner
 * gets far ahead, flies, or changes world, it catches up by teleporting,
 * like a tamed wolf. Told to stay (or with its owner offline) it guards its
 * post (the work area) exactly like a guard.
 */
final class BodyguardJob extends GuardJob {

    private String idle = "Guarding its post";

    @Override
    public Task next(Worker w) {
        Player owner = following(w);
        if (owner == null) {
            Task t = super.next(w);
            idle = w.e.following ? "At its post (waiting for " + w.e.ownerName() + " to come online)" : super.idleStatus(w);
            return t;
        }
        Location me = w.e.location, them = onGround(owner);
        if (me.getWorld() != them.getWorld() || me.distanceSquared(them) > 32 * 32) {
            catchUp(w, owner);
            return null;
        }
        LivingEntity hitMe = w.attacker();
        if (hitMe != null) return new FightTask(hitMe, BoundingBox.of(me.toVector(), 20, 8, 20));
        LivingEntity mob = threat(w, owner);
        if (mob != null) {
            double r = w.settings().bodyguardRadius;
            return new FightTask(mob, BoundingBox.of(them.toVector(), r, 6, r).union(BoundingBox.of(me.toVector(), 6, 4, 6)).expand(8, 3, 8));
        }

        Task pickup = w.backpackFull() ? null : PickupTask.findNear(w, me, 8);
        if (pickup != null) return pickup;

        if (Items.count(w.e.backpack, Material.TORCH) > 0 && w.cooldownReady("dark-scan", 100)) {
            WorkArea near = WorkArea.around(them.getWorld().getName(), them.getBlockX(), them.getBlockY(), them.getBlockZ(), 8);
            BlockPos dark = darkSpot(w, near);
            if (dark != null) return new PlaceTask(w.world(), dark, Material.TORCH, "Lighting a dark spot");
        }
        Vector spot = slot(w, owner);
        boolean offPlace = spot != null && !w.isUnreachable(spot) && me.toVector().distanceSquared(spot) > 3 * 3;
        if (me.distanceSquared(them) > 4.5 * 4.5 || offPlace || crowded(w)) {
            return new FollowTask(owner);
        }
        idle = busyMates(w) ? "Guarding " + owner.getName() + " while the others fight" : "Guarding " + owner.getName();
        return null;
    }

    /**
     * The mob most dangerous to the owner (the nearest to them) that no other
     * employee is already fighting. With two bodyguards and two mobs, each
     * takes one; with one mob, the other stays with the owner.
     */
    static LivingEntity threat(Worker w, Player ownerPlayer) {
        Location owner = onGround(ownerPlayer);
        if (owner.getWorld() != w.world()) return null;
        double r = w.settings().bodyguardRadius;
        // Near the owner, or right next to the bodyguard itself (walking along beside it towards them).
        java.util.Set<org.bukkit.entity.Entity> near = new java.util.HashSet<>(w.world().getNearbyEntities(BoundingBox.of(owner.toVector(), r, 6, r),
                en -> en instanceof org.bukkit.entity.Enemy && en instanceof LivingEntity));
        near.addAll(w.world().getNearbyEntities(BoundingBox.of(w.e.location.toVector(), 5, 3, 5),
                en -> en instanceof org.bukkit.entity.Enemy && en instanceof LivingEntity));
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (org.bukkit.entity.Entity en : near) {
            LivingEntity mob = (LivingEntity) en;
            if (claimedByOther(w, mob) || !fightable(w, mob) || w.isUnreachable(mob.getLocation().toVector())) continue;
            double d = mob.getLocation().distanceSquared(owner);
            if (d < bestD) {
                bestD = d;
                best = mob;
            }
        }
        return best;
    }

    /** Its owner's bodyguards that are following them, in a fixed order. */
    private static List<Employee> mates(Worker w) {
        List<Employee> out = new ArrayList<>();
        for (Employee o : w.plugin().manager().ownedBy(w.e.owner)) {
            if (o.job == JobType.BODYGUARD && o.following && !o.paused && o.isLoaded()) out.add(o);
        }
        out.sort(Comparator.comparing(o -> o.id));
        return out;
    }

    private static boolean busyMates(Worker w) {
        for (Employee o : mates(w)) if (o != w.e && o.worker != null && o.worker.state() == Worker.State.WORKING) return true;
        return false;
    }

    /** Another of the owner's bodyguards is standing (almost) where it stands. */
    private static boolean crowded(Worker w) {
        Location me = w.e.location;
        for (Employee o : mates(w)) {
            if (o == w.e || o.location.getWorld() != me.getWorld()) continue;
            double dx = o.location.getX() - me.getX(), dz = o.location.getZ() - me.getZ();
            if (dx * dx + dz * dz < 0.9 * 0.9 && Math.abs(o.location.getY() - me.getY()) < 1.5) return true;
        }
        return false;
    }

    /** Degrees from the owner's facing for each bodyguard's place: behind left, behind right, left, right... */
    private static final double[] SLOT_ANGLES = {150, -150, 100, -100, 180, 125, -125};

    /** Per owner: the way they're heading (yaw) and where they were when it was last updated. */
    private static final java.util.Map<java.util.UUID, double[]> HEADING = new java.util.HashMap<>();

    /**
     * Which way the owner is going: the direction they last walked at least 3
     * blocks in, not where they look. Turning round on the spot doesn't move
     * the bodyguards' places, so they stand still while their owner does.
     */
    private static double heading(Player owner) {
        Location at = owner.getLocation();
        double[] h = HEADING.get(owner.getUniqueId());
        if (h == null || Math.abs(h[4] - at.getWorld().getUID().getMostSignificantBits()) > 0.5) {
            h = new double[]{at.getYaw(), at.getX(), at.getY(), at.getZ(), at.getWorld().getUID().getMostSignificantBits()};
            HEADING.put(owner.getUniqueId(), h);
            return h[0];
        }
        double dx = at.getX() - h[1], dz = at.getZ() - h[3];
        if (dx * dx + dz * dz > 3 * 3) {
            h[0] = Math.toDegrees(Math.atan2(-dx, dz));
            h[1] = at.getX();
            h[2] = at.getY();
            h[3] = at.getZ();
        }
        return h[0];
    }

    /** Where this bodyguard stands around its owner, about 2.5 blocks off; each has its own place. */
    static Vector slot(Worker w, Player owner) {
        List<Employee> mates = mates(w);
        int i = Math.max(0, mates.indexOf(w.e));
        Location them = onGround(owner);
        double a = Math.toRadians(heading(owner) + SLOT_ANGLES[i % SLOT_ANGLES.length]);
        double dist = 2.5 + (i / SLOT_ANGLES.length) * 1.5;
        return new Vector(them.getX() - Math.sin(a) * dist, them.getY(), them.getZ() + Math.cos(a) * dist);
    }

    /** The owner, if it is following them and they're online and alive (and not a spectator). */
    static Player following(Worker w) {
        if (!w.e.following) return null;
        Player p = Bukkit.getPlayer(w.e.owner);
        if (p == null || p.isDead() || p.getGameMode() == GameMode.SPECTATOR) return null;
        return p;
    }

    /**
     * Where the owner is, or with them flying (or gliding, or falling) the
     * ground under them: bodyguards walk along below and wait there, rather
     * than chase a spot in the air they can't reach.
     */
    static Location onGround(Player owner) {
        Location at = owner.getLocation();
        org.bukkit.World w = at.getWorld();
        int top = (int) Math.floor(at.getY() - 0.01), x = at.getBlockX(), z = at.getBlockZ();
        for (int y = top; y > Math.max(w.getMinHeight(), top - 96); y--) {
            org.bukkit.block.Block b = w.getBlockAt(x, y, z);
            if (b.isSolid() || b.isLiquid()) {
                if (y < top) at.setY(y + 1);
                return at;
            }
        }
        return at;
    }

    /** Teleports to its place round the owner (or just behind them). */
    static void catchUp(Worker w, Player owner) {
        Location them = onGround(owner);
        Location at = slot(w, owner).toLocation(them.getWorld());
        Pathfinder pf = new Pathfinder(at.getWorld(), BoundingBox.of(at.toVector(), 4, 8, 4));
        Vector v = pf.snap(at.getX(), at.getY(), at.getZ());
        if (v == null) v = pf.snap(them.getX(), them.getY(), them.getZ());
        if (v == null) return;   // nowhere to stand (they're flying over the sea): try again later
        at = new Location(them.getWorld(), v.getX(), v.getY(), v.getZ(), them.getYaw(), 0);
        w.plugin().manager().teleport(w.e, at);
    }

    @Override
    public String idleStatus(Worker w) {
        return idle;
    }

    @Override
    public boolean wandersHome(Worker w) {
        return following(w) == null;
    }

    /** Walks after the owner (re-planning as they move), teleporting if it falls too far behind. */
    private static final class FollowTask implements Task {

        private final Player owner;
        private Pathfinder.Goal goal;
        private Vector goalAt;
        private int ticks;
        private boolean toOwner;

        FollowTask(Player owner) {
            this.owner = owner;
        }

        @Override
        public Result tick(Worker w) {
            if (!owner.isOnline() || ++ticks > 20 * 30) return Result.DONE;
            // Something to fight on the way (beside it, or near the owner): stop following and deal with it.
            if (ticks % 5 == 0 && (w.attacker() != null || threat(w, owner) != null)) return Result.DONE;
            Location them = onGround(owner);
            // Falling behind, or keeping up with an owner who's running: sprint.
            if (owner.isSprinting() || them.distanceSquared(w.e.location) > 6 * 6) w.sprint();
            if (them.getWorld() != w.world() || them.distanceSquared(w.e.location) > 24 * 24) {
                catchUp(w, owner);
                return Result.DONE;
            }
            Vector spot = toOwner ? them.toVector() : slot(w, owner);
            if (spot.distanceSquared(w.e.location.toVector()) < (toOwner ? 3 * 3 : 1.2 * 1.2)) return Result.DONE;
            if (goal == null || goalAt.distanceSquared(spot) > 4) {
                goalAt = spot;
                goal = Pathfinder.near(spot.getX(), spot.getY(), spot.getZ(), toOwner ? 2.5 : 1.0, 3, 3);
            }
            switch (w.walk(goal, goalAt)) {
                case FAILED -> {
                    if (!toOwner) {
                        w.markUnreachable(goalAt);
                        toOwner = true;   // its place is in a wall or over a drop: just go near them
                        goal = null;
                        return Result.RUNNING;
                    }
                    catchUp(w, owner);   // can't walk there (they flew, jumped a gap): catch up
                    return Result.DONE;
                }
                case ARRIVED -> {
                    return Result.DONE;
                }
                default -> {
                    return Result.RUNNING;
                }
            }
        }

        @Override
        public boolean idle() {
            return true;
        }

        @Override
        public String status(Worker w) {
            return "Following " + owner.getName();
        }
    }
}
