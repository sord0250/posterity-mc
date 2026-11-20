package site.remotearc.employees.job;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.entity.memory.MemoryKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import site.remotearc.employees.Employee;
import site.remotearc.employees.util.BlockPos;
import site.remotearc.employees.util.Items;
import site.remotearc.employees.util.Pathfinder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * A trader looking after its trading hall's stalls: fetches a villager for
 * each empty one (leading it there), ties it to the stall's workstation
 * (giving it that profession if it has never been traded with), and rerolls
 * its trades until it offers what the stall asks for.
 */
public final class Stalls {

    private Stalls() {}

    /** How far from a stall it looks for villagers to bring. */
    private static final int RANGE = 48;
    /** Rerolls before giving up on a stall's wanted trade. */
    private static final int MAX_TRIES = 400;

    /** Villagers it couldn't get to, and until when it leaves them be (so it doesn't keep trying the same walled-in one). */
    private static final Map<UUID, Long> UNREACHABLE = new HashMap<>();

    /** Stalls it found no way to, and until when it leaves them be. */
    private static final Map<Long, Long> NO_WAY = new HashMap<>();

    /** Per stall (stand position key): rerolls so far. */
    private static final Map<Long, Integer> TRIES = new HashMap<>();

    public static final Map<Material, Villager.Profession> WORKSTATIONS = new HashMap<>();

    static {
        WORKSTATIONS.put(Material.BLAST_FURNACE, Villager.Profession.ARMORER);
        WORKSTATIONS.put(Material.SMOKER, Villager.Profession.BUTCHER);
        WORKSTATIONS.put(Material.CARTOGRAPHY_TABLE, Villager.Profession.CARTOGRAPHER);
        WORKSTATIONS.put(Material.BREWING_STAND, Villager.Profession.CLERIC);
        WORKSTATIONS.put(Material.COMPOSTER, Villager.Profession.FARMER);
        WORKSTATIONS.put(Material.BARREL, Villager.Profession.FISHERMAN);
        WORKSTATIONS.put(Material.FLETCHING_TABLE, Villager.Profession.FLETCHER);
        WORKSTATIONS.put(Material.CAULDRON, Villager.Profession.LEATHERWORKER);
        WORKSTATIONS.put(Material.WATER_CAULDRON, Villager.Profession.LEATHERWORKER);
        WORKSTATIONS.put(Material.LAVA_CAULDRON, Villager.Profession.LEATHERWORKER);
        WORKSTATIONS.put(Material.POWDER_SNOW_CAULDRON, Villager.Profession.LEATHERWORKER);
        WORKSTATIONS.put(Material.LECTERN, Villager.Profession.LIBRARIAN);
        WORKSTATIONS.put(Material.STONECUTTER, Villager.Profession.MASON);
        WORKSTATIONS.put(Material.LOOM, Villager.Profession.SHEPHERD);
        WORKSTATIONS.put(Material.SMITHING_TABLE, Villager.Profession.TOOLSMITH);
        WORKSTATIONS.put(Material.GRINDSTONE, Villager.Profession.WEAPONSMITH);
    }

    static Location standAt(World w, Employee.Stall st) {
        return new Location(w, st.stand.x() + 0.5, st.stand.y(), st.stand.z() + 0.5);
    }

    /** The profession the stall's workstation gives, or null (none, or it's gone). */
    static Villager.Profession profession(World w, Employee.Stall st) {
        if (st.workstation != null) {
            Villager.Profession p = WORKSTATIONS.get(st.workstation.block(w).getType());
            if (p != null) return p;
        }
        return byName(st.profession);
    }

    /** A profession by its name ("librarian"), or null. */
    public static Villager.Profession byName(String name) {
        if (name == null) return null;
        return org.bukkit.Registry.VILLAGER_PROFESSION.get(org.bukkit.NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT).trim()));
    }

    /** The block that gives a profession (a lectern for a librarian). */
    public static Material workstationFor(Villager.Profession p) {
        if (p == Villager.Profession.LEATHERWORKER) return Material.CAULDRON;
        for (Map.Entry<Material, Villager.Profession> e : WORKSTATIONS.entrySet()) if (e.getValue() == p) return e.getKey();
        return null;
    }

    public static Villager inStallPublic(World w, Employee.Stall st) {
        return inStall(w, st);
    }

    /** The villager standing in the stall, or null. */
    static Villager inStall(World w, Employee.Stall st) {
        Location at = standAt(w, st);
        for (Entity en : w.getNearbyEntities(at, 0.8, 1.2, 0.8, en -> en instanceof Villager)) {
            if (Math.abs(en.getLocation().getY() - at.getY()) < 1.2) return (Villager) en;
        }
        return null;
    }

    /** Whether one of the villager's offers sells what's wanted ("mending", "protection 4", "emerald"...). */
    public static boolean offers(Villager v, String want) {
        String[] parts = want.toLowerCase(Locale.ROOT).trim().split("[ :]+");
        String key = parts[0];
        int level = parts.length > 1 ? parse(parts[1]) : 0;
        for (MerchantRecipe r : v.getRecipes()) {
            ItemStack out = r.getResult();
            if (out.getType().getKey().getKey().equals(key)) return true;
            if (out.getItemMeta() instanceof EnchantmentStorageMeta book) {
                for (Map.Entry<Enchantment, Integer> en : book.getStoredEnchants().entrySet()) {
                    if (en.getKey().getKey().getKey().equals(key) && en.getValue() >= level) return true;
                }
            }
        }
        return false;
    }

    private static int parse(String s) {
        return switch (s) {
            case "i" -> 1;
            case "ii" -> 2;
            case "iii" -> 3;
            case "iv" -> 4;
            case "v" -> 5;
            default -> {
                try {
                    yield Integer.parseInt(s);
                } catch (NumberFormatException ex) {
                    yield 0;
                }
            }
        };
    }

    /** The next thing to do for the stalls, or null. Sets the idle text when it's waiting on one. */
    static Task work(Worker w, java.util.function.Consumer<String> idle) {
        World world = w.world();
        List<Employee.Stall> stalls = w.e.stalls;
        java.util.Set<UUID> placed = new java.util.HashSet<>();
        for (Employee.Stall st : stalls) {
            if (!world.isChunkLoaded(st.stand.x() >> 4, st.stand.z() >> 4)) continue;
            Villager v = inStall(world, st);
            if (v != null) placed.add(v.getUniqueId());
        }
        // Villagers you asked it to move somewhere else.
        while (!w.e.moveQueue.isEmpty() && w.e.moveTo != null) {
            Entity en = org.bukkit.Bukkit.getEntity(w.e.moveQueue.peekFirst());
            if (!(en instanceof Villager v) || !v.isValid() || v.getWorld() != world) {
                w.e.moveQueue.pollFirst();
                continue;
            }
            if (v.getLocation().distanceSquared(w.e.moveTo) < 4 * 4) {
                w.e.moveQueue.pollFirst();
                continue;
            }
            w.e.moveQueue.pollFirst();
            return new LeadTask(null, v, 0, w.e.moveTo);
        }
        for (int i = 0; i < stalls.size(); i++) {
            Employee.Stall st = stalls.get(i);
            if (!world.isChunkLoaded(st.stand.x() >> 4, st.stand.z() >> 4)) continue;
            Villager.Profession job = profession(world, st);
            Villager v = inStall(world, st);
            if (v == null) {
                if (!w.cooldownReady("stall-look-" + st.stand.key(), 100)) continue;
                Long noWay = NO_WAY.get(st.stand.key());
                if (noWay != null && noWay > System.currentTimeMillis()) {
                    idle.accept("Can't find a way to stall " + (i + 1) + "; trying again shortly");
                    continue;
                }
                // Have the job block and trapdoor to shut it in with before fetching it, or it'd just walk back out.
                Task ready = ready(w, st, job, i + 1, idle);
                if (ready != null) return ready;
                if (!readyToShut(w, st, job)) continue;
                Villager pick = candidate(w, world, st, job, placed);
                if (pick == null) {
                    idle.accept("No villager to bring to stall " + (i + 1));
                    continue;
                }
                placed.add(pick.getUniqueId());
                return new LeadTask(st, pick, i + 1, null);
            }
            // In: shut it in and put its workstation down, as you would.
            Task shut = shutIn(w, st, v, job, i + 1);
            if (shut != null) return shut;
            // Point it at this stall's job block, as one would be by a job block put down right in front of it:
            // it claims that one (the game's own way, so it sticks) rather than one farther off.
            boolean fresh = v.getVillagerExperience() == 0;
            if (st.workstation != null && WORKSTATIONS.containsKey(st.workstation.block(world).getType())) {
                Location ws = new Location(world, st.workstation.x(), st.workstation.y(), st.workstation.z());
                Location site = v.getMemory(MemoryKey.JOB_SITE);
                boolean here = site != null && site.getBlockX() == ws.getBlockX() && site.getBlockY() == ws.getBlockY() && site.getBlockZ() == ws.getBlockZ();
                if (!here && fresh) {
                    if (site != null) v.setMemory(MemoryKey.JOB_SITE, null);   // let go of one farther off
                    v.setMemory(MemoryKey.POTENTIAL_JOB_SITE, ws);
                    idle.accept("Waiting for stall " + (i + 1) + "'s villager to take its job");
                    continue;
                }
            }
            if (st.want != null && fresh && v.getProfession() != Villager.Profession.NONE && !offers(v, st.want)) {
                int tries = TRIES.getOrDefault(st.stand.key(), 0);
                if (tries >= MAX_TRIES) {
                    idle.accept("Gave up rerolling stall " + (i + 1) + " for " + st.want + " after " + MAX_TRIES + " tries");
                    continue;
                }
                return new StallTask(st, v, i + 1, true);
            }
            if (st.want != null && offers(v, st.want)) {
                w.plugin().manager().notifyOwner(w.e, "<green>" + w.e.name + " found " + st.want + " at stall " + (i + 1)
                        + ". Trade with it once to lock the offer in (rerolling only works on villagers nobody has traded with).", true);
                st.want = null;
                TRIES.remove(st.stand.key());
                w.plugin().manager().markDirty();
            }
        }
        return null;
    }

    /** Whether it holds what shutting this stall needs (or the stall needs nothing). */
    private static boolean readyToShut(Worker w, Employee.Stall st, Villager.Profession job) {
        World world = w.world();
        Material station = job == null ? null : workstationFor(job);
        boolean needStation = st.workstation != null && station != null && st.workstation.block(world).isReplaceable();
        boolean needCap = st.door != null && st.door.block(world).isReplaceable();
        if (needStation && Items.count(w.e.backpack, station) == 0) return false;
        if (needCap) {
            boolean any = false;
            for (Material m : org.bukkit.Tag.WOODEN_TRAPDOORS.getValues()) if (Items.count(w.e.backpack, m) > 0) any = true;
            if (!any) return false;
        }
        return true;
    }

    /**
     * Gets the job block and trapdoor a stall needs into the backpack: from
     * storage, or crafted from what's in storage (planks into a trapdoor,
     * a lectern from planks and a bookshelf). Null when there's nothing to
     * do about it now (in hand already, or nothing to make it from: it says so).
     */
    private static Task ready(Worker w, Employee.Stall st, Villager.Profession job, int number, java.util.function.Consumer<String> idle) {
        World world = w.world();
        Material station = job == null ? null : workstationFor(job);
        List<Material> want = new ArrayList<>();
        if (st.workstation != null && station != null && st.workstation.block(world).isReplaceable() && !have(w, station)) want.add(station);
        if (st.door != null && st.door.block(world).isReplaceable() && trapdoor(w) == null) want.add(Material.SPRUCE_TRAPDOOR);
        if (st.workstation != null && station == null && st.workstation.block(world).isReplaceable()) {
            idle.accept("Stall " + number + " has no job set: /employees stalls " + w.e.name + " job " + number + " <job>");
        }
        for (Material m : want) {
            Map<Material, Integer> spare = site.remotearc.employees.storage.Supply.available(w.plugin(), w.e);
            for (ItemStack it : w.e.backpack.getStorageContents()) if (it != null) spare.merge(it.getType(), it.getAmount(), Integer::sum);
            Crafting.Plan plan = null;
            if (m == Material.SPRUCE_TRAPDOOR) {
                for (Material t : org.bukkit.Tag.WOODEN_TRAPDOORS.getValues()) {
                    plan = Crafting.plan(t, 1, 1, spare, w.e.craftNever, 5);
                    if (plan != null) break;
                }
            } else {
                plan = Crafting.plan(m, 1, 1, spare, w.e.craftNever, 5);
            }
            if (plan == null) {
                String what = m == Material.SPRUCE_TRAPDOOR ? "a trapdoor" : "a " + Items.name(m);
                idle.accept("Needs " + what + " for stall " + number);
                if (w.cooldownReady("stall-need-" + st.stand.key() + m, 20 * 600)) {
                    w.plugin().manager().notifyOwner(w.e, "<yellow>" + w.e.name + " needs " + what + " to shut a villager into stall " + number
                            + ": put one in its storage (or what it's made from). It won't fetch a villager for it till then.", false);
                }
                return null;
            }
            // Take what it's made from out of storage, then craft it.
            Map<Material, Integer> short_ = new java.util.EnumMap<>(Material.class);
            plan.raw().forEach((r, n) -> {
                int in = Items.count(w.e.backpack, r);
                if (n > in) short_.put(r, n - in);
            });
            if (!short_.isEmpty()) w.stash(site.remotearc.employees.storage.Supply.takeRemote(w.plugin(), w.e, short_));
            return new CraftTask(plan.steps(), "for stall " + number);
        }
        return null;
    }

    /** A trapdoor in the backpack, or one fetched from storage; null if there's none. */
    private static Material trapdoor(Worker w) {
        for (Material m : org.bukkit.Tag.WOODEN_TRAPDOORS.getValues()) if (Items.count(w.e.backpack, m) > 0) return m;
        for (Material m : org.bukkit.Tag.WOODEN_TRAPDOORS.getValues()) {
            List<ItemStack> got = site.remotearc.employees.storage.Supply.takeRemote(w.plugin(), w.e, new java.util.EnumMap<>(Map.of(m, 1)));
            if (!got.isEmpty()) {
                w.stash(got);
                return m;
            }
        }
        return null;
    }

    /** A workstation of this kind in the backpack, or fetched from storage. */
    private static boolean have(Worker w, Material m) {
        if (Items.count(w.e.backpack, m) > 0) return true;
        List<ItemStack> got = site.remotearc.employees.storage.Supply.takeRemote(w.plugin(), w.e, new java.util.EnumMap<>(Map.of(m, 1)));
        w.stash(got);
        return !got.isEmpty();
    }

    /** A villager in its stall without its cap or workstation yet: a task to put them in, or null. */
    private static Task shutIn(Worker w, Employee.Stall st, Villager v, Villager.Profession job, int number) {
        World world = w.world();
        boolean needsCap = st.door != null && st.door.block(world).isReplaceable();
        if (job == null && v.getProfession() != Villager.Profession.NONE && v.getProfession() != Villager.Profession.NITWIT) job = v.getProfession();
        Material station = job == null ? null : workstationFor(job);
        boolean needsStation = st.workstation != null && station != null && WORKSTATIONS.get(st.workstation.block(world).getType()) == null
                && st.workstation.block(world).isReplaceable();
        if (!needsCap && !needsStation) return null;
        if (!w.cooldownReady("stall-shut-" + st.stand.key(), 40)) return null;
        Material cap = needsCap ? trapdoor(w) : null;
        if (needsStation && !have(w, station)) needsStation = false;
        if ((needsCap && cap == null) || (st.workstation != null && station != null && !needsStation
                && WORKSTATIONS.get(st.workstation.block(world).getType()) == null)) {
            if (w.cooldownReady("stall-need-" + st.stand.key(), 20 * 600)) {
                w.plugin().manager().notifyOwner(w.e, "<yellow>" + w.e.name + " needs " + (needsCap && cap == null ? "a trapdoor" : "")
                        + (needsCap && cap == null && station != null && !needsStation ? " and " : "")
                        + (station != null && !needsStation && st.workstation != null && WORKSTATIONS.get(st.workstation.block(world).getType()) == null ? "a " + Items.name(station) : "")
                        + " for stall " + number + " (put some in its storage).", false);
            }
        }
        if (cap == null && !needsStation) return null;
        return new ShutTask(st, number, cap, needsStation ? station : null);
    }

    /** Puts a stall's workstation down and the trapdoor under its roof, once its villager is in. */
    private static final class ShutTask implements Task {
        private final Employee.Stall st;
        private final int number;
        private final Material cap, station;
        private final Pathfinder.Goal goal;
        private final org.bukkit.util.Vector goalAt;
        private int ticks;

        ShutTask(Employee.Stall st, int number, Material cap, Material station) {
            this.st = st;
            this.number = number;
            this.cap = cap;
            this.station = station;
            BlockPos at = st.door != null ? st.door : st.workstation;
            this.goalAt = new org.bukkit.util.Vector(at.centerX(), at.centerY(), at.centerZ());
            this.goal = Pathfinder.reachBlock(null, at, Pathfinder.REACH - 0.5, false);
        }

        @Override
        public Result tick(Worker w) {
            if (++ticks > 20 * 60) return Result.FAILED;
            Worker.Move m = w.walk(goal, goalAt);
            if (m == Worker.Move.MOVING) return Result.RUNNING;
            placeShut(w, st, station, cap);
            return Result.DONE;
        }

        @Override
        public String status(Worker w) {
            return "Shutting stall " + number + "'s villager in";
        }
    }

    /** Puts the stall's job block (in its doorway) and then the trapdoor above it, from the backpack. */
    static void placeShut(Worker w, Employee.Stall st, Material station, Material cap) {
        World world = w.world();
        if (station != null && st.workstation != null) {
            Block b = st.workstation.block(world);
            if (b.isReplaceable() && Items.take(w.e.backpack, station, 1) == 1) {
                w.lookAt(st.workstation);
                w.swing();
                b.setType(station);
                w.plugin().blockLog().placed(w.e, b);
                site.remotearc.employees.build.FollowChanges.changedBy(w.e, b);
                world.playSound(b.getLocation(), b.getBlockData().getSoundGroup().getPlaceSound(), 1f, 0.9f);
            }
        }
        if (cap != null && st.door != null) {
            Block d = st.door.block(world);
            if (d.isReplaceable() && Items.take(w.e.backpack, cap, 1) == 1) {
                w.lookAt(st.door);
                w.swing();
                org.bukkit.block.data.type.TrapDoor t = (org.bukkit.block.data.type.TrapDoor) cap.createBlockData();
                t.setHalf(org.bukkit.block.data.Bisected.Half.TOP);
                t.setOpen(false);
                d.setBlockData(t, true);
                w.plugin().blockLog().placed(w.e, d);
                site.remotearc.employees.build.FollowChanges.changedBy(w.e, d);
                world.playSound(d.getLocation(), d.getBlockData().getSoundGroup().getPlaceSound(), 1f, 0.9f);
            }
        }
    }

    /** The nearest villager worth bringing: grown up, not in a stall, not named or leashed; unemployed or already the stall's profession. */
    private static Villager candidate(Worker w, World world, Employee.Stall st, Villager.Profession job, java.util.Set<UUID> placed) {
        Location at = standAt(world, st);
        Villager best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity en : world.getNearbyEntities(at, RANGE, 16, RANGE, en -> en instanceof Villager)) {
            Villager v = (Villager) en;
            if (!w.mayWork(v.getLocation())) continue;   // not in a no-work zone
            if (nearSomeoneElses(w, v.getLocation())) continue;   // close to another player's employee's home: likely theirs, not a free villager
            Long until = UNREACHABLE.get(v.getUniqueId());
            if (until != null && until > System.currentTimeMillis()) continue;
            // Already working somewhere (it has a workstation of its own, likely in its own pod): leave it there.
            Location site = v.getMemory(MemoryKey.JOB_SITE);
            if (site != null && (st.workstation == null || site.getBlockX() != st.workstation.x() || site.getBlockY() != st.workstation.y()
                    || site.getBlockZ() != st.workstation.z())) continue;
            if (placed.contains(v.getUniqueId()) || !v.isAdult() || v.customName() != null || v.isLeashed() || v.isInsideVehicle() || v.isSleeping()) continue;
            Villager.Profession p = v.getProfession();
            if (p == Villager.Profession.NITWIT) continue;
            boolean fits = p == Villager.Profession.NONE || (job != null && p == job) || (job == null && v.getVillagerExperience() == 0);
            if (!fits) continue;
            double d = v.getLocation().distanceSquared(at);
            if (d >= bestD) continue;
            if (!free(v)) continue;   // penned in (an iron farm, a breeder): left where it is
            if (d < bestD) {
                bestD = d;
                best = v;
            }
        }
        return best;
    }

    /**
     * No land claims mean no "someone else's base" to check directly, but a
     * villager standing right by another player's employee's home is
     * probably one they were already counting on: left alone, instead of
     * being leashed off to a stranger's trading hall.
     */
    private static boolean nearSomeoneElses(Worker w, Location at) {
        for (site.remotearc.employees.Employee other : w.plugin().manager().all()) {
            if (other.owner.equals(w.e.owner) || w.plugin().managers().trusts(other.owner, w.e.owner)) continue;
            if (other.home != null && other.home.getWorld() == at.getWorld() && other.home.distanceSquared(at) < 24 * 24) return true;
        }
        return false;
    }

    /**
     * Whether a villager could walk off somewhere: a path at least 4 blocks
     * out in some direction. One penned in (an iron farm, a breeder, a pod
     * of its own) can't, and is left where it is.
     */
    private static boolean free(Villager v) {
        Location at = v.getLocation();
        int[][] dirs = {{8, 0}, {-8, 0}, {0, 8}, {0, -8}};
        for (int[] d : dirs) {
            Location to = at.clone().add(d[0], 0, d[1]);
            com.destroystokyo.paper.entity.Pathfinder.PathResult r = v.getPathfinder().findPath(to);
            if (r != null && r.getFinalPoint() != null && r.getFinalPoint().distanceSquared(at) >= 4 * 4) return true;
        }
        return false;
    }

    /**
     * Walks to a villager and leads it on a lead: into a stall (it walks in
     * by itself while the employee waits at the way in, the cap taken off
     * first), or to a spot you picked. On the way it's only pulled along if
     * it's truly stuck (in a hole, no stairs): never into a stall.
     */
    private static final class LeadTask implements Task {
        private final Employee.Stall st;
        private final Villager v;
        private final int number;
        private final Location dest;
        private boolean leading, flying;
        private int ticks, stuck, waiting;
        private double lastGap = Double.MAX_VALUE;
        /** Where the villager was a moment ago: it's only stuck if it isn't moving (not just if it hasn't caught up). */
        private Location lastSpot;
        /** Where the employee has been since it took the lead: the way the villager is guided if it gets stuck. */
        private final java.util.ArrayDeque<Location> trail = new java.util.ArrayDeque<>();
        private Pathfinder.Goal goal;
        private org.bukkit.util.Vector goalAt;

        LeadTask(Employee.Stall st, Villager v, int number, Location dest) {
            this.st = st;
            this.v = v;
            this.number = number;
            this.dest = dest;
        }

        @Override
        public Result tick(Worker w) {
            if (!v.isValid() || v.isDead() || ++ticks > 20 * 180) return release(w, Result.DONE);
            if (!leading) {
                Location at = v.getLocation();
                if (at.distanceSquared(w.e.location) < 2.8 * 2.8) {
                    leading = true;
                    goal = null;
                    w.swing();
                    try {
                        if (w.e.isLoaded()) v.setLeashHolder(w.e.entity);
                    } catch (RuntimeException ignored) {
                        // some versions won't put villagers on a lead: it follows anyway
                    }
                    w.playSound(at, Sound.ITEM_LEAD_TIED, 0.6f, 1f);
                    w.moveAs = Job.Movement.WALK;   // on foot from here, so it can follow
                    return Result.RUNNING;
                }
                if (goal == null || goalAt.distanceSquared(at.toVector()) > 4) {
                    goalAt = at.toVector();
                    goal = Pathfinder.near(at.getX(), at.getY(), at.getZ(), 2.0, 2, 2);
                }
                if (w.walk(goal, goalAt) == Worker.Move.FAILED) {
                    UNREACHABLE.put(v.getUniqueId(), System.currentTimeMillis() + 10 * 60_000L);   // walled in: try another
                    return release(w, Result.DONE);
                }
                return Result.RUNNING;
            }
            Location target = st != null ? standAt(w.world(), st) : dest;
            if (waiting == 0) v.setFallDistance(0);   // lowered on the lead, not dropped
            Location me = w.e.location;
            if (trail.isEmpty() || trail.peekLast().distanceSquared(me) > 0.7 * 0.7) {
                trail.addLast(me.clone());
                while (trail.size() > 64) trail.pollFirst();
            }
            if (ticks % 10 == 0 && w.e.location.distanceSquared(v.getLocation()) > 2.5 * 2.5) v.getPathfinder().moveTo(w.e.location, 1.25);
            // Keep it close: it follows on its own; down a hole or up a ledge it can't take, it's hauled along
            // on the lead (gently, no fall damage), like lowering it down a shaft. Never left behind.
            double gap = v.getLocation().getWorld() == w.world() ? v.getLocation().distanceSquared(w.e.location) : Double.MAX_VALUE;
            boolean carried = false;
            if (waiting > 0) {
                // At the stall: it's off the lead and walking in; nothing to haul.
            } else if (gap == Double.MAX_VALUE || gap > 9 * 9) {
                hop(w, behind(w));   // somehow far behind (another world, a teleport, a glitch): back to its handler
                gap = 0;
            } else if (dangling()) {
                // Off the edge (carried over a drop, a ravine): it swings along under the employee on the lead
                // instead of hanging back, and lands softly where the employee comes down.
                carried = true;
                org.bukkit.util.Vector pull = me.toVector().subtract(new org.bukkit.util.Vector(0, 1.2, 0)).subtract(v.getLocation().toVector());
                double len = pull.length();
                if (len > 0.3) v.setVelocity(pull.multiply(Math.min(0.9, 0.35 * len) / len));
                stuck = 0;
            } else if (gap > 2.5 * 2.5) {
                // Walking after the employee at the same distance isn't stuck: only standing still while behind is.
                Location vat = v.getLocation();
                boolean still = lastSpot != null && lastSpot.getWorld() == vat.getWorld() && lastSpot.distanceSquared(vat) < 0.03 * 0.03;
                lastSpot = vat;
                stuck = still && gap > 3 * 3 ? stuck + 1 : Math.max(0, stuck - 2);
                if (stuck > 20 && gap > 6 * 6) {
                    // Well apart and not catching up (it wandered off, or slipped up to another floor): back to its handler.
                    hop(w, behind(w));
                    stuck = 0;
                    gap = 0;
                } else if (stuck > 20 && stuck % 4 == 0) {
                    // Stuck nearby (a hole it won't drop down, a ledge, a hatch): guided along the way the employee went,
                    // a block at a time from where it is, like being lowered down on the lead. No fall damage.
                    Location next = nextOnTrail();
                    hop(w, next != null ? next : behind(w));
                    stuck = 15;   // keep stepping while it's still behind
                }
            } else stuck = 0;
            lastGap = gap;
            try {
                if (waiting == 0 && !v.isLeashed() && w.e.isLoaded()) v.setLeashHolder(w.e.entity);   // the lead snapped: back on
            } catch (RuntimeException ignored) {
                // follows anyway
            }
            if (goal == null) {
                goalAt = target.toVector();
                goal = Pathfinder.near(target.getX(), target.getY(), target.getZ(), st != null ? 2.2 : 2.0, 3, 3);
            }
            // Don't get ahead: wait for it to catch up (it's pulled along when it's hanging off the lead).
            if (waiting == 0 && gap > (carried ? 5 * 5 : 3 * 3)) return Result.RUNNING;
            Worker.Move m = w.walk(goal, goalAt);
            if (m == Worker.Move.FAILED && !flying) {
                // No way on foot (a shaft, a drop it can't climb back up): fly, and pull it along when it's stuck.
                flying = true;
                w.moveAs = null;
                goal = null;
                return Result.RUNNING;
            }
            if (m == Worker.Move.FAILED) {
                // No way there at all: let the villager go where it is
                // and leave this stall be for a while.
                NO_WAY.put(st != null ? st.stand.key() : 0L, System.currentTimeMillis() + 3 * 60_000L);
                w.plugin().manager().events().add(w.e.owner, w.e.name, st != null ? "Can't find a way to stall " + number
                        + " (a gap a villager can't get through? a block in the way?)" : "Can't find a way to where the villagers should go", true);
                return release(w, Result.DONE);
            }
            if (m == Worker.Move.MOVING) return Result.RUNNING;
            if (st == null) {
                // Somewhere you picked: let it go there.
                w.world().spawnParticle(Particle.HAPPY_VILLAGER, v.getLocation().add(0, 1.8, 0), 4, 0.3, 0.2, 0.3, 0);
                return release(w, Result.DONE);
            }
            // At the stall: take the cap off if it's on, then the villager walks in by itself.
            if (st.door != null) {
                Block cap = st.door.block(w.world());
                if (cap.getBlockData() instanceof org.bukkit.block.data.type.TrapDoor && waiting == 0) {
                    w.lookAt(st.door);
                    w.swing();
                    w.stash(List.of(new ItemStack(cap.getType())));
                    w.plugin().blockLog().removing(w.e, cap);
                    cap.setType(Material.AIR);
                    site.remotearc.employees.build.FollowChanges.changedBy(w.e, cap);
                }
            }
            // Off the lead, and told where to go now and then (asking every tick would restart its path every tick).
            if (waiting == 0) {
                try {
                    if (v.isLeashed()) v.setLeashHolder(null);
                } catch (RuntimeException ignored) {
                    // wasn't on one
                }
            }
            if (waiting % 20 == 0) v.getPathfinder().moveTo(target, 0.8);
            // Not stepping in by itself: a nudge from behind, as you'd push one in.
            if (waiting > 40 && v.getLocation().distanceSquared(target) < 3 * 3) {
                org.bukkit.util.Vector push = target.toVector().subtract(v.getLocation().toVector());
                push.setY(0);
                double len = push.length();
                if (len > 0.05) {
                    v.setVelocity(push.multiply(Math.min(0.2, 0.12 * len) / len).setY(v.getVelocity().getY()));
                    if (waiting % 10 == 0) w.swing();
                }
            }
            if (v.getLocation().distanceSquared(target) < 0.6 * 0.6) {
                // In: plug the doorway with its job block and put the trapdoor over it straight away, before it turns round.
                Villager.Profession job = profession(w.world(), st);
                if (job == null && v.getProfession() != Villager.Profession.NONE && v.getProfession() != Villager.Profession.NITWIT) job = v.getProfession();
                if (job == null && st.workstation != null) {
                    w.plugin().manager().notifyOwner(w.e, "<yellow>Which job is stall " + number + " for? " + w.e.name + " doesn't know which job block to put down: "
                            + "<white>/employees stalls " + w.e.name + " job " + number + " librarian</white> (or another job).", false);
                }
                Material station = job == null ? null : workstationFor(job);
                if (station != null && (st.workstation == null || !st.workstation.block(w.world()).isReplaceable() || !have(w, station))) station = null;
                Material cap = st.door != null && st.door.block(w.world()).isReplaceable() ? trapdoor(w) : null;
                placeShut(w, st, station, cap);
                w.world().spawnParticle(Particle.HAPPY_VILLAGER, target.clone().add(0, 1.8, 0), 6, 0.3, 0.3, 0.3, 0);
                w.plugin().manager().events().add(w.e.owner, w.e.name, "Walked a villager into stall " + number, false);
                return release(w, Result.DONE);   // the cap and workstation go down next
            }
            if (++waiting > 20 * 20) {
                // It won't go in (the way in is blocked, or too tight): leave it be and try later.
                UNREACHABLE.put(v.getUniqueId(), System.currentTimeMillis() + 5 * 60_000L);
                return release(w, Result.DONE);
            }
            return Result.RUNNING;
        }

        /** Hanging in the air off the lead (not walking, swimming or climbing). */
        private boolean dangling() {
            if (v.isOnGround() || v.isInWater() || v.isInLava() || v.isClimbing() || !v.isLeashed()) return false;
            Block below = v.getLocation().getBlock().getRelative(org.bukkit.block.BlockFace.DOWN);
            // Only a real drop: stepping off a slab or a stair isn't being carried.
            return below.isPassable() && below.getRelative(org.bukkit.block.BlockFace.DOWN).isPassable();
        }

        /** The next spot along the employee's way after the one the villager is nearest (never back the way they came). */
        private Location nextOnTrail() {
            Location at = v.getLocation();
            int i = 0, best = -1;
            double bestD = 2.0 * 2.0;
            for (Location l : trail) {
                double d = l.getWorld() == at.getWorld() ? l.distanceSquared(at) : Double.MAX_VALUE;
                if (d < bestD) {
                    bestD = d;
                    best = i;
                }
                i++;
            }
            if (best < 0) return null;   // it's off the way they came: straight to the employee then
            for (int k = 0; k <= best; k++) trail.pollFirst();
            return trail.peekFirst();
        }

        /** Just behind the employee, where the villager is put back if they get separated. */
        private Location behind(Worker w) {
            Location me = w.e.location;
            Location prev = null;
            for (java.util.Iterator<Location> it = trail.descendingIterator(); it.hasNext(); ) {
                Location l = it.next();
                if (l.distanceSquared(me) > 0.9 * 0.9) {
                    prev = l;
                    break;
                }
            }
            return prev != null && prev.distanceSquared(me) < 2.5 * 2.5 ? prev : me;
        }

        /** Moves the villager there (the lead comes off for the move and goes back on), with no fall damage. */
        private void hop(Worker w, Location where) {
            Location to = where.clone();
            to.setYaw(v.getLocation().getYaw());
            to.setPitch(0);
            boolean leashed = v.isLeashed();
            try {
                if (leashed) v.setLeashHolder(null);
            } catch (RuntimeException ignored) {
                // wasn't on one
            }
            v.teleport(to);
            v.setVelocity(new org.bukkit.util.Vector());
            try {
                if (w.e.isLoaded()) v.setLeashHolder(w.e.entity);
            } catch (RuntimeException ignored) {
                // follows anyway
            }
            v.setFallDistance(0);
            trail.removeIf(l -> l.distanceSquared(to) < 0.8 * 0.8);
        }

        private Result release(Worker w, Result r) {
            w.moveAs = null;
            try {
                if (v.isValid() && v.isLeashed()) v.setLeashHolder(null);
            } catch (RuntimeException ignored) {
                // nothing to undo
            }
            return r;
        }

        @Override
        public void end(Worker w) {
            release(w, Result.DONE);
        }

        @Override
        public String status(Worker w) {
            if (st == null) return leading ? "Moving a villager" : "Going to get a villager to move";
            return leading ? (waiting > 0 ? "Walking a villager into stall " + number : "Leading a villager to stall " + number) : "Fetching a villager for stall " + number;
        }
    }

    /** At a stall: gives the villager the workstation's profession, or rerolls its trades (as breaking and replacing the workstation would). */
    private static final class StallTask implements Task {
        private final Employee.Stall st;
        private final Villager v;
        private final int number;
        private final boolean reroll;
        private int ticks, timer;
        private final Pathfinder.Goal goal;
        private final org.bukkit.util.Vector goalAt;

        StallTask(Employee.Stall st, Villager v, int number, boolean reroll) {
            this.st = st;
            this.v = v;
            this.number = number;
            this.reroll = reroll;
            BlockPos at = st.workstation != null ? st.workstation : st.stand;
            this.goalAt = new org.bukkit.util.Vector(at.centerX(), at.centerY(), at.centerZ());
            this.goal = Pathfinder.near(at.centerX(), at.y(), at.centerZ(), 3.5, 3, 3);
        }

        @Override
        public Result tick(Worker w) {
            if (!v.isValid() || ++ticks > 20 * 60) return Result.FAILED;
            if (goalAt.distanceSquared(w.e.location.toVector()) > 4.5 * 4.5) {
                Worker.Move m = w.walk(goal, goalAt);
                if (m == Worker.Move.MOVING) return Result.RUNNING;
                // Can't get closer (the hall's a maze): it does it from here.
            }
            if (++timer < 10) return Result.RUNNING;
            World world = w.world();
            Block ws = st.workstation != null ? st.workstation.block(world) : null;
            if (ws != null) {
                w.lookAt(st.workstation);
                world.playSound(ws.getLocation(), ws.getBlockData().getSoundGroup().getBreakSound(), 0.7f, 1f);
                world.spawnParticle(Particle.BLOCK, ws.getLocation().add(0.5, 0.6, 0.5), 10, 0.3, 0.3, 0.3, ws.getBlockData());
            }
            w.swing();
            if (!reroll) {
                Villager.Profession job = profession(world, st);
                if (job != null) v.setProfession(job);
            } else {
                v.setRecipes(new ArrayList<>());
                v.addTrades(2);
                TRIES.merge(st.stand.key(), 1, Integer::sum);
            }
            world.spawnParticle(Particle.HAPPY_VILLAGER, v.getLocation().add(0, 1.8, 0), 4, 0.3, 0.2, 0.3, 0);
            return Result.DONE;
        }

        @Override
        public String status(Worker w) {
            return reroll ? "Rerolling stall " + number + "'s trades for " + st.want + " (try " + (TRIES.getOrDefault(st.stand.key(), 0) + 1) + ")"
                    : "Giving the villager in stall " + number + " its job";
        }
    }

    /** "Stall 2: a librarian (fresh), wants mending" for lists. */
    public static String describe(World w, Employee.Stall st, int number) {
        StringBuilder sb = new StringBuilder("Stall " + number + " at " + st.stand.x() + " " + st.stand.y() + " " + st.stand.z());
        if (w != null && w.isChunkLoaded(st.stand.x() >> 4, st.stand.z() >> 4)) {
            Villager.Profession job = profession(w, st);
            sb.append(job != null ? " (" + site.remotearc.employees.util.Msg.titleCase(job.getKey().getKey()) + " station)" : st.workstation != null ? " (workstation missing)" : " (no workstation)");
            Villager v = inStall(w, st);
            sb.append(v == null ? ": empty" : ": " + site.remotearc.employees.util.Msg.titleCase(v.getProfession().getKey().getKey()) + (v.getVillagerExperience() == 0 ? " (never traded)" : ""));
        }
        if (st.want != null) sb.append(", looking for ").append(st.want);
        return sb.toString();
    }
}
