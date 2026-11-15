package site.remotearc.employees.job;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Furnace;
import org.bukkit.inventory.FurnaceInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import site.remotearc.employees.storage.Supply;
import site.remotearc.employees.util.BlockPos;
import site.remotearc.employees.util.Items;
import site.remotearc.employees.util.WorkArea;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the furnaces, smokers and blast furnaces placed in its work area:
 * loads them with what its filter says to smelt (food goes to smokers and
 * ores to blast furnaces when there are any), keeps them fuelled, and takes
 * out the results, which are delivered like any harvest. Smelting itself
 * happens in the real furnaces at vanilla speed. Inputs and fuel come from
 * the backpack, topped up from the supply.
 */
final class SmelterJob implements Job {

    private List<BlockPos> furnaces = List.of();
    private String idle = "Looking for furnaces";

    @Override
    public Task next(Worker w) {
        WorkArea a = w.e.area;
        World world = w.world();
        if (a == null || !a.world().equals(world.getName())) {
            idle = "No work area in this world";
            return null;
        }
        if (w.cooldownReady("furnace-scan", 100) || furnaces.isEmpty()) {
            scan(world, a);
            furnaces.removeIf(p -> !w.mayWork(p));
        }
        if (furnaces.isEmpty()) {
            idle = "No furnaces, smokers or blast furnaces in the work area";
            return null;
        }

        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : furnaces) {
            if (w.isUnreachable(p) || plan(w, p, false) == null) continue;
            double d = p.distanceSq(w.e.location.getX(), w.e.location.getY(), w.e.location.getZ());
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        if (best != null) return new TendTask(best);

        // Nothing to do at the furnaces: top up from the supply.
        boolean noInput = inputs(w).isEmpty(), noFuel = fuel(w) == null;
        if ((noInput || noFuel) && w.cooldownReady("smelter-fetch", 20 * 30)) {
            Map<Material, Integer> avail = Supply.available(w.plugin(), w.e);
            Map<Material, Integer> want = new EnumMap<>(Material.class);
            for (Map.Entry<Material, Integer> e : avail.entrySet()) {
                Material m = e.getKey();
                if (w.e.filter.contains(m) && Smelting.smeltable(m)) want.put(m, e.getValue());
                else if (w.settings().smelterFuels.contains(m) && Items.count(w.e.backpack, m) < 32) want.put(m, Math.min(e.getValue(), 64));
            }
            if (!noInput) want.keySet().removeIf(m -> !w.settings().smelterFuels.contains(m));
            Task t = FetchTask.create(w, want, noInput ? "things to smelt" : "fuel");
            if (t != null) return t;
        }
        if (noInput) idle = "Nothing to smelt (filter: " + filterText(w) + ")";
        else if (noFuel) idle = "Out of fuel";
        else idle = "Waiting for the furnaces";
        return null;
    }

    private void scan(World world, WorkArea a) {
        List<BlockPos> list = new ArrayList<>();
        for (int x = a.minX(); x <= a.maxX(); x++) {
            for (int z = a.minZ(); z <= a.maxZ(); z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = a.minY(); y <= a.maxY(); y++) {
                    if (Smelting.Kind.of(world.getType(x, y, z)) != null) list.add(new BlockPos(x, y, z));
                }
            }
        }
        furnaces = list;
    }

    /** One thing to do at a furnace, or null if it's fine as it is. */
    private enum Step { TAKE, FUEL, LOAD, UNFUEL }

    /** Items one piece of fuel smelts (the same in every furnace kind). */
    private static double smeltsPer(Material fuel) {
        return switch (fuel) {
            case COAL, CHARCOAL -> 8;
            case COAL_BLOCK -> 80;
            case BLAZE_ROD -> 12;
            case DRIED_KELP_BLOCK -> 20;
            case LAVA_BUCKET -> 100;
            default -> fuel.name().endsWith("_LOG") || fuel.name().endsWith("_PLANKS") || fuel.name().endsWith("_STEM") ? 1.5 : 1;
        };
    }

    /** How much fuel a furnace should hold for what is (or will be) in it. */
    private static int fuelWanted(Material fuel, int items) {
        return Math.max(1, Math.min(fuel.getMaxStackSize(), (int) Math.ceil(items / smeltsPer(fuel))));
    }

    private Step plan(Worker w, BlockPos p, boolean here) {
        Block b = p.block(w.world());
        Smelting.Kind kind = Smelting.Kind.of(b.getType());
        if (kind == null || !(b.getState(false) instanceof Furnace f)) return null;
        FurnaceInventory inv = f.getInventory();
        ItemStack result = inv.getResult();
        if (result != null && !result.getType().isAir() && Items.room(w.e.backpack, result.getType()) > 0
                && (here || result.getAmount() >= 8 || inv.getSmelting() == null)) {
            return Step.TAKE;
        }
        ItemStack smelting = inv.getSmelting();
        Material load = inputFor(w, kind, smelting);
        boolean smelts = smelting != null && !smelting.getType().isAir();
        ItemStack fuelSlot = inv.getFuel();
        boolean fuelled = fuelSlot != null && !fuelSlot.getType().isAir();
        // Load first, so the fuel can be sized to what's in it.
        if (load != null && (!smelts || smelting.getAmount() <= 32)) return Step.LOAD;
        if (smelts) {
            int items = smelting.getAmount();
            if (!fuelled && f.getBurnTime() <= 0 && fuel(w) != null) return Step.FUEL;
            if (fuelled && w.settings().smelterFuels.contains(fuelSlot.getType()) && Items.count(w.e.backpack, fuelSlot.getType()) > 0
                    && fuelSlot.getAmount() < fuelWanted(fuelSlot.getType(), items) - 1) return Step.FUEL;
        } else if (fuelled && w.settings().smelterFuels.contains(fuelSlot.getType()) && fuel(w) == null && othersNeedFuel(w, p)) {
            return Step.UNFUEL;   // fuel stranded in an idle furnace while another one has none
        }
        return null;
    }

    private boolean othersNeedFuel(Worker w, BlockPos self) {
        for (BlockPos p : furnaces) {
            if (p.equals(self) || !(p.block(w.world()).getState(false) instanceof Furnace f)) continue;
            FurnaceInventory inv = f.getInventory();
            ItemStack in = inv.getSmelting(), fu = inv.getFuel();
            if (in != null && !in.getType().isAir() && (fu == null || fu.getType().isAir()) && f.getBurnTime() <= 0) return true;
        }
        return false;
    }

    /** What to put in this furnace's input slot (matching what's there, if anything). */
    private Material inputFor(Worker w, Smelting.Kind kind, ItemStack smelting) {
        if (smelting != null && !smelting.getType().isAir()) {
            Material m = smelting.getType();
            return smelting.getAmount() < m.getMaxStackSize() && Items.count(w.e.backpack, m) > 0 && w.e.filter.contains(m) ? m : null;
        }
        for (Material m : inputs(w)) {
            if (!Smelting.accepts(kind, m)) continue;
            // A plain furnace leaves food to smokers and ores to blast furnaces, if the area has them.
            if (kind == Smelting.Kind.FURNACE && betterKindExists(w, m)) continue;
            return m;
        }
        return null;
    }

    /** A smoker or blast furnace that could take this item right now (empty, or already smelting it). */
    private boolean betterKindExists(Worker w, Material m) {
        for (BlockPos p : furnaces) {
            Block b = p.block(w.world());
            Smelting.Kind k = Smelting.Kind.of(b.getType());
            if (k == null || k == Smelting.Kind.FURNACE || !Smelting.accepts(k, m) || !(b.getState(false) instanceof Furnace f)) continue;
            ItemStack in = f.getInventory().getSmelting();
            if (in == null || in.getType().isAir() || (in.getType() == m && in.getAmount() < m.getMaxStackSize())) return true;
        }
        return false;
    }

    private static List<Material> inputs(Worker w) {
        List<Material> out = new ArrayList<>();
        for (ItemStack s : w.e.backpack.getStorageContents()) {
            if (Items.isPlain(s) && w.e.filter.contains(s.getType()) && !out.contains(s.getType())) out.add(s.getType());
        }
        return out;
    }

    private static Material fuel(Worker w) {
        for (Material m : w.settings().smelterFuels) if (Items.count(w.e.backpack, m) > 0) return m;
        return null;
    }

    private static String filterText(Worker w) {
        if (w.e.filter.isEmpty()) return "empty";
        List<String> names = new ArrayList<>();
        for (Material m : w.e.filter) {
            if (names.size() == 3) {
                names.add("...");
                break;
            }
            names.add(Items.name(m));
        }
        return String.join(", ", names);
    }

    // ── tending ───────────────────────────────────────────────────────────

    private final class TendTask extends WalkThenAct {

        private final BlockPos pos;
        private int timer;

        TendTask(BlockPos pos) {
            super(new Vector(pos.centerX(), pos.centerY(), pos.centerZ()), 3.0, 2, 2.5);
            this.pos = pos;
        }

        @Override
        protected void arrive(Worker w) {
            w.lookAt(pos);
        }

        @Override
        protected Result act(Worker w) {
            if (++timer < w.clickTicks() * 2) return Result.RUNNING;
            timer = 0;
            Step step = plan(w, pos, true);
            if (step == null) return Result.DONE;
            Block b = pos.block(w.world());
            if (!(b.getState(false) instanceof Furnace f)) return Result.DONE;
            w.plugin().blockLog().container(w.e, b.getLocation());
            FurnaceInventory inv = f.getInventory();
            w.lookAt(pos);
            w.swing();
            switch (step) {
                case TAKE -> {
                    ItemStack result = inv.getResult();
                    int before = result.getAmount();
                    List<ItemStack> left = Items.add(w.e.backpack, List.of(result));
                    int kept = left.isEmpty() ? 0 : left.getFirst().getAmount();
                    inv.setResult(kept == 0 ? null : left.getFirst());
                    w.e.stats.itemsSmelted += before - kept;
                    w.playSound(b.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.4f, 1.2f);
                    w.plugin().manager().markDirty();
                }
                case FUEL -> {
                    ItemStack slot = inv.getFuel();
                    Material m = slot != null && !slot.getType().isAir() ? slot.getType() : fuel(w);
                    if (m == null) return Result.DONE;
                    int have = slot == null || slot.getType().isAir() ? 0 : slot.getAmount();
                    ItemStack in = inv.getSmelting();
                    int items = in == null || in.getType().isAir() ? 0 : in.getAmount();
                    int add = Math.min(Math.max(1, fuelWanted(m, items) - have), Items.count(w.e.backpack, m));
                    if (add <= 0) return Result.DONE;
                    Items.take(w.e.backpack, m, add);
                    inv.setFuel(new ItemStack(m, have + add));
                }
                case UNFUEL -> {
                    ItemStack slot = inv.getFuel();
                    if (slot == null || slot.getType().isAir()) return Result.DONE;
                    List<ItemStack> left = Items.add(w.e.backpack, List.of(slot));
                    inv.setFuel(left.isEmpty() ? null : left.getFirst());
                }
                case LOAD -> {
                    ItemStack slot = inv.getSmelting();
                    Smelting.Kind kind = Smelting.Kind.of(b.getType());
                    Material m = inputFor(w, kind, slot);
                    if (m == null) return Result.DONE;
                    int have = slot == null || slot.getType().isAir() ? 0 : slot.getAmount();
                    int share = Math.max(8, (Items.count(w.e.backpack, m) + furnaces.size() - 1) / Math.max(1, furnaces.size()));
                    int add = Math.min(Math.min(share, m.getMaxStackSize() - have), Items.count(w.e.backpack, m));
                    if (add <= 0) return Result.DONE;
                    Items.take(w.e.backpack, m, add);
                    inv.setSmelting(new ItemStack(m, have + add));
                }
            }
            w.playSound(b.getLocation(), Sound.BLOCK_FURNACE_FIRE_CRACKLE, 0.5f, 1f);
            return Result.RUNNING;
        }

        @Override
        public String status(Worker w) {
            Material type = pos.block(w.world()).getType();
            return "Tending the " + Items.name(type);
        }
    }

    // ── job hooks ─────────────────────────────────────────────────────────

    @Override
    public String idleStatus(Worker w) {
        return idle;
    }

    @Override
    public boolean isReserved(Material m) {
        return false;
    }

    @Override
    public int reserve(Worker w, Material m) {
        return w.e.filter.contains(m) || w.settings().smelterFuels.contains(m) ? w.jobSettings().reserve() : 0;
    }

    @Override
    public boolean collects(Material m) {
        return false;
    }

    @Override
    public boolean canWorkWhenFull() {
        return true;
    }
}
