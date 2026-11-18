package site.remotearc.employees;

import org.bukkit.World;

/**
 * The working day. Employees work from {@code schedule.work-start} to
 * {@code work-end} on the Minecraft clock, relax in the evening, and sleep
 * from {@code sleep-start} until work starts again. Night-shift jobs (guards)
 * are always on duty. Later, holidays and family time slot in here.
 */
public final class Schedule {

    private Schedule() {}

    public enum Period { WORK, EVENING, NIGHT }

    /** The hour on the Minecraft clock (0-24, 6 = sunrise) in a world. */
    public static double hour(World w) {
        double h = w.getTime() / 1000.0 + 6;
        return h >= 24 ? h - 24 : h;
    }

    public static Period period(ArcEmployees plugin, Employee e, World w) {
        Settings s = plugin.settings();
        if (!s.scheduleEnabled || w == null || s.nightShift.contains(e.job)) return Period.WORK;
        if (w.getEnvironment() != World.Environment.NORMAL) return Period.WORK;   // no day and night in the Nether or the End
        double h = hour(w);
        if (within(h, s.workStart, s.workEnd)) return Period.WORK;
        if (within(h, s.workEnd, s.sleepStart)) return Period.EVENING;
        return Period.NIGHT;
    }

    /** From {@code from} up to {@code to}, going round midnight if need be. */
    private static boolean within(double h, double from, double to) {
        if (from == to) return false;
        return from < to ? h >= from && h < to : h >= from || h < to;
    }

    /** "6:00", "18:30". */
    public static String clock(double hour) {
        int h = (int) Math.floor(hour) % 24, m = (int) Math.round((hour - Math.floor(hour)) * 60) % 60;
        return h + ":" + (m < 10 ? "0" : "") + m;
    }
}
