package site.remotearc.employees.board;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.Employee;
import site.remotearc.employees.job.JobType;
import site.remotearc.employees.job.Worker;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Control boards: big wall-mounted text displays listing an owner's
 * employees by job with their live status, plus the latest problems and
 * events. Refreshed every two seconds while their chunk is loaded. The
 * display entities are never saved with the world; they are respawned
 * from boards.yml.
 */
public final class ControlBoards {

    /**
     * width: line width in text pixels (bigger boards fit more per line);
     * wall: flat against a wall (fixed facing), otherwise free-standing and
     * turning to face whoever looks at it, so it reads from every side.
     */
    /** kind: status, sales, employee (one employee, given by {@code employee}) or business. */
    /** top: y is the board's top edge and it grows downward (older boards: y is the bottom). */
    public record Board(UUID id, UUID owner, String world, double x, double y, double z, float yaw, float scale, int width, boolean wall,
                        String kind, UUID employee, boolean top) {
        /** Size as shown to players: 1-20, where 2 is a comfortable wall board. */
        public double size() {
            return Math.round(scale * 4 * 10) / 10.0;
        }

        public int letters() {
            return width / 6;
        }

        public Board resized(double size, int letters) {
            return new Board(id, owner, world, x, y, z, yaw, (float) (size / 4), letters * 6, wall, kind, employee, top);
        }

        public Board movedTo(Location at, float yaw, boolean wall) {
            return new Board(id, owner, at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), yaw, scale, width, wall, kind, employee, true);
        }

        Location location() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x, y, z, yaw, 0);
        }
    }

    private final ArcEmployees plugin;
    private final File file;
    private final NamespacedKey key;
    private final Map<UUID, Board> boards = new LinkedHashMap<>();
    private final Map<UUID, TextDisplay> shown = new java.util.HashMap<>();
    private BukkitTask task;

    public ControlBoards(ArcEmployees plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "boards.yml");
        this.key = new NamespacedKey(plugin, "board");
    }

    public void start() {
        load();
        for (World w : Bukkit.getWorlds()) {
            for (TextDisplay t : w.getEntitiesByClass(TextDisplay.class)) removeIfStray(t);
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 40L, 40L);
    }

    public void stop() {
        if (task != null) task.cancel();
        for (TextDisplay t : shown.values()) if (t.isValid()) t.remove();
        shown.clear();
    }

    public void removeIfStray(Entity e) {
        if (!(e instanceof TextDisplay)) return;
        String id = e.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (id == null) return;
        TextDisplay mine = shown.get(UUID.fromString(id));
        if (mine == null || !mine.equals(e)) e.remove();
    }

    /** Puts up a board for an owner at a spot, facing the given way. */
    public Board create(UUID owner, Location at, float yaw, float scale, int width, boolean wall, String kind, UUID employee) {
        Board b = new Board(UUID.randomUUID(), owner, at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), yaw, scale, width, wall, kind, employee, true);
        boards.put(b.id(), b);
        save();
        refresh();
        return b;
    }

    /** The owner's board nearest to a spot in the same world (within range), or null. */
    public Board nearest(UUID owner, Location at, double range) {
        Board best = null;
        double bestD = range * range;
        for (Board b : boards.values()) {
            if (!b.owner().equals(owner)) continue;
            Location l = b.location();
            if (l == null || !l.getWorld().equals(at.getWorld())) continue;
            double d = l.distanceSquared(at);
            if (d <= bestD) {
                bestD = d;
                best = b;
            }
        }
        return best;
    }

    /** Replaces a board with a changed copy (moved or resized) and redraws it. */
    public void replace(Board old, Board now) {
        boards.remove(old.id());
        TextDisplay t = shown.remove(old.id());
        if (t != null && t.isValid()) t.remove();
        boards.put(now.id(), now);
        save();
        refresh();
    }

    /** Removes the owner's board nearest to a spot (within range); returns whether one was removed. */
    public boolean removeNear(UUID owner, Location at, double range, boolean any) {
        Board best = null;
        double bestD = range * range;
        for (Board b : boards.values()) {
            if (!any && !b.owner().equals(owner)) continue;
            Location l = b.location();
            if (l == null || !l.getWorld().equals(at.getWorld())) continue;
            double d = l.distanceSquared(at);
            if (d <= bestD) {
                bestD = d;
                best = b;
            }
        }
        if (best == null) return false;
        boards.remove(best.id());
        TextDisplay t = shown.remove(best.id());
        if (t != null && t.isValid()) t.remove();
        save();
        return true;
    }

    public int count(UUID owner) {
        int n = 0;
        for (Board b : boards.values()) if (b.owner().equals(owner)) n++;
        return n;
    }

    // ── drawing ───────────────────────────────────────────────────────────

    private void refresh() {
        for (Board b : boards.values()) {
            try {
                Location l = b.location();
                if (l == null || !l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) continue;
                TextDisplay t = shown.get(b.id());
                if (t == null || !t.isValid()) {
                    t = spawn(b, l);
                    shown.put(b.id(), t);
                }
                int chars = b.width() / 6;
                Component text = switch (b.kind()) {
                    case "sales" -> renderSales(b.owner(), chars);
                    case "business" -> renderBusiness(b.owner(), chars);
                    case "employee" -> renderEmployee(b.employee(), chars);
                    default -> render(b.owner(), chars);
                };
                t.text(text);
                if (b.top()) hangFromTop(t, b, text);
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.WARNING, "Could not draw a control board", ex);
            }
        }
    }

    private final Map<UUID, Float> drop = new java.util.HashMap<>();

    /**
     * Text displays grow upward from where they stand, so a board hung from its
     * top edge is shifted down by its own height (estimated from its lines),
     * and re-shifted as it gets longer or shorter.
     */
    private void hangFromTop(TextDisplay t, Board b, Component text) {
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(text);
        int lines = 0;
        for (String line : plain.split("\n", -1)) lines += Math.max(1, (int) Math.ceil(line.length() * 6.0 / b.width()));
        float h = (lines * 10 + 2) / 40f * b.scale();
        Float was = drop.get(b.id());
        if (was != null && Math.abs(was - h) < 0.01) return;
        drop.put(b.id(), h);
        t.setTransformation(new Transformation(new Vector3f(0, -h, 0), new AxisAngle4f(),
                new Vector3f(b.scale(), b.scale(), b.scale()), new AxisAngle4f()));
    }

    private TextDisplay spawn(Board b, Location l) {
        drop.remove(b.id());
        return l.getWorld().spawn(l, TextDisplay.class, t -> {
            t.setPersistent(false);
            t.setBillboard(b.wall() ? Display.Billboard.FIXED : Display.Billboard.VERTICAL);
            t.setAlignment(TextDisplay.TextAlignment.LEFT);
            t.setLineWidth(b.width());
            t.setShadowed(true);
            t.setBackgroundColor(Color.fromARGB(200, 12, 14, 20));
            t.setRotation(b.yaw(), 0);
            t.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                    new Vector3f(b.scale(), b.scale(), b.scale()), new AxisAngle4f()));
            t.setViewRange(1.5f);
            t.getPersistentDataContainer().set(key, PersistentDataType.STRING, b.id().toString());
        });
    }

    /** chars: about how many characters fit on one line of this board. */
    private Component render(UUID owner, int chars) {
        List<Employee> mine = plugin.manager().ownedBy(owner);
        int working = 0;
        List<String> attention = new ArrayList<>();
        for (Employee e : mine) {
            Worker.State s = e.worker == null ? null : e.worker.state();
            if (s == Worker.State.WORKING) working++;
            if (s == Worker.State.FULL || s == Worker.State.STRIKE || EventLog.isProblem(e.status)) attention.add(e.name);
        }
        int problems = attention.size();
        String ownerName = Bukkit.getOfflinePlayer(owner).getName();
        TextComponent.Builder out = Component.text();
        out.append(Component.text("⚒ " + (ownerName == null ? "Your" : ownerName + "'s") + " employees", NamedTextColor.GOLD, TextDecoration.BOLD));
        out.append(Component.text("   " + working + " working · " + (mine.size() - working) + " idle", NamedTextColor.GRAY));
        if (plugin.market() != null && plugin.market().hasMerchants()) {
            double cap = plugin.market().limit(owner);
            out.append(Component.text("   Sales today " + site.remotearc.employees.util.Msg.money(plugin.market().earnedToday(owner))
                    + (cap > 0 ? " of " + site.remotearc.employees.util.Msg.money(cap) : ""), NamedTextColor.GREEN));
        }
        if (problems > 0) out.append(Component.text(" · needs attention: " + String.join(", ", attention), NamedTextColor.RED));
        if (mine.isEmpty()) out.append(Component.newline()).append(Component.text("Nobody hired yet: /employees", NamedTextColor.GRAY));
        for (JobType job : JobType.values()) {
            List<Employee> list = new ArrayList<>();
            for (Employee e : mine) if (e.job == job) list.add(e);
            if (list.isEmpty()) continue;
            out.append(Component.newline()).append(Component.text(job.display() + "s", NamedTextColor.AQUA, TextDecoration.UNDERLINED));
            for (Employee e : list) {
                Worker.State s = e.worker == null ? null : e.worker.state();
                NamedTextColor dot = s == null ? NamedTextColor.DARK_GRAY : switch (s) {
                    case WORKING -> NamedTextColor.GREEN;
                    case IDLE -> NamedTextColor.GRAY;
                    case FULL -> NamedTextColor.YELLOW;
                    case PAUSED, RESTING -> NamedTextColor.DARK_GRAY;
                    case STRIKE -> NamedTextColor.RED;
                };
                boolean problem = EventLog.isProblem(e.status) || s == Worker.State.FULL || s == Worker.State.STRIKE;
                out.append(Component.newline())
                        .append(Component.text(" ● ", dot))
                        .append(Component.text(e.name, NamedTextColor.WHITE))
                        .append(Component.text("  " + cut(e.status, chars - e.name.length() - 5), problem ? NamedTextColor.GOLD : NamedTextColor.GRAY));
                double[] sold = e.soldToday();
                if (sold[1] > 0) out.append(Component.text("  · sold " + (long) sold[0] + " for "
                        + site.remotearc.employees.util.Msg.money(sold[1]) + " today", NamedTextColor.GREEN));
                String detail = e.worker == null ? null : e.worker.detail();
                if (detail != null && (job == JobType.BUILDER || job == JobType.QUARRY)) {
                    out.append(Component.newline()).append(Component.text("     " + cut(detail, chars - 6), NamedTextColor.DARK_GRAY));
                }
            }
        }
        List<EventLog.Entry> recent = plugin.manager().events().recent(owner, 8);
        if (!recent.isEmpty()) {
            out.append(Component.newline()).append(Component.text("Recent", NamedTextColor.AQUA, TextDecoration.UNDERLINED));
            for (EventLog.Entry en : recent) {
                out.append(Component.newline())
                        .append(Component.text(" " + EventLog.age(en.time()) + " ", NamedTextColor.DARK_GRAY))
                        .append(Component.text(en.employee() + ": ", NamedTextColor.WHITE))
                        .append(Component.text(cut(en.text(), chars - en.employee().length() - 8), en.problem() ? NamedTextColor.GOLD : NamedTextColor.GRAY));
            }
        }
        return out.build();
    }

    /** The sales board: today's money against the limit, each seller's day, and the latest sales. */
    private Component renderSales(UUID owner, int chars) {
        var market = plugin.market();
        String ownerName = Bukkit.getOfflinePlayer(owner).getName();
        TextComponent.Builder out = Component.text();
        out.append(Component.text("$ " + (ownerName == null ? "Your" : ownerName + "'s") + " sales", NamedTextColor.GOLD, TextDecoration.BOLD));
        double today = market.earnedToday(owner), cap = plugin.settings().marketDailyCap;
        out.append(Component.text("   Today " + site.remotearc.employees.util.Msg.money(today)
                + (cap > 0 ? " of " + site.remotearc.employees.util.Msg.money(cap) + " limit" : ""), NamedTextColor.GREEN));
        if (!market.hasMerchants()) out.append(Component.newline()).append(Component.text("No merchant yet: /employees market place", NamedTextColor.GRAY));
        List<Employee> sellers = new ArrayList<>();
        for (Employee e : plugin.manager().ownedBy(owner)) if (e.selling() || !e.sales.isEmpty()) sellers.add(e);
        sellers.sort((a, b) -> Double.compare(b.soldToday()[1], a.soldToday()[1]));
        if (!sellers.isEmpty()) out.append(Component.newline()).append(Component.text("Employees (last 24 hours)", NamedTextColor.AQUA, TextDecoration.UNDERLINED));
        for (Employee e : sellers) {
            double[] d = e.soldToday();
            out.append(Component.newline())
                    .append(Component.text(" " + e.name, NamedTextColor.WHITE))
                    .append(Component.text(" (" + e.job.display() + ")  ", NamedTextColor.DARK_GRAY))
                    .append(Component.text((long) d[0] + " items  " + site.remotearc.employees.util.Msg.money(d[1]), NamedTextColor.GREEN))
                    .append(Component.text("   all time " + site.remotearc.employees.util.Msg.money(e.stats.earned), NamedTextColor.GRAY));
            if (!e.selling()) out.append(Component.text("  (not selling now)", NamedTextColor.DARK_GRAY));
        }
        List<Object[]> recent = new ArrayList<>();
        for (Employee e : sellers) for (Employee.SaleRecord r : e.sales) recent.add(new Object[]{r, e.name});
        recent.sort((a, b) -> Long.compare(((Employee.SaleRecord) b[0]).time(), ((Employee.SaleRecord) a[0]).time()));
        if (!recent.isEmpty()) out.append(Component.newline()).append(Component.text("Latest sales", NamedTextColor.AQUA, TextDecoration.UNDERLINED));
        for (int i = 0; i < Math.min(10, recent.size()); i++) {
            Employee.SaleRecord r = (Employee.SaleRecord) recent.get(i)[0];
            String who = (String) recent.get(i)[1];
            out.append(Component.newline())
                    .append(Component.text(" " + EventLog.age(r.time()) + " ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(who + ": ", NamedTextColor.WHITE))
                    .append(Component.text(cut(r.what(), chars - who.length() - 18), NamedTextColor.GRAY))
                    .append(Component.text(" " + site.remotearc.employees.util.Msg.money(r.money()), NamedTextColor.GREEN));
        }
        if (market.real().enabled() && !market.real().quotes().isEmpty()) {
            out.append(Component.newline()).append(Component.text("Real-world markets", NamedTextColor.AQUA, TextDecoration.UNDERLINED));
            StringBuilder line = new StringBuilder(" ");
            for (RealPricesQuote q : quotes()) {
                String part = q.label + String.format(" %+.0f%%  ", q.change * 100);
                if (line.length() + part.length() > chars) {
                    out.append(Component.newline()).append(Component.text(line.toString(), NamedTextColor.GRAY));
                    line = new StringBuilder(" ");
                }
                line.append(part);
            }
            out.append(Component.newline()).append(Component.text(line.toString(), NamedTextColor.GRAY));
        }
        return out.build();
    }

    private static Component mini(String s) {
        return site.remotearc.employees.util.Msg.parse(s);
    }

    private static String money(double v) {
        return site.remotearc.employees.util.Msg.money(v);
    }

    /** One employee in detail: everything its menu says, its selling rules, its sales and its recent events. */
    private Component renderEmployee(UUID id, int chars) {
        Employee e = null;
        for (Employee x : plugin.manager().all()) if (x.id.equals(id)) e = x;
        if (e == null) return Component.text("This employee no longer works here. /employees board remove", NamedTextColor.GRAY);
        TextComponent.Builder out = Component.text();
        out.append(Component.text(e.name, NamedTextColor.GOLD, TextDecoration.BOLD))
                .append(Component.text("  " + e.job.display() + " · " + e.ownerName(), NamedTextColor.GRAY));
        for (String line : site.remotearc.employees.gui.Describe.lore(plugin, e)) {
            out.append(Component.newline()).append(mini(" " + line));
        }
        out.append(Component.newline()).append(Component.text("Selling", NamedTextColor.AQUA, TextDecoration.UNDERLINED));
        if (!e.selling() && e.neverSell.isEmpty()) out.append(Component.newline()).append(Component.text(" Not selling (keeps everything)", NamedTextColor.GRAY));
        if (e.sellAll) out.append(Component.newline()).append(Component.text(" Everything merchants buy: " + e.sellAllRule.describe(), NamedTextColor.WHITE));
        for (var r : e.sellRules.entrySet()) {
            out.append(Component.newline()).append(Component.text(" " + site.remotearc.employees.util.Items.name(r.getKey()) + ": " + r.getValue().describe()
                    + " (" + e.soldToday(r.getKey()) + " sold today)", NamedTextColor.WHITE));
        }
        if (!e.neverSell.isEmpty()) {
            out.append(Component.newline()).append(Component.text(" Never sells: " + cut(String.join(", ",
                    e.neverSell.stream().map(site.remotearc.employees.util.Items::name).toList()), chars - 14), NamedTextColor.GOLD));
        }
        double[] today = e.soldToday();
        out.append(Component.newline()).append(Component.text(" Last 24h: " + (long) today[0] + " items for " + money(today[1])
                + " · all time " + e.stats.itemsSold + " for " + money(e.stats.earned), NamedTextColor.GREEN));
        int n = 0;
        for (Employee.SaleRecord r : e.sales) {
            if (n++ >= 5) break;
            out.append(Component.newline()).append(Component.text("  " + EventLog.age(r.time()) + " ago: " + cut(r.what(), chars - 24) + " → "
                    + money(r.money()), NamedTextColor.GRAY));
        }
        List<EventLog.Entry> events = new ArrayList<>();
        for (EventLog.Entry en : plugin.manager().events().recent(e.owner, 30)) if (en.employee().equals(e.name)) events.add(en);
        if (!events.isEmpty()) out.append(Component.newline()).append(Component.text("Recent", NamedTextColor.AQUA, TextDecoration.UNDERLINED));
        for (int i = 0; i < Math.min(6, events.size()); i++) {
            EventLog.Entry en = events.get(i);
            out.append(Component.newline()).append(Component.text(" " + EventLog.age(en.time()) + " " + cut(en.text(), chars - 8),
                    en.problem() ? NamedTextColor.GOLD : NamedTextColor.GRAY));
        }
        return out.build();
    }

    /** The business at a glance: what each employee costs a day against what it brings in, the totals, and the market. */
    private Component renderBusiness(UUID owner, int chars) {
        var market = plugin.market();
        String ownerName = Bukkit.getOfflinePlayer(owner).getName();
        TextComponent.Builder out = Component.text();
        out.append(Component.text("$ " + (ownerName == null ? "Your" : ownerName + "'s") + " business", NamedTextColor.GOLD, TextDecoration.BOLD));
        var econ = plugin.economy();
        if (econ != null) out.append(Component.text("   Balance " + money(econ.getBalance(Bukkit.getOfflinePlayer(owner))), NamedTextColor.WHITE));
        double perDay = 24.0 / (plugin.settings().payIntervalMillis / 3_600_000.0);
        double wages = 0, earned = 0;
        out.append(Component.newline()).append(Component.text("Last 24h: goods stored · sales · taken from storage · wages = profit", NamedTextColor.AQUA, TextDecoration.UNDERLINED));
        List<Employee> mine = new ArrayList<>(plugin.manager().ownedBy(owner));
        Map<UUID, site.remotearc.employees.Ledger.Bucket> books = new java.util.HashMap<>();
        for (Employee e : mine) books.put(e.id, plugin.ledger().last(e.id, 86_400_000L));
        mine.sort((a, b) -> Double.compare(books.get(b.id).profit(), books.get(a.id).profit()));
        double profit = 0;
        for (Employee e : mine) {
            var b = books.get(e.id);
            wages += e.paused ? 0 : plugin.settings().job(e.job).wage() * perDay;
            earned += e.soldToday()[1];
            double net = b.profit();
            profit += net;
            boolean expected = site.remotearc.employees.Reports.worthNotInGoods(e.job);
            out.append(Component.newline())
                    .append(Component.text(" " + e.name, NamedTextColor.WHITE))
                    .append(Component.text(" (" + e.job.display() + ")", NamedTextColor.DARK_GRAY))
                    .append(Component.text("  " + money(b.stored), NamedTextColor.WHITE))
                    .append(Component.text("  +" + money(b.sales), NamedTextColor.GREEN))
                    .append(Component.text(b.taken > 0 ? "  -" + money(b.taken) : "", NamedTextColor.GOLD))
                    .append(Component.text("  -" + money(b.wages), NamedTextColor.RED))
                    .append(Component.text("  = " + (net >= 0 ? "+" : "-") + money(Math.abs(net)),
                            net >= 0 ? NamedTextColor.GREEN : expected ? NamedTextColor.GRAY : NamedTextColor.RED))
                    .append(Component.text(e.paused ? "  (paused)" : e.job == JobType.COURIER && b.sales > 0 ? "  (sold others' goods)"
                            : net < 0 && !expected ? "  costs more than it brings in" : "",
                            net < 0 && !expected && !e.paused ? NamedTextColor.RED : NamedTextColor.DARK_GRAY));
        }
        double you = Math.max(0, market.earnedToday(owner) - earned);
        out.append(Component.newline()).append(Component.text("Totals", NamedTextColor.AQUA, TextDecoration.UNDERLINED));
        out.append(Component.newline()).append(Component.text(" Wages " + money(wages) + "/day · employee sales " + money(earned)
                + " · your own sales " + money(you), NamedTextColor.WHITE));
        double net = profit + you;
        out.append(Component.newline()).append(Component.text(" Profit " + (net >= 0 ? "+" : "-") + money(Math.abs(net)) + " in the last 24h"
                + (market.limit(owner) > 0 ? " · selling limit " + money(market.earnedToday(owner)) + " of "
                + money(market.limit(owner)) : ""), net >= 0 ? NamedTextColor.GREEN : NamedTextColor.GOLD));
        List<Map.Entry<Material, double[]>> top = new ArrayList<>(market.revenueToday(owner).entrySet());
        top.sort((a, b) -> Double.compare(b.getValue()[1], a.getValue()[1]));
        if (!top.isEmpty()) out.append(Component.newline()).append(Component.text("Top sellers today", NamedTextColor.AQUA, TextDecoration.UNDERLINED));
        for (int i = 0; i < Math.min(5, top.size()); i++) {
            var t = top.get(i);
            out.append(Component.newline()).append(Component.text(" " + site.remotearc.employees.util.Items.name(t.getKey()) + ": "
                    + (long) t.getValue()[0] + " sold for " + money(t.getValue()[1]), NamedTextColor.WHITE));
        }
        List<Map.Entry<Material, Double>> best = new ArrayList<>();
        for (Material m : market.catalog().keySet()) best.add(Map.entry(m, market.priceNow(owner, m)));
        best.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        if (!best.isEmpty()) out.append(Component.newline()).append(Component.text("Best prices right now (each)", NamedTextColor.AQUA, TextDecoration.UNDERLINED));
        StringBuilder line = new StringBuilder(" ");
        for (int i = 0; i < Math.min(8, best.size()); i++) {
            String part = site.remotearc.employees.util.Items.name(best.get(i).getKey()) + " " + money(best.get(i).getValue()) + "   ";
            if (line.length() + part.length() > chars) {
                out.append(Component.newline()).append(Component.text(line.toString(), NamedTextColor.GRAY));
                line = new StringBuilder(" ");
            }
            line.append(part);
        }
        out.append(Component.newline()).append(Component.text(line.toString(), NamedTextColor.GRAY));
        return out.build();
    }

    private record RealPricesQuote(String label, double change) {}

    private List<RealPricesQuote> quotes() {
        List<RealPricesQuote> out = new ArrayList<>();
        for (var q : plugin.market().real().quotes().values()) out.add(new RealPricesQuote(q.label(), q.change()));
        out.sort((a, b) -> a.label.compareTo(b.label));
        return out;
    }

    private static String cut(String s, int max) {
        max = Math.max(12, max);
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // ── storage ───────────────────────────────────────────────────────────

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection all = y.getConfigurationSection("boards");
        if (all == null) return;
        for (String k : all.getKeys(false)) {
            ConfigurationSection s = all.getConfigurationSection(k);
            try {
                Board b = new Board(UUID.fromString(k), UUID.fromString(s.getString("owner")), s.getString("world"),
                        s.getDouble("x"), s.getDouble("y"), s.getDouble("z"), (float) s.getDouble("yaw"), (float) s.getDouble("scale", 0.5),
                        s.getInt("width", 600), s.getBoolean("wall", true),
                        s.getString("kind", s.getBoolean("sales", false) ? "sales" : "status"),
                        s.getString("employee") == null ? null : UUID.fromString(s.getString("employee")), s.getBoolean("top", false));
                boards.put(b.id(), b);
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("Skipping a broken control board entry " + k);
            }
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Board b : boards.values()) {
            ConfigurationSection s = y.createSection("boards." + b.id());
            s.set("owner", b.owner().toString());
            s.set("world", b.world());
            s.set("x", b.x());
            s.set("y", b.y());
            s.set("z", b.z());
            s.set("yaw", b.yaw());
            s.set("scale", b.scale());
            s.set("width", b.width());
            s.set("wall", b.wall());
            s.set("kind", b.kind());
            s.set("top", b.top());
            if (b.employee() != null) s.set("employee", b.employee().toString());
        }
        try {
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not save boards.yml", ex);
        }
    }
}
