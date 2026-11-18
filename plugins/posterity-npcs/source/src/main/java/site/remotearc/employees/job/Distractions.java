package site.remotearc.employees.job;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Lightable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import site.remotearc.employees.Employee;
import site.remotearc.employees.Personality;
import site.remotearc.employees.util.Msg;
import site.remotearc.employees.util.Pathfinder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Personality at work: between tasks, an employee whose trait is triggered
 * (a sheep walks by, there's water to swim in, it's raining...) drops its work
 * for a minute or two, then goes back to it. Between two distractions it stays
 * focused for a while ({@code personalities.calm-minutes}).
 */
public final class Distractions {

    private Distractions() {}

    private static final int SCAN = 10;

    /** A distraction to start now, or null. Checked by the worker before it picks its next task. */
    static Task check(Worker w) {
        Employee e = w.e;
        Personality p = e.personality;
        if (p == null || !w.settings().personalitiesEnabled || !e.isLoaded()) return null;
        if (w.now() < w.calmUntil || !w.cooldownReady("personality", 100)) return null;
        // A bodyguard following its owner stays with them; nobody gets distracted while under attack or (guards) with enemies about.
        if (!w.job.wandersHome(w) || threatened(w)) return null;
        return trait(w, p, w.random());
    }

    /** What its trait has it do now, if there's the chance for it here (randomly: not every time there is). */
    private static Task trait(Worker w, Personality p, Random r) {
        return switch (p.kind) {
            case FOLLOW -> {
                LivingEntity a = nearestLoved(w, p);
                yield a != null && r.nextInt(3) == 0 ? new Follow(a, ticks(r)) : null;
            }
            case SWIM -> r.nextInt(3) == 0 ? Swim.find(w, ticks(r)) : null;
            case FLOWERS -> {
                Block f = r.nextInt(3) == 0 ? nearestBlock(w, b -> Tag.FLOWERS.isTagged(b.getType())) : null;
                yield f == null ? null : new Admire(f, ticks(r), "smelling the flowers", Particle.HAPPY_VILLAGER, true,
                        b -> Tag.FLOWERS.isTagged(b.getType()));
            }
            case CAMPFIRE -> {
                Block c = r.nextInt(3) == 0 ? nearestBlock(w, b -> Tag.CAMPFIRES.isTagged(b.getType())
                        && b.getBlockData() instanceof Lightable l && l.isLit()) : null;
                yield c == null ? null : new Admire(c, ticks(r), "warming up by the campfire", Particle.SMOKE, false, LIT_CAMPFIRE);
            }
            case STARGAZE -> night(w.world()) && !w.world().hasStorm() && openSky(w) && onGround(w) && r.nextInt(3) == 0
                    ? new Stargaze(ticks(r)) : null;
            case DANCE -> w.world().hasStorm() && openSky(w) && onGround(w) && r.nextInt(3) == 0 ? new Dance(ticks(r)) : null;
            case NAP -> onGround(w) && r.nextInt(24) == 0 ? new Nap(ticks(r)) : null;
            case CHAT -> {
                Entity mate = r.nextInt(8) == 0 ? someoneToChatWith(w) : null;
                yield mate == null ? null : new Chat(mate, ticks(r));
            }
            case WANDER -> r.nextInt(24) == 0 ? Wander.find(w, ticks(r)) : null;
        };
    }

    // ── off duty ──────────────────────────────────────────────────────────

    /**
     * The evening, after work: something pleasant nearby. Its own pastime when
     * it can (follow the sheep, a swim), otherwise warm up by a campfire, chat
     * with someone, or stroll about. Null if there's nothing to do here (it
     * just stands about, looking round).
     */
    static Task relax(Worker w) {
        if (!w.e.isLoaded()) return null;
        Random r = w.random();
        Personality p = w.e.personality;
        Distraction own = null;
        if (p != null && w.settings().personalitiesEnabled && w.job.wandersHome(w)
                && trait(w, p, r) instanceof Distraction d) own = d;
        if (own == null) {
            Block c = nearestBlock(w, LIT_CAMPFIRE);
            if (c != null && r.nextInt(2) == 0) own = new Admire(c, ticks(r), "warming up by the campfire", Particle.SMOKE, false, LIT_CAMPFIRE);
        }
        if (own == null && r.nextInt(2) == 0) {
            Entity mate = someoneToChatWith(w);
            if (mate != null) own = new Chat(mate, ticks(r));
        }
        if (own == null) own = Wander.find(w, ticks(r));
        if (own != null) own.label = "Relaxing";
        return own;
    }

    /** Night: off to bed (its home's, if it can get there), and asleep until morning. */
    static Task sleep(Worker w) {
        return w.e.isLoaded() ? new Sleep() : null;
    }

    /** Sleeps until the night is over (the worker interrupts it when work starts). */
    static final class Sleep extends Distraction {
        private Vector bed;
        private Pathfinder.Goal goal;
        private boolean there;

        Sleep() {
            super(20 * 60 * 20);
            label = "Sleeping";
        }

        @Override
        void start(Worker w) {
            var h = w.e.house;
            if (h == null || !h.world.equals(w.world().getName()) || !loaded(w.world(), h.bed.x(), h.bed.z())) return;
            Vector v = new Vector(h.bed.x() + 0.5, h.bed.y(), h.bed.z() + 0.5);
            if (v.distanceSquared(w.e.location.toVector()) <= 96 * 96) bed = v;
        }

        @Override
        String doing(Worker w) {
            return bed == null ? "no bed of their own" : there ? "in bed" : "heading home to bed";
        }

        @Override
        public String status(Worker w) {
            return bed == null ? "Sleeping rough (no home yet)" : there ? "Sleeping in bed" : "Heading home to bed";
        }

        @Override
        Result step(Worker w) {
            if (bed != null && !there) {
                if (goal == null) goal = Pathfinder.near(bed.getX(), bed.getY(), bed.getZ(), 1.5, 3, 2);
                Worker.Move m = w.walk(goal, bed);   // the bed as target: the path search reaches past the work area
                if (m == Worker.Move.MOVING) return Result.RUNNING;
                if (m == Worker.Move.FAILED) bed = null;   // can't get home: sleeps where it is
                there = true;
            }
            pose(w, Pose.SLEEPING);
            return Result.RUNNING;
        }
    }

    /** A minute or two. */
    private static int ticks(Random r) {
        return 20 * (60 + r.nextInt(61));
    }

    /** Guards and bodyguards keep their minds on the job while there are monsters about. */
    private static final java.util.function.Predicate<Block> LIT_CAMPFIRE =
            b -> Tag.CAMPFIRES.isTagged(b.getType()) && b.getBlockData() instanceof Lightable l && l.isLit();

    private static boolean threatened(Worker w) {
        if (w.attacker() != null) return true;
        if (w.e.job != JobType.GUARD && w.e.job != JobType.BODYGUARD) return false;
        return !w.world().getNearbyEntities(w.e.location, 16, 8, 16, en -> en instanceof org.bukkit.entity.Enemy).isEmpty();
    }

    /**
     * Distractions stay in and round its work area (within 8 blocks: the part the
     * plugin keeps loaded), so an employee never strolls off into ground that
     * unloads when the players leave and is stranded there.
     */
    private static boolean inReach(Worker w, double x, double z) {
        var a = w.e.area;
        return a == null || !a.world().equals(w.world().getName()) || a.expand(8, 0).containsColumn((int) Math.floor(x), (int) Math.floor(z));
    }

    private static boolean night(World world) {
        long t = world.getTime();
        return t >= 13_000 && t <= 23_000;
    }

    private static boolean openSky(Worker w) {
        Location l = w.e.location;
        return w.world().getHighestBlockYAt(l.getBlockX(), l.getBlockZ()) < l.getY() + 1;
    }

    private static boolean onGround(Worker w) {
        Location l = w.e.location;
        Block below = w.world().getBlockAt(l.getBlockX(), (int) Math.floor(l.getY() - 0.1), l.getBlockZ());
        return below.getType().isSolid();
    }

    private static boolean loaded(World world, double x, double z) {
        return world.isChunkLoaded((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
    }

    private static LivingEntity nearestLoved(Worker w, Personality p) {
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity en : w.world().getNearbyEntities(w.e.location, 12, 6, 12,
                en -> p.loves.contains(en.getType()) && inReach(w, en.getLocation().getX(), en.getLocation().getZ()))) {
            if (!(en instanceof LivingEntity le) || !le.isValid()) continue;
            double d = en.getLocation().distanceSquared(w.e.location);
            if (d < bestD) {
                bestD = d;
                best = le;
            }
        }
        return best;
    }

    private static Block nearestBlock(Worker w, java.util.function.Predicate<Block> wanted) {
        Location l = w.e.location;
        int cx = l.getBlockX(), cy = l.getBlockY(), cz = l.getBlockZ();
        Block best = null;
        double bestD = Double.MAX_VALUE;
        for (int x = cx - SCAN; x <= cx + SCAN; x++) {
            for (int z = cz - SCAN; z <= cz + SCAN; z++) {
                if (!loaded(w.world(), x, z)) continue;
                for (int y = cy - 3; y <= cy + 3; y++) {
                    Block b = w.world().getBlockAt(x, y, z);
                    if (!wanted.test(b) || !inReach(w, x, z)) continue;
                    double d = (x - cx) * (x - cx) + (y - cy) * (y - cy) + (z - cz) * (z - cz);
                    if (d < bestD) {
                        bestD = d;
                        best = b;
                    }
                }
            }
        }
        return best;
    }

    private static Entity someoneToChatWith(Worker w) {
        Entity best = null;
        double bestD = 16 * 16;
        for (Employee o : w.plugin().manager().all()) {
            if (o == w.e || !o.isLoaded() || o.location.getWorld() != w.world() || !inReach(w, o.location.getX(), o.location.getZ())) continue;
            double d = o.location.distanceSquared(w.e.location);
            if (d < bestD) {
                bestD = d;
                best = o.entity;
            }
        }
        for (Player p : w.world().getPlayers()) {
            if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR || !inReach(w, p.getLocation().getX(), p.getLocation().getZ())) continue;
            double d = p.getLocation().distanceSquared(w.e.location);
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    private static void pose(Worker w, Pose pose) {
        if (!w.e.isLoaded()) return;
        if (pose != Pose.STANDING && !Mannequin.validPoses().contains(pose)) return;
        if (w.e.entity.getPose() != pose) w.e.entity.setPose(pose, pose != Pose.STANDING);
    }

    private static String aName(String thing) {
        return ("aeiou".indexOf(thing.charAt(0)) >= 0 ? "an " : "a ") + thing;
    }

    // ── the distractions ──────────────────────────────────────────────────

    /** Runs for a set time; ends early if it gives up. Never counts as a failed task. */
    abstract static class Distraction implements Task {

        private final int length;
        private long started = -1;
        private Pathfinder.Goal goal;
        private Vector goalAt;
        private long goalSince;
        /** What the status line starts with: "Distracted" at work, "Relaxing" off duty. */
        String label = "Distracted";

        Distraction(int length) {
            this.length = length;
        }

        abstract String doing(Worker w);

        /** One tick of it; DONE ends it early. */
        abstract Result step(Worker w);

        void start(Worker w) {}

        @Override
        public final Result tick(Worker w) {
            if (started < 0) {
                started = w.now();
                // Diggers walk to a distraction like anyone else: they don't tunnel after a sheep.
                if (w.job.movement() == Job.Movement.DIG) w.moveAs = Job.Movement.WALK;
                start(w);
            }
            if (w.now() - started >= length || !w.e.isLoaded()) return Result.DONE;
            if ((w.now() - started) % 20 == 0 && threatened(w)) return Result.DONE;   // back to it: there's trouble
            return step(w);
        }

        /** Walks toward a spot. */
        Worker.Move walkNear(Worker w, Vector to, double reach) {
            return walkNear(w, to, reach, false);
        }

        /** Walks toward a spot; a moving one (an animal, a person) is planned again every few seconds and when it moves. */
        Worker.Move walkNear(Worker w, Vector to, double reach, boolean moving) {
            if (goal == null || goalAt.distanceSquared(to) > 2.25 || (moving && w.now() - goalSince > 60)) {
                goal = Pathfinder.near(to.getX(), to.getY(), to.getZ(), reach, 3, 2);
                goalAt = to.clone();
                goalSince = w.now();
            }
            return w.walk(goal, null);
        }

        @Override
        public String status(Worker w) {
            return label + ": " + doing(w);
        }

        @Override
        public boolean idle() {
            return true;
        }

        @Override
        public void end(Worker w) {
            w.moveAs = null;
            pose(w, Pose.STANDING);
            // Gave up straight away (couldn't get there): try again soon. Otherwise, back to focusing for a while.
            w.calmDown(started >= 0 && w.now() - started < 60);
        }
    }

    /** Follows an animal it loves round, hearts and all. */
    static final class Follow extends Distraction {
        private final LivingEntity animal;
        private final String what;

        Follow(LivingEntity animal, int length) {
            super(length);
            this.animal = animal;
            this.what = animal.getType().name().toLowerCase().replace('_', ' ');
        }

        @Override
        String doing(Worker w) {
            return "following " + aName(what);
        }

        @Override
        Result step(Worker w) {
            if (!animal.isValid() || animal.getWorld() != w.world()) return Result.DONE;
            Location at = animal.getLocation();
            double d2 = at.distanceSquared(w.e.location);
            if (d2 > 24 * 24 || !loaded(w.world(), at.getX(), at.getZ())) return Result.DONE;   // lost it
            if (!inReach(w, at.getX(), at.getZ())) return Result.DONE;   // it wandered off: let it go
            if (d2 > 2.5 * 2.5 && walkNear(w, at.toVector(), 2.0, true) == Worker.Move.FAILED) return Result.DONE;
            Location eyes = animal.getEyeLocation();
            w.lookAt(eyes.getX(), eyes.getY(), eyes.getZ());
            if (w.random().nextInt(50) == 0) {
                w.world().spawnParticle(Particle.HEART, w.e.location.clone().add(0, 2.1, 0), 1, 0.2, 0.1, 0.2);
            }
            return Result.RUNNING;
        }
    }

    /** Walks to the water, jumps in and paddles round, then climbs out. */
    static final class Swim extends Distraction {
        private final List<Block> pool;
        private final Block entry;
        private Vector shore;
        private Vector swimTo;
        private boolean in;

        private Swim(Block entry, List<Block> pool, int length) {
            super(length);
            this.entry = entry;
            this.pool = pool;
        }

        /** Water big enough to swim in (not a farm's irrigation ditch) within reach, or null. */
        static Swim find(Worker w, int length) {
            Block water = nearestBlock(w, b -> b.getType() == Material.WATER && b.getRelative(0, 1, 0).getType().isAir());
            if (water == null) return null;
            List<Block> pool = new ArrayList<>();
            Set<Block> seen = new HashSet<>();
            ArrayDeque<Block> queue = new ArrayDeque<>();
            queue.add(water);
            seen.add(water);
            while (!queue.isEmpty() && pool.size() < 64) {
                Block b = queue.poll();
                pool.add(b);
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    Block n = b.getRelative(d[0], 0, d[1]);
                    if (seen.add(n) && loaded(w.world(), n.getX(), n.getZ()) && inReach(w, n.getX(), n.getZ()) && n.getType() == Material.WATER
                            && n.getRelative(0, 1, 0).getType().isAir()) {
                        queue.add(n);
                    }
                }
            }
            return pool.size() < 9 ? null : new Swim(water, pool, length);
        }

        @Override
        String doing(Worker w) {
            return in ? "going for a swim" : "heading for a swim";
        }

        private static Vector surface(Block b) {
            return new Vector(b.getX() + 0.5, b.getY() + 0.4, b.getZ() + 0.5);
        }

        @Override
        Result step(Worker w) {
            if (!in) {
                Vector at = surface(entry);
                if (w.position().distanceSquared(at) > 2.2 * 2.2) {
                    Worker.Move m = walkNear(w, at, 1.8);
                    if (m == Worker.Move.FAILED) return Result.DONE;
                    if (m == Worker.Move.MOVING) return Result.RUNNING;
                }
                shore = w.position();
                in = true;
                w.moveTo(at.getX(), at.getY(), at.getZ());
                pose(w, Pose.SWIMMING);
                w.playSound(w.e.location, Sound.ENTITY_PLAYER_SPLASH, 0.6f, 1f);
                w.world().spawnParticle(Particle.SPLASH, w.e.location.clone().add(0, 0.5, 0), 20, 0.4, 0.1, 0.4);
                return Result.RUNNING;
            }
            if (entry.getType() != Material.WATER) return Result.DONE;   // drained
            if (swimTo == null || swimTo.distanceSquared(w.position()) < 0.3) {
                Block b = pool.get(w.random().nextInt(pool.size()));
                if (b.getType() != Material.WATER) return Result.RUNNING;
                swimTo = surface(b);
            }
            Vector pos = w.position(), d = swimTo.clone().subtract(pos);
            double len = d.length(), speed = 0.12;
            Vector next = len <= speed ? swimTo.clone() : pos.add(d.multiply(speed / len));
            w.lookAt(swimTo.getX(), swimTo.getY() + 1.62, swimTo.getZ());
            w.moveTo(next.getX(), next.getY(), next.getZ());
            pose(w, Pose.SWIMMING);
            if (w.now() % 10 == 0) w.world().spawnParticle(Particle.SPLASH, w.e.location.clone().add(0, 0.5, 0), 4, 0.3, 0.05, 0.3);
            if (w.now() % 30 == 0) w.playSound(w.e.location, Sound.ENTITY_PLAYER_SWIM, 0.4f, 1f);
            return Result.RUNNING;
        }

        @Override
        public void end(Worker w) {
            if (in && shore != null) {
                w.world().spawnParticle(Particle.SPLASH, w.e.location.clone().add(0, 0.5, 0), 12, 0.3, 0.1, 0.3);
                w.moveTo(shore.getX(), shore.getY(), shore.getZ());   // climbs out where it got in
            }
            super.end(w);
        }
    }

    /** Goes over to a block (flowers, a campfire) and enjoys it; a flower sniffer moves on to the next flower now and then. */
    static final class Admire extends Distraction {
        private Block block;
        private final String doing;
        private final Particle particle;
        private final boolean roams;
        /** Whether the block is still what it came for (the flower picked, the campfire put out: it's over). */
        private final java.util.function.Predicate<Block> still;
        private long arrivedAt = -1;

        Admire(Block block, int length, String doing, Particle particle, boolean roams, java.util.function.Predicate<Block> still) {
            super(length);
            this.block = block;
            this.doing = doing;
            this.particle = particle;
            this.roams = roams;
            this.still = still;
        }

        @Override
        String doing(Worker w) {
            return doing;
        }

        @Override
        Result step(Worker w) {
            if (!still.test(block)) return Result.DONE;
            Vector at = block.getLocation().toVector().add(new Vector(0.5, 0.3, 0.5));
            if (arrivedAt < 0) {
                Worker.Move m = walkNear(w, at, 1.6);
                if (m == Worker.Move.FAILED) return Result.DONE;
                if (m == Worker.Move.MOVING) return Result.RUNNING;
                arrivedAt = w.now();
            }
            w.lookAt(at.getX(), at.getY(), at.getZ());
            if (w.random().nextInt(40) == 0) {
                w.world().spawnParticle(particle, at.toLocation(w.world()).add(0, 0.4, 0), 3, 0.2, 0.2, 0.2, 0.01);
            }
            if (roams && w.now() - arrivedAt > 400) {
                Block next = nearestBlock(w, b -> Tag.FLOWERS.isTagged(b.getType()) && !b.equals(block)
                        && b.getLocation().distanceSquared(block.getLocation()) > 4);
                if (next != null) block = next;
                arrivedAt = next != null ? -1 : w.now();   // no other flower: look again in 20 seconds, not every tick
            }
            return Result.RUNNING;
        }
    }

    /** Stands still and gazes up at the night sky, slowly turning. */
    static final class Stargaze extends Distraction {
        private double angle;

        Stargaze(int length) {
            super(length);
        }

        @Override
        void start(Worker w) {
            angle = Math.toRadians(w.e.location.getYaw());
        }

        @Override
        String doing(Worker w) {
            return "gazing at the stars";
        }

        @Override
        Result step(Worker w) {
            if (!night(w.world()) || w.world().hasStorm()) return Result.DONE;
            angle += 0.004;
            Location l = w.e.location;
            w.lookAt(l.getX() - Math.sin(angle) * 6, l.getY() + 1.62 + 14, l.getZ() + Math.cos(angle) * 6);
            return Result.RUNNING;
        }
    }

    /** Spins and hops about in the rain. */
    static final class Dance extends Distraction {
        private Vector base;
        private float spin;

        Dance(int length) {
            super(length);
        }

        @Override
        void start(Worker w) {
            base = w.position();
            spin = w.e.location.getYaw();
        }

        @Override
        String doing(Worker w) {
            return "dancing in the rain";
        }

        @Override
        Result step(Worker w) {
            if (!w.world().hasStorm()) return Result.DONE;
            long t = w.now();
            double hop = Math.abs(Math.sin(t * 0.25)) * 0.45;
            spin += 9f;
            double rad = Math.toRadians(spin);
            w.lookAt(base.getX() - Math.sin(rad) * 3, base.getY() + 1.62, base.getZ() + Math.cos(rad) * 3);
            w.moveTo(base.getX(), base.getY() + hop, base.getZ());
            if (t % 12 == 0) w.swing();
            if (t % 40 == 0) w.world().spawnParticle(Particle.NOTE, w.e.location.clone().add(0, 2.2, 0), 1, 0.3, 0.1, 0.3);
            return Result.RUNNING;
        }

        @Override
        public void end(Worker w) {
            if (base != null) w.moveTo(base.getX(), base.getY(), base.getZ());
            super.end(w);
        }
    }

    /** Lies down for a nap wherever it is. */
    static final class Nap extends Distraction {

        Nap(int length) {
            super(length);
        }

        @Override
        String doing(Worker w) {
            return "taking a nap";
        }

        @Override
        Result step(Worker w) {
            pose(w, Pose.SLEEPING);
            return Result.RUNNING;
        }
    }

    private static final String[] SMALL_TALK = {
            "Lovely day for it, isn't it?",
            "Did you see that sheep earlier? Magnificent.",
            "I've been meaning to ask: do you think the creepers are lonely?",
            "My cousin's a librarian, you know. Very particular about books.",
            "You ever wonder what's at the bottom of the world?",
            "I heard there's a village to the north with a golem that dances.",
            "Have you tried the bread around here? Life-changing.",
            "Between you and me, I think the chickens are plotting something.",
    };

    /** Wanders over to someone nearby (another employee or a player) for a chat. */
    static final class Chat extends Distraction {
        private final Entity mate;
        private boolean greeted;

        Chat(Entity mate, int length) {
            super(length);
            this.mate = mate;
        }

        @Override
        String doing(Worker w) {
            Employee other = w.plugin().manager().byEntity(mate);
            if (other != null) return "chatting with " + other.name;
            String who = mate instanceof Player p ? p.getName() : mate.customName() != null
                    ? net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(mate.customName()) : "someone";
            return "chatting with " + who;
        }

        @Override
        Result step(Worker w) {
            if (!mate.isValid() || mate.getWorld() != w.world()) return Result.DONE;
            Location at = mate.getLocation();
            double d2 = at.distanceSquared(w.e.location);
            if (d2 > 24 * 24) return Result.DONE;
            if (d2 > 3 * 3) {
                if (!inReach(w, at.getX(), at.getZ()) || walkNear(w, at.toVector(), 2.5, true) == Worker.Move.FAILED) return Result.DONE;
                return Result.RUNNING;
            }
            Location eyes = mate instanceof LivingEntity le ? le.getEyeLocation() : at.clone().add(0, 1.6, 0);
            w.lookAt(eyes.getX(), eyes.getY(), eyes.getZ());
            if (!greeted) {
                greeted = true;
                if (mate instanceof Player p) {
                    p.sendMessage(Msg.parse("<gold>" + w.e.name + "</gold><gray>: " + SMALL_TALK[w.random().nextInt(SMALL_TALK.length)]));
                }
            }
            if (w.random().nextInt(60) == 0) {
                w.swing();
                w.playSound(w.e.location, Sound.ENTITY_VILLAGER_AMBIENT, 0.5f, 1f);
            }
            return Result.RUNNING;
        }
    }

    /** Strolls off somewhere nearby, lost in thought, and looks about. */
    static final class Wander extends Distraction {
        private final Vector to;
        private boolean there;

        private Wander(Vector to, int length) {
            super(length);
            this.to = to;
        }

        static Wander find(Worker w, int length) {
            Location l = w.e.location;
            double a = w.random().nextDouble() * Math.PI * 2, dist = 14 + w.random().nextInt(12);
            double x = l.getX() + Math.cos(a) * dist, z = l.getZ() + Math.sin(a) * dist;
            if (!loaded(w.world(), x, z) || !inReach(w, x, z)) return null;
            Pathfinder pf = new Pathfinder(w.world(), BoundingBox.of(l, 32, 12, 32));
            Vector spot = pf.snap(x, l.getY(), z);
            return spot == null ? null : new Wander(spot, length);
        }

        @Override
        String doing(Worker w) {
            return "wandering off, lost in thought";
        }

        @Override
        Result step(Worker w) {
            if (!there) {
                Worker.Move m = walkNear(w, to, 2.0);
                if (m == Worker.Move.FAILED) return Result.DONE;
                if (m == Worker.Move.ARRIVED) there = true;
                return Result.RUNNING;
            }
            if (w.random().nextInt(60) == 0) {
                Location l = w.e.location;
                double a = w.random().nextDouble() * Math.PI * 2;
                w.lookAt(l.getX() + Math.cos(a) * 8, l.getY() + 1.62 + w.random().nextInt(4), l.getZ() + Math.sin(a) * 8);
            }
            return Result.RUNNING;
        }
    }
}
