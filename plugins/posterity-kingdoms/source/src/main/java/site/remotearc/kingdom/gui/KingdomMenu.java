package site.remotearc.kingdom.gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import site.remotearc.kingdom.Kingdom;
import site.remotearc.kingdom.Msg;
import site.remotearc.kingdom.PosterityKingdoms;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The kingdom info-and-buttons screen: who rules it, its Core, and what you can do about it. */
public final class KingdomMenu {

    private KingdomMenu() {}

    public static void show(PosterityKingdoms plugin, Player p) {
        Kingdom k = plugin.kingdoms().of(p.getUniqueId());
        if (k == null) {
            noKingdom(plugin, p);
            return;
        }
        boolean owner = k.owner.equals(p.getUniqueId());
        boolean admin = p.hasPermission("posteritykingdoms.admin");

        List<String> lines = new ArrayList<>();
        lines.add("<gold><b>" + Msg.esc(k.name) + "</b></gold>");
        lines.add("<gray>Core at <white>" + k.coreX + ", " + k.coreY + ", " + k.coreZ + "</white> in " + Msg.esc(k.world));
        lines.add("<gray>Rulers: <white>" + Msg.esc(k.rulerNames()));
        int npcs = employeeCount(k.name);
        if (npcs >= 0) {
            int cap = maxPerKingdom();
            lines.add("<gray>NPCs: <white>" + npcs + (cap > 0 ? " / " + cap : ""));
        }
        lines.add(k.flagBase == null ? "<gray>Flag: <white>none yet" : "<gray>Flag: <white>set");

        List<Dialogs.Button> b = new ArrayList<>();
        if (owner) {
            b.add(Dialogs.button("<aqua>Add a ruler", "<gray>Puts someone else in charge too: they can also buy NPCs for it.",
                    pl -> Prompt.ask(pl, "Which player should help rule " + k.name + "?", name -> {
                pl.performCommand("kingdom addruler " + name);
                show(plugin, pl);
            })));
            for (UUID id : new ArrayList<>(k.rulers)) {
                String n = Bukkit.getOfflinePlayer(id).getName();
                if (n == null) continue;
                b.add(Dialogs.button("<red>Remove " + n, "<gray>They keep what they've already bought, but can't rule it any more.", pl -> {
                    pl.performCommand("kingdom removeruler " + n);
                    show(plugin, pl);
                }));
            }
        } else {
            b.add(Dialogs.button("<yellow>Leave " + k.name, "<gray>You give up your place as a ruler.", pl -> {
                pl.performCommand("kingdom leave");
                show(plugin, pl);
            }));
        }
        if (owner || admin) {
            b.add(Dialogs.button("<aqua>Set flag from banner in hand", "<gray>Hold the banner you want as " + k.name
                    + "'s flag design (made on a loom); anyone can then buy a copy.", pl -> {
                pl.performCommand("kingdom flag set");
                show(plugin, pl);
            }));
        }
        b.add(Dialogs.button("<green>Buy a flag (" + plugin.settings().flagEmeralds + " emeralds)",
                k.flagBase == null ? "<gray>" + k.name + " has no flag design yet." : "<gray>A copy of " + k.name + "'s flag.",
                pl -> pl.performCommand("kingdom flag buy")));
        if (hasEmployees()) {
            b.add(Dialogs.button("<aqua>NPC hangout spots", "<gray>Name areas where your off-duty NPCs gather instead of"
                    + " loitering at their own post. See, add or remove them.", pl -> pl.performCommand("employees hangouts")));
        }
        if (owner || admin) {
            String rulerNote = k.rulers.isEmpty() ? "" : ", and " + k.rulers.size() + " ruler(s) lose their place in it";
            b.add(Dialogs.button("<red>Disband " + k.name, "<gray>Its Core stops being claimed (anyone can claim that spot, or"
                    + " another, afterward)" + rulerNote + ". Employees already tagged with it keep showing the old name."
                    + " <red>This can't be undone.", pl -> new ConfirmMenu(plugin, "Disband " + k.name + "?", "<red>Yes, disband it", x -> {
                x.performCommand("kingdom delete " + k.name + " confirm");
            }, x -> show(plugin, x)).open(pl)));
        }
        if (admin) {
            b.add(Dialogs.button("<gold>Give yourself money", "<gray>Economy stub, until merchants exist.", pl ->
                    Prompt.ask(pl, "How much?", text -> {
                pl.performCommand("kingdom money " + text);
                show(plugin, pl);
            })));
        }

        Dialogs.show(plugin, p, "<gold>" + Msg.esc(k.name), lines, b, 2, Dialogs.close("Done"));
    }

    private static void noKingdom(PosterityKingdoms plugin, Player p) {
        List<String> lines = List.of("<gray>You're not in a kingdom yet. Get a Kingdom Core (a piece of bedrock) and place it"
                + " wherever you want your kingdom centered; that spot becomes its Core, and (with Posterity NPCs installed)"
                + " its first two NPCs arrive free to hire.");
        List<Dialogs.Button> b = new ArrayList<>();
        b.add(Dialogs.button("<green>Get a Kingdom Core", "<gray>Place it wherever you want; that spot becomes its Core.", pl ->
                Prompt.ask(pl, "Name your kingdom?", name -> {
            if (!Kingdom.validName(name)) {
                Msg.error(pl, "Pick another name (1-24 letters, numbers, - or _, no spaces).");
                return;
            }
            pl.performCommand("kingdom core " + name);
            show(plugin, pl);
        })));
        Dialogs.show(plugin, p, "<gold>No kingdom yet", lines, b, 1, Dialogs.close("Done"));
    }

    /** How many NPCs (ArcEmployees) are tagged with this kingdom, or -1 if that plugin isn't installed. Reached by reflection. */
    private static int employeeCount(String kingdom) {
        var employees = Bukkit.getPluginManager().getPlugin("ArcEmployees");
        if (employees == null || !employees.isEnabled()) return -1;
        try {
            return (int) employees.getClass().getMethod("employeesInKingdom", String.class).invoke(null, kingdom);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return -1;
        }
    }

    private static boolean hasEmployees() {
        var employees = Bukkit.getPluginManager().getPlugin("ArcEmployees");
        return employees != null && employees.isEnabled();
    }

    private static int maxPerKingdom() {
        var employees = Bukkit.getPluginManager().getPlugin("ArcEmployees");
        if (employees == null || !employees.isEnabled()) return 0;
        try {
            return (int) employees.getClass().getMethod("maxPerKingdom").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return 0;
        }
    }
}
