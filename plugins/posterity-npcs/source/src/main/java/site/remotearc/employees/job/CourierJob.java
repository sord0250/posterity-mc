package site.remotearc.employees.job;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Container;
import org.bukkit.block.Lidded;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import site.remotearc.employees.Employee;
import site.remotearc.employees.Ledger;
import site.remotearc.employees.storage.StorageTarget;
import site.remotearc.employees.storage.Supply;
import site.remotearc.employees.util.BlockPos;
import site.remotearc.employees.util.Items;
import site.remotearc.employees.util.Pathfinder;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Carries items along its routes, one load at a time: takes what the route
 * moves from the source (leaving its keep amount, and no more than the
 * destination's stock level wants), then unloads it all at the destination.
 * Placed chests are walked to when they're close (jobs.courier.walk-distance)
 * and reachable; farther ones, or other worlds, it gets to by a shortcut (a
 * puff of smoke, like going home). /chest numbers are reached from anywhere,
 * like deliveries. Routes take turns. Only plain items are carried.
 */
final class CourierJob implements Job {

    /** Route being carried for (-1 = nothing loaded). */
    private int carrying = -1;
    /** The load didn't all sell: it goes back to the route's source. */
    private boolean returning;
    private int next;
    /** Route → tick it may be tried again (its destination was full, its source missing...). */
    private final Map<Integer, Long> resting = new HashMap<>();
    private final Map<Integer, String> problems = new HashMap<>();
    private String idle = "Nothing to carry right now";

    @Override
    public Task next(Worker w) {
        List<Employee.Route> routes = w.e.routes;
        if (routes.isEmpty()) {
            idle = "No routes yet: /employees route " + w.e.name + " add <from> <to>";
            return null;
        }
        if (Items.countDeliverable(w.e.backpack, m -> 0) > 0) {
            int r = carrying >= 0 && carrying < routes.size() ? carrying : routeFor(w, routes);
            if (r < 0) {
                idle = "Carrying things none of its routes take (empty its backpack)";
                return null;
            }
            carrying = r;
            Employee.Route route = routes.get(r);
            if (route.sells() && returning) return new CarryTask(w, r, route, Leg.RETURN, null);
            if (waiting(w, r)) {
                idle = "Waiting: " + problems.getOrDefault(r, "can't unload");
                return null;
            }
            if (route.sells() && !w.plugin().market().hasMerchants()) {
                idle = "Waiting: there's no merchant to sell to";
                return null;
            }
            return new CarryTask(w, r, route, route.sells() ? Leg.SELL : Leg.DROP, null);
        }
        carrying = -1;
        for (int i = 0; i < routes.size(); i++) {
            int r = (next + i) % routes.size();
            if (waiting(w, r)) continue;
            Employee.Route route = routes.get(r);
            Map<Material, Integer> want = wanted(w, route);
            if (want.isEmpty()) continue;
            next = r + 1;
            return new CarryTask(w, r, route, Leg.PICKUP, want);
        }
        boolean selling = routes.stream().anyMatch(Employee.Route::sells);
        if (selling && w.plugin().market().roomToday(w.e.owner) <= 0) idle = "Waiting: the selling limit is reached for today";
        else idle = problems.isEmpty() ? "Nothing to carry right now" : "Waiting: " + problems.values().iterator().next();
        return null;
    }

    private boolean waiting(Worker w, int r) {
        Long until = resting.get(r);
        if (until == null) return false;
        if (until > w.now()) return true;
        resting.remove(r);
        problems.remove(r);
        return false;
    }

    private void rest(Worker w, int r, String problem, int seconds) {
        resting.put(r, w.now() + 20L * seconds);
        problems.put(r, problem);
    }

    /** The first route that carries everything in the backpack (after a restart, when the load's route is forgotten). */
    private static int routeFor(Worker w, List<Employee.Route> routes) {
        for (int r = 0; r < routes.size(); r++) {
            boolean all = true;
            for (ItemStack s : w.e.backpack.getContents()) {
                if (s != null && !s.getType().isAir() && !routes.get(r).carries(s.getType())) all = false;
            }
            if (all) return r;
        }
        return routes.isEmpty() ? -1 : 0;
    }

    /** What to pick up for a route now, trimmed to what fits in the backpack. */
    private Map<Material, Integer> wanted(Worker w, Employee.Route route) {
        Map<Material, Integer> at = contents(w, route.from());
        if (at == null || at.isEmpty()) return Map.of();
        if (route.sells()) return forSale(w, route, at);
        Map<Material, Integer> there = route.stock() > 0 ? contents(w, route.to()) : Map.of();
        if (there == null) return Map.of();
        Map<Material, Integer> want = new EnumMap<>(Material.class);
        int slots = Items.freeSlots(w.e.backpack) - 1;
        for (Map.Entry<Material, Integer> en : at.entrySet()) {
            Material m = en.getKey();
            if (!route.carries(m)) continue;
            int n = en.getValue() - route.keep();
            if (route.stock() > 0) n = Math.min(n, route.stock() - there.getOrDefault(m, 0));
            int stacks = (n + m.getMaxStackSize() - 1) / m.getMaxStackSize();
            if (n <= 0 || slots <= 0) continue;
            if (stacks > slots) n = slots * m.getMaxStackSize();
            want.put(m, n);
            slots -= (n + m.getMaxStackSize() - 1) / m.getMaxStackSize();
        }
        return want;
    }

    /** For a selling route: only what a merchant buys and would pay for today (within the selling limit). */
    private Map<Material, Integer> forSale(Worker w, Employee.Route route, Map<Material, Integer> at) {
        var market = w.plugin().market();
        if (!w.settings().marketEnabled || !market.hasMerchants()) return Map.of();
        List<ItemStack> offer = new java.util.ArrayList<>();
        int slots = Items.freeSlots(w.e.backpack) - 1;
        for (Map.Entry<Material, Integer> en : at.entrySet()) {
            Material m = en.getKey();
            int n = en.getValue() - route.keep();
            if (!route.carries(m) || n <= 0 || market.basePrice(m) <= 0) continue;
            while (n > 0 && slots > 0) {
                int stack = Math.min(n, m.getMaxStackSize());
                offer.add(new ItemStack(m, stack));
                n -= stack;
                slots--;
            }
        }
        if (offer.isEmpty()) return Map.of();
        var quote = market.sell(w.e.owner, offer, false, false);
        return new EnumMap<>(quote.sold());
    }

    /** Plain items at a place (null if it can't be looked into right now). */
    private static Map<Material, Integer> contents(Worker w, StorageTarget t) {
        Map<Material, Integer> out = new EnumMap<>(Material.class);
        if (t.kind() == StorageTarget.Kind.VIRTUAL) {
            if (!w.plugin().vchests().available()) return null;
            Items.countPlain(w.plugin().vchests().contents(w.e.owner, t.chestNumber()), out);
        } else {
            Container c = t.container();
            if (c == null) return null;
            Items.countPlain(c.getInventory().getStorageContents(), out);
        }
        return out;
    }

    // ── the trip ──────────────────────────────────────────────────────────

    /** What a trip is for. */
    private enum Leg { PICKUP, DROP, SELL, RETURN }

    /** Goes to one end of a route (or a merchant) and loads, unloads or sells there. */
    private final class CarryTask implements Task {

        private final int index;
        private final Leg leg;
        private final Map<Material, Integer> want;
        /** The chest or /chest at this end (null for a merchant). */
        private final StorageTarget at;
        private final String worldName;
        private final BlockPos pos;
        private final String where;
        private Vector target;
        private Pathfinder.Goal goal;
        private boolean arrived, jumped;
        private int timer;

        CarryTask(Worker w, int index, Employee.Route route, Leg leg, Map<Material, Integer> want) {
            this.index = index;
            this.leg = leg;
            this.want = want;
            if (leg == Leg.SELL) {
                var m = w.plugin().market().closest(w.e.location);
                this.at = null;
                this.worldName = m.world();
                this.pos = new BlockPos((int) Math.floor(m.x()), (int) Math.floor(m.y() + 0.01), (int) Math.floor(m.z()));
                this.where = m.name();
            } else {
                this.at = leg == Leg.DROP ? route.to() : route.from();
                this.worldName = at.world();
                this.pos = at.pos();
                this.where = "the " + at.describe();
            }
        }

        @Override
        public Result tick(Worker w) {
            if (at != null && at.kind() == StorageTarget.Kind.VIRTUAL) {
                if (++timer < 10) return Result.RUNNING;   // a moment, like opening /chest
                return transfer(w, null) ? Result.DONE : Result.FAILED;
            }
            if (!arrived) {
                if (target == null) {
                    target = new Vector(pos.centerX(), at == null ? pos.y() : pos.centerY(), pos.centerZ());
                    goal = Pathfinder.near(target.getX(), target.getY(), target.getZ(), at == null ? 2.5 : 2.3, 2, 2.5);
                    boolean far = !worldName.equals(w.world().getName())
                            || target.distanceSquared(w.e.location.toVector()) > sq(w.settings().courierWalkDistance);
                    if (far && !jump(w)) return fail(w, "can't get to " + where);
                }
                if (!arrived) {
                    switch (w.walk(goal, target)) {
                        case MOVING -> {
                            return Result.RUNNING;
                        }
                        case FAILED -> {
                            if (jumped || !jump(w)) return fail(w, "can't get to " + where);
                        }
                        case ARRIVED -> arrived = true;
                    }
                }
                if (arrived && timer == 0) {
                    if (at == null) {
                        w.lookAt(target.getX(), target.getY() + 1.5, target.getZ());   // face the merchant
                    } else {
                        w.lookAt(pos);
                        Container c = at.container();
                        if (c instanceof Lidded lid) lid.open();
                        else if (c != null) w.playSound(c.getLocation(), Sound.BLOCK_BARREL_OPEN, 0.6f, 1f);
                    }
                }
                if (!arrived) return Result.RUNNING;
            }
            timer++;
            if (timer == (at == null ? 12 : 6)) {
                w.swing();
                Container c = null;
                if (at != null) {
                    c = at.container();
                    if (c == null) return fail(w, where + " is missing");
                }
                if (!transfer(w, c)) {
                    close();
                    return Result.FAILED;
                }
            }
            if (timer >= 16) {
                close();
                return Result.DONE;
            }
            return Result.RUNNING;
        }

        private static double sq(double d) {
            return d * d;
        }

        /** Gets next to the place straight away: a puff of smoke here, and there. */
        private boolean jump(Worker w) {
            jumped = true;
            World world = org.bukkit.Bukkit.getWorld(worldName);
            if (world == null) return false;
            BlockPos p = pos;
            if (!world.isChunkLoaded(p.x() >> 4, p.z() >> 4)) world.getChunkAt(p.x() >> 4, p.z() >> 4);
            Pathfinder pf = new Pathfinder(world, BoundingBox.of(new Vector(p.centerX(), p.centerY(), p.centerZ()), 4, 8, 4));
            int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {2, 0}, {-2, 0}, {0, 2}, {0, -2}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};
            for (int[] d : sides) {
                for (int dy : new int[]{0, 1, -1, 2, -2, 3, -3, -4, -5, -6}) {
                    double f = pf.floor(p.x() + d[0], p.y() + dy, p.z() + d[1]);
                    if (Double.isNaN(f)) continue;
                    Location from = w.e.location.clone().add(0, 1, 0);
                    from.getWorld().spawnParticle(Particle.CLOUD, from, 10, 0.3, 0.5, 0.3, 0.02);
                    Location to = new Location(world, p.x() + d[0] + 0.5, f, p.z() + d[1] + 0.5);
                    w.plugin().manager().teleport(w.e, to);
                    world.spawnParticle(Particle.CLOUD, to.clone().add(0, 1, 0), 10, 0.3, 0.5, 0.3, 0.02);
                    arrived = true;
                    return true;
                }
            }
            return false;
        }

        private boolean transfer(Worker w, Container c) {
            var plugin = w.plugin();
            if (c != null) plugin.blockLog().container(w.e, c.getLocation());
            if (leg == Leg.PICKUP) {
                Map<Material, Integer> left = new EnumMap<>(want);
                List<ItemStack> got = at.kind() == StorageTarget.Kind.VIRTUAL
                        ? plugin.vchests().withdraw(w.e.owner, at.chestNumber(), left)
                        : Supply.takeFrom(c, left);
                if (got.isEmpty()) {
                    rest(w, index, "couldn't take anything from " + where, 30);
                    return false;
                }
                w.stash(got);
                plugin.ledger().taken(w.e, got, false);
                carrying = index;
                returning = false;
                return true;
            }
            List<ItemStack> load = Items.takeAllExcept(w.e.backpack, m -> 0);
            if (leg == Leg.SELL) {
                int before = DeliverVirtualTask.count(load);
                List<ItemStack> unsold = plugin.market().sellCarried(w.e, load);
                int sold = before - DeliverVirtualTask.count(unsold);
                w.e.stats.itemsMoved += Math.max(0, sold);
                w.stash(unsold);
                if (sold > 0) w.playSound(w.e.location, Sound.ENTITY_VILLAGER_YES, 0.8f, 1f);
                if (!unsold.isEmpty()) {
                    returning = true;   // the rest goes back where it came from
                    rest(w, index, "the merchant won't take more today (the selling limit)", 300);
                } else {
                    carrying = -1;
                }
                return true;
            }
            var counts = Ledger.count(load);
            List<ItemStack> leftover = at.kind() == StorageTarget.Kind.VIRTUAL
                    ? plugin.vchests().deposit(w.e.owner, at.chestNumber(), load)
                    : Items.add(c.getInventory(), load);
            int moved = DeliverVirtualTask.count(load) - DeliverVirtualTask.count(leftover);
            plugin.ledger().delivered(w.e, Map.of(), counts, leftover);
            if (leg == Leg.DROP) w.e.stats.itemsMoved += Math.max(0, moved);
            w.stash(leftover);
            plugin.manager().markDirty();
            if (!leftover.isEmpty()) {
                rest(w, index, where + " is full", 60);
                return moved > 0;
            }
            carrying = -1;
            returning = false;
            return true;
        }

        private Result fail(Worker w, String problem) {
            rest(w, index, problem, 120);
            close();
            return Result.FAILED;
        }

        private void close() {
            if (at != null && at.container() instanceof Lidded lid && lid.isOpen()) lid.close();
        }

        @Override
        public void end(Worker w) {
            close();
        }

        @Override
        public String status(Worker w) {
            return switch (leg) {
                case PICKUP -> "Picking up at " + where;
                case DROP -> "Taking a load to " + where;
                case SELL -> "Taking a load to sell to " + where;
                case RETURN -> "Bringing back what didn't sell, to " + where;
            };
        }
    }

    // ── job hooks ─────────────────────────────────────────────────────────

    @Override
    public String idleStatus(Worker w) {
        return idle;
    }

    @Override
    public boolean isReserved(Material m) {
        return true;
    }

    /** Everything it carries is on its way somewhere: never "delivered" to its own storage. */
    @Override
    public int reserve(Worker w, Material m) {
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean collects(Material m) {
        return false;
    }

    @Override
    public boolean canWorkWhenFull() {
        return true;
    }

    @Override
    public void reset(Worker w) {
        resting.clear();
        problems.clear();
    }

    @Override
    public String detail(Worker w) {
        return w.e.routes.size() + " route" + (w.e.routes.size() == 1 ? "" : "s") + ", " + w.e.stats.itemsMoved + " items moved";
    }
}
