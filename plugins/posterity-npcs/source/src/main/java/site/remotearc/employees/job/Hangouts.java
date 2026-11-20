package site.remotearc.employees.job;

import org.bukkit.util.Vector;
import site.remotearc.employees.Employee;

/**
 * Builds a build labels as a hangout spot (a tavern, a park...) with
 * {@code /employees build <name> label <text>}. Off duty, an employee may
 * wander there instead of just loitering at its own post - see
 * {@link Distractions#relax}.
 */
final class Hangouts {

    private Hangouts() {}

    /** How far off (in the same world) a hangout spot may be to be worth the walk. */
    private static final double MAX_DISTANCE = 150;

    record Spot(Vector at, String label) {}

    /** A labeled, finished build this employee's owner (or whoever trusts them) has set up, nearest first; null if none is in reach. */
    static Spot find(Worker w) {
        Employee e = w.e;
        Vector here = e.location.toVector();
        Spot best = null;
        double bestD = MAX_DISTANCE * MAX_DISTANCE;
        for (Employee o : w.plugin().manager().all()) {
            var build = o.build;
            if (build == null || build.hangoutLabel == null || build.hangoutLabel.isBlank() || !build.finished) continue;
            if (!build.world.equals(w.world().getName())) continue;
            if (!o.owner.equals(e.owner) && !w.plugin().managers().trusts(o.owner, e.owner)) continue;
            Vector at = new Vector(build.x + 0.5, build.y, build.z + 0.5);
            double d = at.distanceSquared(here);
            if (d < bestD) {
                bestD = d;
                best = new Spot(at, build.hangoutLabel);
            }
        }
        return best;
    }
}
