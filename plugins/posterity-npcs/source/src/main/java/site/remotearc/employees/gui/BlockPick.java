package site.remotearc.employees.gui;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
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

/** "Click the block you mean": the next block a player clicks (within a minute) is handed back. */
public final class BlockPick implements Listener {

    private record Waiting(java.util.function.BiConsumer<Block, org.bukkit.block.BlockFace> then, long until) {}

    private static final Map<UUID, Waiting> WAITING = new ConcurrentHashMap<>();
    private final ArcEmployees plugin;

    public BlockPick(ArcEmployees plugin) {
        this.plugin = plugin;
    }

    public static void ask(Player p, String what, Consumer<Block> then) {
        askFace(p, what, (b, f) -> then.accept(b));
    }

    /** The same, with the face clicked (to work out the spot next to it, as placing a block would). */
    public static void askFace(Player p, String what, java.util.function.BiConsumer<Block, org.bukkit.block.BlockFace> then) {
        p.closeInventory();
        WAITING.put(p.getUniqueId(), new Waiting(then, System.currentTimeMillis() + 60_000));
        Msg.send(p, "<yellow>Click " + what + " <gray>within a minute.");
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
        if (b == null) return;
        org.bukkit.block.BlockFace face = ev.getBlockFace();
        Bukkit.getScheduler().runTask(plugin, () -> w.then().accept(b, face));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent ev) {
        WAITING.remove(ev.getPlayer().getUniqueId());
    }
}
