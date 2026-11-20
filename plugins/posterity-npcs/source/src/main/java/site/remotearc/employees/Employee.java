package site.remotearc.employees;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Mannequin;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import site.remotearc.employees.build.BuildOrder;
import site.remotearc.employees.job.JobType;
import site.remotearc.employees.job.Worker;
import site.remotearc.employees.storage.StorageTarget;
import site.remotearc.employees.util.BlockPos;
import site.remotearc.employees.util.WorkArea;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** One hired employee: its saved record plus the runtime handles the manager attaches. */
public final class Employee {

    public static final int BACKPACK_SIZE = 36;

    public final UUID id;
    public final UUID owner;
    public final JobType job;
    public String name;
    public Skin skin;
    public WorkArea area;
    /** Where the employee waits when there is nothing to do. */
    public Location home;
    /** Where its feet are right now; the entity is moved to match this. */
    public Location location;
    public StorageTarget storage = StorageTarget.NONE;
    /** Extra /chest numbers claimed when the storage chest filled up, in order. */
    public final List<Integer> overflow = new ArrayList<>();
    public boolean paused;
    /** Keeps working while nobody is on the server (otherwise it rests then). */
    public boolean worksAway;
    /** Log every task it finishes, and why it idles, to the server console (/employees debug); kept over restarts. */
    public boolean debug;
    /**
     * Its food store, in food points (an item's nutrition plus saturation; see
     * Food). Eaten as it goes; topped up from its pantry. At 0 it's too hungry
     * to work.
     */
    public double food = 33;
    /** Where it gets its food: a chest, a /chest, or NONE for wherever it delivers to. */
    public StorageTarget pantry = StorageTarget.NONE;
    public long hiredAt;
    /** The villager type (its biome look, e.g. minecraft:plains) when it was hired from a villager; null if it wasn't. */
    public String villagerType;
    /** Its one personality trait, rolled at hiring: the owner doesn't pick it. */
    public Personality personality;
    /** The kingdom it was hired into (PosterityKingdoms), or null if there wasn't one. */
    public String kingdom;
    /** Its home (bed and door), or null: homeless. */
    public House house;
    /** The catalog house placed for it to live in, while it's built (and after); null if none. */
    public BuildOrder homeSite;
    /** Where the home site's bed (head half) and door (bottom half) go. */
    public BlockPos siteBed, siteDoor;
    /** The box the home site takes (kept so it's known without reading the plans). */
    public WorkArea siteArea;
    /** Blocks the home site takes in all, and how far along it is (percent, -1 = not checked yet; not saved). */
    public int siteBlocks;
    public int siteProgress = -1;
    /** Builds its home site itself, on breaks and when its job has nothing to do. */
    public boolean selfBuild;
    public final Stats stats = new Stats();
    public Inventory backpack;
    /** Items that didn't fit back in the backpack on load (not saved): dropped once it's actually spawned somewhere. */
    public final List<org.bukkit.inventory.ItemStack> spill = new ArrayList<>();
    /**
     * Lumberjack: sapling spots and the sapling each takes.
     * Farmer: crop positions and the crop block that grows there.
     * Quarry miner: plugs it left standing as dams against water and lava.
     */
    public final Map<BlockPos, Material> spots = new HashMap<>();
    /**
     * Miner: the blocks it looks for. Quarry miner: drops it throws away.
     * Smelter: what it smelts or cooks.
     */
    public final Set<Material> filter = EnumSet.noneOf(Material.class);
    /** Extra places a builder, smelter or rancher takes materials from (besides the team's storage). */
    public final List<StorageTarget> supply = new ArrayList<>();
    /** Also take materials from where the owner's other employees deliver. */
    public boolean teamSupply = true;
    /**
     * No-work zones: boxes it leaves alone (a guard leaves mobs there alone, an
     * iron golem farm's zombie; a miner doesn't dig there...). Its own boxes,
     * plus its owner's presets by name. See ZonePresets.
     */
    public final List<WorkArea> zones = new ArrayList<>();
    public final List<String> zonePresets = new ArrayList<>();
    /** Miner: move on to new ground when its area is mined out; the area it started from and how far round it is. */
    public boolean roam;
    public WorkArea roamBase;
    public int roamStep;
    /**
     * Trader: a picked trade, by villager and offer (what goes in and what comes out), so it
     * still matches after the villager restocks or its prices change.
     */
    public record TradeChoice(UUID villager, Material input1, Material input2, Material result) {
        public boolean matches(org.bukkit.inventory.MerchantRecipe r) {
            var ing = r.getIngredients();
            Material a = ing.isEmpty() ? Material.AIR : ing.get(0).getType();
            Material b = ing.size() > 1 && ing.get(1) != null ? ing.get(1).getType() : Material.AIR;
            return r.getResult().getType() == result && a == input1 && b == input2;
        }

        public String save() {
            return villager + ":" + input1.name() + ":" + input2.name() + ":" + result.name();
        }

        public static TradeChoice load(String s) {
            String[] p = s.split(":");
            return new TradeChoice(UUID.fromString(p[0]), Material.valueOf(p[1]), Material.valueOf(p[2]), Material.valueOf(p[3]));
        }
    }

    public final List<TradeChoice> trades = new ArrayList<>();

    /**
     * Trader: a spot in the trading hall for one villager (where it stands),
     * its workstation (or null), and a trade to reroll for (an item or an
     * enchantment, e.g. "mending"), or null.
     */
    public static final class Stall {
        public final BlockPos stand;
        /** Where its workstation is or goes (null: none). */
        public BlockPos workstation;
        public String want;
        /** The job it's for (a profession key, "librarian"), for when the workstation isn't there yet; or null. */
        public String profession;
        /** Where a trapdoor goes under the roof once the villager is in, so it can't jump out (null: none). */
        public BlockPos door;

        public Stall(BlockPos stand, BlockPos workstation, String want) {
            this.stand = stand;
            this.workstation = workstation;
            this.want = want;
        }

        String save() {
            return stand + "|" + (workstation == null ? "" : workstation.toString()) + "|" + (want == null ? "" : want)
                    + "|" + (profession == null ? "" : profession) + "|" + (door == null ? "" : door.toString());
        }

        static Stall load(String s) {
            String[] a = s.split("\\|", -1);
            Stall st = new Stall(BlockPos.parse(a[0]), a.length > 1 && !a[1].isEmpty() ? BlockPos.parse(a[1]) : null,
                    a.length > 2 && !a[2].isEmpty() ? a[2] : null);
            if (a.length > 3 && !a[3].isEmpty()) st.profession = a[3];
            if (a.length > 4 && !a[4].isEmpty()) st.door = BlockPos.parse(a[4]);
            return st;
        }
    }

    /** Trader: villagers to move somewhere (/employees stalls <name> move), and where to. Not saved. */
    public transient java.util.Deque<java.util.UUID> moveQueue = new java.util.ArrayDeque<>();
    public transient org.bukkit.Location moveTo;

    public final List<Stall> stalls = new ArrayList<>();
    /** Trader: crafts what its trades cost out of what's in storage (logs into sticks). */
    public boolean craft = true;
    /** Trader: never crafts with these (diamonds...). */
    public final Set<Material> craftNever = EnumSet.noneOf(Material.class);
    /** Runtime: no saved never-craft list yet, so the config default applies. */
    public transient boolean craftNeverUnset;
    /**
     * Courier: carry items from one place to another. {@code items} empty =
     * everything; {@code keep} of each stays behind at the source; with
     * {@code stock} > 0 it only tops the destination up to that many of each.
     */
    public record Route(StorageTarget from, StorageTarget to, Set<Material> items, int keep, int stock) {
        public boolean carries(Material m) {
            return items.isEmpty() || items.contains(m);
        }

        /** Carries to a merchant and sells there, instead of to a chest. */
        public boolean sells() {
            return to.kind() == StorageTarget.Kind.NONE;
        }

        public String describe() {
            return from.describe() + " → " + (sells() ? "a merchant" : to.describe());
        }

        public String rules() {
            List<String> parts = new ArrayList<>();
            parts.add(items.isEmpty() ? "everything" : items.size() <= 3
                    ? String.join(", ", items.stream().map(site.remotearc.employees.util.Items::name).toList())
                    : items.size() + " kinds of item");
            if (keep > 0) parts.add("leaves " + keep + " of each");
            if (stock > 0 && !sells()) parts.add("tops up to " + stock + " of each");
            if (sells()) parts.add("sells it at the nearest merchant");
            return String.join("; ", parts);
        }

        public String save() {
            return from.toShort() + "|" + (sells() ? "merchant" : to.toShort()) + "|" + String.join(",", items.stream().map(Material::name).toList())
                    + "|" + keep + "|" + stock;
        }

        public static Route load(String s) {
            String[] p = s.split("\\|", -1);
            if (p.length < 5) return null;
            Set<Material> items = EnumSet.noneOf(Material.class);
            for (String n : p[2].split(",")) {
                Material m = n.isEmpty() ? null : Material.matchMaterial(n);
                if (m != null) items.add(m);
            }
            StorageTarget from = StorageTarget.fromShort(p[0]), to = StorageTarget.fromShort(p[1]);
            if (from.kind() == StorageTarget.Kind.NONE || (to.kind() == StorageTarget.Kind.NONE && !p[1].equals("merchant"))) return null;
            try {
                return new Route(from, to, items, Math.max(0, Integer.parseInt(p[3])), Math.max(0, Integer.parseInt(p[4])));
            } catch (NumberFormatException ex) {
                return null;
            }
        }
    }

    public final List<Route> routes = new ArrayList<>();
    /** Guard, bodyguard: armour worn, boots to helmet (null = none), put on from its backpack. */
    public final org.bukkit.inventory.ItemStack[] armor = new org.bukkit.inventory.ItemStack[4];
    /** Guard, bodyguard: knocked out by mobs until this time (epoch millis), and what did it. */
    public long knockedOutUntil;
    public String knockedOutBy;
    /** Runtime: when a mob last hurt it. */
    public transient long lastHurt;
    /** Bodyguard: following its owner (false = staying at its post). */
    public boolean following = true;
    /** Rancher: animals of each kind to keep (0 = the config default). */
    public int herdSize;
    /**
     * How an item is sold. Any mix of:
     *  keep    stock its storage up to this many first (0 = none), selling down any extra;
     *  percent of what's beyond that, the share sold (the rest is stored); 100 = all;
     *  daily   at most this many sold per 24 hours (0 = no limit).
     * "keep 256", "sell 256" (= daily), "keep 50%" (= percent 50), "sell 25%",
     * "keep 256 sell 50%" all map onto these three.
     */
    public record SellRule(int keep, int percent, int daily) {
        public static final SellRule ALL = new SellRule(0, 100, 0);

        public String describe() {
            List<String> parts = new ArrayList<>();
            if (keep > 0) parts.add("keeps " + keep + " in storage");
            String what = keep > 0 ? "the rest" : "of each delivery";
            if (percent >= 100) parts.add(keep > 0 ? "sells the rest" : "sells all of it");
            else parts.add("sells " + percent + "% " + what);
            if (daily > 0) parts.add("up to " + daily + " a day");
            return String.join(", ", parts);
        }

        public String save() {
            return keep + ":" + percent + ":" + daily;
        }

        /** "keep:percent:daily", or the short-lived "KIND:value" format. */
        public static SellRule load(String[] p, int from) {
            String a = p[from];
            if (Character.isLetter(a.charAt(0))) {
                int v = Integer.parseInt(p[from + 1]);
                return switch (a) {
                    case "KEEP" -> new SellRule(v, 100, 0);
                    case "SELL" -> new SellRule(0, 100, v);
                    case "KEEP_PERCENT" -> new SellRule(0, 100 - v, 0);
                    default -> new SellRule(0, v, 0);
                };
            }
            int keep = Integer.parseInt(a);
            int pct = p.length > from + 1 ? Integer.parseInt(p[from + 1]) : 100;
            int daily = p.length > from + 2 ? Integer.parseInt(p[from + 2]) : 0;
            return new SellRule(keep, pct, daily);
        }
    }

    /** One sale, for the menus and boards. */
    public record SaleRecord(long time, int count, double money, String what) {}

    /** Sells everything the market buys from its deliveries, by sellAllRule. */
    public boolean sellAll;
    public SellRule sellAllRule = SellRule.ALL;
    /** Or sells just these, each by its own rule. */
    public final Map<Material, SellRule> sellRules = new java.util.EnumMap<>(Material.class);
    /** Runtime: fractions of an item owed by a percentage rule, and the latest sales. */
    public final Map<Material, Double> sellCarry = new java.util.EnumMap<>(Material.class);
    public final java.util.Deque<SaleRecord> sales = new java.util.ArrayDeque<>();
    /** How many of each item it sold, with when: {time, count}, for "sell n a day". */
    public final Map<Material, java.util.Deque<long[]>> soldLog = new java.util.EnumMap<>(Material.class);

    /** How many of an item it sold in the last 24 hours. */
    public int soldToday(Material m) {
        java.util.Deque<long[]> log = soldLog.get(m);
        if (log == null) return 0;
        long cutoff = System.currentTimeMillis() - 86_400_000L;
        while (!log.isEmpty() && log.peekLast()[0] < cutoff) log.removeLast();
        int n = 0;
        for (long[] r : log) n += (int) r[1];
        return n;
    }

    public boolean selling() {
        return sellAll || !sellRules.isEmpty();
    }

    /** Never sold, whatever else says so: "sell everything except these". */
    public final java.util.Set<Material> neverSell = java.util.EnumSet.noneOf(Material.class);

    public SellRule ruleFor(Material m) {
        if (neverSell.contains(m)) return null;
        SellRule r = sellRules.get(m);
        return r != null ? r : sellAll ? sellAllRule : null;
    }

    /** Items sold and money made in the last 24 hours. */
    public double[] soldToday() {
        long cutoff = System.currentTimeMillis() - 86_400_000L;
        double money = 0, count = 0;
        for (SaleRecord r : sales) {
            if (r.time() < cutoff) continue;
            money += r.money();
            count += r.count();
        }
        return new double[]{count, money};
    }
    /** Builder: the blueprint it is building, or null. */
    public BuildOrder build;

    // Runtime only.
    public long groupFullWarned;
    public Mannequin entity;
    public Worker worker;
    public String status = "Starting work";

    public Employee(UUID id, UUID owner, JobType job, String name) {
        this.id = id;
        this.owner = owner;
        this.job = job;
        this.name = name;
        this.backpack = Bukkit.createInventory(new Backpack(this), BACKPACK_SIZE, Component.text(name + "'s backpack"));
    }

    /** Renames the employee; the backpack is rebuilt so its title follows. */
    public void rename(String newName) {
        this.name = newName;
        Inventory old = backpack;
        for (var viewer : java.util.List.copyOf(old.getViewers())) viewer.closeInventory();
        backpack = Bukkit.createInventory(new Backpack(this), BACKPACK_SIZE, Component.text(newName + "'s backpack"));
        backpack.setContents(old.getContents());
        old.clear();
    }

    public OfflinePlayer ownerPlayer() {
        return Bukkit.getOfflinePlayer(owner);
    }

    public String ownerName() {
        String n = ownerPlayer().getName();
        return n == null ? owner.toString().substring(0, 8) : n;
    }

    public boolean isLoaded() {
        return entity != null && entity.isValid();
    }

    /** Lifetime counters shown in the menu. */
    public static final class Stats {
        public long treesFelled;
        public long logsCut;
        public long saplingsPlanted;
        public long cropsHarvested;
        public long blocksMined;
        public long oresMined;
        public long blocksPlaced;
        public long itemsSmelted;
        public long animalsBred;
        public long animalsCulled;
        public long sheepSheared;
        public long mobsKilled;
        public double earned;
        public long tradesMade;
        public long itemsSold;
        public long itemsDelivered;
        /** Courier: items carried from one chest to another. */
        public long itemsMoved;
        /** Builder: blocks put back by repair mode. */
        public long blocksRepaired;

        /** The activity counters, in the order of {@link site.remotearc.employees.Ledger#ACTS}. */
        public long[] counters() {
            return new long[]{treesFelled, logsCut, saplingsPlanted, cropsHarvested, blocksMined, oresMined, blocksPlaced,
                    itemsSmelted, animalsBred, animalsCulled, sheepSheared, mobsKilled, tradesMade, itemsMoved, blocksRepaired};
        }
    }

    /** Marks the backpack inventory so menus and listeners can tell whose it is. */
    public static final class Backpack implements InventoryHolder {
        public final Employee employee;

        Backpack(Employee employee) {
            this.employee = employee;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return employee.backpack;
        }
    }
}
