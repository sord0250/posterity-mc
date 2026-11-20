package site.remotearc.employees;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import site.remotearc.employees.build.BuildOrder;
import site.remotearc.employees.job.JobType;
import site.remotearc.employees.storage.StorageTarget;
import site.remotearc.employees.util.BlockPos;
import site.remotearc.employees.util.WorkArea;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Reads and writes plugins/ArcEmployees/employees.yml. Writes go to a temp file first, then replace. */
final class EmployeeStore {

    private final File file;
    private final Logger log;
    private final Random random = new Random();
    /**
     * Records that could not be loaded (e.g. their world is not loaded yet).
     * They are written back unchanged on every save so they are never lost.
     */
    private final Map<String, ConfigurationSection> preserved = new java.util.LinkedHashMap<>();

    EmployeeStore(File dataFolder, Logger log) {
        this.file = new File(dataFolder, "employees.yml");
        this.log = log;
    }

    List<Employee> load() {
        List<Employee> out = new ArrayList<>();
        if (!file.exists()) return out;
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.load(file);
        } catch (IOException | InvalidConfigurationException ex) {
            // Keep the unreadable file for inspection; never overwrite it with an empty list.
            File bad = new File(file.getParentFile(), "employees.yml.broken-" + System.currentTimeMillis());
            try {
                Files.copy(file.toPath(), bad.toPath());
            } catch (IOException ignored) {
                // best effort
            }
            throw new IllegalStateException("employees.yml is unreadable (copied to " + bad.getName() + ")", ex);
        }
        ConfigurationSection all = y.getConfigurationSection("employees");
        if (all == null) return out;
        for (String key : all.getKeys(false)) {
            try {
                out.add(read(UUID.fromString(key), all.getConfigurationSection(key)));
            } catch (RuntimeException ex) {
                log.warning("Not loading employee " + key + " (" + all.getString(key + ".name") + "): "
                        + ex.getMessage() + ". Its record is kept as it is.");
                preserved.put(key, all.getConfigurationSection(key));
            }
        }
        return out;
    }

    /**
     * Tries the records that couldn't be loaded again (a world that's loaded
     * now, like a studio world made on demand); returns the ones that load.
     */
    List<Employee> retryPreserved() {
        List<Employee> out = new ArrayList<>();
        for (var it = preserved.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, ConfigurationSection> en = it.next();
            try {
                out.add(read(UUID.fromString(en.getKey()), en.getValue()));
                it.remove();
            } catch (RuntimeException ex) {
                // still not loadable
            }
        }
        return out;
    }

    /** Records waiting for their world: {owner uuid, name, job, world}. */
    List<String[]> waiting() {
        List<String[]> out = new ArrayList<>();
        for (ConfigurationSection s : preserved.values()) {
            if (s == null) continue;
            String loc = s.getString("location", "");
            out.add(new String[]{s.getString("owner", ""), s.getString("name", "?"), s.getString("job", "?"),
                    loc.contains(";") ? loc.substring(0, loc.indexOf(';')) : "?"});
        }
        return out;
    }

    private Employee read(UUID id, ConfigurationSection s) {
        String savedJob = s.getString("job", "");
        JobType job = JobType.parse(savedJob);
        if (job == null) throw new IllegalArgumentException("unknown job " + savedJob);
        Employee e = new Employee(id, UUID.fromString(s.getString("owner")), job, s.getString("name", "Employee"));
        e.hiredAt = s.getLong("hired-at");
        e.villagerType = s.getString("villager-type");
        e.personality = Personality.parse(s.getString("personality"));
        if (e.personality == null) e.personality = Personality.random(random);   // hired before personalities existed
        e.house = House.load(s.getString("house"));
        e.homeSite = BuildOrder.load(s.getConfigurationSection("home-site"));
        if (e.homeSite != null) {
            try {
                e.siteBed = BlockPos.parse(s.getString("home-site-bed"));
                e.siteDoor = BlockPos.parse(s.getString("home-site-door"));
                e.siteBlocks = Math.max(1, s.getInt("home-site-blocks", 1));
                e.selfBuild = s.getBoolean("home-self-build");
                e.siteArea = WorkArea.load(s.getConfigurationSection("home-site-area"));
            } catch (RuntimeException ex) {
                e.homeSite = null;
            }
        }
        e.paused = s.getBoolean("paused");
        e.food = Math.max(0, s.getDouble("food", 33));   // from before food: a few days' worth to start with
        if (s.isConfigurationSection("pantry")) e.pantry = StorageTarget.load(s.getConfigurationSection("pantry"));
        e.skin = Skin.load(s.getConfigurationSection("skin"), random);
        e.area = WorkArea.load(s.getConfigurationSection("area"));
        e.home = readLocation(s.getString("home"));
        e.location = readLocation(s.getString("location"));
        if (e.location == null) e.location = e.home;
        if (e.home == null) e.home = e.location;
        if (e.location == null) throw new IllegalArgumentException("no location (is its world loaded?)");
        e.storage = StorageTarget.load(s.getConfigurationSection("storage"));

        ConfigurationSection st = s.getConfigurationSection("stats");
        if (st != null) {
            e.stats.treesFelled = st.getLong("trees-felled");
            e.stats.logsCut = st.getLong("logs-cut");
            e.stats.saplingsPlanted = st.getLong("saplings-planted");
            e.stats.cropsHarvested = st.getLong("crops-harvested");
            e.stats.blocksMined = st.getLong("blocks-mined");
            e.stats.oresMined = st.getLong("ores-mined");
            e.stats.blocksPlaced = st.getLong("blocks-placed");
            e.stats.itemsSmelted = st.getLong("items-smelted");
            e.stats.animalsBred = st.getLong("animals-bred");
            e.stats.animalsCulled = st.getLong("animals-culled");
            e.stats.sheepSheared = st.getLong("sheep-sheared");
            e.stats.mobsKilled = st.getLong("mobs-killed");
            e.stats.earned = st.getDouble("earned");
            e.stats.tradesMade = st.getLong("trades-made");
            e.stats.itemsMoved = st.getLong("items-moved");
            e.stats.blocksRepaired = st.getLong("blocks-repaired");
            e.stats.itemsSold = st.getLong("items-sold");
            e.stats.itemsDelivered = st.getLong("items-delivered");
        }
        for (String spot : s.getStringList("spots")) {
            int colon = spot.lastIndexOf(':');
            Material m = Material.matchMaterial(spot.substring(colon + 1));
            if (colon > 0 && m != null) e.spots.put(BlockPos.parse(spot.substring(0, colon)), m);
        }
        for (String f : s.getStringList("filter")) {
            Material m = Material.matchMaterial(f);
            if (m != null) e.filter.add(m);
        }
        ConfigurationSection sup = s.getConfigurationSection("supply");
        if (sup != null) {
            e.teamSupply = sup.getBoolean("team", true);
            ConfigurationSection extra = sup.getConfigurationSection("extra");
            if (extra != null) {
                for (String k : extra.getKeys(false)) {
                    StorageTarget t = StorageTarget.load(extra.getConfigurationSection(k));
                    if (t.kind() != StorageTarget.Kind.NONE) e.supply.add(t);
                }
            }
        }
        e.herdSize = s.getInt("herd-size");
        // GUARD no longer exists (merged into BODYGUARD with the follow toggle): one, staying at its post, is how it always behaved.
        e.following = s.getBoolean("following", !savedJob.equalsIgnoreCase("GUARD"));
        for (String n : s.getStringList("never-sell")) {
            Material m = Material.matchMaterial(n);
            if (m != null) e.neverSell.add(m);
        }
        e.roam = s.getBoolean("roam");
        for (String r : s.getStringList("routes")) {
            Employee.Route route = Employee.Route.load(r);
            if (route != null) e.routes.add(route);
        }
        for (String t : s.getStringList("stalls")) {
            try {
                e.stalls.add(Employee.Stall.load(t));
            } catch (RuntimeException ignored) {
                // skip a broken entry
            }
        }
        for (String t : s.getStringList("trades")) {
            try {
                e.trades.add(Employee.TradeChoice.load(t));
            } catch (RuntimeException ignored) {
                // skip a broken entry
            }
        }
        e.craft = s.getBoolean("craft", true);
        e.worksAway = s.getBoolean("works-away", false);
        e.debug = s.getBoolean("debug", false);
        if (s.isList("craft-never")) {
            for (String n : s.getStringList("craft-never")) {
                Material m = Material.matchMaterial(n);
                if (m != null) e.craftNever.add(m);
            }
        } else {
            e.craftNeverUnset = true;   // the manager fills in the config's list
        }
        e.roamStep = s.getInt("roam-step");
        e.roamBase = WorkArea.load(s.getConfigurationSection("roam-base"));
        for (String z : s.getStringList("zones")) {
            WorkArea box = site.remotearc.employees.ZonePresets.parse(z);
            if (box != null) e.zones.add(box);
        }
        e.zonePresets.addAll(s.getStringList("zone-presets"));
        e.overflow.addAll(s.getIntegerList("overflow"));
        e.sellAll = s.getBoolean("sell-all");
        String all = s.getString("sell-all-rule");
        if (all != null && all.contains(":")) e.sellAllRule = Employee.SellRule.load(all.split(":"), 0);
        for (String rule : s.getStringList("sell")) {   // ITEM:KIND:value
            String[] p = rule.split(":");
            Material mat = Material.matchMaterial(p[0]);
            try {
                if (mat != null) e.sellRules.put(mat, p.length >= 2 ? Employee.SellRule.load(p, 1) : Employee.SellRule.ALL);
            } catch (RuntimeException ignored) {
                // skip a broken rule
            }
        }
        for (String line : s.getStringList("sold-log")) {   // ITEM:time:count
            String[] p = line.split(":");
            Material mat = p.length == 3 ? Material.matchMaterial(p[0]) : null;
            if (mat == null) continue;
            try {
                e.soldLog.computeIfAbsent(mat, k -> new java.util.ArrayDeque<>()).addLast(new long[]{Long.parseLong(p[1]), Long.parseLong(p[2])});
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        for (String sale : s.getStringList("sales")) {   // time:count:money:what
            String[] p = sale.split(":", 4);
            try {
                if (p.length == 4) e.sales.addLast(new Employee.SaleRecord(Long.parseLong(p[0]), Integer.parseInt(p[1]), Double.parseDouble(p[2]), p[3]));
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        e.build = BuildOrder.load(s.getConfigurationSection("build"));
        String worn = s.getString("armor");
        if (worn != null && !worn.isEmpty()) {
            ItemStack[] items = ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(worn));
            for (int i = 0; i < 4 && i < items.length; i++) e.armor[i] = items[i] == null || items[i].getType().isAir() ? null : items[i];
        }
        e.knockedOutUntil = s.getLong("knocked-out-until");
        e.knockedOutBy = s.getString("knocked-out-by");
        String bag = s.getString("backpack");
        if (bag != null && !bag.isEmpty()) {
            ItemStack[] items = ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(bag));
            ItemStack[] sized = new ItemStack[Employee.BACKPACK_SIZE];
            List<ItemStack> overflow = new ArrayList<>();
            for (int i = 0; i < items.length; i++) {
                if (i < sized.length) sized[i] = items[i];
                else if (items[i] != null) overflow.add(items[i]);
            }
            e.backpack.setContents(sized);
            if (!overflow.isEmpty()) {
                var leftover = e.backpack.addItem(overflow.toArray(new ItemStack[0]));
                // Didn't fit back in (the backpack shrank, or it was already full): kept, not silently lost;
                // dropped once this employee is actually placed somewhere (its world may not be loaded yet).
                e.spill.addAll(leftover.values());
            }
        }
        return e;
    }

    void save(Iterable<Employee> employees) {
        writeToDisk(snapshot(employees));
    }

    /**
     * Everything a save needs from live employee data (backpacks encoded and
     * all), without touching the disk. Safe only on the main thread; the
     * string it returns can then be written from any thread.
     */
    String snapshot(Iterable<Employee> employees) {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of(
                "ArcEmployees data. Edit only while the server is stopped;",
                "the plugin rewrites this file while it runs."));
        for (Map.Entry<String, ConfigurationSection> kept : preserved.entrySet()) {
            if (kept.getValue() != null) copy(kept.getValue(), y.createSection("employees." + kept.getKey()));
        }
        for (Employee e : employees) {
            preserved.remove(e.id.toString());
            ConfigurationSection s = y.createSection("employees." + e.id);
            s.set("name", e.name);
            s.set("owner", e.owner.toString());
            s.set("job", e.job.name());
            s.set("hired-at", e.hiredAt);
            if (e.villagerType != null) s.set("villager-type", e.villagerType);
            if (e.personality != null) s.set("personality", e.personality.name());
            if (e.house != null) s.set("house", e.house.save());
            if (e.homeSite != null && e.siteBed != null && e.siteDoor != null) {
                e.homeSite.save(s.createSection("home-site"));
                s.set("home-site-bed", e.siteBed.toString());
                s.set("home-site-door", e.siteDoor.toString());
                s.set("home-site-blocks", e.siteBlocks);
                if (e.selfBuild) s.set("home-self-build", true);
                if (e.siteArea != null) e.siteArea.save(s.createSection("home-site-area"));
            }
            s.set("paused", e.paused);
            s.set("food", Math.round(e.food * 100) / 100.0);
            if (e.pantry.kind() != StorageTarget.Kind.NONE) e.pantry.save(s.createSection("pantry"));
            e.skin.save(s.createSection("skin"));
            if (e.area != null) e.area.save(s.createSection("area"));
            s.set("home", writeLocation(e.home));
            s.set("location", writeLocation(e.location));
            e.storage.save(s.createSection("storage"));
            ConfigurationSection st = s.createSection("stats");
            st.set("trees-felled", e.stats.treesFelled);
            st.set("logs-cut", e.stats.logsCut);
            st.set("saplings-planted", e.stats.saplingsPlanted);
            st.set("crops-harvested", e.stats.cropsHarvested);
            st.set("blocks-mined", e.stats.blocksMined);
            st.set("ores-mined", e.stats.oresMined);
            st.set("blocks-placed", e.stats.blocksPlaced);
            st.set("items-smelted", e.stats.itemsSmelted);
            st.set("animals-bred", e.stats.animalsBred);
            st.set("animals-culled", e.stats.animalsCulled);
            st.set("sheep-sheared", e.stats.sheepSheared);
            st.set("mobs-killed", e.stats.mobsKilled);
            st.set("earned", e.stats.earned);
            st.set("trades-made", e.stats.tradesMade);
            if (e.stats.itemsMoved > 0) st.set("items-moved", e.stats.itemsMoved);
            if (e.stats.blocksRepaired > 0) st.set("blocks-repaired", e.stats.blocksRepaired);
            st.set("items-sold", e.stats.itemsSold);
            st.set("items-delivered", e.stats.itemsDelivered);
            List<String> spots = new ArrayList<>(e.spots.size());
            for (Map.Entry<BlockPos, Material> spot : e.spots.entrySet()) spots.add(spot.getKey() + ":" + spot.getValue().name());
            s.set("spots", spots);
            if (!e.filter.isEmpty()) s.set("filter", e.filter.stream().map(Material::name).toList());
            if (!e.teamSupply || !e.supply.isEmpty()) {
                ConfigurationSection sup = s.createSection("supply");
                sup.set("team", e.teamSupply);
                for (int i = 0; i < e.supply.size(); i++) e.supply.get(i).save(sup.createSection("extra." + i));
            }
            if (e.herdSize > 0) s.set("herd-size", e.herdSize);
            if (e.job == JobType.BODYGUARD) s.set("following", e.following);
            if (e.roam) s.set("roam", true);
            if (e.worksAway) s.set("works-away", true);
            if (e.debug) s.set("debug", true);
            if (!e.trades.isEmpty()) s.set("trades", e.trades.stream().map(Employee.TradeChoice::save).toList());
            if (!e.stalls.isEmpty()) s.set("stalls", e.stalls.stream().map(Employee.Stall::save).toList());
            if (!e.routes.isEmpty()) s.set("routes", e.routes.stream().map(Employee.Route::save).toList());
            if (e.job == JobType.TRADER || e.job == JobType.BUILDER) {
                if (!e.craft) s.set("craft", false);
                s.set("craft-never", e.craftNever.stream().map(Material::name).toList());
            }
            if (!e.neverSell.isEmpty()) s.set("never-sell", e.neverSell.stream().map(Material::name).toList());
            if (e.roamStep > 0) s.set("roam-step", e.roamStep);
            if (e.roamBase != null) e.roamBase.save(s.createSection("roam-base"));
            if (!e.zones.isEmpty()) s.set("zones", e.zones.stream().map(site.remotearc.employees.ZonePresets::save).toList());
            if (!e.zonePresets.isEmpty()) s.set("zone-presets", e.zonePresets);
            if (!e.overflow.isEmpty()) s.set("overflow", e.overflow);
            if (e.sellAll) s.set("sell-all", true);
            if (!e.sellAllRule.equals(Employee.SellRule.ALL)) s.set("sell-all-rule", e.sellAllRule.save());
            if (!e.sellRules.isEmpty()) {
                s.set("sell", e.sellRules.entrySet().stream().map(en -> en.getKey().name() + ":" + en.getValue().save()).toList());
            }
            List<String> soldLog = new ArrayList<>();
            for (var en : e.soldLog.entrySet()) {
                if (e.soldToday(en.getKey()) == 0) continue;
                for (long[] r : en.getValue()) soldLog.add(en.getKey().name() + ":" + r[0] + ":" + r[1]);
            }
            if (!soldLog.isEmpty()) s.set("sold-log", soldLog);
            if (!e.sales.isEmpty()) {
                s.set("sales", e.sales.stream().map(r -> r.time() + ":" + r.count() + ":" + r.money() + ":" + r.what().replace("\n", " ")).toList());
            }
            if (e.build != null) e.build.save(s.createSection("build"));
            s.set("backpack", Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(e.backpack.getContents())));
            boolean anyArmor = false;
            for (ItemStack a : e.armor) anyArmor |= a != null;
            if (anyArmor) {
                ItemStack[] worn = new ItemStack[4];
                for (int i = 0; i < 4; i++) worn[i] = e.armor[i] == null ? ItemStack.empty() : e.armor[i];
                s.set("armor", Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(worn)));
            }
            if (e.knockedOutUntil > System.currentTimeMillis()) {
                s.set("knocked-out-until", e.knockedOutUntil);
                s.set("knocked-out-by", e.knockedOutBy);
            }
        }
        return y.saveToString();
    }

    /**
     * Pulls one employee out of the live list and files it the same way a
     * record whose world wasn't loaded yet at startup is kept: written back
     * unchanged on every save until {@link #retryPreserved()} can read it
     * again (its world reloading). Used when a world unloads while the
     * plugin is running, so that employee isn't lost or left pointing at a
     * dead world.
     */
    void preserve(Employee e) {
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.loadFromString(snapshot(List.of(e)));
        } catch (InvalidConfigurationException ex) {
            return;   // can't happen: we just built this string ourselves
        }
        ConfigurationSection sec = y.getConfigurationSection("employees." + e.id);
        if (sec != null) preserved.put(e.id.toString(), sec);
    }

    /** The disk half of a save: plain file I/O, safe to run off the main thread. */
    void writeToDisk(String yaml) {
        try {
            Files.createDirectories(file.getParentFile().toPath());
            Path tmp = file.toPath().resolveSibling("employees.yml.tmp");
            Files.writeString(tmp, yaml, StandardCharsets.UTF_8);
            Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            log.log(Level.SEVERE, "Could not save employees.yml", ex);
        }
    }

    private static void copy(ConfigurationSection from, ConfigurationSection to) {
        for (String key : from.getKeys(false)) {
            Object v = from.get(key);
            if (v instanceof ConfigurationSection sub) copy(sub, to.createSection(key));
            else to.set(key, v);
        }
    }

    private static String writeLocation(Location l) {
        if (l == null || l.getWorld() == null) return null;
        return l.getWorld().getName() + ";" + round(l.getX()) + ";" + round(l.getY()) + ";" + round(l.getZ()) + ";" + round(l.getYaw());
    }

    private static Location readLocation(String s) {
        if (s == null) return null;
        String[] p = s.split(";");
        World w = Bukkit.getWorld(p[0]);
        if (w == null) return null;
        return new Location(w, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                p.length > 4 ? Float.parseFloat(p[4]) : 0f, 0f);
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
