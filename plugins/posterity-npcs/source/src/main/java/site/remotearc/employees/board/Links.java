package site.remotearc.employees.board;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.Powerable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.util.Msg;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Buttons, levers (or any block) in the world linked to /employees actions,
 * for a physical control room. A linked lever follows its position for the
 * pause/resume actions: on = working, off = paused. Only the player who
 * linked it (or an admin) can use it. Stored in links.yml.
 */
public final class Links implements Listener {

    public record Link(UUID owner, String action) {}

    private final ArcEmployees plugin;
    private final File file;
    private final Map<String, Link> links = new HashMap<>();
    /** Players about to link (action) or unlink (null value) the next block they right-click. */
    private final Map<UUID, String> pending = new HashMap<>();

    public Links(ArcEmployees plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "links.yml");
        load();
    }

    /** The next block this player right-clicks gets linked to the action (or unlinked, for null). */
    public void arm(Player p, String action) {
        pending.put(p.getUniqueId(), action == null ? "" : action);
    }

    public int count(UUID owner) {
        int n = 0;
        for (Link l : links.values()) if (l.owner().equals(owner)) n++;
        return n;
    }

    private static String key(Block b) {
        return b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ();
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent ev) {
        if (ev.getAction() != Action.RIGHT_CLICK_BLOCK || ev.getHand() != EquipmentSlot.HAND || ev.getClickedBlock() == null) return;
        Player p = ev.getPlayer();
        Block b = ev.getClickedBlock();
        String armed = pending.remove(p.getUniqueId());
        if (armed != null) {
            ev.setCancelled(true);
            if (armed.isEmpty()) {
                Link gone = links.remove(key(b));
                Msg.send(p, gone == null ? "<gray>That block wasn't linked." : "<gray>Unlinked (it was: " + gone.action() + ").");
            } else {
                links.put(key(b), new Link(p.getUniqueId(), armed));
                Msg.send(p, "<green>Linked that " + b.getType().name().toLowerCase().replace('_', ' ') + " to <white>" + describe(armed)
                        + "</white>." + (b.getType() == Material.LEVER && isPauseAction(armed) ? " <gray>Lever on = working, off = paused." : ""));
            }
            save();
            return;
        }
        Link link = links.get(key(b));
        if (link == null) return;
        boolean control = Tag.BUTTONS.isTagged(b.getType()) || b.getType() == Material.LEVER;
        if (!control) ev.setCancelled(true);   // a linked sign or block shouldn't do its own thing
        if (!link.owner().equals(p.getUniqueId()) && !p.hasPermission("employees.admin")) {
            Msg.error(p, "This control belongs to " + Bukkit.getOfflinePlayer(link.owner()).getName() + ".");
            if (control) ev.setCancelled(true);
            return;
        }
        String action = link.action();
        if (b.getType() == Material.LEVER && isPauseAction(action) && b.getBlockData() instanceof Powerable pw) {
            boolean turningOn = !pw.isPowered();   // the lever flips after this event
            String who = action.contains(" ") ? action.substring(action.indexOf(' ') + 1) : null;
            action = who == null ? (turningOn ? "resumeall" : "pauseall") : (turningOn ? "resume " : "pause ") + who;
        }
        String run = action;
        Bukkit.getScheduler().runTask(plugin, () -> p.performCommand("employees " + run));
    }

    private static boolean isPauseAction(String a) {
        return a.equals("toggleall") || a.equals("pauseall") || a.equals("resumeall") || a.startsWith("toggle ");
    }

    public static String describe(String action) {
        return switch (action) {
            case "pauseall" -> "pause everyone";
            case "resumeall" -> "put everyone back to work";
            case "toggleall" -> "pause / resume everyone";
            case "", "menu" -> "the employees menu";
            case "chests" -> "the chests menu";
            case "market prices" -> "the merchant price list";
            default -> "/employees " + action;
        };
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent ev) {
        if (links.remove(key(ev.getBlock())) != null) save();
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection s = y.getConfigurationSection("links");
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            try {
                links.put(s.getString(k + ".block"), new Link(UUID.fromString(s.getString(k + ".owner")), s.getString(k + ".action", "")));
            } catch (RuntimeException ignored) {
                // skip a broken entry
            }
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        int i = 0;
        for (Map.Entry<String, Link> e : links.entrySet()) {
            String k = "links." + i++;
            y.set(k + ".block", e.getKey());
            y.set(k + ".owner", e.getValue().owner().toString());
            y.set(k + ".action", e.getValue().action());
        }
        try {
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not save links.yml", ex);
        }
    }
}
