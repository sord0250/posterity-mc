package site.remotearc.kingdom;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

/**
 * PosterityKingdoms: claim a single spot as your kingdom's Core. That's it -
 * no territory, no land to buy or sell. A kingdom exists so NPCs (with
 * Posterity NPCs / ArcEmployees installed) can be told apart by whose
 * kingdom they belong to; a kingdom's first two NPCs arrive at its Core free
 * to hire.
 */
public final class PosterityKingdoms extends JavaPlugin {

    private static PosterityKingdoms instance;

    private Economy economy;
    private Settings settings;
    private Kingdoms kingdoms;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        settings = new Settings(getConfig());
        Msg.setCurrency(settings.currency);

        try {
            RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
            economy = rsp == null ? null : rsp.getProvider();
        } catch (NoClassDefFoundError noVault) {
            economy = null;
        }
        if (economy == null) getLogger().warning("No Vault economy provider: /kingdom money won't work.");

        kingdoms = new Kingdoms(this);
        KingdomCommand cmd = new KingdomCommand(this);
        PluginCommand pc = getCommand("kingdom");
        if (pc != null) {
            pc.setExecutor(cmd);
            pc.setTabCompleter(cmd);
        }
        getServer().getPluginManager().registerEvents(new site.remotearc.kingdom.gui.Prompt(this), this);
        getLogger().info("Loaded " + kingdoms.all().size() + " kingdom(s).");
    }

    @Override
    public void onDisable() {
        if (kingdoms != null) kingdoms.save();
        instance = null;
    }

    void reloadSettings() {
        reloadConfig();
        settings = new Settings(getConfig());
        Msg.setCurrency(settings.currency);
    }

    public Economy economy() { return economy; }
    public Settings settings() { return settings; }
    public Kingdoms kingdoms() { return kingdoms; }

    /**
     * For other plugins (Posterity NPCs / ArcEmployees asks this when an
     * employee is hired): the kingdom this player owns or rules, by name, or
     * null if they aren't in one. Reached by reflection, so neither plugin
     * needs the other.
     */
    public static String kingdomOf(UUID player) {
        PosterityKingdoms p = instance;
        if (p == null || !p.isEnabled()) return null;
        Kingdom k = p.kingdoms.of(player);
        return k == null ? null : k.name;
    }
}
