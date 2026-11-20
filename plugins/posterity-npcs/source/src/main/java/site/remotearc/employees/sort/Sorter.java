package site.remotearc.employees.sort;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.util.Msg;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Sorting containers by category: the chests, barrels and other containers
 * round a player (or in their WorldEdit selection) and any of their virtual
 * chests. Organize plans where every category goes (chests pinned to a
 * category first, then the ones already holding most of it), shows the plan
 * with labels on the chests, and moves everything in one go, with undo.
 */
public final class Sorter {

    public static final int MAX_RADIUS = 16;
    private static final Set<Material> CONTAINERS = EnumSet.of(Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL);

    /** One container: a placed one (double chests once) or a virtual chest. */
    public static final class Box {
        public final String key;
        final Inventory inv;
        public final Location at;
        public final Integer vc;
        final UUID owner;
        private final ArcEmployees plugin;

        private Box(ArcEmployees plugin, String key, Inventory inv, Location at, Integer vc, UUID owner) {
            this.plugin = plugin;
            this.key = key;
            this.inv = inv;
            this.at = at;
            this.vc = vc;
            this.owner = owner;
        }

        public int size() {
            return inv != null ? inv.getStorageContents().length : plugin.vchests().size();
        }

        /** A snapshot: each slot is its own clone, so nothing that later mutates the working copy touches this one. */
        ItemStack[] read() {
            ItemStack[] src = inv != null ? inv.getStorageContents() : Arrays.copyOf(plugin.vchests().contents(owner, vc), size());
            ItemStack[] out = new ItemStack[src.length];
            for (int i = 0; i < src.length; i++) out[i] = src[i] == null ? null : src[i].clone();
            return out;
        }

        boolean write(ItemStack[] contents) {
            if (inv != null) {
                inv.setStorageContents(Arrays.copyOf(contents, size()));
                return true;
            }
            return plugin.vchests().write(owner, vc, contents);
        }

        /** Someone has it open: left alone so nothing they're holding gets lost. */
        boolean busy() {
            return inv != null ? !inv.getViewers().isEmpty() : plugin.vchests().isOpen(owner, vc);
        }

        /** "chest 4 north, 2 east, 1 up" (from the player), or "virtual chest 12". */
        public String describe(Player p) {
            if (vc != null) return "virtual chest " + vc;
            Block b = at.getBlock();
            String what = b.getType() == Material.BARREL ? "barrel" : inv.getHolder() instanceof DoubleChest ? "double chest" : Msg.titleCase(b.getType().name()).toLowerCase();
            if (p == null || !p.getWorld().equals(at.getWorld())) return what + " at " + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ();
            int dx = at.getBlockX() - p.getLocation().getBlockX(), dy = at.getBlockY() - p.getLocation().getBlockY(), dz = at.getBlockZ() - p.getLocation().getBlockZ();
            List<String> parts = new ArrayList<>();
            if (dz != 0) parts.add(Math.abs(dz) + (dz < 0 ? " north" : " south"));
            if (dx != 0) parts.add(Math.abs(dx) + (dx < 0 ? " west" : " east"));
            if (dy != 0) parts.add(Math.abs(dy) + (dy < 0 ? " down" : " up"));
            return what + " " + (parts.isEmpty() ? "where you stand" : String.join(", ", parts))
                    + " <dark_gray>(" + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ() + ")</dark_gray>";
        }
    }

    /** Where each category goes; what each box ends up holding. */
    public record Plan(List<Box> boxes, Map<String, List<Box>> where, Map<String, Integer> stacks, Map<String, Integer> items,
                       Map<Box, ItemStack[]> result, Map<Box, ItemStack[]> before, List<String> notes, boolean fits, int skippedBusy) {}

    /** A container before and after; {@code fromPlayer}: what went in from the player's inventory (given back on undo). */
    private record Snapshot(Box box, ItemStack[] before, ItemStack[] after, List<ItemStack> fromPlayer) {
        Snapshot(Box box, ItemStack[] before, ItemStack[] after) {
            this(box, before, after, List.of());
        }
    }

    private final ArcEmployees plugin;
    private final SortProfiles profiles;
    private final Map<UUID, List<Snapshot>> undo = new HashMap<>();
    private final ChestLabels labels;

    public Sorter(ArcEmployees plugin, SortProfiles profiles) {
        this.plugin = plugin;
        this.profiles = profiles;
        this.labels = new ChestLabels(plugin, this);
        labels.start();
    }

    /** Forgets a player's undo-able deposit (they can't come back for it anyway); called when they quit. */
    public void forget(UUID player) {
        undo.remove(player);
    }

    public SortProfiles profiles() {
        return profiles;
    }

    // ── finding containers ───────────────────────────────────────────────

    public static String key(Location l) {
        return l.getWorld().getName() + ";" + l.getBlockX() + ";" + l.getBlockY() + ";" + l.getBlockZ();
    }

    /** The container at a block, keyed by the left half of a double chest. */
    public Box boxAt(Block b) {
        if (!CONTAINERS.contains(b.getType()) || !(b.getState(false) instanceof Container c)) return null;
        Inventory inv = c.getInventory();
        Location at = b.getLocation();
        InventoryHolder h = inv.getHolder(false);
        if (h instanceof DoubleChest dc && dc.getLeftSide(false) instanceof BlockState left) at = left.getLocation();
        return new Box(plugin, key(at), inv, at, null, null);
    }

    public Box virtualBox(UUID owner, int n) {
        return new Box(plugin, "vc:" + n, null, null, n, owner);
    }

    /** The containers round the player (radius) or in their selection, then their virtual ones. */
    public List<Box> boxes(Player p, site.remotearc.employees.util.WorkArea sel) {
        SortProfiles.Profile prof = profiles.get(p.getUniqueId());
        Map<String, Box> out = new LinkedHashMap<>();
        World w = sel != null ? Bukkit.getWorld(sel.world()) : p.getWorld();
        int r = Math.min(MAX_RADIUS, Math.max(1, prof.radius));
        Location l = p.getLocation();
        int x0 = sel != null ? sel.minX() : l.getBlockX() - r, x1 = sel != null ? sel.maxX() : l.getBlockX() + r;
        int y0 = sel != null ? sel.minY() : l.getBlockY() - r, y1 = sel != null ? sel.maxY() : l.getBlockY() + r;
        int z0 = sel != null ? sel.minZ() : l.getBlockZ() - r, z1 = sel != null ? sel.maxZ() : l.getBlockZ() + r;
        if (w == null || (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1) > 500_000) return List.of();
        y0 = Math.max(y0, w.getMinHeight());
        y1 = Math.min(y1, w.getMaxHeight() - 1);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = y0; y <= y1; y++) {
                    Block b = w.getBlockAt(x, y, z);
                    if (!CONTAINERS.contains(b.getType()) || !mayOpen(p, b)) continue;
                    Box box = boxAt(b);
                    if (box != null) out.putIfAbsent(box.key, box);
                }
            }
        }
        // Pinned chests a little further off still count when they're loaded and in this world.
        for (List<String> keys : prof.pins.values()) {
            for (String k : keys) {
                if (out.containsKey(k) || k.startsWith("vc:")) continue;
                Location at = location(k);
                if (at == null || !at.getWorld().equals(w) || at.distanceSquared(l) > 48 * 48 || !at.isChunkLoaded()) continue;
                Box box = boxAt(at.getBlock());
                if (box != null && mayOpen(p, at.getBlock())) out.putIfAbsent(box.key, box);
            }
        }
        if (plugin.vchests().available()) {
            Set<Integer> vcs = new LinkedHashSet<>(prof.virtual);
            for (List<String> keys : prof.pins.values()) for (String k : keys) if (k.startsWith("vc:")) vcs.add(Integer.parseInt(k.substring(3)));
            for (int n : vcs) out.putIfAbsent("vc:" + n, virtualBox(p.getUniqueId(), n));
        }
        return new ArrayList<>(out.values());
    }

    /** Not already claimed by someone else's employees, unless an admin. */
    private boolean mayOpen(Player p, Block b) {
        return p.hasPermission("employees.admin")
                || plugin.manager().mayUseChest(p.getUniqueId(), b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
    }

    public static Location location(String key) {
        String[] a = key.split(";");
        if (a.length != 4) return null;
        World w = Bukkit.getWorld(a[0]);
        if (w == null) return null;
        try {
            return new Location(w, Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // ── planning ─────────────────────────────────────────────────────────

    /** Full stacks of everything, like items merged by hand. */
    static List<ItemStack> merge(List<ItemStack> items) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack it : items) {
            if (it == null || it.getType().isAir()) continue;
            int left = it.getAmount();
            for (ItemStack o : out) {
                if (left <= 0) break;
                if (o.isSimilar(it) && o.getAmount() < o.getMaxStackSize()) {
                    int add = Math.min(left, o.getMaxStackSize() - o.getAmount());
                    o.setAmount(o.getAmount() + add);
                    left -= add;
                }
            }
            while (left > 0) {
                ItemStack s = it.clone();
                int n = Math.min(left, Math.max(1, it.getMaxStackSize()));
                s.setAmount(n);
                out.add(s);
                left -= n;
            }
        }
        out.sort(Comparator.comparing((ItemStack s) -> s.getType().name()).thenComparing(s -> -s.getAmount()));
        return out;
    }

    public Plan plan(Player p, List<Box> all) {
        SortProfiles.Profile prof = profiles.get(p.getUniqueId());
        List<Box> boxes = new ArrayList<>();
        int busy = 0;
        for (Box b : all) {
            if (b.busy()) busy++;
            else boxes.add(b);
        }
        Map<Box, ItemStack[]> before = new LinkedHashMap<>();
        Map<String, List<ItemStack>> byCat = new LinkedHashMap<>();
        Map<Box, Map<String, Integer>> holds = new HashMap<>();
        for (Box b : boxes) {
            ItemStack[] c = b.read();
            before.put(b, c);
            Map<String, Integer> h = new HashMap<>();
            for (ItemStack it : c) {
                if (it == null || it.getType().isAir()) continue;
                String cat = prof.categoryOf(it.getType());
                byCat.computeIfAbsent(cat, k -> new ArrayList<>()).add(it.clone());
                h.merge(cat, it.getAmount(), Integer::sum);
            }
            holds.put(b, h);
        }
        Map<String, List<ItemStack>> stacks = new LinkedHashMap<>();
        Map<String, Integer> stackCount = new LinkedHashMap<>(), itemCount = new LinkedHashMap<>();
        for (String cat : prof.active()) {
            List<ItemStack> l = byCat.get(cat);
            if (l == null) continue;
            List<ItemStack> m = merge(l);
            stacks.put(cat, m);
            stackCount.put(cat, m.size());
            itemCount.put(cat, m.stream().mapToInt(ItemStack::getAmount).sum());
        }
        // Biggest first, so they get whole chests before the small ones start sharing.
        List<String> order = new ArrayList<>(stacks.keySet());
        order.sort(Comparator.comparingInt((String c) -> -stackCount.get(c)));

        Map<String, List<Box>> where = new LinkedHashMap<>();
        Map<Box, Integer> free = new LinkedHashMap<>();
        Set<Box> taken = new LinkedHashSet<>();
        for (Box b : boxes) free.put(b, b.size());
        // 1. Pinned containers belong to their category (even when it has nothing right now).
        for (Box b : boxes) {
            String cat = prof.pinnedTo(b.key);
            if (cat == null || !prof.on(cat)) continue;
            where.computeIfAbsent(cat, k -> new ArrayList<>()).add(b);
            taken.add(b);
        }
        // 2. Every category without one gets a container; 3. then more until it fits.
        for (int pass = 0; pass < 2; pass++) {
            for (String cat : order) {
                List<Box> mine = where.computeIfAbsent(cat, k -> new ArrayList<>());
                if (pass == 0 && !mine.isEmpty()) continue;
                int need = stackCount.get(cat), have = 0;
                for (Box b : mine) have += b.size();
                while (have < need) {
                    Box best = null;
                    long bestScore = Long.MIN_VALUE;
                    for (Box b : boxes) {
                        if (taken.contains(b)) continue;
                        long score = holds.get(b).getOrDefault(cat, 0) * 1000L;
                        if (!mine.isEmpty() && b.at != null && mine.getFirst().at != null && b.at.getWorld().equals(mine.getFirst().at.getWorld())) {
                            score -= (long) b.at.distanceSquared(mine.getFirst().at);
                        } else if (b.vc != null) {
                            score -= 100_000;   // nearby chests before virtual ones, unless they hold this already
                        }
                        if (score > bestScore) {
                            bestScore = score;
                            best = b;
                        }
                    }
                    if (best == null) break;
                    mine.add(best);
                    taken.add(best);
                    have += best.size();
                    if (pass == 0) break;
                }
            }
        }
        where.values().removeIf(List::isEmpty);

        // Fill: each category into its own containers, then what's left into any free space.
        Map<Box, List<ItemStack>> fill = new LinkedHashMap<>();
        for (Box b : boxes) fill.put(b, new ArrayList<>());
        List<String> notes = new ArrayList<>();
        Map<String, List<ItemStack>> leftover = new LinkedHashMap<>();
        for (String cat : order) {
            List<ItemStack> l = new ArrayList<>(stacks.get(cat));
            for (Box b : where.getOrDefault(cat, List.of())) {
                while (!l.isEmpty() && free.get(b) > 0) {
                    fill.get(b).add(l.removeFirst());
                    free.merge(b, -1, Integer::sum);
                }
            }
            if (!l.isEmpty()) leftover.put(cat, l);
        }
        boolean fits = true;
        for (Map.Entry<String, List<ItemStack>> e : leftover.entrySet()) {
            String cat = e.getKey();
            List<ItemStack> l = e.getValue();
            // Shares the container with the most room left.
            while (!l.isEmpty()) {
                Box roomiest = null;
                for (Box b : boxes) if (free.get(b) > 0 && (roomiest == null || free.get(b) > free.get(roomiest))) roomiest = b;
                if (roomiest == null) break;
                List<Box> mine = where.computeIfAbsent(cat, k -> new ArrayList<>());
                if (!mine.contains(roomiest)) mine.add(roomiest);
                while (!l.isEmpty() && free.get(roomiest) > 0) {
                    fill.get(roomiest).add(l.removeFirst());
                    free.merge(roomiest, -1, Integer::sum);
                }
            }
            if (!l.isEmpty()) {
                fits = false;
                notes.add(prof.name(cat) + ": " + l.size() + " stacks don't fit anywhere");
            }
        }
        int needed = stackCount.values().stream().mapToInt(Integer::intValue).sum();
        int room = boxes.stream().mapToInt(Box::size).sum();
        if (!fits) notes.addFirst("Needs " + needed + " slots but these containers have " + room + ". Add a chest or two, or a virtual chest.");
        Map<Box, ItemStack[]> result = new LinkedHashMap<>();
        for (Box b : boxes) result.put(b, fill.get(b).toArray(new ItemStack[0]));
        return new Plan(boxes, where, stackCount, itemCount, result, before, notes, fits, busy);
    }

    // ── showing a plan ───────────────────────────────────────────────────

    /** The plan in chat: each category and the containers it goes into. */
    public void tell(Player p, Plan plan) {
        SortProfiles.Profile prof = profiles.get(p.getUniqueId());
        Msg.send(p, "<gold>Sorting plan</gold> <gray>(" + plan.boxes().size() + " containers" + (plan.skippedBusy() > 0
                ? ", " + plan.skippedBusy() + " left alone because someone has them open" : "") + "):");
        for (Map.Entry<String, List<Box>> e : plan.where().entrySet()) {
            String cat = e.getKey();
            int st = plan.stacks().getOrDefault(cat, 0);
            List<String> boxes = new ArrayList<>();
            for (Box b : e.getValue()) boxes.add(b.describe(p) + (cat.equals(prof.pinnedTo(b.key)) ? " <aqua>(kept)</aqua>" : ""));
            p.sendMessage(Msg.parse("<white>" + prof.name(cat) + "</white> <gray>(" + plan.items().getOrDefault(cat, 0) + " items, " + st
                    + " slots) → " + String.join("<gray>, ", boxes)));
        }
        for (String n : plan.notes()) Msg.error(p, n);
        if (plan.fits()) {
            p.sendMessage(Msg.parse("<green><click:run_command:'/sort confirm'>[Sort it]</click></green> <gray>or</gray> "
                    + "<aqua><click:run_command:'/sort'>[Change categories and chests]</click></aqua> <gray>Labels on the chests show the plan for a minute."));
        }
    }

    /** Labels on the containers naming what the plan puts in each, for a minute. */
    public void label(Player p, Plan plan) {
        SortProfiles.Profile prof = profiles.get(p.getUniqueId());
        Map<Box, List<String>> cats = new LinkedHashMap<>();
        plan.where().forEach((cat, bs) -> bs.forEach(b -> cats.computeIfAbsent(b, k -> new ArrayList<>()).add(prof.name(cat))));
        Map<String, String> texts = new HashMap<>();
        for (Box b : plan.boxes()) {
            if (b.at == null) continue;
            List<String> c = cats.getOrDefault(b, List.of());
            texts.put(b.key, "<yellow>Sorting puts here:</yellow>\n" + (c.isEmpty() ? "<gray>nothing" : "<gold>" + String.join("<gray>,</gray>\n", c))
                    + (prof.pinnedTo(b.key) != null ? "\n<aqua>(kept for it)" : ""));
        }
        labels.showPlan(p, texts);
    }

    public void clearLabels(Player p) {
        labels.clearPlan(p);
    }

    public void stop() {
        labels.stop();
    }

    // ── doing it ─────────────────────────────────────────────────────────

    private static Map<String, Integer> totals(java.util.Collection<ItemStack[]> all) {
        Map<String, Integer> out = new HashMap<>();
        for (ItemStack[] c : all) {
            for (ItemStack it : c) {
                if (it == null || it.getType().isAir()) continue;
                ItemStack one = it.asOne();
                out.merge(one.getType() + "@" + one.hashCode(), it.getAmount(), Integer::sum);
            }
        }
        return out;
    }

    /**
     * Moves everything as planned, after checking nothing changed since the
     * plan was read and nothing would be lost. Returns a reason it didn't, or null.
     */
    public String apply(Player p, Plan plan) {
        if (!plan.fits()) return "It doesn't all fit.";
        for (Box b : plan.boxes()) {
            if (b.busy()) return "Someone opened a " + (b.vc != null ? "virtual chest" : "chest") + " meanwhile. Try again.";
            if (!Arrays.equals(b.read(), plan.before().get(b))) return "Something changed in the chests meanwhile. Try again.";
        }
        if (!totals(plan.before().values()).equals(totals(plan.result().values()))) return "The plan didn't add up, so nothing was moved.";
        List<Snapshot> done = new ArrayList<>();
        // Virtual chests first: a failed write there is undone before any placed chest is touched.
        List<Box> order = new ArrayList<>(plan.boxes());
        order.sort(Comparator.comparing(b -> b.vc == null));
        for (Box b : order) {
            ItemStack[] after = Arrays.copyOf(plan.result().get(b), b.size());
            if (Arrays.equals(after, plan.before().get(b))) continue;
            if (!b.write(after)) {
                for (Snapshot s : done) s.box.write(s.before);
                return "Couldn't write " + b.describe(p) + ", so nothing was moved.";
            }
            done.add(new Snapshot(b, plan.before().get(b), after));
        }
        undo.put(p.getUniqueId(), done);
        return null;
    }

    /** Puts back what the last sort, deposit or tidy moved (containers changed since are left alone). */
    /** {put back, left alone because they changed since}, or null if there's nothing to undo. */
    public int[] undo(Player p) {
        List<Snapshot> l = undo.remove(p.getUniqueId());
        if (l == null || l.isEmpty()) return null;
        int back = 0, changed = 0;
        for (Snapshot s : l) {
            if (s.box.busy() || !Arrays.equals(s.box.read(), Arrays.copyOf(s.after, s.box.size()))) {
                changed++;
                continue;
            }
            if (s.box.write(s.before)) {
                back++;
                for (ItemStack it : p.getInventory().addItem(s.fromPlayer.stream().map(ItemStack::clone).toArray(ItemStack[]::new)).values()) {
                    p.getWorld().dropItem(p.getLocation(), it);
                }
            }
        }
        return new int[]{back, changed};
    }

    /** Quick stack: the player's items (not the hotbar) into containers that have the same, or are their category's. */
    public int[] deposit(Player p, List<Box> boxes, boolean hotbar) {
        SortProfiles.Profile prof = profiles.get(p.getUniqueId());
        Plan plan = plan(p, boxes);
        Map<Box, ItemStack[]> now = new LinkedHashMap<>();
        for (Box b : plan.boxes()) {
            ItemStack[] src = plan.before().get(b);
            ItemStack[] copy = new ItemStack[b.size()];
            for (int i = 0; i < copy.length && i < src.length; i++) copy[i] = src[i] == null ? null : src[i].clone();
            now.put(b, copy);
        }
        Map<Box, String> catOf = new HashMap<>();
        for (Box b : plan.boxes()) {
            String pin = prof.pinnedTo(b.key);
            if (pin != null) catOf.put(b, pin);
        }
        // Unpinned: the category it mostly holds.
        for (Box b : plan.boxes()) {
            if (catOf.containsKey(b)) continue;
            Map<String, Integer> h = new HashMap<>();
            for (ItemStack it : now.get(b)) if (it != null && !it.getType().isAir()) h.merge(prof.categoryOf(it.getType()), it.getAmount(), Integer::sum);
            h.entrySet().stream().max(Map.Entry.comparingByValue()).ifPresent(e -> catOf.put(b, e.getKey()));
        }
        Inventory inv = p.getInventory();
        int moved = 0;
        Set<Box> touched = new LinkedHashSet<>();
        Map<Box, List<ItemStack>> given = new HashMap<>();
        for (int slot = hotbar ? 0 : 9; slot < 36; slot++) {
            ItemStack it = inv.getItem(slot);
            if (it == null || it.getType().isAir()) continue;
            String cat = prof.categoryOf(it.getType());
            int start = it.getAmount();
            // Containers that already have it, then its category's.
            for (int round = 0; round < 2 && it.getAmount() > 0; round++) {
                for (Box b : plan.boxes()) {
                    ItemStack[] c = now.get(b);
                    boolean has = Arrays.stream(c).anyMatch(x -> x != null && x.isSimilar(it));
                    if (round == 0 ? !has : !cat.equals(catOf.get(b))) continue;
                    int before = it.getAmount();
                    put(c, it);
                    if (it.getAmount() < before) {
                        touched.add(b);
                        ItemStack in = it.clone();
                        in.setAmount(before - it.getAmount());
                        given.computeIfAbsent(b, k -> new ArrayList<>()).add(in);
                    }
                    if (it.getAmount() <= 0) break;
                }
            }
            moved += start - it.getAmount();
            inv.setItem(slot, it.getAmount() <= 0 ? null : it);
        }
        List<Snapshot> done = new ArrayList<>();
        for (Box b : touched) {
            if (b.write(now.get(b))) done.add(new Snapshot(b, plan.before().get(b), now.get(b), given.getOrDefault(b, List.of())));
            else for (ItemStack it : given.getOrDefault(b, List.of())) p.getInventory().addItem(it).values().forEach(x -> p.getWorld().dropItem(p.getLocation(), x));
        }
        if (!done.isEmpty()) undo.put(p.getUniqueId(), done);
        return new int[]{moved, touched.size()};
    }

    /** Adds as much of {@code it} as fits (topping up stacks first); shrinks it by what went in. */
    private static void put(ItemStack[] c, ItemStack it) {
        for (int i = 0; i < c.length && it.getAmount() > 0; i++) {
            if (c[i] != null && c[i].isSimilar(it) && c[i].getAmount() < c[i].getMaxStackSize()) {
                int add = Math.min(it.getAmount(), c[i].getMaxStackSize() - c[i].getAmount());
                c[i].setAmount(c[i].getAmount() + add);
                it.setAmount(it.getAmount() - add);
            }
        }
        for (int i = 0; i < c.length && it.getAmount() > 0; i++) {
            if (c[i] == null || c[i].getType().isAir()) {
                c[i] = it.clone();
                it.setAmount(0);
            }
        }
    }

    /** Each container merged and sorted on its own: nothing moves between containers. */
    public int tidy(Player p, List<Box> boxes) {
        List<Snapshot> done = new ArrayList<>();
        for (Box b : boxes) {
            if (b.busy()) continue;
            ItemStack[] before = b.read();
            List<ItemStack> list = new ArrayList<>();
            for (ItemStack it : before) if (it != null && !it.getType().isAir()) list.add(it.clone());
            ItemStack[] after = Arrays.copyOf(merge(list).toArray(new ItemStack[0]), b.size());
            if (Arrays.equals(after, before)) continue;
            if (b.write(after)) done.add(new Snapshot(b, before, after));
        }
        if (!done.isEmpty()) undo.put(p.getUniqueId(), done);
        return done.size();
    }
}
