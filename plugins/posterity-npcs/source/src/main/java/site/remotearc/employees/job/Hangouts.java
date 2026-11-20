package site.remotearc.employees.job;

import org.bukkit.util.Vector;
import site.remotearc.employees.Employee;
import site.remotearc.employees.HangoutSpots;
import site.remotearc.employees.util.WorkArea;

import java.util.UUID;

/**
 * Finds a kingdom hangout spot (named areas managed from the kingdom menu's
 * "NPC hangout spots" button, or {@code /employees hangouts}) for an
 * off-duty employee to wander to instead of just loitering at its own post
 * - see {@link Distractions#relax}.
 */
final class Hangouts {

    private Hangouts() {}

    /** How far off (in the same world) a hangout spot may be to be worth the walk. */
    private static final double MAX_DISTANCE = 150;

    record Spot(Vector at, String label) {}

    /** The nearest in-reach hangout spot of this employee's owner's kingdom, or null (no kingdom, or none close enough). */
    static Spot find(Worker w) {
        Employee e = w.e;
        UUID kingdomOwner = w.plugin().kingdomBridge().kingdomOwnerOf(e.owner);
        if (kingdomOwner == null) return null;
        Vector here = e.location.toVector();
        Spot best = null;
        double bestD = MAX_DISTANCE * MAX_DISTANCE;
        for (HangoutSpots.Spot spot : w.plugin().hangoutSpots().of(kingdomOwner)) {
            WorkArea area = spot.area();
            if (!area.world().equals(w.world().getName())) continue;
            Vector center = new Vector((area.minX() + area.maxX()) / 2.0 + 0.5, area.minY(), (area.minZ() + area.maxZ()) / 2.0 + 0.5);
            double d = center.distanceSquared(here);
            if (d < bestD) {
                bestD = d;
                best = new Spot(randomIn(w, area), spot.name());
            }
        }
        return best;
    }

    /** A random point on the ground somewhere inside the area, not always its exact middle. */
    private static Vector randomIn(Worker w, WorkArea area) {
        int x = area.minX() + w.random().nextInt(Math.max(1, area.width()));
        int z = area.minZ() + w.random().nextInt(Math.max(1, area.length()));
        return new Vector(x + 0.5, area.minY(), z + 0.5);
    }
}
