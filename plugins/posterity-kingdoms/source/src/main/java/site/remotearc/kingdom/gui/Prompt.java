package site.remotearc.kingdom.gui;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import site.remotearc.kingdom.Msg;
import site.remotearc.kingdom.PosterityKingdoms;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Asks a player to type something in chat (a name, an amount) and hands the answer back on the main thread. */
public final class Prompt implements Listener {

    private record Waiting(Consumer<String> answer, long until) {}

    private static final Map<UUID, Waiting> WAITING = new ConcurrentHashMap<>();

    private final PosterityKingdoms plugin;

    public Prompt(PosterityKingdoms plugin) {
        this.plugin = plugin;
    }

    public static void ask(Player p, String question, Consumer<String> answer) {
        WAITING.put(p.getUniqueId(), new Waiting(answer, System.currentTimeMillis() + 120_000));
        Msg.send(p, "<yellow>" + question + " <gray>Type it in chat, or <white>cancel</white>.");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent ev) {
        Waiting w = WAITING.remove(ev.getPlayer().getUniqueId());
        if (w == null) return;
        if (System.currentTimeMillis() > w.until()) return;   // expired: not ours any more, let the message through as ordinary chat
        ev.setCancelled(true);
        Player p = ev.getPlayer();
        String t = PlainTextComponentSerializer.plainText().serialize(ev.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (t.equalsIgnoreCase("cancel") || t.isEmpty()) Msg.send(p, "<gray>Cancelled.");
            else w.answer().accept(t);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent ev) {
        WAITING.remove(ev.getPlayer().getUniqueId());
    }
}
