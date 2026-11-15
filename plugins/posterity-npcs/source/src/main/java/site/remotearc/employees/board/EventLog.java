package site.remotearc.employees.board;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Recent problems and notable events per owner ("Bram: can't get to the
 * coal", "Bob finished building house"), for control boards and the menu.
 * In memory only: a restart starts a fresh log.
 */
public final class EventLog {

    public record Entry(long time, String employee, String text, boolean problem) {}

    private static final int KEEP = 30;
    private static final long REPEAT_MILLIS = 10 * 60 * 1000;

    private final Map<UUID, Deque<Entry>> byOwner = new HashMap<>();

    public void add(UUID owner, String employee, String text, boolean problem) {
        Deque<Entry> d = byOwner.computeIfAbsent(owner, k -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        for (Entry e : d) {
            if (now - e.time() > REPEAT_MILLIS) break;
            if (e.employee().equals(employee) && e.text().equals(text)) return;   // already said recently
        }
        d.addFirst(new Entry(now, employee, text, problem));
        while (d.size() > KEEP) d.removeLast();
    }

    /** Newest first. */
    public List<Entry> recent(UUID owner, int max) {
        List<Entry> out = new ArrayList<>();
        Deque<Entry> d = byOwner.get(owner);
        if (d == null) return out;
        for (Entry e : d) {
            if (out.size() >= max) break;
            out.add(e);
        }
        return out;
    }

    /** Statuses worth logging as a problem when an employee settles on them. */
    public static boolean isProblem(String status) {
        String s = status.toLowerCase();
        return s.startsWith("can't") || s.startsWith("needs") || s.startsWith("out of") || s.startsWith("error")
                || s.startsWith("waiting for materials") || s.startsWith("nothing to") || s.startsWith("no work area")
                || s.startsWith("backpack full") || s.startsWith("on strike") || s.contains("is full") || s.contains("is missing")
                || s.startsWith("couldn't");
    }

    public static String age(long time) {
        long s = (System.currentTimeMillis() - time) / 1000;
        if (s < 60) return s + "s";
        if (s < 3600) return s / 60 + "m";
        if (s < 86400) return s / 3600 + "h";
        return s / 86400 + "d";
    }
}
