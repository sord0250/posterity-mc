package site.remotearc.employees.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Villager;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.HireCost;
import site.remotearc.employees.Settings;
import site.remotearc.employees.job.JobType;
import site.remotearc.employees.util.Msg;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pick a job to hire for; hover a job for what it does and what it asks for
 * (the tools of the trade and some food, see HireCost).
 *
 * Opened on a villager (shift-right-click one), each job opens the villager's
 * offer (HireDialog): hire, or reserve it. Opened from /employees, it hires someone where
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

    /** Who's looking: the food asked for grows with how many employees they have. */
    private java.util.UUID viewer = new java.util.UUID(0, 0);

    @Override
    public void openFor(org.bukkit.entity.Player p) {
        viewer = p.getUniqueId();
    }

    @Override
    protected void draw() {
        for (int s = 0; s < 36; s++) button(s, filler(), null);
        if (villager != null) {
            button(4, icon(Material.VILLAGER_SPAWN_EGG, "<green>" + villagerLabel(villager), List.of(
                    "<gray>Pick a job below and this villager", "<gray>becomes your employee: it takes on",
                    "<gray>a player's look and works here.", "",
                    plugin.villagerHiring().isSettler(villager) ? "<gray>Your settler works for <white>free</white>."
                            : "<gray>It asks for the <white>tools</white> of the job", plugin.villagerHiring().isSettler(villager) ? ""
                            : "<gray>and some <white>food</white> to start with.")), null);
        }
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
        int k = 0;
        for (JobType t : JobType.values()) {
            if (k >= slots.length) break;
            int slot = slots[k++];
            java.util.Map<Material, Integer> cost = HireCost.of(plugin, viewer, t);
            List<String> lore = new ArrayList<>(wrap("<gray>", t.description(), 36));
            lore.add("");
            if (villager != null) {
                boolean free = plugin.villagerHiring().isSettler(villager);
                lore.addAll(wrap("<gray>", "Asks for: " + (free ? "nothing (a settler)" : HireCost.describe(cost)), 36));
                lore.add("");
                lore.add("<yellow>Click to hear the villager's offer");
                button(slot, icon(t.icon(), "<gold>" + t.display(), lore), (p, c) -> HireDialog.show(plugin, p, villager, t));
                continue;
            }
            lore.addAll(wrap("<gray>", "Asks for: " + HireCost.describe(cost), 36));
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
                            "<green>Hire here (admin, free)", pl -> {
                        pl.closeInventory();
                        run(pl, "hire " + t.key());
                    }, this::open).open(p);
                });
                continue;
            }
            lore.add("<yellow>Click to hire one where you stand");
            button(slot, icon(t.icon(), "<gold>" + t.display(), lore), (p, c) -> new ConfirmMenu(plugin,
                    "Hire a " + t.display().toLowerCase() + "?", "<green>Hire for " + HireCost.describe(cost), pl -> {
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
