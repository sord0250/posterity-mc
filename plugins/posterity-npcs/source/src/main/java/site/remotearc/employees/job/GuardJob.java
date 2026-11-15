package site.remotearc.employees.job;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import site.remotearc.employees.util.BlockPos;
import site.remotearc.employees.util.Items;
import site.remotearc.employees.util.Pathfinder;
import site.remotearc.employees.util.WorkArea;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps its area clear of hostile mobs: hunts down any that come within 8
 * blocks of it and fights them with its sword at player attack speed. With
 * torches in its backpack it also lights up dark ground in the area, so fewer
 * mobs spawn. Mobs can't target employees (see EmployeeListener), so a
 * creeper it fights never blows up.
 *
 * With a bow (or crossbow) and arrows in its backpack it shoots mobs that are
 * more than a few blocks off, at player draw speed, and only when the shot is
 * clear: never past a player, a pet or another employee. Up close it uses its
 * sword. Arrows are used up (unless the bow has Infinity) and the bow wears.
 */
class GuardJob implements Job {

    private static final Set<Material> DROPS = EnumSet.noneOf(Material.class);

    static {
        for (String n : List.of("ROTTEN_FLESH", "BONE", "ARROW", "STRING", "SPIDER_EYE", "GUNPOWDER", "ENDER_PEARL", "SLIME_BALL",
                "MAGMA_CREAM", "PHANTOM_MEMBRANE", "BLAZE_ROD", "GHAST_TEAR", "PRISMARINE_SHARD", "PRISMARINE_CRYSTALS", "INK_SAC",
                "COPPER_INGOT", "IRON_INGOT", "CARROT", "POTATO", "GLASS_BOTTLE", "GLOWSTONE_DUST", "REDSTONE", "SUGAR", "STICK",
                "BREEZE_ROD")) {
            Material m = Material.matchMaterial(n);
            if (m != null) DROPS.add(m);
        }
    }

    private String idle = "Patrolling";

    /** Mobs being fought, and by which employee: so guards and bodyguards split up instead of all chasing one. */
    static final Map<UUID, UUID> CLAIMS = new HashMap<>();

    static boolean claimedByOther(Worker w, LivingEntity mob) {
        UUID by = CLAIMS.get(mob.getUniqueId());
        return by != null && !by.equals(w.e.id);
    }

    @Override
    public Task next(Worker w) {
        LivingEntity hitMe = w.attacker();
        if (hitMe != null) return new FightTask(hitMe, BoundingBox.of(w.e.location.toVector(), 20, 8, 20));
        WorkArea a = w.e.area;
        World world = w.world();
        if (a == null || !a.world().equals(world.getName())) {
            idle = "No work area in this world";
            return null;
        }
        WorkArea r = a.expand(8, 4);
        BoundingBox around = new BoundingBox(r.minX(), r.minY(), r.minZ(), r.maxX() + 1, r.maxY() + 1, r.maxZ() + 1);
        LivingEntity target = nearestMob(w, around);
        if (target != null) return new FightTask(target, around.clone().expand(4, 2, 4));

        Task pickup = PickupTask.find(w);
        if (pickup != null) return pickup;

        if (Items.count(w.e.backpack, Material.TORCH) > 0 && w.cooldownReady("dark-scan", 100)) {
            BlockPos dark = darkSpot(w, a);
            if (dark != null) return new PlaceTask(world, dark, Material.TORCH, "Lighting a dark spot");
        }
        int arrows = Items.count(w.e.backpack, Material.ARROW) + Items.count(w.e.backpack, Material.SPECTRAL_ARROW);
        idle = "Patrolling (" + w.e.stats.mobsKilled + " mobs dealt with" + (Bow.of(w) != null ? "; bow and " + arrows + " arrows" : "") + ")";
        return null;
    }

    /** The nearest hostile mob in (or within 8 blocks of) the area that it may fight. */
    static LivingEntity nearestMob(Worker w, BoundingBox box) {
        Location me = w.e.location;
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity en : w.world().getNearbyEntities(box, en -> en instanceof Enemy && en instanceof LivingEntity)) {
            LivingEntity mob = (LivingEntity) en;
            if (!fightable(w, mob) || w.isUnreachable(mob.getLocation().toVector())) continue;
            double d = mob.getLocation().distanceSquared(me) + (claimedByOther(w, mob) ? 20 * 20 : 0);   // rather one nobody's on
            if (d < bestD) {
                bestD = d;
                best = mob;
            }
        }
        return best;
    }

    static boolean fightable(Worker w, LivingEntity mob) {
        if (mob.isDead() || !mob.isValid() || mob.customName() != null || mob.isInvulnerable()) return false;
        if (w.settings().guardIgnore.contains(mob.getType())) return false;
        // Farm mobs: in a boat or minecart, leashed, or kept from despawning (name-tagged, picked something up).
        if (mob.isInsideVehicle() || mob.isLeashed() || !mob.getRemoveWhenFarAway()) return false;
        if (!w.outsideZones(mob.getLocation())) return false;   // in a no-work zone (a golem farm's zombie)
        Block at = mob.getLocation().getBlock();
        return !at.isLiquid();   // it doesn't swim after them
    }

    /** A spot on the ground in the area with no block light (where mobs can spawn), for a torch. */
    static BlockPos darkSpot(Worker w, WorkArea a) {
        World world = w.world();
        for (int i = 0; i < 40; i++) {
            int x = a.minX() + w.random().nextInt(a.width()), z = a.minZ() + w.random().nextInt(a.length());
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
            int y = Math.min(a.maxY(), world.getHighestBlockYAt(x, z)) + 1;
            if (y < a.minY() || y > a.maxY() + 1) continue;
            Block cell = world.getBlockAt(x, y, z), below = cell.getRelative(0, -1, 0);
            if (!cell.getType().isAir() || !below.getType().isOccluding() || below.isLiquid()) continue;
            if (org.bukkit.Tag.LEAVES.isTagged(below.getType())) continue;
            if (cell.getLightFromBlocks() > 0 || !w.mayWork(x, y, z)) continue;
            BlockPos p = BlockPos.of(cell);
            if (!w.isUnreachable(p)) return p;
        }
        return null;
    }

    // ── fighting ──────────────────────────────────────────────────────────

    /** Chases a mob and hits it at sword speed until it dies, escapes, or a minute passes. */
    static final class FightTask implements Task {

        private final LivingEntity mob;
        /** Where the chase ends: past this the mob has run off. */
        private final BoundingBox leash;
        private Pathfinder.Goal goal;
        private Vector goalAt;
        private int timer, ticks;

        FightTask(LivingEntity mob, BoundingBox leash) {
            this.mob = mob;
            this.leash = leash;
        }

        @Override
        public void end(Worker w) {
            CLAIMS.remove(mob.getUniqueId(), w.e.id);
            if (drawing > 0 || bowOut) holdSword(w);
        }

        private int drawing;
        private boolean bowOut;

        /** Draws (a full draw takes a second, like a player's) and looses an arrow, leading the target for the drop. */
        private void shoot(Worker w, Bow bow, Location at) {
            double tx = at.getX(), ty = at.getY() + mob.getHeight() * 0.55, tz = at.getZ();
            Location eye = w.e.location.clone().add(0, Pathfinder.EYE_HEIGHT, 0);
            double dist = Math.sqrt(eye.distanceSquared(new Location(eye.getWorld(), tx, ty, tz)));
            w.lookAt(tx, ty + 0.0035 * dist * dist, tz);
            if (!bowOut && w.e.isLoaded()) {
                w.e.entity.getEquipment().setItemInMainHand(bow.item.clone());
                bowOut = true;
            }
            if (drawing == 0 && w.e.isLoaded()) {
                try {
                    w.e.entity.startUsingItem(org.bukkit.inventory.EquipmentSlot.HAND);   // the drawing pose
                } catch (RuntimeException ignored) {
                    // not every build shows it; shooting works the same
                }
            }
            int draw = Math.max(4, (int) Math.round((bow.item.getType() == Material.CROSSBOW ? 25 : 20) / w.workSpeed()));
            if (++drawing < draw || !w.e.isLoaded()) return;
            drawing = 0;
            if (w.e.entity.hasActiveItem()) w.e.entity.clearActiveItem();
            Vector dir = new Vector(tx - eye.getX(), ty - eye.getY() + 0.0035 * dist * dist * 1.1, tz - eye.getZ()).normalize();
            org.bukkit.entity.AbstractArrow arrow = bow.spectral
                    ? w.e.entity.launchProjectile(org.bukkit.entity.SpectralArrow.class, dir.multiply(3.0))
                    : w.e.entity.launchProjectile(org.bukkit.entity.Arrow.class, dir.multiply(3.0));
            arrow.setDamage(2.0 + (bow.power > 0 ? 0.5 * (bow.power + 1) : 0));
            if (bow.flame) arrow.setFireTicks(100);
            arrow.setPickupStatus(bow.infinite ? org.bukkit.entity.AbstractArrow.PickupStatus.CREATIVE_ONLY
                    : org.bukkit.entity.AbstractArrow.PickupStatus.ALLOWED);
            w.playSound(eye, bow.item.getType() == Material.CROSSBOW ? org.bukkit.Sound.ITEM_CROSSBOW_SHOOT : org.bukkit.Sound.ENTITY_ARROW_SHOOT,
                    1f, 0.9f + w.random().nextFloat() * 0.2f);
            bow.used(w);
        }

        private void holdSword(Worker w) {
            drawing = 0;
            bowOut = false;
            if (w.e.isLoaded() && w.e.entity.hasActiveItem()) w.e.entity.clearActiveItem();
            if (w.e.isLoaded()) w.e.entity.getEquipment().setItemInMainHand(new org.bukkit.inventory.ItemStack(w.jobSettings().tool()));
        }

        @Override
        public Result tick(Worker w) {
            CLAIMS.putIfAbsent(mob.getUniqueId(), w.e.id);
            if (!mob.isValid() || mob.isDead()) {
                w.e.stats.mobsKilled++;
                w.plugin().manager().markDirty();
                return Result.DONE;
            }
            if (++ticks > 20 * 60 || !fightable(w, mob)) return Result.FAILED;
            Location at = mob.getLocation();
            if (!leash.contains(at.toVector())) return Result.DONE;   // it ran off
            if (mob instanceof Creeper c) {
                c.setIgnited(false);
            }
            double reach = 3.0;
            double d2 = at.distanceSquared(w.e.location);
            if (d2 > 4.0 * 4.0 && d2 < 24.0 * 24.0) {
                Bow bow = Bow.of(w);
                if (bow != null && clearShot(w, mob)) {
                    shoot(w, bow, at);
                    return Result.RUNNING;
                }
            }
            if (drawing > 0) holdSword(w);
            if (d2 > reach * reach || Math.abs(at.getY() - w.e.location.getY()) > 2.5) {
                w.sprint();   // closing in, like a player would
                if (goal == null || goalAt.distanceSquared(at.toVector()) > 2.25) {
                    goalAt = at.toVector();
                    goal = Pathfinder.near(at.getX(), at.getY(), at.getZ(), reach - 0.7, 2, 2);
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
            w.lookAt(at.getX(), at.getY() + mob.getHeight() * 0.6, at.getZ());
            if (++timer < Combat.interval(w)) return Result.RUNNING;
            timer = 0;
            w.swing();
            mob.damage(Combat.damage(w), w.e.entity);
            return Result.RUNNING;
        }

        @Override
        public String status(Worker w) {
            return "Fighting a " + mob.getType().name().toLowerCase().replace('_', ' ');
        }
    }

    // ── armour and health ─────────────────────────────────────────────────

    /** Every two seconds: put on better armour from the backpack, keep track of wear, heal a little. */
    @Override
    public void tick(Worker w) {
        if (w.now() % 40 != 0) return;
        wearArmor(w);
        if (w.e.isLoaded() && w.e.knockedOutUntil == 0 && System.currentTimeMillis() - w.e.lastHurt > 5000) {
            var max = w.e.entity.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            double cap = max == null ? 20 : max.getValue();
            if (w.e.entity.getHealth() < cap) w.e.entity.setHealth(Math.min(cap, w.e.entity.getHealth() + 1));
        }
    }

    /** Armour slot (0 boots .. 3 helmet) for an item, or -1. */
    static int armorSlot(Material m) {
        String n = m.name();
        if (n.endsWith("_BOOTS")) return 0;
        if (n.endsWith("_LEGGINGS")) return 1;
        if (n.endsWith("_CHESTPLATE")) return 2;
        if (n.endsWith("_HELMET")) return 3;
        return -1;
    }

    /** How good a piece is: by material, then enchantments. */
    static int armorRank(org.bukkit.inventory.ItemStack s) {
        if (s == null) return -1;
        String n = s.getType().name();
        int tier = n.startsWith("NETHERITE") ? 7 : n.startsWith("DIAMOND") ? 6 : n.startsWith("IRON") || n.startsWith("TURTLE") ? 5
                : n.startsWith("COPPER") ? 4 : n.startsWith("CHAINMAIL") ? 3 : n.startsWith("GOLDEN") ? 2 : 1;
        return tier * 100 + s.getEnchantments().size();
    }

    /** Keeps the record of what it wears in step with the entity (wear, breaks), and swaps in better pieces from the backpack. */
    static void wearArmor(Worker w) {
        var e = w.e;
        if (e.isLoaded()) {
            org.bukkit.inventory.ItemStack[] worn = e.entity.getEquipment().getArmorContents();
            for (int i = 0; i < 4; i++) {
                org.bukkit.inventory.ItemStack s = i < worn.length ? worn[i] : null;
                boolean had = e.armor[i] != null;
                e.armor[i] = s == null || s.getType().isAir() ? null : s.clone();
                if (had && e.armor[i] == null) w.plugin().manager().events().add(e.owner, e.name, "Its armour wore out", true);
            }
        }
        boolean changed = false;
        org.bukkit.inventory.ItemStack[] bag = e.backpack.getContents();
        for (int i = 0; i < bag.length; i++) {
            org.bukkit.inventory.ItemStack s = bag[i];
            if (s == null) continue;
            int slot = armorSlot(s.getType());
            if (slot < 0 || armorRank(s) <= armorRank(e.armor[slot])) continue;
            org.bukkit.inventory.ItemStack old = e.armor[slot];
            e.armor[slot] = s.clone();
            e.backpack.setItem(i, old);
            changed = true;
        }
        if (changed) {
            if (e.isLoaded()) {
                org.bukkit.inventory.ItemStack[] put = new org.bukkit.inventory.ItemStack[4];
                for (int i = 0; i < 4; i++) put[i] = e.armor[i] == null ? null : e.armor[i].clone();
                e.entity.getEquipment().setArmorContents(put);
                w.playSound(e.location, org.bukkit.Sound.ITEM_ARMOR_EQUIP_IRON, 0.8f, 1f);
            }
            w.plugin().manager().markDirty();
        }
    }

    // ── bows ──────────────────────────────────────────────────────────────

    /** The bow it would shoot with, and what it has to shoot. */
    static final class Bow {
        final org.bukkit.inventory.ItemStack item;
        final int slot;
        final boolean spectral, infinite, flame;
        final int power, unbreaking;

        private Bow(org.bukkit.inventory.ItemStack item, int slot, boolean spectral) {
            this.item = item;
            this.slot = slot;
            this.spectral = spectral;
            this.power = item.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.POWER);
            this.unbreaking = item.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.UNBREAKING);
            this.flame = item.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.FLAME) > 0;
            this.infinite = !spectral && item.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.INFINITY) > 0;
        }

        /** A bow or crossbow in the backpack, if there are arrows for it. */
        static Bow of(Worker w) {
            boolean arrows = Items.count(w.e.backpack, Material.ARROW) > 0;
            boolean spectral = !arrows && Items.count(w.e.backpack, Material.SPECTRAL_ARROW) > 0;
            if (!arrows && !spectral) return null;
            org.bukkit.inventory.ItemStack[] c = w.e.backpack.getContents();
            for (int i = 0; i < c.length; i++) {
                if (c[i] != null && (c[i].getType() == Material.BOW || c[i].getType() == Material.CROSSBOW)) return new Bow(c[i], i, spectral);
            }
            return null;
        }

        /** An arrow gone (unless Infinity), and wear on the bow (Unbreaking spares it some). */
        void used(Worker w) {
            if (!infinite) Items.take(w.e.backpack, spectral ? Material.SPECTRAL_ARROW : Material.ARROW, 1);
            if (w.random().nextInt(unbreaking + 1) != 0) return;
            org.bukkit.inventory.ItemStack bow = w.e.backpack.getItem(slot);
            if (bow == null || !(bow.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable d)) return;
            if (d.isUnbreakable()) return;
            d.setDamage(d.getDamage() + 1);
            if (d.getDamage() >= bow.getType().getMaxDurability()) {
                w.e.backpack.setItem(slot, null);
                w.playSound(w.e.location, org.bukkit.Sound.ENTITY_ITEM_BREAK, 0.8f, 1f);
                w.plugin().manager().events().add(w.e.owner, w.e.name, "Its bow broke", true);
            } else {
                bow.setItemMeta(d);
                w.e.backpack.setItem(slot, bow);
            }
        }
    }

    /** Nothing solid, and no player, pet or other employee, between its eyes and the mob. */
    static boolean clearShot(Worker w, LivingEntity mob) {
        if (!w.e.isLoaded()) return false;
        Location eye = w.e.entity.getEyeLocation();
        Location to = mob.getLocation().add(0, mob.getHeight() * 0.55, 0);
        Vector dir = to.toVector().subtract(eye.toVector());
        double dist = dir.length();
        if (dist < 0.5) return false;
        dir.normalize();
        World world = w.world();
        if (world.rayTraceBlocks(eye, dir, dist, org.bukkit.FluidCollisionMode.NEVER, true) != null) return false;
        var hit = world.rayTraceEntities(eye, dir, dist + 1, 0.6, en -> en != mob && !en.equals(w.e.entity)
                && en instanceof LivingEntity && !(en instanceof Enemy));
        return hit == null || hit.getHitEntity() == null;
    }

    // ── job hooks ─────────────────────────────────────────────────────────

    @Override
    public String idleStatus(Worker w) {
        return idle;
    }

    @Override
    public boolean isReserved(Material m) {
        return m == Material.TORCH;
    }

    /**
     * Torches (up to the reserve), one bow and one crossbow, a stack of each
     * kind of arrow, and a spare of each armour piece at least as good as what
     * it wears stay in the backpack. Extra bows, arrows and worse armour (what
     * it took off for something better) are delivered with the loot.
     */
    @Override
    public int reserve(Worker w, Material m) {
        if (m == Material.BOW || m == Material.CROSSBOW) return 1;
        if (m == Material.ARROW || m == Material.SPECTRAL_ARROW) return 64;
        int slot = armorSlot(m);
        if (slot >= 0) return armorRank(ItemStack.of(m)) / 100 >= armorRank(w.e.armor[slot]) / 100 ? 1 : 0;
        return isReserved(m) ? w.jobSettings().reserve() : 0;
    }

    @Override
    public boolean collects(Material m) {
        return DROPS.contains(m);
    }
}
