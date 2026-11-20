package site.remotearc.employees;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;
import site.remotearc.employees.job.JobType;
import site.remotearc.employees.util.MaterialList;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/** A typed snapshot of config.yml, rebuilt on /employees reload. */
public final class Settings {

    /**
     * workSpeed: 1.0 = as fast as a survival player with the same tool; 2.0 = twice that.
     * The area values are what hiring sets up: radius blocks each way, from
     * areaBelow blocks below the hiring spot to areaAbove above it.
     */
    /** hireItems: the tools of the trade a villager asks for to take the job (the food comes on top, see hireFood). */
    public record JobSettings(List<org.bukkit.inventory.ItemStack> hireItems, Material tool, double workSpeed, int reserve,
                              int areaRadius, int areaBelow, int areaAbove, int maxAreaSize, int maxAreaHeight) {}

    public final String currency;
    public final int maxPerPlayer;
    public final int maxPerKingdom;
    public final boolean keepLoaded;
    public final int maxAreaSize;
    public final int defaultRadius;
    public final boolean mailWhenOffline;
    public final boolean restWhenEmpty;

    // Food (see Food): employees eat from a pantry instead of being paid.
    /** Food points (an item's nutrition plus saturation: bread is 11) eaten per Minecraft day. */
    public final double foodPerDay;
    /** Never eaten, even when it's all there is (rotten flesh, golden apples...). */
    public final Set<Material> foodNever;
    /** When its own store drops under this many days, it takes more from the pantry, up to foodTopUpDays. */
    public final double foodLowDays;
    public final double foodTopUpDays;

    // Hiring cost: tools of the trade (per job) plus food.
    public final Material hireFood;
    public final int hireFoodAmount;
    public final int hireFoodPerEmployee;
    public final int hireFoodMax;
    /** Days a villager stays reserved for someone before going back to village life; 0 = for ever. */
    public final double reserveDays;
    /** How many villagers one player may have reserved at once. */
    public final int maxReserved;

    // The working day (see Schedule), in hours on the Minecraft clock (6 = sunrise, 18 = sunset).
    public final boolean scheduleEnabled;
    public final double workStart, workEnd, sleepStart;
    /** Jobs that stay on duty through the evening and night. */
    public final Set<JobType> nightShift;
    public final boolean vcEnabled;
    public final boolean chestsEnabled;
    public final int vcFirstChest;
    public final long vcDeliveryTicks;
    public final int vcOverflow;
    public final int groupSize;
    private final Map<JobType, Integer> groupStart = new EnumMap<>(JobType.class);
    public final double walkSpeed;
    public final List<String> names;
    private final Map<JobType, JobSettings> jobs = new EnumMap<>(JobType.class);

    // Job-specific settings.
    public final Set<Material> minerTargets;
    public final boolean minerXray;
    public final boolean minerKeepTunnel;
    public final List<Material> quarryFiller;
    public final int builderMaxVolume;
    public final int previewBlocks;
    public final int previewSeconds;
    public final Set<Material> smelterInputs;
    public final Set<Material> smelterFuels;
    public final int herdSize;
    /** Ranchers keep breeding past the herd size and cull the oldest, so a full herd keeps producing. */
    public final boolean rancherKeepBreeding;
    /** Guards and bodyguards can be hurt by mobs (not creepers), wear armour, and are knocked out instead of dying. */
    public final boolean guardsTakeDamage;
    public final int knockoutSeconds;
    /** Employees' personality traits now and then distract them from work. */
    public final boolean personalitiesEnabled;
    /** Ticks an employee stays focused between two distractions (varied by +-30%). */
    public final int personalityCalmTicks;
    /** Employees need a home; without one they work at homelessWorkSpeed. */
    public final boolean homesEnabled;
    public final double homelessWorkSpeed;
    /** Percent of a home site that must be built (bed and door included) before the employee moves in. */
    public final int homeFinishedPercent;
    /** Ticks between an employee's breaks to build its own home. */
    public final int homeSelfBuildBreakTicks;
    public final Set<EntityType> ranchAnimals;
    public final Set<EntityType> guardIgnore;
    /** What a new trader never crafts with. */
    public final Set<Material> traderNeverCraft;
    public final boolean lumberAutoPlant;
    public final double bodyguardRadius;
    public final int minerRoamDistance;
    public final int lumberSpacing;
    public final int lumberRowSpacing;
    public final boolean marketEnabled;
    public final double marketRatio;
    public final double marketDemand;
    public final double marketRecoveryHours;
    public final double marketDailyCap;
    /** Past the day's limit merchants still buy from players, at this share of the price (0 = not at all). */
    public final double marketOverRate;
    public final boolean reportOnJoin;
    public final long reportMinAwayMillis;
    public final boolean coreProtect;
    public final String coreProtectPrefix;
    public final int repairCheckSeconds;
    public final int courierWalkDistance;
    public final boolean marketSellAnywhere;
    public final boolean merchantsOnly;
    public final Set<String> blockedCommands = new java.util.HashSet<>();
    public final Map<Material, Double> marketExtras = new EnumMap<>(Material.class);
    /** What merchants charge for blocks ArcShop doesn't sell, when buying for a build (leaves, vines...). */
    public final Map<Material, Double> buildPrices = new EnumMap<>(Material.class);
    /** What merchants charge, for builds, for anything else not in /shop that can't be crafted from it (0: not sold). */
    public final double buildFallbackPrice;
    public final boolean downloadsEnabled;
    public final long downloadMaxBytes;
    /** BlueMap is public with no sign-in, so each of its three layers can be turned off if that's too much to share. */
    public final boolean blueMapShowEmployees, blueMapShowPlans, blueMapShowTrades;
    /** The web page uploads go through, ending in /; empty turns uploads off. */
    public final String uploadUrl;

    // Hiring villagers (see VillagerHiring).
    /** Employees are hired from villagers only; /employees hire on its own (spawning one from nothing) is for admins. */
    public final boolean villagersOnly;
    /** Only villagers that came to the world the normal way (generated, born, cured) can be hired, not spawn-egg or command ones. */
    public final boolean villagerNaturalOnly;
    /** A fired employee that was a villager goes back to being one where it stands. */
    public final boolean villagerReleaseOnFire;
    /** Villagers that come to a player's first kingdom (with Kingdoms &amp; Claims), free to hire; 0 = none. */
    public final int kingdomSettlers;
    /** Days an unhired settler stays invulnerable before it's just a villager again (0 = forever). */
    public final int settlerExpireDays;
    /** A hired villager's village gets a baby villager to replace it, after a delay, up to its old population. */
    public final boolean replenishEnabled;
    public final long replenishDelayMillis;
    /** How far round the hiring spot counts as the village, for its population. */
    public final int replenishRadius;
    /** Never replenish a village that already has this many villagers round the spot. */
    public final int replenishMaxNearby;

    public Settings(FileConfiguration c, Logger log) {
        currency = c.getString("currency-symbol", "$");
        maxPerPlayer = Math.max(0, c.getInt("max-employees-per-player", 6));
        maxPerKingdom = Math.max(0, c.getInt("max-employees-per-kingdom", 6));
        keepLoaded = c.getBoolean("keep-work-sites-loaded", true);
        maxAreaSize = clamp(c.getInt("max-area-size", 48), 3, 128);
        defaultRadius = clamp(c.getInt("default-area-radius", 8), 1, maxAreaSize / 2);
        mailWhenOffline = c.getBoolean("mail-when-offline", c.getBoolean("payroll.mail-when-offline", true));
        restWhenEmpty = c.getBoolean("rest-when-server-empty", c.getBoolean("payroll.rest-when-server-empty", true));
        foodPerDay = Math.max(0, c.getDouble("food.points-per-day", 11));
        foodNever = MaterialList.parse(c.getStringList("food.never-eat"), log, "food.never-eat");
        foodLowDays = Math.max(0.1, c.getDouble("food.top-up-below-days", 1));
        foodTopUpDays = Math.max(foodLowDays, c.getDouble("food.top-up-to-days", 3));
        Material hf = Material.matchMaterial(c.getString("hiring.food", "BREAD"));
        hireFood = hf != null && hf.isItem() ? hf : Material.BREAD;
        hireFoodAmount = clamp(c.getInt("hiring.food-amount", 16), 0, 64 * 9);
        hireFoodPerEmployee = clamp(c.getInt("hiring.food-extra-per-employee", 2), 0, 64);
        hireFoodMax = clamp(c.getInt("hiring.food-max", 64), hireFoodAmount, 64 * 9);
        reserveDays = Math.max(0, c.getDouble("hiring.reserve-days", 7));
        maxReserved = clamp(c.getInt("hiring.max-reserved", 3), 0, 50);
        scheduleEnabled = c.getBoolean("schedule.enabled", true);
        workStart = hour(c.getDouble("schedule.work-start", 6));
        workEnd = hour(c.getDouble("schedule.work-end", 18));
        sleepStart = hour(c.getDouble("schedule.sleep-start", 21));
        nightShift = EnumSet.noneOf(JobType.class);
        for (String k : c.getStringList("schedule.night-shift-jobs")) {
            JobType t = JobType.parse(k);
            if (t == null) log.warning("schedule.night-shift-jobs: unknown job " + k);
            else nightShift.add(t);
        }
        vcEnabled = c.getBoolean("virtual-chests.enabled", true);
        chestsEnabled = c.getBoolean("physical-chests.enabled", true);
        vcFirstChest = Math.max(1, c.getInt("virtual-chests.first-chest", 5));
        vcDeliveryTicks = Math.max(1, c.getInt("virtual-chests.delivery-interval-seconds", 30)) * 20L;
        vcOverflow = Math.max(0, Math.min(20, c.getInt("virtual-chests.overflow-chests", 10)));
        groupSize = Math.max(2, Math.min(100, c.getInt("virtual-chests.group-size", 50)));
        int g = 100;
        for (JobType t : JobType.values()) {
            groupStart.put(t, Math.max(1, c.getInt("virtual-chests.groups." + t.key(), g)));
            g += 50;
        }
        walkSpeed = Math.min(0.6, Math.max(0.05, c.getDouble("walk-speed", 0.2)));
        names = c.getStringList("names");

        for (JobType t : JobType.values()) {
            ConfigurationSection s = c.getConfigurationSection("jobs." + t.key());
            if (s == null) s = c.createSection("jobs." + t.key());
            Material tool = Material.matchMaterial(s.getString("tool", t.defaultTool().name()));
            if (tool == null || !tool.isItem()) {
                log.warning("jobs." + t.key() + ".tool is not an item; using " + t.defaultTool());
                tool = t.defaultTool();
            }
            int[] area = defaultArea(t);   // radius, below, above, max size, max height
            int maxSize = clamp(s.getInt("max-area-size", area[3]), 3, 256);
            List<org.bukkit.inventory.ItemStack> hireItems = new java.util.ArrayList<>();
            List<String> asked = s.isList("hire-items") ? s.getStringList("hire-items") : List.of(t.defaultTool().name());
            for (String entry : asked) {
                org.bukkit.inventory.ItemStack it = itemStack(entry);
                if (it == null) log.warning("jobs." + t.key() + ".hire-items: can't read \"" + entry + "\" (write e.g. IRON_HOE or BREAD 16)");
                else hireItems.add(it);
            }
            jobs.put(t, new JobSettings(
                    List.copyOf(hireItems),
                    tool,
                    Math.max(0.05, Math.min(20, s.getDouble("work-speed", 1.0))),
                    clamp(s.getInt("reserve", defaultReserve(t)), 0, 2304),
                    clamp(s.getInt("area-radius", area[0]), 1, maxSize / 2),
                    clamp(s.getInt("area-below", area[1]), -1, 512),
                    clamp(s.getInt("area-above", area[2]), -1, 512),
                    maxSize,
                    clamp(s.getInt("max-area-height", area[4]), 3, 512)));
        }

        minerTargets = MaterialList.parse(c.getStringList("jobs.miner.targets"), log, "jobs.miner.targets");
        if (minerTargets.isEmpty()) minerTargets.addAll(MaterialList.ORES);
        minerXray = c.getBoolean("jobs.miner.x-ray", false);
        minerKeepTunnel = c.getBoolean("jobs.miner.keep-tunnel-blocks", false);
        quarryFiller = List.copyOf(MaterialList.parse(c.getStringList("jobs.quarry.filler"), log, "jobs.quarry.filler"));
        builderMaxVolume = clamp(c.getInt("jobs.builder.max-volume", 2_000_000), 1000, 16_000_000);
        previewBlocks = clamp(c.getInt("jobs.builder.preview-blocks", 30_000), 0, 200_000);
        previewSeconds = clamp(c.getInt("jobs.builder.preview-seconds", 60), 5, 600);
        smelterInputs = MaterialList.parse(c.getStringList("jobs.smelter.smelts"), log, "jobs.smelter.smelts");
        smelterFuels = MaterialList.parse(c.getStringList("jobs.smelter.fuels"), log, "jobs.smelter.fuels");
        smelterFuels.removeIf(m -> !m.isFuel());
        if (smelterFuels.isEmpty()) smelterFuels.addAll(EnumSet.of(Material.COAL, Material.CHARCOAL, Material.COAL_BLOCK));
        herdSize = clamp(c.getInt("jobs.rancher.herd-size", 10), 2, 200);
        rancherKeepBreeding = c.getBoolean("jobs.rancher.keep-breeding", true);
        guardsTakeDamage = c.getBoolean("jobs.guard.take-damage", true);
        knockoutSeconds = clamp(c.getInt("jobs.guard.knockout-seconds", 60), 5, 3600);
        personalitiesEnabled = c.getBoolean("personalities.enabled", true);
        personalityCalmTicks = 20 * 60 * clamp(c.getInt("personalities.calm-minutes", 8), 1, 240);
        homesEnabled = c.getBoolean("homes.enabled", true);
        homelessWorkSpeed = Math.max(0.1, Math.min(1.0, c.getDouble("homes.homeless-work-speed", 0.75)));
        homeFinishedPercent = clamp(c.getInt("homes.finished-percent", 90), 1, 100);
        homeSelfBuildBreakTicks = 20 * 60 * clamp(c.getInt("homes.self-build-break-minutes", 5), 1, 240);
        Set<EntityType> animals = EnumSet.noneOf(EntityType.class);
        for (String a : c.getStringList("jobs.rancher.animals")) {
            try {
                animals.add(EntityType.valueOf(a.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                log.warning("jobs.rancher.animals: unknown animal " + a);
            }
        }
        if (animals.isEmpty()) animals.addAll(EnumSet.of(EntityType.COW, EntityType.PIG, EntityType.SHEEP, EntityType.CHICKEN));
        ranchAnimals = animals;
        Set<EntityType> ignore = EnumSet.noneOf(EntityType.class);
        for (String a : c.getStringList("jobs.bodyguard.ignore")) {
            try {
                ignore.add(EntityType.valueOf(a.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                log.warning("jobs.bodyguard.ignore: unknown mob " + a);
            }
        }
        guardIgnore = ignore;
        traderNeverCraft = MaterialList.parse(c.getStringList("jobs.trader.never-craft-with"), log, "jobs.trader.never-craft-with");
        lumberAutoPlant = c.getBoolean("jobs.lumberjack.auto-plant", true);
        bodyguardRadius = Math.max(3, Math.min(32, c.getDouble("jobs.bodyguard.protect-radius", 12)));
        minerRoamDistance = Math.max(16, Math.min(4096, c.getInt("jobs.miner.roam-distance", 256)));
        lumberSpacing = clamp(c.getInt("jobs.lumberjack.spacing", 3), 2, 16);
        lumberRowSpacing = clamp(c.getInt("jobs.lumberjack.row-spacing", 5), 2, 16);
        marketEnabled = c.getBoolean("market.enabled", true);
        marketRatio = Math.max(0, Math.min(1, c.getDouble("market.sell-ratio", 0.25)));
        marketDemand = Math.max(1, c.getDouble("market.demand", 256));
        marketRecoveryHours = Math.max(0.1, c.getDouble("market.demand-recovery-hours", 24));
        marketDailyCap = Math.max(0, c.getDouble("market.max-income-per-day", 300));
        marketOverRate = Math.max(0, Math.min(1, c.getDouble("market.over-limit-rate", 0.25)));
        reportOnJoin = c.getBoolean("report.on-join", true);
        reportMinAwayMillis = Math.max(0, c.getLong("report.min-away-minutes", 30)) * 60_000L;
        coreProtect = c.getBoolean("coreprotect.enabled", true);
        String prefix = c.getString("coreprotect.user-prefix", "#");
        coreProtectPrefix = prefix == null ? "#" : prefix.replaceAll("\\s", "");
        repairCheckSeconds = clamp(c.getInt("jobs.builder.repair-check-seconds", 60), 10, 3600);
        courierWalkDistance = clamp(c.getInt("jobs.courier.walk-distance", 64), 0, 256);
        marketSellAnywhere = c.getBoolean("market.sell-anywhere", false);
        merchantsOnly = c.getBoolean("market.merchants-only", true);
        for (String cmd : c.getStringList("market.blocked-commands")) blockedCommands.add(cmd.toLowerCase(Locale.ROOT).replace("/", ""));
        ConfigurationSection extra = c.getConfigurationSection("market.prices");
        if (extra != null) {
            for (String k : extra.getKeys(false)) {
                Material m = Material.matchMaterial(k);
                if (m == null) log.warning("market.prices: unknown item " + k);
                else marketExtras.put(m, Math.max(0, extra.getDouble(k)));
            }
        }
        // Blocks nobody can get from /shop or by crafting, so a build that uses them could never be bought.
        for (Material m : org.bukkit.Tag.LEAVES.getValues()) buildPrices.put(m, 2.0);
        for (Material m : org.bukkit.Tag.FLOWERS.getValues()) if (m.isItem()) buildPrices.put(m, 2.0);
        for (Material m : org.bukkit.Tag.SAPLINGS.getValues()) if (m.isItem()) buildPrices.put(m, 2.0);
        // Plants and the like by name, so a version without one of them still loads.
        for (String n : List.of("FIREFLY_BUSH", "BUSH", "LEAF_LITTER", "WILDFLOWERS", "SHORT_DRY_GRASS", "TALL_DRY_GRASS",
                "CACTUS_FLOWER", "DEAD_BUSH", "PINK_PETALS", "SPORE_BLOSSOM", "AZALEA", "FLOWERING_AZALEA", "BIG_DRIPLEAF",
                "SMALL_DRIPLEAF", "SEAGRASS", "SEA_PICKLE", "KELP", "GLOW_BERRIES", "SWEET_BERRIES", "CRIMSON_ROOTS", "WARPED_ROOTS",
                "NETHER_SPROUTS", "TWISTING_VINES", "WEEPING_VINES", "CRIMSON_FUNGUS", "WARPED_FUNGUS", "SHROOMLIGHT",
                "OPEN_EYEBLOSSOM", "CLOSED_EYEBLOSSOM", "MANGROVE_ROOTS", "MUDDY_MANGROVE_ROOTS", "MANGROVE_PROPAGULE",
                "BROWN_MUSHROOM", "RED_MUSHROOM", "CACTUS", "SUGAR_CANE", "BAMBOO", "CHORUS_FLOWER")) {
            Material m = Material.matchMaterial(n);
            if (m != null && m.isItem()) buildPrices.put(m, 2.0);
        }
        for (Material m : List.of(Material.VINE, Material.MOSS_BLOCK, Material.SHORT_GRASS, Material.TALL_GRASS, Material.FERN,
                Material.LARGE_FERN, Material.LILY_PAD, Material.GLOW_LICHEN, Material.HANGING_ROOTS, Material.PALE_MOSS_BLOCK,
                Material.PALE_HANGING_MOSS, Material.MUD, Material.ICE, Material.SNOW_BLOCK, Material.COBWEB)) buildPrices.put(m, 2.0);
        buildFallbackPrice = Math.max(0, c.getDouble("market.build-fallback-price", 2.0));
        ConfigurationSection bp = c.getConfigurationSection("market.build-prices");
        if (bp != null) {
            for (String k : bp.getKeys(false)) {
                Material m = Material.matchMaterial(k);
                if (m == null) log.warning("market.build-prices: unknown item " + k);
                else if (bp.getDouble(k) <= 0) buildPrices.remove(m);
                else buildPrices.put(m, bp.getDouble(k));
            }
        }
        blueMapShowEmployees = c.getBoolean("web.bluemap.show-employees", true);
        blueMapShowPlans = c.getBoolean("web.bluemap.show-plans", true);
        blueMapShowTrades = c.getBoolean("web.bluemap.show-trades", true);
        downloadsEnabled = c.getBoolean("blueprints.downloads-enabled", true);
        downloadMaxBytes = (long) (Math.max(1, c.getDouble("blueprints.max-download-mb", 50)) * 1024 * 1024);
        String up = c.getString("blueprints.upload-url", "").trim();
        uploadUrl = up.isEmpty() || up.endsWith("/") ? up : up + "/";
        villagersOnly = c.getBoolean("villager-hiring.villagers-only", true);
        villagerNaturalOnly = c.getBoolean("villager-hiring.natural-only", true);
        villagerReleaseOnFire = c.getBoolean("villager-hiring.release-on-fire", true);
        kingdomSettlers = clamp(c.getInt("villager-hiring.kingdom-settlers", 2), 0, 6);
        settlerExpireDays = Math.max(0, c.getInt("villager-hiring.settler-expire-days", 7));
        replenishEnabled = c.getBoolean("villager-hiring.replenish.enabled", true);
        replenishDelayMillis = (long) (Math.max(0, c.getDouble("villager-hiring.replenish.delay-minutes", 20)) * 60_000L);
        replenishRadius = clamp(c.getInt("villager-hiring.replenish.radius", 48), 8, 128);
        replenishMaxNearby = clamp(c.getInt("villager-hiring.replenish.max-villagers-nearby", 30), 1, 200);
    }

    private int[] defaultArea(JobType t) {
        return switch (t) {
            case LUMBERJACK, FARMER -> new int[]{defaultRadius, 3, 5, maxAreaSize, 64};
            case MINER -> new int[]{24, 160, 10, 128, 400};
            case QUARRY -> new int[]{4, 32, -1, 48, 400};
            case BUILDER -> new int[]{8, 3, 5, maxAreaSize, 64};
            case SMELTER -> new int[]{4, 2, 4, 32, 16};
            case RANCHER -> new int[]{8, 3, 5, 48, 32};
            case BODYGUARD -> new int[]{16, 8, 8, 96, 64};
            case TRADER -> new int[]{8, 3, 6, 64, 32};
            case COURIER -> new int[]{3, 3, 5, 32, 16};
        };
    }

    private static int defaultReserve(JobType t) {
        return switch (t) {
            case LUMBERJACK -> 64;
            case FARMER -> 32;
            case QUARRY -> 64;
            case RANCHER -> 64;
            case BODYGUARD -> 64;
            case TRADER -> 256;
            case SMELTER -> 640;
            default -> 0;
        };
    }

    /** First /chest number of a job's group (before any per-player move). */
    public int groupStart(JobType t) {
        return groupStart.get(t);
    }

    public JobSettings job(JobType t) {
        return jobs.get(t);
    }

    /** "IRON_HOE", "BREAD 16" or "BREAD x16" as an item stack, or null. */
    static org.bukkit.inventory.ItemStack itemStack(String entry) {
        String[] parts = entry.trim().split("\\s+");
        if (parts.length == 0 || parts.length > 2) return null;
        Material m = Material.matchMaterial(parts[0]);
        if (m == null || !m.isItem() || m.isAir()) return null;
        int n = 1;
        if (parts.length == 2) {
            try {
                n = Integer.parseInt(parts[1].toLowerCase(Locale.ROOT).replace("x", ""));
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        if (n < 1) return null;
        return new org.bukkit.inventory.ItemStack(m, Math.min(n, m.getMaxStackSize() * 9));
    }

    /** An hour on the Minecraft clock, 0 up to (not including) 24. */
    private static double hour(double h) {
        h %= 24;
        return h < 0 ? h + 24 : h;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
