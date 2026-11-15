package site.remotearc.employees.gui;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.util.WorkArea;

/** Draws a work area's outline in particles, visible only to one player. */
public final class AreaPreview {

    private static final Particle.DustOptions EDGE = new Particle.DustOptions(Color.fromRGB(80, 255, 120), 1.4f);
    private static final Particle.DustOptions CORNER = new Particle.DustOptions(Color.fromRGB(255, 200, 40), 1.8f);

    private AreaPreview() {}

    public static void show(ArcEmployees plugin, Player p, WorkArea a, int ticks) {
        World w = a.bukkitWorld();
        if (w == null || !w.equals(p.getWorld())) return;
        new BukkitRunnable() {
            int left = ticks;

            @Override
            public void run() {
                if (!p.isOnline() || !p.getWorld().equals(w) || (left -= 10) < 0) {
                    cancel();
                    return;
                }
                double y0 = a.minY(), y1 = a.maxY() + 1;
                double x0 = a.minX(), x1 = a.maxX() + 1, z0 = a.minZ(), z1 = a.maxZ() + 1;
                for (double x = x0; x <= x1; x += 0.5) {
                    dot(x, ground(w, x, z0, a), z0, EDGE);
                    dot(x, ground(w, x, z1 - 0.01, a), z1, EDGE);
                }
                for (double z = z0; z <= z1; z += 0.5) {
                    dot(x0, ground(w, x0, z, a), z, EDGE);
                    dot(x1, ground(w, x1 - 0.01, z, a), z, EDGE);
                }
                for (double y = y0; y <= y1; y += 0.5) {
                    dot(x0, y, z0, CORNER);
                    dot(x1, y, z0, CORNER);
                    dot(x0, y, z1, CORNER);
                    dot(x1, y, z1, CORNER);
                }
            }

            private void dot(double x, double y, double z, Particle.DustOptions o) {
                p.spawnParticle(Particle.DUST, new Location(w, x, y + 0.15, z), 1, 0, 0, 0, 0, o);
            }
        }.runTaskTimer(plugin, 0L, 10L);
    }

    /** Top of the highest solid block in the column, clamped to the area's height. */
    private static double ground(World w, double x, double z, WorkArea a) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (!w.isChunkLoaded(bx >> 4, bz >> 4)) return a.minY();
        int top = Math.min(a.maxY(), w.getHighestBlockYAt(bx, bz));
        for (int y = top; y >= a.minY() - 1; y--) {
            if (w.getType(bx, y, bz).isSolid()) return y + 1;
        }
        return a.minY();
    }
}
