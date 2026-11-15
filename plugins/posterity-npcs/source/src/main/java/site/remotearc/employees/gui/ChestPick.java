package site.remotearc.employees.gui;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.util.Msg;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** "Click the chest you mean": the next container a player clicks (within a minute) is handed to a menu. */
public final class ChestPick implements Listener {

    private record Waiting(Consumer<Block> then, long until) {}

    private static final Map<UUID, Waiting> WAITING = new ConcurrentHashMap<>();
    private final ArcEmployees plugin;

    public ChestPick(ArcEmployees plugin) {
        this.plugin = plugin;
    }

    public static void ask(Player p, String what, Consumer<Block> then) {
        p.closeInventory();
        WAITING.put(p.getUniqueId(), new Waiting(then, System.currentTimeMillis() + 60_000));
        Msg.send(p, "<yellow>Click " + what + " <gray>(a chest, barrel or other container) within a minute. Click anything else to cancel.");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(PlayerInteractEvent ev) {
        Waiting w = WAITING.get(ev.getPlayer().getUniqueId());
        if (w == null) return;
        if (ev.getAction() != Action.LEFT_CLICK_BLOCK && ev.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        WAITING.remove(ev.getPlayer().getUniqueId());
        ev.setCancelled(true);
        Block b = ev.getClickedBlock();
        Player p = ev.getPlayer();
        if (System.currentTimeMillis() > w.until()) return;
        if (b == null || !(b.getState(false) instanceof Container)) {
            Msg.send(p, "<gray>That isn't a container; cancelled.");
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> w.then().accept(b));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent ev) {
        WAITING.remove(ev.getPlayer().getUniqueId());
    }
}
