package site.remotearc.employees.gui;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryHolder;
import site.remotearc.employees.ArcEmployees;

/**
 * Routes clicks to menus, keeps open menus' status text live, takes typed
 * answers for chat prompts, and brings players back to the menu they came
 * from when they close a backpack or chest opened from it.
 */
public final class MenuListener implements Listener {

    private final ArcEmployees plugin;

    public MenuListener(ArcEmployees plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                InventoryHolder h = p.getOpenInventory().getTopInventory().getHolder(false);
                if (h instanceof Menu m && !(m instanceof ConfirmMenu) && m.live()) m.redraw();
            }
        }, 20L, 20L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent ev) {
        if (!(ev.getView().getTopInventory().getHolder(false) instanceof Menu menu)) return;
        ev.setCancelled(true);
        if (!(ev.getWhoClicked() instanceof Player p)) return;
        if (ev.getClickedInventory() != ev.getView().getTopInventory()) return;
        menu.click(p, ev.getRawSlot(), ev.getClick());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent ev) {
        if (ev.getView().getTopInventory().getHolder(false) instanceof Menu) ev.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent ev) {
        if (!(ev.getPlayer() instanceof Player p)) return;
        if (ev.getInventory().getHolder(false) instanceof Menu) return;
        Menu back = Menu.RETURN_TO.remove(p.getUniqueId());
        if (back == null || ev.getReason() != InventoryCloseEvent.Reason.PLAYER) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) back.open(p);
        });
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent ev) {
        String text = PlainTextComponentSerializer.plainText().serialize(ev.message());
        if (Prompt.handle(plugin, ev.getPlayer(), text)) ev.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent ev) {
        Menu.RETURN_TO.remove(ev.getPlayer().getUniqueId());
        Prompt.cancel(ev.getPlayer());
    }
}
