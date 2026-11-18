package site.remotearc.employees;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import site.remotearc.employees.gui.Dialogs;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The Guide: one invincible, stationary NPC (near spawn by default) that
 * greets new players and shows them their first steps. Not an employee - it
 * never moves, works or can be hired, just stands there and answers to a
 * click. Respawned from greeter.yml, never saved with the world.
 */
public final class Greeter {

    private static final UUID IDENTITY = UUID.nameUUIDFromBytes("guide".getBytes(StandardCharsets.UTF_8));

    private final ArcEmployees plugin;
    private final File file;
    private final NamespacedKey key;
    private Location at;
    private Skin skin = defaultSkin();
    private Mannequin shown;
    private BukkitTask task;

    /** The Guide's look out of the box: the skin picked for it (generated once via mineskin.org from the uploaded image). */
    private static Skin defaultSkin() {
        String value = "ewogICJ0aW1lc3RhbXAiIDogMTc5NDA0OTc5NzYxNSwKICAicHJvZmlsZUlkIiA6ICIyYmQyZTAxNGI5YWE0YzRjYmRmMzdmZjc5MzE2NDE5MSIsCiAgInByb2ZpbGVOYW1lIiA6ICJBdXRvU2tpbiIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS84YWU3NDc4MDI0MzlmNmFmZWQ1ODg1YTlmZjU1ZDEwMzRkMzI5YmNkMTMxZDRmOGQ0Y2VjZjZmZWZhNTYxNTZiIiwKICAgICAgIm1ldGFkYXRhIiA6IHsKICAgICAgICAibW9kZWwiIDogInNsaW0iCiAgICAgIH0KICAgIH0KICB9Cn0=";
        String signature = "Lmr7DnXDs8JhYJ9jqbOc4/FUTdn0un2bREN9UbWYLj8oe8xp+wFQIllnYdPblXb9SY4iDlyonfY+w7rlUKin3uD9Gwjwiz+toD2hOK++jyeqOUAKpLz6pS/6sHImfk5MeggjgESJOp4K3T3kzT3eT95xz5+CLAX5gSomWiRbWzSIW+wznraStOwZsppCKyh5u6NtRxR8OFiJvNQRvZJnyQGeCpAuudFE2SE1FGmz6bBhYRdr9UE9PZrVKZFIoLXYY0k89N/LAZC0HRHhguk53oYAko0TW9WDmtvrLjBAXLP2UtdgIVYVwUlt4fdtyYTvdf06TNOr8xWEjcoVxSAjBP26Cdbl6MPrexlyuJ6+S9ZMyXUEx/ElXhBon3eW55GayDZC8nq+aSyf3bZY8bT7PS09K4+Nkw04brLEyrQ+cPp7Cgq1rEttl60on8RdNSX7flZcflysuMxxOGKuczpXOaG8g3Zu6e+ywXruFeD9Um4gZxHZ91N11Bu85S7kfcXGK7kQahpPsCgYFSHkCmiXheIwhA09l1cCAhW5vYV6uUgKl+KtsPGo+zWN8UXbAMP5NnE+jqe4i99xEdvwhn7LKpjGWxzlHGl8s087YdCHwEGX6eTlRgTcAEG+et4fMkohXtlLivdF7pT9EYs1ZVjeIRDzBAZ6ihwfLNoPK4rjIKM=";
        return new Skin(Skin.Kind.PLAYER, "Guide", true, value, signature);
    }

    public Greeter(ArcEmployees plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "greeter.yml");
        this.key = new NamespacedKey(plugin, "greeter");
    }

    public void start() {
        load();
        if (at == null) {
            at = Bukkit.getWorlds().get(0).getSpawnLocation();
            save();
        }
        for (World w : Bukkit.getWorlds()) for (Mannequin m : w.getEntitiesByClass(Mannequin.class)) removeIfStray(m);
        refresh();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 40L, 100L);
    }

    public void stop() {
        if (task != null) task.cancel();
        if (shown != null && shown.isValid()) shown.remove();
        shown = null;
    }

    /** Moves the Guide to a new spot (an admin standing where they want it). */
    public void moveTo(Location to) {
        at = to.clone();
        if (shown != null && shown.isValid()) shown.remove();
        shown = null;
        save();
        refresh();
    }

    /** Gives the Guide a different look. */
    public void setSkin(Skin skin) {
        this.skin = skin;
        if (shown != null && shown.isValid()) shown.setProfile(skin.profile(IDENTITY));
        save();
    }

    public boolean isGreeter(Entity entity) {
        return entity.getPersistentDataContainer().has(key, PersistentDataType.STRING);
    }

    public void removeIfStray(Entity entity) {
        if (!isGreeter(entity)) return;
        if (shown == null || !shown.equals(entity)) entity.remove();
    }

    /** The welcome screen: what to do first, and where to manage things once started. */
    public static void greet(ArcEmployees plugin, Player p) {
        List<String> lines = new ArrayList<>();
        lines.add("<gold><b>Welcome!</b></gold> <gray>Here's how to get going:");
        lines.add("<white>1.</white> <gray>Find a place you want to settle.");
        lines.add("<white>2.</white> <gray>Claim it: <white>/kingdom claim \\<name></white>");
        lines.add("<white>3.</white> <gray>Two free NPCs show up there. Give them jobs: shift-right-click a villager,"
                + " or <white>/employees hire \\<job></white>. Later, any grown-up villager can be turned into one too -"
                + " it'll ask for the job's tool and some bread. <gold>A kingdom can have " + plugin.settings().maxPerKingdom
                + " NPCs at most</gold>, for now.");
        lines.add("<white>4.</white> <gray>Manage it all any time: <white>/employees</white> (your NPCs) and"
                + " <white>/kingdom</white> (your kingdom) - both open a screen of buttons.");
        Dialogs.show(plugin, p, "<gold>Guide", lines, List.of(Dialogs.close("Got it!")), 1, null);
    }

    private void refresh() {
        if (at == null) return;
        World w = at.getWorld();
        if (w == null || !w.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) return;
        if (shown == null || !shown.isValid()) shown = spawn();
    }

    private Mannequin spawn() {
        return at.getWorld().spawn(at, Mannequin.class, mm -> {
            mm.setPersistent(false);   // never saved with the chunk: we respawn it from greeter.yml
            mm.setRemoveWhenFarAway(false);
            mm.setGravity(false);
            mm.setInvulnerable(true);
            mm.setCollidable(false);
            mm.setSilent(true);
            mm.setImmovable(true);
            mm.setCustomNameVisible(true);
            mm.customName(Component.text("Guide", NamedTextColor.GOLD));
            mm.setDescription(Component.text("Right-click for your first steps", NamedTextColor.GRAY));
            mm.setProfile(skin.profile(IDENTITY));
            mm.getPersistentDataContainer().set(key, PersistentDataType.STRING, "guide");
        });
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        String worldName = y.getString("world");
        if (worldName != null) {
            World w = Bukkit.getWorld(worldName);
            if (w == null) w = Bukkit.getWorlds().get(0);
            at = new Location(w, y.getDouble("x"), y.getDouble("y"), y.getDouble("z"), (float) y.getDouble("yaw"), 0);
        }
        var skinSection = y.getConfigurationSection("skin");
        if (skinSection != null) skin = Skin.load(skinSection, new Random());
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("world", at.getWorld().getName());
        y.set("x", at.getX());
        y.set("y", at.getY());
        y.set("z", at.getZ());
        y.set("yaw", at.getYaw());
        skin.save(y.createSection("skin"));
        try {
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not save greeter.yml", ex);
        }
    }
}
