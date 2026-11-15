package site.remotearc.employees.job;

import io.papermc.paper.entity.Shearable;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Goat;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Tameable;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import site.remotearc.employees.storage.Supply;
import site.remotearc.employees.util.Items;
import site.remotearc.employees.util.Pathfinder;
import site.remotearc.employees.util.WorkArea;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Looks after the animals in its pen (the work area): breeds each kind up to
 * the herd size with food from the backpack or supply, culls adults above it
 * (oldest first) with its sword at player attack speed, shears sheep, milks
 * cows and goats into empty buckets, and picks up eggs, wool, feathers and
 * meat. Never touches named, tamed, leashed or baby animals.
 */
final class RancherJob implements Job {

    private static final Set<Material> PRODUCE = EnumSet.noneOf(Material.class);

    static {
        PRODUCE.addAll(Tag.WOOL.getValues());
        for (String n : List.of("EGG", "BROWN_EGG", "BLUE_EGG", "FEATHER", "LEATHER", "BEEF", "COOKED_BEEF", "PORKCHOP",
                "COOKED_PORKCHOP", "CHICKEN", "COOKED_CHICKEN", "MUTTON", "COOKED_MUTTON", "RABBIT", "COOKED_RABBIT",
                "RABBIT_HIDE", "RABBIT_FOOT", "MILK_BUCKET", "ARMADILLO_SCUTE", "TURTLE_SCUTE")) {
            Material m = Material.matchMaterial(n);
            if (m != null) PRODUCE.add(m);
        }
    }

    private final Map<UUID, Long> milked = new HashMap<>();
    private String idle = "Counting the herd";

    @Override
    public Task next(Worker w) {
        WorkArea a = w.e.area;
        World world = w.world();
        if (a == null || !a.world().equals(world.getName())) {
            idle = "No work area in this world";
            return null;
        }
        Task pickup = PickupTask.find(w);
        if (pickup != null) return pickup;

        Set<EntityType> kinds = w.settings().ranchAnimals;
        int herd = w.e.herdSize > 0 ? w.e.herdSize : w.settings().herdSize;
        BoundingBox box = new BoundingBox(a.minX(), a.minY(), a.minZ(), a.maxX() + 1, a.maxY() + 1, a.maxZ() + 1);
        Map<EntityType, List<Animals>> byKind = new EnumMap<>(EntityType.class);
        // Dead ones linger for their death animation; counting them would cull one too many.
        for (Entity en : world.getNearbyEntities(box, en -> en instanceof Animals an && kinds.contains(en.getType()) && !an.isDead() && w.mayWork(en.getLocation()))) {
            byKind.computeIfAbsent(en.getType(), k -> new ArrayList<>()).add((Animals) en);
        }
        if (byKind.isEmpty()) {
            idle = "No animals in the pen";
            return null;
        }

        boolean produce = w.settings().rancherKeepBreeding;
        // 1. Too many: cull the oldest adult the rancher may touch. The herd size counts the young
        //    too, so the pen never holds more than that for long; producing, a few calves at a time
        //    push the herd over and the oldest grown-ups make way for them (that's the meat).
        int stuck = 0;
        for (Map.Entry<EntityType, List<Animals>> e : byKind.entrySet()) {
            List<Animals> all = e.getValue();
            long adults = all.stream().filter(Animals::isAdult).count();
            // Never down to fewer than two grown-ups, or nothing is left to breed.
            if (all.size() <= herd || adults <= 2) continue;
            Animals oldest = null;
            for (Animals an : all) {
                if (!mayCull(an) || w.isUnreachable(an.getLocation().toVector())) continue;
                if (oldest == null || an.getTicksLived() > oldest.getTicksLived()) oldest = an;
            }
            if (oldest != null) return new AnimalTask(oldest, Action.CULL);
            stuck += all.size() - herd;
        }
        // 2. Wool ready.
        for (List<Animals> list : byKind.values()) {
            for (Animals an : list) {
                if (an instanceof Shearable s && s.readyToBeSheared() && an.getType() == EntityType.SHEEP && mayTouch(an)
                        && !w.isUnreachable(an.getLocation().toVector())) {
                    return new AnimalTask(an, Action.SHEAR);
                }
            }
        }
        // 3. Milk, if there are empty buckets.
        if (Items.count(w.e.backpack, Material.BUCKET) > 0) {
            long now = w.now();
            milked.values().removeIf(t -> t < now);
            for (List<Animals> list : byKind.values()) {
                for (Animals an : list) {
                    boolean milkable = an.getType() == EntityType.COW || an.getType() == EntityType.MOOSHROOM || an instanceof Goat;
                    if (milkable && an.isAdult() && !milked.containsKey(an.getUniqueId()) && mayTouch(an)
                            && !w.isUnreachable(an.getLocation().toVector())) {
                        return new AnimalTask(an, Action.MILK);
                    }
                }
            }
        }
        // 4. Too few: feed two adults of a kind to breed them.
        boolean hungry = false;
        for (Map.Entry<EntityType, List<Animals>> e : byKind.entrySet()) {
            // Producing: keep a few young coming (a tenth of the herd over, at least two); otherwise just fill the herd.
            if (e.getValue().size() >= (produce ? herd + Math.max(2, herd / 10) : herd)) continue;
            List<Animals> ready = new ArrayList<>();
            for (Animals an : e.getValue()) if (canBreedNow(an) && mayTouch(an)) ready.add(an);
            long inLove = e.getValue().stream().filter(Animals::isLoveMode).count();
            if (ready.size() + inLove < 2 || ready.isEmpty()) continue;
            Material food = food(w, ready.getFirst());
            if (food == null) {
                hungry = true;
                continue;
            }
            Animals pick = partnered(w, ready, e.getValue());
            if (pick != null) return new AnimalTask(pick, Action.FEED);
        }
        if (hungry && w.cooldownReady("rancher-fetch", 20 * 30)) {
            Task t = fetchFood(w, byKind);
            if (t != null) return t;
        }
        idle = stuck > 0 ? "Over the herd size by " + stuck + " but can't get to them (named, leashed, in love, or out of reach): " + summary(byKind, herd)
                : hungry ? "Needs food to breed the animals (wheat, carrots, seeds... in its supply)"
                : "Watching the herd (" + summary(byKind, herd) + ")";
        return null;
    }

    /**
     * The animal to feed next: one with a partner close by (in love already,
     * or ready to be fed), so the two can find each other. With several pens
     * in the area, animals in different pens are never paired up.
     */
    private static Animals partnered(Worker w, List<Animals> ready, List<Animals> all) {
        Animals best = null;
        double bestD = 8 * 8;
        for (Animals an : ready) {
            if (w.isUnreachable(an.getLocation().toVector())) continue;
            for (Animals other : all) {
                if (other == an || !(other.isLoveMode() || ready.contains(other))) continue;
                if (!other.getWorld().equals(an.getWorld())) continue;
                double d = other.getLocation().distanceSquared(an.getLocation());
                if (other.isLoveMode()) d -= 4;   // finish a pair before starting another
                if (d < bestD && samePen(an, other)) {
                    bestD = d;
                    best = an;
                }
            }
        }
        return best;
    }

    /** Nothing solid (a fence, a wall, a closed gate) between them at leg height. */
    private static boolean samePen(Animals a, Animals b) {
        org.bukkit.Location from = a.getLocation().add(0, 0.4, 0), to = b.getLocation().add(0, 0.4, 0);
        Vector dir = to.toVector().subtract(from.toVector());
        double dist = dir.length();
        if (dist < 0.1) return true;
        return a.getWorld().rayTraceBlocks(from, dir.normalize(), dist, org.bukkit.FluidCollisionMode.NEVER, true) == null;
    }

    private static String summary(Map<EntityType, List<Animals>> byKind, int herd) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<EntityType, List<Animals>> e : byKind.entrySet()) {
            long young = e.getValue().stream().filter(an -> !an.isAdult()).count();
            parts.add(e.getValue().size() + "/" + herd + " " + e.getKey().name().toLowerCase().replace('_', ' ') + (young > 0 ? " (" + young + " young)" : ""));
        }
        return String.join(", ", parts);
    }

    private static boolean mayTouch(Animals an) {
        if (an.customName() != null || an.isLeashed() || an.isInsideVehicle() || !an.getPassengers().isEmpty()) return false;
        return !(an instanceof Tameable t && t.isTamed());
    }

    private static boolean mayCull(Animals an) {
        return mayTouch(an) && an.isAdult() && !an.isLoveMode() && !an.isDead();
    }

    private static boolean canBreedNow(Animals an) {
        return an.isAdult() && an.getAge() == 0 && !an.isLoveMode() && an.canBreed();
    }

    /** A breeding food for this animal from the backpack. */
    private static Material food(Worker w, Animals an) {
        for (ItemStack s : w.e.backpack.getStorageContents()) {
            if (Items.isPlain(s) && an.isBreedItem(s.getType())) return s.getType();
        }
        return null;
    }

    private Task fetchFood(Worker w, Map<EntityType, List<Animals>> byKind) {
        Map<Material, Integer> avail = Supply.available(w.plugin(), w.e);
        Map<Material, Integer> want = new EnumMap<>(Material.class);
        for (List<Animals> list : byKind.values()) {
            Animals sample = list.getFirst();
            for (Map.Entry<Material, Integer> e : avail.entrySet()) {
                if (sample.isBreedItem(e.getKey())) {
                    want.put(e.getKey(), Math.min(e.getValue(), 64));
                    break;
                }
            }
        }
        return FetchTask.create(w, want, "animal feed");
    }

    // ── tasks ─────────────────────────────────────────────────────────────

    private enum Action { CULL, SHEAR, MILK, FEED }

    /** Follows an animal (it wanders, and runs when hit) until within reach, then acts on it. */
    private final class AnimalTask implements Task {

        private final LivingEntity animal;
        private final Action action;
        private Pathfinder.Goal goal;
        private Vector goalAt;
        private int timer;
        private int ticks;

        AnimalTask(LivingEntity animal, Action action) {
            this.animal = animal;
            this.action = action;
        }

        @Override
        public Result tick(Worker w) {
            if (!animal.isValid() || animal.isDead()) return action == Action.CULL ? Result.DONE : Result.FAILED;
            if (++ticks > 20 * 60) return Result.FAILED;
            Location at = animal.getLocation();
            double reach = action == Action.CULL ? 2.8 : 2.5;
            if (at.distanceSquared(w.e.location) > reach * reach || Math.abs(at.getY() - w.e.location.getY()) > 2) {
                if (goal == null || goalAt.distanceSquared(at.toVector()) > 2.25) {
                    goalAt = at.toVector();
                    goal = Pathfinder.near(at.getX(), at.getY(), at.getZ(), reach - 0.6, 1.5, 1.5);
                }
                switch (w.walk(goal, goalAt)) {
                    case FAILED -> {
                        w.markUnreachable(goalAt);
                        return Result.FAILED;
                    }
                    case ARRIVED -> goal = null;
                    default -> { }
                }
                return Result.RUNNING;
            }
            w.lookAt(at.getX(), at.getY() + animal.getHeight() * 0.6, at.getZ());
            if (++timer < interval(w)) return Result.RUNNING;
            timer = 0;
            w.swing();
            switch (action) {
                case CULL -> {
                    animal.damage(Combat.damage(w), w.e.entity);
                    if (animal.isDead() || !animal.isValid()) {
                        w.e.stats.animalsCulled++;
                        w.plugin().manager().markDirty();
                        return Result.DONE;
                    }
                    return Result.RUNNING;
                }
                case SHEAR -> {
                    if (animal instanceof Shearable s && s.readyToBeSheared()) {
                        s.shear(Sound.Source.NEUTRAL);
                        w.e.stats.sheepSheared++;
                        w.plugin().manager().markDirty();
                    }
                }
                case MILK -> {
                    if (Items.take(w.e.backpack, Material.BUCKET, 1) == 1) {
                        w.stash(List.of(new ItemStack(Material.MILK_BUCKET)));
                        w.playSound(at, org.bukkit.Sound.ENTITY_COW_MILK, 0.8f, 1f);
                        milked.put(animal.getUniqueId(), w.now() + 20 * 60 * 2);
                    }
                }
                case FEED -> {
                    if (animal instanceof Animals an && canBreedNow(an)) {
                        Material food = food(w, an);
                        if (food != null && Items.take(w.e.backpack, food, 1) == 1) {
                            an.setLoveModeTicks(600);
                            an.setBreedCause(w.e.owner);
                            animal.getWorld().spawnParticle(Particle.HEART, at.clone().add(0, animal.getHeight() + 0.3, 0), 3, 0.3, 0.2, 0.3);
                            w.playSound(at, org.bukkit.Sound.ENTITY_GENERIC_EAT, 0.6f, 1f);
                            w.e.stats.animalsBred++;
                            w.plugin().manager().markDirty();
                        }
                    }
                }
            }
            return Result.DONE;
        }

        /** Ticks between actions: a sword swing recharges in 12.5 ticks; a right-click takes 4. */
        private int interval(Worker w) {
            return action == Action.CULL ? Combat.interval(w) : w.clickTicks();
        }

        @Override
        public String status(Worker w) {
            String kind = animal.getType().name().toLowerCase().replace('_', ' ');
            return switch (action) {
                case CULL -> "Butchering a " + kind;
                case SHEAR -> "Shearing a sheep";
                case MILK -> "Milking a " + kind;
                case FEED -> "Feeding a " + kind;
            };
        }
    }

    // ── job hooks ─────────────────────────────────────────────────────────

    @Override
    public String detail(Worker w) {
        return "Herd size: " + (w.e.herdSize > 0 ? w.e.herdSize : w.settings().herdSize) + " of each kind";
    }

    @Override
    public String idleStatus(Worker w) {
        return idle;
    }

    @Override
    public boolean isReserved(Material m) {
        return m == Material.BUCKET || BREED_FOODS.contains(m);
    }

    private static final Set<Material> BREED_FOODS = EnumSet.of(Material.WHEAT, Material.WHEAT_SEEDS, Material.CARROT,
            Material.POTATO, Material.BEETROOT, Material.BEETROOT_SEEDS, Material.MELON_SEEDS, Material.PUMPKIN_SEEDS,
            Material.TORCHFLOWER_SEEDS, Material.PITCHER_POD, Material.DANDELION, Material.GOLDEN_CARROT, Material.HAY_BLOCK);

    @Override
    public boolean collects(Material m) {
        return PRODUCE.contains(m);
    }
}
