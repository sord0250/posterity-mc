package site.remotearc.employees.job;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Villager;
import org.bukkit.scheduler.BukkitTask;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.Employee;
import site.remotearc.employees.util.BlockPos;
import site.remotearc.employees.util.Msg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /emp stalls &lt;trader&gt; show: each stall marked in the world for a minute
 * and a half, only for you. A label over it (its number, job, who's in it,
 * what it's after), green sparks round the villager's spot, orange round the
 * job block's and blue round the trapdoor's.
 */
public final class StallView {

    private static final Particle.DustOptions STAND = new Particle.DustOptions(Color.fromRGB(70, 230, 90), 0.9f);
    private static final Particle.DustOptions JOB = new Particle.DustOptions(Color.fromRGB(255, 150, 30), 0.9f);
    private static final Particle.DustOptions CAP = new Particle.DustOptions(Color.fromRGB(60, 140, 255), 0.9f);
    private static final Map<UUID, BukkitTask> SHOWING = new HashMap<>();
    private static final Map<UUID, List<TextDisplay>> LABELS = new HashMap<>();

    private StallView() {}

    public static void hide(Player p) {
        BukkitTask t = SHOWING.remove(p.getUniqueId());
        if (t != null) t.cancel();
        List<TextDisplay> l = LABELS.remove(p.getUniqueId());
        if (l != null) l.forEach(TextDisplay::remove);
    }

    public static void show(ArcEmployees plugin, Player p, Employee e, int seconds) {
        hide(p);
        World w = e.location == null ? null : e.location.getWorld();
        if (w == null || !w.equals(p.getWorld())) return;
        List<TextDisplay> labels = new ArrayList<>();
        for (int i = 0; i < e.stalls.size(); i++) {
            Employee.Stall st = e.stalls.get(i);
            Location at = new Location(w, st.stand.x() + 0.5, st.stand.y() + 2.7, st.stand.z() + 0.5);
            String text = text(w, st, i + 1);
            TextDisplay d = w.spawn(at, TextDisplay.class, t -> {
                t.setPersistent(false);
                t.setVisibleByDefault(false);
                t.setBillboard(Display.Billboard.CENTER);
                t.setShadowed(true);
                t.setBackgroundColor(Color.fromARGB(120, 0, 0, 0));
                t.setLineWidth(160);
                t.setSeeThrough(true);
                t.text(Msg.parse(text));
            });
            p.showEntity(plugin, d);
            labels.add(d);
        }
        LABELS.put(p.getUniqueId(), labels);
        int[] left = {seconds * 2};
        SHOWING.put(p.getUniqueId(), Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!p.isOnline() || --left[0] <= 0 || !p.getWorld().equals(w)) {
                hide(p);
                return;
            }
            for (int i = 0; i < e.stalls.size(); i++) {
                Employee.Stall st = e.stalls.get(i);
                outline(p, st.stand, STAND, 0.0);
                if (st.workstation != null) outline(p, st.workstation, JOB, 0.0);
                if (st.door != null) outline(p, st.door, CAP, 0.5);
            }
            if (left[0] % 10 == 0) {
                // Who's in them changes as the trader works: keep the labels current.
                for (int i = 0; i < labels.size() && i < e.stalls.size(); i++) {
                    if (labels.get(i).isValid()) labels.get(i).text(Msg.parse(text(w, e.stalls.get(i), i + 1)));
                }
            }
        }, 1L, 10L));
    }

    private static String text(World w, Employee.Stall st, int n) {
        StringBuilder b = new StringBuilder();
        Villager v = w.isChunkLoaded(st.stand.x() >> 4, st.stand.z() >> 4) ? Stalls.inStall(w, st) : null;
        b.append(v != null ? "<green>" : "<yellow>").append("<bold>Stall ").append(n).append("</bold>");
        Villager.Profession job = Stalls.profession(w, st);
        String jobName = job != null ? Msg.titleCase(job.getKey().getKey()) : st.profession != null ? Msg.titleCase(st.profession) : "no job set";
        b.append("\n<gray>").append(jobName);
        if (v == null) b.append("\n<yellow>empty");
        else {
            b.append("\n<white>").append(Msg.titleCase(v.getProfession().getKey().getKey())).append(" in it");
            List<String> books = new ArrayList<>();
            for (var r : v.getRecipes()) {
                String name = site.remotearc.employees.trades.TradeBoard.describe(r.getResult());
                if (name.endsWith("book")) books.add(name);
            }
            if (!books.isEmpty()) b.append("\n<aqua>").append(site.remotearc.employees.gui.Describe.esc(String.join(", ", books)));
        }
        if (st.want != null) b.append("\n<gold>wants ").append(site.remotearc.employees.gui.Describe.esc(st.want));
        return b.toString();
    }

    /** The edges of a block, in sparks only this player sees. */
    private static void outline(Player p, BlockPos b, Particle.DustOptions dust, double topOnly) {
        double x0 = b.x(), y0 = b.y() + topOnly, z0 = b.z();
        double y1 = b.y() + 1;
        for (double t = 0; t <= 1.001; t += 0.25) {
            for (double[] c : new double[][]{{x0 + t, y0, z0}, {x0 + t, y0, z0 + 1}, {x0, y0, z0 + t}, {x0 + 1, y0, z0 + t},
                    {x0 + t, y1, z0}, {x0 + t, y1, z0 + 1}, {x0, y1, z0 + t}, {x0 + 1, y1, z0 + t}}) {
                p.spawnParticle(Particle.DUST, c[0], c[1], c[2], 1, 0, 0, 0, 0, dust);
            }
        }
    }
}
