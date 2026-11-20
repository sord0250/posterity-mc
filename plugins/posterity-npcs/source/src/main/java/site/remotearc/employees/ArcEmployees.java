package site.remotearc.employees;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import site.remotearc.employees.board.ControlBoards;
import site.remotearc.employees.build.Blueprints;
import site.remotearc.employees.gui.MenuListener;
import site.remotearc.employees.market.Market;
import site.remotearc.employees.market.SellMenu;
import site.remotearc.employees.storage.ChestGroups;
import site.remotearc.employees.storage.VirtualChestsBridge;
import site.remotearc.employees.util.Msg;

/**
 * ArcEmployees: hire NPC workers (skinned Mannequins) who farm, run tree
 * farms, mine, quarry, build schematics, smelt and ranch, deliver to
 * VirtualChests, and eat from a pantry instead of drawing wages.
 */
public final class ArcEmployees extends JavaPlugin {

    private Settings settings;
    private Economy economy;
    private VirtualChestsBridge vchests;
    private site.remotearc.employees.sort.Sorter sorter;
    private site.remotearc.employees.guide.Guide guide;
    private site.remotearc.employees.trades.TradeBoard trades;
    private site.remotearc.employees.web.WebServer web;
    private EmployeeManager manager;
    private Blueprints blueprints;
    private site.remotearc.employees.build.HomeCatalog homeCatalog;
    private HomeSites homeSites;
    private site.remotearc.employees.build.Uploads uploads;
    private ControlBoards boards;
    private ChestGroups chestGroups;
    private ZonePresets zonePresets;
    private Ledger ledger;
    private Reports reports;
    private Managers managers;
    private final site.remotearc.employees.util.KingdomBridge kingdomBridge = new site.remotearc.employees.util.KingdomBridge();
    private site.remotearc.employees.util.BlockLog blockLog;
    private Market market;
    private site.remotearc.employees.board.Links links;
    private site.remotearc.employees.board.Avatars avatars;
    private VillagerHiring villagerHiring;
    private Greeter greeter;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrateConfig();
        settings = new Settings(getConfig(), getLogger());
        Msg.setCurrency(settings.currency);

        // Vault is optional: without it (or without an economy plugin) merchants can't buy or sell for money.
        try {
            RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
            economy = rsp == null ? null : rsp.getProvider();
        } catch (NoClassDefFoundError noVault) {
            economy = null;
        }
        if (economy == null) {
            getLogger().warning("No Vault economy provider: merchants can't buy or sell for money.");
        }

        vchests = new VirtualChestsBridge(getLogger());
        if (settings.vcEnabled) vchests.hook(getServer().getPluginManager().getPlugin("VirtualChests"));

        blueprints = new Blueprints(this);
        homeCatalog = new site.remotearc.employees.build.HomeCatalog(this);
        if (getServer().getPluginManager().isPluginEnabled("WorldEdit")) {
            try {
                site.remotearc.employees.build.StarterHouses.seedIfEmpty(this);
            } catch (NoClassDefFoundError | RuntimeException ex) {
                getLogger().warning("Couldn't seed starter houses: " + ex);
            }
        }
        homeSites = new HomeSites(this);
        uploads = new site.remotearc.employees.build.Uploads(this);
        chestGroups = new ChestGroups(this);
        zonePresets = new ZonePresets(this);
        ledger = new Ledger(this);
        reports = new Reports(this);
        managers = new Managers(this);
        blockLog = new site.remotearc.employees.util.BlockLog(this);
        manager = new EmployeeManager(this);
        try {
            manager.load();
        } catch (IllegalStateException ex) {
            getLogger().severe(ex.getMessage() + ". Disabling so nothing is overwritten.");
            manager = null;
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        manager.start();
        ledger.start();
        boards = new ControlBoards(this);
        boards.start();
        market = new Market(this);
        market.start();
        getServer().getPluginManager().registerEvents(new SellMenu.Listener(this), this);
        avatars = new site.remotearc.employees.board.Avatars(this);
        avatars.start();
        greeter = new Greeter(this);
        greeter.start();
        uploads.start();
        links = new site.remotearc.employees.board.Links(this);
        getServer().getPluginManager().registerEvents(links, this);

        StudioWorld studio = new StudioWorld(this);
        studio.load();
        getServer().getPluginManager().registerEvents(studio, this);
        getServer().getPluginManager().registerEvents(new EmployeeListener(this), this);
        villagerHiring = new VillagerHiring(this);
        villagerHiring.start();
        getServer().getPluginManager().registerEvents(villagerHiring, this);
        getServer().getPluginManager().registerEvents(new site.remotearc.employees.gui.ChestPick(this), this);
        getServer().getPluginManager().registerEvents(new site.remotearc.employees.gui.OutOfBody(this), this);
        getServer().getPluginManager().registerEvents(new site.remotearc.employees.gui.BlockPick(this), this);
        getServer().getPluginManager().registerEvents(new site.remotearc.employees.gui.BuildPicker(this), this);
        getServer().getPluginManager().registerEvents(new site.remotearc.employees.build.PlanMode(this), this);
        getServer().getPluginManager().registerEvents(new site.remotearc.employees.build.FollowChanges(this), this);
        if (getServer().getPluginManager().isPluginEnabled("WorldEdit")) {
            try {
                site.remotearc.employees.build.PlanWorldEdit.register(this);
            } catch (NoClassDefFoundError | RuntimeException ex) {
                getLogger().warning("WorldEdit is there but plan mode couldn't hook into it: " + ex);
            }
        }
        getServer().getPluginManager().registerEvents(new MenuListener(this), this);
        sorter = new site.remotearc.employees.sort.Sorter(this, new site.remotearc.employees.sort.SortProfiles(this));
        site.remotearc.employees.sort.SortCommand sort = new site.remotearc.employees.sort.SortCommand(this, sorter);
        PluginCommand sc = getCommand("sort");
        if (sc != null) {
            sc.setExecutor(sort);
            sc.setTabCompleter(sort);
            // ArcShop has a /sort too: this one takes the name (ArcShop's stays as /arcshop:sort).
            site.remotearc.employees.sort.SortCommand.takeOver(this);
            getServer().getScheduler().runTask(this, () -> site.remotearc.employees.sort.SortCommand.takeOver(this));
        }
        web = new site.remotearc.employees.web.WebServer(this);
        web.start();
        PluginCommand mc = getCommand("map");
        if (mc != null) {
            site.remotearc.employees.web.MapCommand mapCmd = new site.remotearc.employees.web.MapCommand(this, web);
            mc.setExecutor(mapCmd);
            mc.setTabCompleter(mapCmd);
        }
        if (getServer().getPluginManager().getPlugin("BlueMap") != null) {
            try {
                site.remotearc.employees.web.BlueMapHook.start(this, web);
            } catch (NoClassDefFoundError | RuntimeException ex) {
                getLogger().warning("BlueMap is there but its layers couldn't be added: " + ex);
            }
        }
        trades = new site.remotearc.employees.trades.TradeBoard(this);
        PluginCommand tc = getCommand("trades");
        if (tc != null) {
            tc.setExecutor(trades);
            tc.setTabCompleter(trades);
        }
        getServer().getPluginManager().registerEvents(trades, this);
        trades.start();
        guide = new site.remotearc.employees.guide.Guide(this);
        PluginCommand gc = getCommand("guide");
        if (gc != null) {
            gc.setExecutor(guide);
            gc.setTabCompleter(guide);
        }
        getServer().getPluginManager().registerEvents(guide, this);
        guide.start();
        EmployeesCommand command = new EmployeesCommand(this);
        PluginCommand pc = getCommand("employees");
        if (pc != null) {
            pc.setExecutor(command);
            pc.setTabCompleter(command);
        }
    }

    @Override
    public void onDisable() {
        site.remotearc.employees.gui.OutOfBody.stopAll();   // everyone back in their bodies first
        site.remotearc.employees.build.PlanMode.stopAll();
        if (guide != null) guide.stop();
        if (trades != null) trades.stop();
        if (web != null) web.stop();
        if (getServer().getPluginManager().getPlugin("BlueMap") != null) {
            try {
                site.remotearc.employees.web.BlueMapHook.stop();
            } catch (NoClassDefFoundError ignored) {
                // no BlueMap
            }
        }
        if (sorter != null) {
            sorter.stop();
            sorter.profiles().save();
        }
        if (boards != null) boards.stop();
        if (market != null) market.stop();
        if (avatars != null) avatars.stop();
        if (greeter != null) greeter.stop();
        if (uploads != null) uploads.stop();
        if (ledger != null) ledger.stop();
        if (villagerHiring != null) villagerHiring.stop();
        if (manager != null) manager.stop();
    }

    /** Adds settings introduced after a config.yml was written, and drops retired ones. */
    private void migrateConfig() {
        var cfg = getConfig();
        boolean changed = false;
        for (String job : java.util.List.of("lumberjack", "farmer")) {
            for (String old : java.util.List.of("action-ticks", "leaves-per-tick")) {
                String path = "jobs." + job + "." + old;
                if (cfg.isSet(path)) {
                    cfg.set(path, null);
                    changed = true;
                }
            }
        }
        // spacing 5 was the old default for the grid; rows use 3 in a row and 5 between rows.
        if (cfg.getInt("jobs.lumberjack.spacing", 0) == 5 && !cfg.isSet("jobs.lumberjack.row-spacing")) {
            cfg.set("jobs.lumberjack.spacing", 3);
            changed = true;
        }
        // The selling limit went from $150 to $300 a day.
        if (cfg.getDouble("market.max-income-per-day", 0) == 150) {
            cfg.set("market.max-income-per-day", 300);
            changed = true;
        }
        // 16 saplings was too few to plant a whole area; 64 is the new default.
        if (cfg.getInt("jobs.lumberjack.reserve", 0) == 16) {
            cfg.set("jobs.lumberjack.reserve", 64);
            changed = true;
        }
        // Uploads used to go through an outside site's /blueprints/upload/ page (the old default);
        // now the plugin serves the page itself.
        if (cfg.getString("blueprints.upload-url", "").endsWith("/blueprints/upload/")) {
            cfg.set("blueprints.upload-url", "");
            changed = true;
        }
        var defaults = cfg.getDefaults();
        if (defaults != null) {
            for (String key : defaults.getKeys(true)) {
                if (cfg.isSet(key)) continue;
                // Sections first (getKeys lists parents before children), so their comments come along too.
                if (defaults.isConfigurationSection(key)) cfg.createSection(key);
                else cfg.set(key, defaults.get(key));
                cfg.setComments(key, defaults instanceof org.bukkit.configuration.file.FileConfiguration f
                        ? f.getComments(key) : java.util.List.of());
                changed = true;
            }
        }
        if (changed) {
            saveConfig();
            getLogger().info("Updated config.yml with new settings.");
        }
    }

    void reloadSettings() {
        reloadConfig();
        if (homeCatalog != null) homeCatalog.reload();
        migrateConfig();
        settings = new Settings(getConfig(), getLogger());
        Msg.setCurrency(settings.currency);
        manager.refreshTickets();
        manager.refreshEquipment();
        if (market != null) market.real().restart();
        if (blockLog != null) blockLog.reset();
        kingdomBridge.reset();
    }

    public Settings settings() { return settings; }
    public Economy economy() { return economy; }
    public VirtualChestsBridge vchests() { return vchests; }
    public site.remotearc.employees.sort.Sorter sorter() { return sorter; }
    public EmployeeManager manager() { return manager; }
    public Blueprints blueprints() { return blueprints; }
    public site.remotearc.employees.build.HomeCatalog homeCatalog() { return homeCatalog; }
    public HomeSites homeSites() { return homeSites; }

    public site.remotearc.employees.build.Uploads uploads() { return uploads; }
    public ControlBoards boards() { return boards; }
    public ChestGroups chestGroups() { return chestGroups; }
    public ZonePresets zonePresets() { return zonePresets; }
    public Ledger ledger() { return ledger; }
    public Reports reports() { return reports; }
    public Managers managers() { return managers; }
    public site.remotearc.employees.web.WebServer web() { return web; }
    public site.remotearc.employees.util.KingdomBridge kingdomBridge() { return kingdomBridge; }
    public site.remotearc.employees.util.BlockLog blockLog() { return blockLog; }
    public Market market() { return market; }
    public site.remotearc.employees.board.Links links() { return links; }
    public site.remotearc.employees.board.Avatars avatars() { return avatars; }
    public Greeter greeter() { return greeter; }
    public VillagerHiring villagerHiring() { return villagerHiring; }

    /**
     * Called by Kingdoms &amp; Claims (ArcEstate), by reflection, when a player founds a
     * kingdom: its settlers arrive at the Core (see VillagerHiring.settle). Neither
     * plugin needs the other; without this one installed, nothing happens.
     * Returns how many settlers arrived (0 if none: turned off, or not their first kingdom).
     */
    public static int kingdomFounded(java.util.UUID founder, org.bukkit.Location core, String kingdom) {
        ArcEmployees p = getPlugin(ArcEmployees.class);
        if (!p.isEnabled() || p.villagerHiring == null) return 0;
        return p.villagerHiring.settle(founder, core, kingdom);
    }

    /**
     * Called by PosterityKingdoms, by reflection, to show how many NPCs a
     * kingdom has on its info screen. Neither plugin needs the other.
     */
    public static int employeesInKingdom(String kingdom) {
        ArcEmployees p = getPlugin(ArcEmployees.class);
        if (!p.isEnabled() || p.manager == null) return 0;
        return (int) p.manager.countInKingdom(kingdom);
    }

    /** Called by PosterityKingdoms, by reflection, to show the cap alongside the count on its info screen. */
    public static int maxPerKingdom() {
        ArcEmployees p = getPlugin(ArcEmployees.class);
        return !p.isEnabled() || p.settings == null ? 0 : p.settings.maxPerKingdom;
    }
}
