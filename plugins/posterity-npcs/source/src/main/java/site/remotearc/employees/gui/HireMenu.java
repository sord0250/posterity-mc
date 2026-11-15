package site.remotearc.employees.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Villager;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.Settings;
import site.remotearc.employees.job.JobType;
import site.remotearc.employees.util.Msg;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pick a job to hire for; hover a job for what it does and what it costs.
 *
 * Opened on a villager (shift-right-click one), each job is a button that hires
 * that villager for it, free. Opened from /employees, it hires someone where
 * you stand, unless employees come only from villagers (villager-hiring.villagers-only),
 * when it tells you to go and find a villager instead (admins can still hire here).
 */
public final class HireMenu extends Menu {

    private final Menu back;
    /** The villager being hired, or null when hiring from /employees. */
    private final Villager villager;

    HireMenu(ArcEmployees plugin, Menu back) {
        this(plugin, back, null);
    }

    private HireMenu(ArcEmployees plugin, Menu back, Villager villager) {
        super(plugin, 4, Component.text(villager != null ? "Hire this villager" : "Hire an employee"));
        this.back = back;
        this.villager = villager;
    }

    /** The menu for hiring this villager. */
    public static HireMenu forVillager(ArcEmployees plugin, Villager v) {
        return new HireMenu(plugin, null, v);
    }

    @Override
    protected void draw() {
        for (int s = 0; s < 36; s++) button(s, filler(), null);
        if (villager != null) {
            button(4, icon(Material.VILLAGER_SPAWN_EGG, "<green>" + villagerLabel(villager), List.of(
                    "<gray>Pick a job below and this villager", "<gray>becomes your employee: it takes on",
                    "<gray>a player's look and works here.", "",
                    "<gray>Hiring a villager is <white>free</white> for now.")), null);
        }
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
        int k = 0;
        for (JobType t : JobType.values()) {
            if (k >= slots.length) break;
            int slot = slots[k++];
            Settings.JobSettings js = plugin.settings().job(t);
            double cost = js.hireCost() + js.wage();
            List<String> lore = new ArrayList<>(wrap("<gray>", t.description(), 36));
            lore.add("");
            if (villager != null) {
                lore.add("<gray>Hire: <white>free");
                lore.add("<gray>Wage: <white>" + Describe.wageLine(plugin, t));
                lore.add("");
                lore.add("<yellow>Click to hire this villager as a " + t.display().toLowerCase(Locale.ROOT));
                button(slot, icon(t.icon(), "<gold>" + t.display(), lore), (p, c) -> new ConfirmMenu(plugin,
                        "Hire it as a " + t.display().toLowerCase(Locale.ROOT) + "?", "<green>Hire (free)", pl -> {
                    pl.closeInventory();
                    plugin.villagerHiring().hire(pl, villager, t, null);
                }, this::open).open(p));
                continue;
            }
            lore.add("<gray>Hire: <white>" + Msg.money(js.hireCost()) + "</white> + first wage <white>" + Msg.money(js.wage()));
            lore.add("<gray>Wage: <white>" + Describe.wageLine(plugin, t));
            lore.add("");
            if (plugin.settings().villagersOnly) {
                lore.add("<yellow>Shift-right-click a grown-up villager");
                lore.add("<yellow>to hire it as a " + t.display().toLowerCase(Locale.ROOT));
                button(slot, icon(t.icon(), "<gold>" + t.display(), lore), (p, c) -> {
                    if (!p.hasPermission("employees.admin")) {
                        p.closeInventory();
                        Msg.send(p, "<gray>Employees are hired from villagers: find a grown-up villager and <white>shift-right-click</white>"
                                + " it, or look at it and type <white>/employees hire " + t.key() + "</white>.");
                        return;
                    }
                    new ConfirmMenu(plugin, "Hire a " + t.display().toLowerCase(Locale.ROOT) + " here?",
                            "<green>Hire for " + Msg.money(cost) + " (admin)", pl -> {
                        pl.closeInventory();
                        run(pl, "hire " + t.key());
                    }, this::open).open(p);
                });
                continue;
            }
            lore.add("<yellow>Click to hire one where you stand");
            button(slot, icon(t.icon(), "<gold>" + t.display(), lore), (p, c) -> new ConfirmMenu(plugin,
                    "Hire a " + t.display().toLowerCase() + "?", "<green>Hire for " + Msg.money(cost), pl -> {
                pl.closeInventory();
                run(pl, "hire " + t.key());
            }, this::open).open(p));
        }
        if (back != null) button(27, back(), (p, c) -> back.open(p));
        else button(27, icon(Material.BARRIER, "<red>Not now", List.of("<gray>Leave the villager be.")), (p, c) -> p.closeInventory());
    }

    /** "Farmer villager (Bob)", "Villager". */
    private static String villagerLabel(Villager v) {
        String prof = v.getProfession().getKey().getKey().replace('_', ' ');
        String what = prof.equals("none") ? "Villager" : Character.toUpperCase(prof.charAt(0)) + prof.substring(1) + " villager";
        if (v.customName() != null) {
            what += " (" + Describe.esc(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(v.customName())) + ")";
        }
        return what;
    }
}
