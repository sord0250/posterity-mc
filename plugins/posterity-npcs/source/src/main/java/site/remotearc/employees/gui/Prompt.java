package site.remotearc.employees.gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.util.Msg;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Asks a player to type something in chat (a name, a chest number, a link) and hands the answer back on the main thread. */
public final class Prompt {

    private record Waiting(Consumer<String> answer, long until) {}

    private static final Map<UUID, Waiting> WAITING = new ConcurrentHashMap<>();

    private Prompt() {}

    public static void ask(Player p, String question, Consumer<String> answer) {
        p.closeInventory();
        WAITING.put(p.getUniqueId(), new Waiting(answer, System.currentTimeMillis() + 120_000));
        Msg.send(p, "<yellow>" + question + " <gray>Type it in chat, or <white>cancel</white>.");
    }

    static boolean handle(ArcEmployees plugin, Player p, String text) {
        Waiting w = WAITING.remove(p.getUniqueId());
        if (w == null || System.currentTimeMillis() > w.until()) return false;
        String t = text.trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (t.equalsIgnoreCase("cancel") || t.isEmpty()) Msg.send(p, "<gray>Cancelled.");
            else w.answer().accept(t);
        });
        return true;
    }

    static void cancel(Player p) {
        WAITING.remove(p.getUniqueId());
    }
}
