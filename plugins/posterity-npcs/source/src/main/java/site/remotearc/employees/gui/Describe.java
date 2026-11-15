package site.remotearc.employees.gui;

import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.Employee;
import site.remotearc.employees.Settings;
import site.remotearc.employees.job.JobType;
import site.remotearc.employees.storage.Supply;
import site.remotearc.employees.util.Items;
import site.remotearc.employees.util.MaterialList;
import site.remotearc.employees.util.Msg;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;

/** The text shown about an employee, shared by the menus and /employees info. */
public final class Describe {

    private static final NumberFormat NUM = NumberFormat.getIntegerInstance();

    private Describe() {}

    public static String wageLine(ArcEmployees plugin, JobType job) {
        Settings s = plugin.settings();
        double hours = s.payIntervalMillis / 3_600_000.0;
        String per = Math.abs(hours - 24) < 1e-6 ? "per day" : Math.abs(hours - 1) < 1e-6 ? "per hour"
                : "every " + (hours == Math.rint(hours) ? String.valueOf((long) hours) : String.valueOf(hours)) + "h";
        return Msg.money(s.job(job).wage()) + " " + per;
    }

    public static List<String> lore(ArcEmployees plugin, Employee e) {
        List<String> out = new ArrayList<>();
        String statusColor = e.onStrike ? "<red>" : e.paused ? "<gray>" : "<white>";
        out.add("<gray>Status: " + statusColor + esc(e.status));
        if (plugin.settings().homesEnabled) {
            out.add(site.remotearc.employees.HomeSites.describe(plugin, e));
        }
        if (e.personality != null && plugin.settings().personalitiesEnabled) {
            out.add("<gray>Personality: <light_purple>" + e.personality.display + " <dark_gray>(" + e.personality.does + ")");
        }
        if (e.area != null) out.add("<gray>Work area: <white>" + e.area.describe() + (e.area.world().equals("world") ? "" : " (" + esc(e.area.world()) + ")"));
        out.add("<gray>Delivers to: <white>" + esc(e.storage.describe())
                + (e.overflow.isEmpty() ? "" : " <gray>(+ overflow " + e.overflow.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(", ")) + ")"));
        long left = e.paused ? e.pausedRemaining : e.paidUntil - System.currentTimeMillis();
        out.add("<gray>Wage: <white>" + wageLine(plugin, e.job)
                + (e.onStrike ? " <red>(unpaid)" : " <dark_gray>(next in " + Msg.duration(left) + (e.paused ? ", paused" : "") + ")"));
        if (e.worksAway) out.add("<yellow>Works while nobody's on the server");
        if (e.worker != null) {
            String detail = e.worker.detail();
            if (detail != null) out.add("<gray>Progress: <white>" + esc(detail));
        }
        if (e.job.usesFilter()) out.add("<gray>" + filterLabel(e) + ": <white>" + esc(filterText(e, 4)));
        if (e.job.usesSupply()) out.addAll(Menu.wrap("<white>", "<gray>Takes materials from:</gray> " + esc(Supply.describe(plugin, e)), 44));
        Employee.Stats st = e.stats;
        switch (e.job) {
            case LUMBERJACK -> out.add("<gray>Felled <white>" + NUM.format(st.treesFelled) + "</white> trees, planted <white>"
                    + NUM.format(st.saplingsPlanted) + "</white> saplings");
            case FARMER -> out.add("<gray>Harvested <white>" + NUM.format(st.cropsHarvested) + "</white> crops");
            case MINER -> out.add("<gray>Mined <white>" + NUM.format(st.oresMined) + "</white> ores, dug <white>"
                    + NUM.format(st.blocksMined) + "</white> blocks");
            case QUARRY -> out.add("<gray>Dug <white>" + NUM.format(st.blocksMined) + "</white> blocks");
            case BUILDER -> out.add("<gray>Placed <white>" + NUM.format(st.blocksPlaced) + "</white> blocks");
            case SMELTER -> out.add("<gray>Smelted or cooked <white>" + NUM.format(st.itemsSmelted) + "</white> items");
            case RANCHER -> out.add("<gray>Bred <white>" + NUM.format(st.animalsBred) + "</white>, culled <white>"
                    + NUM.format(st.animalsCulled) + "</white>, sheared <white>" + NUM.format(st.sheepSheared) + "</white>");
            case GUARD -> out.add("<gray>Fought off <white>" + NUM.format(st.mobsKilled) + "</white> mobs");
            case TRADER -> out.add("<gray>" + e.trades.size() + " trades picked; made <white>" + NUM.format(st.tradesMade) + "</white> trades");
            case COURIER -> out.add("<gray>" + e.routes.size() + " route" + (e.routes.size() == 1 ? "" : "s") + "; moved <white>"
                    + NUM.format(st.itemsMoved) + "</white> items");
            case BODYGUARD -> out.add("<gray>" + (e.following ? "Following " + e.ownerName() : "At its post") + "; fought off <white>"
                    + NUM.format(st.mobsKilled) + "</white> mobs");
        }
        if (e.selling() || e.stats.earned > 0) {
            double[] today = e.soldToday();
            out.add("<gray>Sells " + (e.sellAll ? "everything merchants buy" : e.sellRules.isEmpty() ? "nothing" : e.sellRules.size() + " kinds")
                    + "; today <white>" + (long) today[0] + "</white> for <green>" + Msg.money(today[1]) + "</green><gray>, all time <white>"
                    + Msg.money(e.stats.earned));
        }
        out.add("<gray>Delivered <white>" + NUM.format(e.stats.itemsDelivered) + "</white> items, earned <white>"
                + Msg.money(e.stats.wagesPaid) + "</white> in total");
        return out;
    }

    public static String filterLabel(Employee e) {
        return switch (e.job) {
            case MINER -> "Looks for";
            case QUARRY -> "Throws away";
            case SMELTER -> "Smelts";
            default -> "Filter";
        };
    }

    /** The filter as text: "ores" for the full ore list, otherwise the first few names. */
    public static String filterText(Employee e, int max) {
        if (e.filter.isEmpty()) return e.job == JobType.QUARRY ? "nothing (keeps everything)" : "nothing";
        java.util.Set<org.bukkit.Material> rest = java.util.EnumSet.copyOf(e.filter);
        List<String> parts = new ArrayList<>();
        if (rest.containsAll(MaterialList.ORES)) {
            parts.add("all ores");
            rest.removeAll(MaterialList.ORES);
        }
        for (org.bukkit.Material m : rest) {
            // Stone and deepslate ores read better as one.
            if (m.name().startsWith("DEEPSLATE_") && rest.contains(org.bukkit.Material.matchMaterial(m.name().substring(10)))) continue;
            if (parts.size() >= max) {
                parts.add("and more");
                break;
            }
            parts.add(Items.name(m));
        }
        return String.join(", ", parts);
    }

    /** Escapes MiniMessage tags in free text (world names, statuses). */
    public static String esc(String s) {
        // Backslashes first: otherwise "\<bold>" would come out as an escaped backslash and a real tag.
        return s.replace("\\", "\\\\").replace("<", "\\<");
    }
}
