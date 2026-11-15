package site.remotearc.employees.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.Employee;
import site.remotearc.employees.job.JobType;
import site.remotearc.employees.util.Msg;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * /employees: one row per job, the job's icon on the left and its employees'
 * heads after it (long rows carry on to the next line). Click a job icon to
 * see just that job. The bottom row holds hiring and the shared controls.
 * (For /employees all, everyone on the server.)
 */
public final class EmployeeListMenu extends Menu {

    private static final int ROWS = 5;

    private final UUID owner;   // null = all employees
    private final JobType only; // null = every job
    private final int page;

    public EmployeeListMenu(ArcEmployees plugin, UUID owner, int page) {
        this(plugin, owner, null, page);
    }

    public EmployeeListMenu(ArcEmployees plugin, UUID owner, JobType only, int page) {
        this(plugin, owner, only, page, false);
    }

    /** {@code managing}: someone else's employees, opened by a player they trust (/employees manage). */
    public EmployeeListMenu(ArcEmployees plugin, UUID owner, JobType only, int page, boolean managing) {
        super(plugin, 6, Component.text((owner == null ? "All employees" : managing
                ? org.bukkit.Bukkit.getOfflinePlayer(owner).getName() + "'s employees" : "Your employees")
                + (only == null ? "" : " · " + only.display() + "s")));
        this.owner = owner;
        this.only = only;
        this.page = page;
        this.managing = managing;
    }

    private final boolean managing;

    private List<Employee> everyone() {
        return new ArrayList<>(owner == null ? plugin.manager().all() : plugin.manager().ownedBy(owner));
    }

    /** One line of the table: a job, and up to eight of its employees. */
    private record Line(JobType job, boolean first, List<Employee> people) {}

    @Override
    protected void draw() {
        List<Employee> all = everyone();
        List<Line> lines = new ArrayList<>();
        for (JobType t : JobType.values()) {
            if (only != null && t != only) continue;
            List<Employee> of = new ArrayList<>();
            for (Employee e : all) if (e.job == t) of.add(e);
            if (of.isEmpty() && only == null) continue;
            for (int i = 0; i < Math.max(1, of.size()); i += 8) lines.add(new Line(t, i == 0, of.subList(i, Math.min(of.size(), i + 8))));
        }
        int pages = Math.max(1, (lines.size() + ROWS - 1) / ROWS);
        int pg = Math.min(page, pages - 1);
        for (int s = 0; s < 54; s++) button(s, filler(), null);
        for (int r = 0; r < ROWS && pg * ROWS + r < lines.size(); r++) {
            Line line = lines.get(pg * ROWS + r);
            JobType t = line.job();
            if (line.first()) {
                List<String> lore = new ArrayList<>(wrap("<gray>", t.description(), 36));
                lore.add("");
                long n = all.stream().filter(e -> e.job == t).count();
                lore.add("<white>" + n + "</white> <gray>employed · hire " + Msg.money(plugin.settings().job(t).hireCost())
                        + ", wage " + Describe.wageLine(plugin, t));
                lore.add("");
                lore.add(only == null ? "<yellow>Click to show only " + t.display().toLowerCase() + "s" : "<yellow>Click to show every job");
                ItemStack label = icon(t.icon(), "<gold>" + t.display() + "s", lore);
                button(r * 9, glow(label), (p, c) -> new EmployeeListMenu(plugin, owner, only == null ? t : null, 0, managing).open(p));
            } else {
                button(r * 9, icon(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<gray>" + t.display() + "s (more)", List.of()), null);
            }
            for (int i = 0; i < line.people().size(); i++) {
                Employee e = line.people().get(i);
                // Kept short so it doesn't cover the rest of the menu; the full details are one click away.
                List<String> lore = new ArrayList<>();
                if (owner == null) lore.add("<gray>Employer: <white>" + e.ownerName());
                String status = e.status.length() > 44 ? e.status.substring(0, 43) + "…" : e.status;
                lore.add((e.onStrike ? "<red>" : e.paused ? "<gray>" : "<white>") + Describe.esc(status));
                String detail = e.worker == null ? null : e.worker.detail();
                if (detail != null) lore.add("<gray>" + Describe.esc(detail.length() > 44 ? detail.substring(0, 43) + "…" : detail));
                lore.add("<yellow>Click to manage");
                button(r * 9 + 1 + i, head(e, "<gold>" + e.name + " <gray>(" + e.job.display() + ")", lore),
                        (p, click) -> new EmployeeMenu(plugin, e, this).open(p));
            }
        }
        if (all.isEmpty()) {
            button(22, icon(Material.PAPER, "<yellow>No employees yet", List.of("<gray>Click the emerald below", "<gray>to hire someone.")), null);
        }

        // Bottom row: paging and the shared controls.
        List<String> waiting = owner == null ? List.of() : plugin.manager().waitingFor(owner);
        if (pg == 0 && !waiting.isEmpty()) {
            List<String> lore = new ArrayList<>(List.of("<gray>Their world isn't loaded right now", "<gray>(a studio world is loaded when you", "<gray>go there). They come back then:"));
            for (String w : waiting) lore.add("<white>" + Describe.esc(w));
            button(45, icon(Material.CLOCK, "<yellow>Away: " + waiting.size(), lore), null);
        }
        if (pg > 0) button(45, icon(Material.ARROW, "<yellow>Previous page", List.of()),
                (p, c) -> new EmployeeListMenu(plugin, owner, only, pg - 1, managing).open(p));
        boolean anyWorking = all.stream().anyMatch(e -> !e.paused);
        button(46, icon(anyWorking ? Material.GRAY_DYE : Material.LIME_DYE, anyWorking ? "<gray>Pause everyone" : "<green>Put everyone back to work",
                List.of("<gray>" + all.stream().filter(e -> e.paused).count() + " of " + all.size() + " paused.",
                        "<gray>Paused employees keep their paid time.", "", "<yellow>Click to " + (anyWorking ? "pause all" : "resume all"))), (p, c) -> {
            if (managing) {
                for (Employee e : all) plugin.manager().setPaused(e, anyWorking);
            } else {
                run(p, anyWorking ? "pauseall" : "resumeall");
            }
            redraw();
        });
        if (managing) {
            button(48, icon(Material.ENDER_CHEST, "<aqua>Chests", List.of(
                    "<gray>Which /chest numbers these employees",
                    "<gray>use, grouped by job.", "", "<yellow>Click to view")), (p, c) -> new ChestsMenu(plugin, owner, this).open(p));
            if (pg + 1 < pages) button(53, icon(Material.ARROW, "<yellow>Next page", List.of()),
                    (p, c) -> new EmployeeListMenu(plugin, owner, only, pg + 1, true).open(p));
            return;
        }
        var trusting = plugin.managers().ownersFor(owner == null ? new UUID(0, 0) : owner);
        if (only == null && !trusting.isEmpty()) {
            List<String> names = trusting.stream().map(o -> String.valueOf(org.bukkit.Bukkit.getOfflinePlayer(o).getName())).toList();
            button(52, icon(Material.PLAYER_HEAD, "<aqua>Friends' employees", concat(List.of("<gray>You can manage the employees of:"),
                    names.stream().map(n -> "<white>" + n).toArray(String[]::new))), (p, c) ->
                    new EmployeeListMenu(plugin, trusting.get(c.isRightClick() && trusting.size() > 1 ? 1 : 0), null, 0, true).open(p));
        }
        button(47, icon(Material.OAK_SIGN, "<aqua>Put up a board", List.of(
                "<gray>Live status of all your employees, or",
                "<gray>your sales, on the wall you look at.",
                "", "<yellow>Click: status board", "<yellow>Right-click: sales board", "<yellow>Shift-click: business board",
                "<dark_gray>/employees board help for sizes")), (p, c) -> {
            p.closeInventory();
            run(p, "board " + (c.isShiftClick() ? "business" : c.isRightClick() ? "sales" : "status"));
        });
        button(48, icon(Material.ENDER_CHEST, "<aqua>Chests", List.of(
                "<gray>Which /chest numbers your employees",
                "<gray>use, grouped by job.", "", "<yellow>Click to view")), (p, c) -> new ChestsMenu(plugin, owner == null ? p.getUniqueId() : owner, this).open(p));
        button(49, glow(icon(Material.EMERALD, "<green>Hire someone", plugin.settings().villagersOnly ? List.of(
                "<gray>Employees are hired from villagers:",
                "<gray>shift-right-click a grown-up villager",
                "<gray>and pick a job. Free for now.", "", "<yellow>Click to see the jobs") : List.of(
                "<gray>Pick a job; they start work",
                "<gray>where you're standing.", "", "<yellow>Click to choose a job"))), (p, c) -> new HireMenu(plugin, this).open(p));
        button(50, icon(Material.LEVER, "<aqua>Control panel", List.of(
                "<gray>Make a button or lever in your base",
                "<gray>pause everyone, open menus and more.", "", "<yellow>Click to set one up")), (p, c) -> new LinkMenu(plugin, this).open(p));
        button(51, icon(Material.GOLD_INGOT, "<aqua>Merchant prices", List.of(
                "<gray>What merchants pay you today.", "", "<yellow>Click to see")),
                (p, c) -> new site.remotearc.employees.market.PriceMenu(plugin, p.getUniqueId(), 0).open(p));
        if (only != null) button(52, icon(Material.BOOK, "<yellow>All jobs", List.of("<gray>Back to everyone.")),
                (p, c) -> new EmployeeListMenu(plugin, owner, null, 0, managing).open(p));
        if (pg + 1 < pages) button(53, icon(Material.ARROW, "<yellow>Next page", List.of()),
                (p, c) -> new EmployeeListMenu(plugin, owner, only, pg + 1, managing).open(p));
    }
}
