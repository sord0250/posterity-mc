package site.remotearc.employees;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import site.remotearc.employees.board.EventLog;
import site.remotearc.employees.job.Worker;

/**
 * What an employee is doing right now, in one short line: the line over its
 * head, and the top of its card. Sorted into a few kinds, each with its own
 * symbol and colour, so a glance tells working from sleeping from in trouble.
 */
public final class Activity {

    private Activity() {}

    public enum Kind {
        WORKING("⚒", NamedTextColor.GREEN, "green"),
        IDLE("⌛", NamedTextColor.GRAY, "gray"),
        PERSONALITY("❤", NamedTextColor.LIGHT_PURPLE, "light_purple"),
        RELAXING("☕", NamedTextColor.GOLD, "gold"),
        SLEEPING("☾", NamedTextColor.DARK_AQUA, "dark_aqua"),
        PAUSED("✋", NamedTextColor.GRAY, "gray"),
        RESTING("☁", NamedTextColor.GRAY, "gray"),
        PROBLEM("⚠", NamedTextColor.RED, "red");

        public final String symbol;
        public final TextColor color;
        /** The colour as a MiniMessage tag name. */
        public final String mini;

        Kind(String symbol, TextColor color, String mini) {
            this.symbol = symbol;
            this.color = color;
            this.mini = mini;
        }
    }

    public static Kind kind(Employee e) {
        Worker.State s = e.worker == null ? null : e.worker.state();
        String status = e.status == null ? "" : e.status;
        if (e.paused || s == Worker.State.PAUSED) return Kind.PAUSED;
        if (s == Worker.State.RESTING) return Kind.RESTING;
        if (s == Worker.State.HUNGRY || s == Worker.State.FULL || status.startsWith("Knocked out") || status.startsWith("Error")
                || EventLog.isProblem(status)) return Kind.PROBLEM;
        if (s == Worker.State.OFF_DUTY) {
            return status.startsWith("Sleeping") || status.startsWith("Heading home to bed") ? Kind.SLEEPING : Kind.RELAXING;
        }
        if (status.startsWith("Distracted")) return Kind.PERSONALITY;
        if (s == Worker.State.WORKING) return Kind.WORKING;
        return Kind.IDLE;
    }

    /** True if it needs the owner: trouble, or working unhappily for want of a home. */
    public static boolean needsAttention(ArcEmployees plugin, Employee e) {
        return kind(e) == Kind.PROBLEM || plugin.manager().homeless(e);
    }

    /** Its status without the "Distracted: " or "Relaxing: " prefix, capitalised: "Following a sheep". */
    public static String text(Employee e) {
        String s = e.status == null || e.status.isEmpty() ? "Starting work" : e.status;
        int colon = s.indexOf(": ");
        if ((s.startsWith("Distracted: ") || s.startsWith("Relaxing: ")) && colon + 2 < s.length()) {
            s = Character.toUpperCase(s.charAt(colon + 2)) + s.substring(colon + 3);
        }
        return s;
    }

    /** The line over its head: symbol and what it's doing, plus "· no home" while homeless. */
    public static Component line(ArcEmployees plugin, Employee e) {
        Kind k = kind(e);
        Component c = Component.text(k.symbol + " " + cut(text(e), 44), k.color);
        if (plugin.manager().homeless(e) && k != Kind.PROBLEM) {
            c = c.append(Component.text(" · unhappy: no home", NamedTextColor.GOLD));
        }
        return c;
    }

    /** The same as MiniMessage, for menus and cards (escaped). */
    public static String mini(ArcEmployees plugin, Employee e) {
        Kind k = kind(e);
        String out = "<" + k.mini + ">" + k.symbol + " " + site.remotearc.employees.gui.Describe.esc(text(e)) + "</" + k.mini + ">";
        if (plugin.manager().homeless(e) && k != Kind.PROBLEM) out += " <gold>· unhappy: no home</gold>";
        return out;
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
