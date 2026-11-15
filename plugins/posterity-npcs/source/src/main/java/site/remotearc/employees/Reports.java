package site.remotearc.employees;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import site.remotearc.employees.board.EventLog;
import site.remotearc.employees.job.JobType;
import site.remotearc.employees.util.Items;
import site.remotearc.employees.util.Msg;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What employees did, in chat: the "while you were away" report on joining,
 * /employees report [hours], and /employees profit [days].
 */
public final class Reports {

    static final long DAY = 86_400_000L;

    /** Jobs whose worth isn't in goods (a build, safety), so a loss on paper is expected. */
    public static boolean worthNotInGoods(JobType j) {
        return j == JobType.BUILDER || j == JobType.GUARD || j == JobType.BODYGUARD || j == JobType.COURIER;
    }

    private final ArcEmployees plugin;

    Reports(ArcEmployees plugin) {
        this.plugin = plugin;
    }

    /** A player joined: after a moment, tell them what happened while they were away. */
    void joined(Player p) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            Ledger.Away a = plugin.ledger().takeAway(p.getUniqueId());
            if (a == null || !plugin.settings().reportOnJoin) return;
            long gone = System.currentTimeMillis() - a.since;
            if (gone < plugin.settings().reportMinAwayMillis || plugin.manager().ownedBy(p.getUniqueId()).isEmpty()) return;
            send(p, p.getUniqueId(), a.employees, "While you were away (" + Msg.duration(gone) + ")", true);
        }, 60L);
    }

    void quit(Player p) {
        if (!plugin.manager().ownedBy(p.getUniqueId()).isEmpty()) plugin.ledger().quit(p.getUniqueId());
    }

    /** /employees report [hours]. */
    void report(CommandSender s, UUID owner, int hours) {
        java.util.Map<UUID, Ledger.Bucket> books = new java.util.HashMap<>();
        plugin.ledger().sample();
        for (Employee e : plugin.manager().ownedBy(owner)) books.put(e.id, plugin.ledger().last(e.id, hours * 3_600_000L));
        send(s, owner, books, "The last " + (hours == 24 ? "24 hours" : hours + " hour" + (hours == 1 ? "" : "s")), false);
    }

    private void send(CommandSender s, UUID owner, Map<UUID, Ledger.Bucket> books, String title, boolean away) {
        s.sendMessage(Msg.parse("<gold><bold>" + title + "</bold>"));
        Ledger.Bucket total = new Ledger.Bucket();
        List<String> quiet = new ArrayList<>();
        List<Employee> mine = new ArrayList<>(plugin.manager().ownedBy(owner));
        mine.sort(Comparator.comparing((Employee e) -> e.job).thenComparing(e -> e.name));
        for (Employee e : mine) {
            Ledger.Bucket b = books.get(e.id);
            if (b == null || b.isEmpty()) {
                quiet.add(e.name);
                continue;
            }
            total.add(b);
            List<String> parts = new ArrayList<>();
            String did = activity(b);
            if (!did.isEmpty()) parts.add(did);
            String made = top(b.made, 3);
            if (!made.isEmpty()) parts.add(made);
            if (b.sales > 0) parts.add("sold for " + Msg.money(b.sales));
            String line = "<gold>" + esc(e.name) + "</gold> <dark_gray>(" + e.job.display() + ")</dark_gray> <gray>"
                    + esc(parts.isEmpty() ? "just drew wages" : String.join(" · ", parts)) + "</gray> " + profit(b.profit());
            s.sendMessage(Msg.parse(line).hoverEvent(HoverEvent.showText(Msg.parse(breakdown(b)))));
        }
        if (!quiet.isEmpty()) {
            s.sendMessage(Msg.parse("<dark_gray>Nothing to report: " + esc(String.join(", ", quiet))
                    + (away && plugin.settings().restWhenEmpty ? " (they rest while nobody's on)" : "")));
        }
        if (!total.isEmpty()) {
            s.sendMessage(Msg.parse("<aqua>Altogether</aqua> <gray>" + breakdown(total).replace("\n", " · ") + "</gray>"));
        }
        List<String> problems = new ArrayList<>();
        for (Employee e : mine) {
            if (e.onStrike || (EventLog.isProblem(e.status) && !e.paused)) problems.add("<gold>" + esc(e.name) + ":</gold> <yellow>" + esc(e.status));
        }
        if (!problems.isEmpty()) {
            s.sendMessage(Msg.parse("<red>Needs attention:"));
            for (String p : problems) s.sendMessage(Msg.parse(" " + p));
        }
        if (s instanceof Player) {
            s.sendMessage(Component.text()
                    .append(Component.text("[Profit per employee]", NamedTextColor.YELLOW)
                            .clickEvent(ClickEvent.runCommand("/employees profit"))
                            .hoverEvent(HoverEvent.showText(Component.text("The last 7 days, best first"))))
                    .append(Component.text("  "))
                    .append(Component.text("[Last 24 hours]", NamedTextColor.YELLOW)
                            .clickEvent(ClickEvent.runCommand("/employees report 24")))
                    .append(Component.text("  "))
                    .append(Component.text("[Employees]", NamedTextColor.YELLOW)
                            .clickEvent(ClickEvent.runCommand("/employees")))
                    .build());
        }
    }

    /** /employees profit [days]: every employee, best first, with what they cost and bring in. */
    void profit(CommandSender s, UUID owner, int days) {
        plugin.ledger().sample();
        List<Employee> mine = new ArrayList<>(plugin.manager().ownedBy(owner));
        if (mine.isEmpty()) {
            Msg.send(s, "<gray>Nobody hired yet.");
            return;
        }
        long span = days * DAY;
        Map<UUID, Ledger.Bucket> books = new java.util.HashMap<>();
        for (Employee e : mine) books.put(e.id, plugin.ledger().last(e.id, span));
        mine.sort((a, b) -> Double.compare(books.get(b.id).profit(), books.get(a.id).profit()));
        s.sendMessage(Msg.parse("<gold><bold>Profit per employee</bold></gold> <gray>(last " + days + " day" + (days == 1 ? "" : "s")
                + "; goods valued at what merchants pay)"));
        Ledger.Bucket total = new Ledger.Bucket();
        for (Employee e : mine) {
            Ledger.Bucket b = books.get(e.id);
            total.add(b);
            String note = "";
            boolean settled = System.currentTimeMillis() - e.hiredAt > 2 * DAY;
            if (b.profit() < 0 && worthNotInGoods(e.job)) note = " <dark_gray>(its worth isn't in goods)";
            else if (b.profit() < 0 && settled) note = " <red>costs more than it brings in";
            s.sendMessage(Msg.parse(" <white>" + esc(e.name) + "</white> <dark_gray>(" + e.job.display() + ")</dark_gray> "
                    + profit(b.profit()) + note).hoverEvent(HoverEvent.showText(Msg.parse(breakdown(b)))));
        }
        s.sendMessage(Msg.parse("<aqua>Altogether</aqua> " + profit(total.profit()) + " <gray>(" + breakdown(total).replace("\n", " · ") + ")"));
        s.sendMessage(Msg.parse("<dark_gray>Hover over a name for the details. Profit = goods put in storage + sales"
                + " - materials taken from storage - wages."));
    }

    /** Profit lines for an employee's menu. */
    public List<String> menuLore(Employee e) {
        Ledger.Bucket day = plugin.ledger().last(e.id, DAY), week = plugin.ledger().last(e.id, 7 * DAY),
                month = plugin.ledger().last(e.id, 30 * DAY);
        List<String> out = new ArrayList<>();
        out.add("<gray>Last 24h: " + profit(day.profit()));
        out.add("<gray>Last 7 days: " + profit(week.profit()));
        out.add("<gray>Last 30 days: " + profit(month.profit()));
        out.add("");
        out.add("<gray>The last 7 days:");
        for (String l : breakdown(week).split("\n")) out.add(" " + l);
        String made = top(week.made, 3);
        if (!made.isEmpty()) out.add("<gray>Made: <white>" + esc(made));
        if (e.job == JobType.COURIER && week.sales > 0) {
            out.add("<dark_gray>It sells goods others made (they were");
            out.add("<dark_gray>credited at full price when stored), so");
            out.add("<dark_gray>its profit is what selling gained or lost.");
        } else if (week.profit() < 0 && worthNotInGoods(e.job)) out.add("<dark_gray>A " + e.job.display().toLowerCase()
                + "'s worth isn't in goods.");
        else if (week.profit() < 0 && System.currentTimeMillis() - e.hiredAt > 2 * DAY) out.add("<red>Costs more than it brings in.");
        out.add("");
        out.add("<dark_gray>Goods are valued at what merchants pay.");
        return out;
    }

    static String breakdown(Ledger.Bucket b) {
        List<String> l = new ArrayList<>();
        l.add("<gray>Goods stored: <white>" + Msg.money(b.stored));
        l.add("<gray>Sales: <green>" + Msg.money(b.sales));
        if (b.taken > 0) l.add("<gray>Goods taken from storage: <gold>-" + Msg.money(b.taken)
                + (b.used.isEmpty() ? "" : " <dark_gray>(" + esc(top(b.used, 2)) + ")"));
        l.add("<gray>Wages: <red>-" + Msg.money(b.wages));
        return String.join("\n", l);
    }

    private static String profit(double v) {
        return (v >= 0 ? "<green>+" : "<red>-") + Msg.money(Math.abs(v));
    }

    /** "412 cobblestone, 38 coal and 3 more". */
    static String top(Map<Material, Integer> m, int max) {
        List<Map.Entry<Material, Integer>> l = new ArrayList<>(m.entrySet());
        l.sort((a, b) -> b.getValue() - a.getValue());
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < l.size() && i < max; i++) parts.add(l.get(i).getValue() + " " + Items.name(l.get(i).getKey()));
        if (l.size() > max) parts.add("and " + (l.size() - max) + " more");
        return String.join(", ", parts);
    }

    /** "felled 12 trees, mined 340 blocks (12 ores)". */
    static String activity(Ledger.Bucket b) {
        List<String> parts = new ArrayList<>();
        add(parts, b.act("trees"), "felled %d tree%s");
        add(parts, b.act("crops"), "harvested %d crop%s");
        long mined = b.act("mined"), ores = b.act("ores");
        if (mined > 0) parts.add("mined " + mined + " block" + (mined == 1 ? "" : "s") + (ores > 0 ? " (" + ores + " ores)" : ""));
        add(parts, b.act("placed"), "placed %d block%s");
        add(parts, b.act("repaired"), "repaired %d block%s");
        add(parts, b.act("smelted"), "smelted %d item%s");
        add(parts, b.act("bred"), "bred %d animal%s");
        add(parts, b.act("sheared"), "sheared %d sheep%s");
        add(parts, b.act("mobs"), "dealt with %d mob%s");
        add(parts, b.act("trades"), "made %d trade%s");
        add(parts, b.act("moved"), "moved %d item%s");
        if (parts.isEmpty()) add(parts, b.act("saplings"), "planted %d sapling%s");
        return String.join(", ", parts.subList(0, Math.min(2, parts.size())));
    }

    private static void add(List<String> parts, long n, String fmt) {
        if (n <= 0) return;
        String plural = fmt.endsWith("sheep%s") ? "" : n == 1 ? "" : "s";
        parts.add(String.format(fmt, n, plural));
    }

    private static String esc(String s) {
        return site.remotearc.employees.gui.Describe.esc(s);
    }
}
