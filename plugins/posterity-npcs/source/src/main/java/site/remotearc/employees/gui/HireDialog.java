package site.remotearc.employees.gui;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.HireCost;
import site.remotearc.employees.VillagerHiring;
import site.remotearc.employees.job.JobType;
import site.remotearc.employees.util.Items;
import site.remotearc.employees.util.Msg;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The villager's offer, as a pop-up: "I'll be your farmer if you give me an
 * iron hoe and 16 bread", with a tick or a cross by each thing (what you have),
 * and the buttons: Hire, Reserve for me (it waits by its bed until you're
 * back), Pick another job, Not now.
 */
public final class HireDialog {

    private HireDialog() {}

    public static void show(ArcEmployees plugin, Player p, Villager v, JobType job) {
        VillagerHiring vh = plugin.villagerHiring();
        String why = vh.whyNot(p, v);
        if (why != null) {
            Msg.error(p, why);
            return;
        }
        boolean free = vh.isSettler(v);
        Map<Material, Integer> cost = free ? Map.of() : HireCost.of(plugin, p.getUniqueId(), job);
        Map<Material, Integer> missing = p.getGameMode() == org.bukkit.GameMode.CREATIVE ? Map.of() : HireCost.missing(p, cost);
        String jobName = job.display().toLowerCase(Locale.ROOT);
        String who = Describe.esc(VillagerHiring.label(v));

        List<String> lines = new ArrayList<>();
        lines.add("<gray>" + Describe.esc(job.description()));
        if (free) {
            lines.add("<white>\"I came here with your kingdom. I'll be your " + jobName + " for nothing but a roof and a meal.\"");
        } else {
            lines.add("<white>\"I'll be your " + jobName + " if you give me " + HireCost.describe(cost) + ".\"");
            StringBuilder list = new StringBuilder();
            for (Map.Entry<Material, Integer> c : cost.entrySet()) {
                int lack = missing.getOrDefault(c.getKey(), 0);
                if (!list.isEmpty()) list.append("\n");
                if (lack <= 0) list.append("<green>✔ ").append(c.getValue()).append(" ").append(Items.name(c.getKey())).append("</green>");
                else list.append("<red>✘ ").append(c.getValue()).append(" ").append(Items.name(c.getKey()))
                        .append("</red> <gray>(you have ").append(c.getValue() - lack).append(")</gray>");
            }
            lines.add(list.toString());
            lines.add("<dark_gray>They keep the tools to work with; the food goes in their food store. After that they eat from a pantry chest.");
        }
        VillagerHiring.Reservation r = vh.reservation(v);
        boolean mine = r != null && r.owner().equals(p.getUniqueId());
        if (mine) {
            lines.add("<aqua>Waiting for you by their bed" + (r.until() > 0 ? " for " + Msg.duration(r.until() - System.currentTimeMillis()) + " more" : "") + ".");
        }

        List<Dialogs.Button> buttons = new ArrayList<>();
        if (missing.isEmpty()) {
            buttons.add(Dialogs.button("<green><b>Hire " + who + "</b>", "<gray>" + (free ? "Free: they came with your kingdom."
                    : "Hand over " + HireCost.describe(cost) + "."), pl -> vh.hire(pl, v, job, null)));
        } else {
            buttons.add(Dialogs.button("<gray>Hire <dark_gray>(missing items)", "<red>Still missing: " + HireCost.describe(missing),
                    pl -> {
                        Msg.error(pl, "You still need " + HireCost.describe(HireCost.missing(pl, cost)) + ".");
                        show(plugin, pl, v, job);
                    }));
        }
        if (!free) {
            if (mine) {
                buttons.add(Dialogs.button("<yellow>Let them go", "<gray>Cancel the reservation: they go back to village life.", pl -> {
                    if (!v.isValid()) return;
                    vh.release(v);
                    Msg.send(pl, "<gray>" + who + " goes back to village life.");
                }));
            } else {
                double days = plugin.settings().reserveDays;
                buttons.add(Dialogs.button("<aqua>Reserve for me", "<gray>They go and stand by their bed and wait for you"
                        + (days > 0 ? " (up to " + (days == Math.rint(days) ? String.valueOf((long) days) : String.valueOf(days)) + " days)" : "")
                        + ".\n<gray>Only you can hire them meanwhile.", pl -> vh.reserve(pl, v, job)));
            }
        }
        buttons.add(Dialogs.button("<white>Pick another job", "<gray>See every job and what each one asks for.",
                pl -> HireMenu.forVillager(plugin, v).open(pl)));

        Dialogs.show(plugin, p, who + " <gray>· " + job.display(), lines, buttons, buttons.size() >= 3 ? 3 : buttons.size(),
                Dialogs.close("Not now"));
    }
}
