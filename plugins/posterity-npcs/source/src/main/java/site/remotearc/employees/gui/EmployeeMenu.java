package site.remotearc.employees.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.Employee;
import site.remotearc.employees.board.EventLog;
import site.remotearc.employees.build.BuildOrder;
import site.remotearc.employees.job.JobType;
import site.remotearc.employees.storage.StorageTarget;
import site.remotearc.employees.storage.Supply;
import site.remotearc.employees.util.Msg;

import java.util.ArrayList;
import java.util.List;

/**
 * One employee's full control panel ("More settings" on its card): everything
 * the commands can do, as buttons. The card (EmployeeCard) has the everyday ones.
 */
public final class EmployeeMenu extends Menu {

    private final Employee e;
    private final Menu back;

    public EmployeeMenu(ArcEmployees plugin, Employee e, Menu back) {
        super(plugin, 1, Component.empty());   // the chest inventory is never shown; see open()
        this.e = e;
        this.back = back;   // null: back to its card
    }

    @Override
    protected void draw() {}

    @Override
    public void open(Player p) {
        if (!plugin.manager().all().contains(e)) {
            Msg.error(p, e.name + " doesn't work for you any more.");
            return;
        }
        List<String> lines = new ArrayList<>();
        lines.add("<gray>Skin: <white>" + Describe.esc(e.skin.describe()) + (e.isLoaded() ? "" : " <dark_gray>(not loaded right now)"));
        List<String> recent = new ArrayList<>();
        for (EventLog.Entry en : plugin.manager().events().recent(e.owner, 30)) {
            if (!en.employee().equals(e.name)) continue;
            recent.add((en.problem() ? "<gold>" : "<gray>") + EventLog.age(en.time()) + " ago: " + Describe.esc(cut(en.text(), 60)));
            if (recent.size() >= 5) break;
        }
        if (!recent.isEmpty()) lines.add(String.join("\n", recent));

        List<Dialogs.Button> b = new ArrayList<>();

        StorageTarget st = e.storage;
        b.add(Dialogs.button("<aqua>Storage", "<gray>Delivers to " + Describe.esc(st.describe())
                + (e.overflow.isEmpty() ? "" : "\n<gray>Overflow: <white>/chest " + e.overflow.stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(", "))), pl -> new StorageMenu(plugin, e, this, 0).open(pl)));
        b.add(Dialogs.button("<aqua>Backpack", "<gray>What " + e.name + " is carrying. Seeds, saplings, fuel,\n<gray>feed and"
                + " building blocks are used before storage.", pl -> openThenReturn(pl, () -> pl.openInventory(e.backpack))));

        boolean builder = e.job == JobType.BUILDER;
        if (builder) {
            b.add(Dialogs.button("<aqua>Build site", e.build == null ? "<gray>None yet: it's wherever you place a blueprint."
                    : "<gray>Where you placed " + Describe.esc(e.build.blueprint) + ": " + e.area.describe(),
                    pl -> { if (e.area != null) AreaPreview.show(plugin, pl, e.area, 400); }));
            if (e.build != null) b.add(Dialogs.button("<aqua>Move the build to me", null, pl -> run(pl, "build " + ref(pl, e) + " move")));
        } else {
            b.add(Dialogs.button("<aqua>Work area", e.area == null ? "<gray>No work area set." : "<gray>" + e.area.describe()
                    + ", y " + e.area.minY() + " to " + e.area.maxY(), pl -> new AreaMenu(plugin, e, this).open(pl)));
        }

        jobButtons(b);

        boolean away = e.worksAway;
        b.add(Dialogs.button(away ? "<yellow>Works while nobody's on" : "<aqua>Rests while nobody's on",
                away ? "<gray>Keeps working when the server is empty, and is fed every day."
                        : "<gray>Stops when the last player leaves, and isn't fed for days nobody comes on.",
                pl -> { run(pl, "away " + ref(pl, e) + " " + (away ? "rest" : "work")); open(pl); }));

        int zoneCount = e.zones.size() + e.zonePresets.size();
        boolean guard = e.job == JobType.BODYGUARD;
        b.add(Dialogs.button("<aqua>" + (guard ? "No-fight" : "No-work") + " zones: " + zoneCount,
                guard ? "<gray>Places where " + e.name + " leaves mobs alone (a golem farm's zombie)."
                        : "<gray>Places " + e.name + " leaves alone (no mining, building, harvesting).",
                pl -> new ZonesMenu(plugin, e, this).open(pl)));

        if (e.job.usesSupply()) {
            b.add(Dialogs.button("<aqua>Takes materials from", Describe.esc(Supply.describe(plugin, e))
                    + "\n<gray>Team storage: <white>" + (e.teamSupply ? "on" : "off"), pl -> new SupplyMenu(plugin, e, this).open(pl)));
        }

        b.add(Dialogs.button("<aqua>Skin", "<gray>Now: <white>" + Describe.esc(e.skin.describe()), pl -> new SkinMenu(plugin, e, this).open(pl)));
        b.add(Dialogs.button("<aqua>Rename", null, pl -> {
            String who = ref(pl, e);
            Prompt.ask(pl, "New name for " + e.name + "?", name -> run(pl, "rename " + who + " " + name));
        }));

        if (e.paused) {
            b.add(Dialogs.button("<green>Resume work", "<gray>Back to work.", pl -> { plugin.manager().setPaused(e, false); open(pl); }));
        } else {
            b.add(Dialogs.button("<yellow>Pause work", "<gray>Stops work (and eating) until you resume.",
                    pl -> { plugin.manager().setPaused(e, true); open(pl); }));
        }

        b.add(Dialogs.button("<aqua>Food and pantry", Describe.foodLine(plugin, e), pl -> EmployeeCard.pantry(plugin, pl, e)));
        b.add(Dialogs.button("<aqua>Bring " + e.name + " here", builder ? "<gray>Moves them to you (the build stays put)."
                : "<gray>Moves them and their work area to you, keeping its shape.", pl ->
                new ConfirmMenu(plugin, "Bring " + e.name + " here?", "<green>Yes, move " + e.name + (builder ? "" : " and the area"), x -> {
            run(x, "move " + ref(x, e));
        }, this::open).open(pl)));

        String selling = e.sellAll ? "everything" : e.sellRules.isEmpty() ? "nothing" : e.sellRules.size() + " kinds of item";
        double[] today = e.soldToday();
        b.add(Dialogs.button("<aqua>Selling: <white>" + selling, "<gray>Last 24h: <white>" + (long) today[0] + "</white> items for <green>"
                + Msg.money(today[1]) + "\n<gray>All time: <white>" + e.stats.itemsSold + "</white> items for <green>" + Msg.money(e.stats.earned),
                pl -> new SellRulesMenu(plugin, e, this).open(pl)));

        if (guard) {
            List<String> armorHover = new ArrayList<>();
            if (plugin.manager().takesDamage(e)) {
                double hp = e.isLoaded() ? e.entity.getHealth() : 20;
                armorHover.add("<gray>Health: <white>" + (int) Math.ceil(hp) + "/20");
            } else armorHover.add("<gray>Can't be hurt (jobs.guard.take-damage is off).");
            armorHover.add("<gray>Put armour, a bow and arrows in its backpack.");
            b.add(Dialogs.button("<aqua>Take off armour", String.join("\n", armorHover), pl -> {
                for (int i = 0; i < 4; i++) {
                    if (e.armor[i] == null) continue;
                    var left = pl.getInventory().addItem(e.armor[i]);
                    for (var s : left.values()) pl.getWorld().dropItem(pl.getLocation(), s);
                    e.armor[i] = null;
                }
                if (e.isLoaded()) e.entity.getEquipment().setArmorContents(new org.bukkit.inventory.ItemStack[4]);
                plugin.manager().markDirty();
                open(pl);
            }));
        }

        if (plugin.settings().homesEnabled) {
            b.add(Dialogs.button("<aqua>Home", site.remotearc.employees.HomeSites.describe(plugin, e), pl -> new HomeMenu(plugin, e, this, 0).open(pl)));
        }

        b.add(Dialogs.button("<aqua>Board for " + e.name, "<gray>Everything about " + e.name + " on a big wall board.",
                pl -> run(pl, "board employee " + e.name)));

        boolean hasAvatar = plugin.avatars().has(e.id);
        b.add(Dialogs.button("<aqua>Place avatar" + (hasAvatar ? " <gray>(placed)" : ""), "<gray>A life-size stand-in for your control room,"
                + " showing their live status.", pl -> run(pl, "avatar " + ref(pl, e))));
        if (hasAvatar) b.add(Dialogs.button("<aqua>Remove avatar", null, pl -> run(pl, "avatar " + ref(pl, e) + " remove")));

        b.add(Dialogs.button("<red>Fire " + e.name, "<gray>Their backpack is handed to you. The tools and food\n<gray>they were hired with stay theirs.",
                pl -> new ConfirmMenu(plugin, "Fire " + e.name + "?", "<red>Yes, fire " + e.name, x -> {
            plugin.manager().fire(e, x);
            Msg.send(x, "<gray>You fired " + e.name + ".");
        }, this::open).open(pl)));

        Dialogs.Button backBtn = Dialogs.back(pl -> {
            if (back != null) back.open(pl);
            else EmployeeCard.show(plugin, pl, e);
        });
        Dialogs.show(plugin, p, "<gold>" + e.name + "</gold> <gray>· More settings", lines, b, 4, backBtn);
    }

    /** The job's own buttons: filter, build, herd size, trades, routes, or following. */
    private void jobButtons(List<Dialogs.Button> b) {
        switch (e.job) {
            case MINER, QUARRY, SMELTER -> {
                b.add(Dialogs.button("<aqua>" + Describe.filterLabel(e), "<white>" + Describe.esc(Describe.filterText(e, 6)),
                        pl -> new FilterMenu(plugin, e, this).open(pl)));
                if (e.job == JobType.MINER) {
                    b.add(Dialogs.button("<aqua>Roaming: " + (e.roam ? "<green>on" : "<red>off"), "<gray>When its area is mined out, it moves on"
                            + " to the next patch of ground, up to " + plugin.settings().minerRoamDistance + " blocks from where it started."
                            + (e.roamStep > 0 ? "\n<gray>Moved on " + e.roamStep + " time(s) so far." : ""), pl -> {
                        run(pl, "roam " + ref(pl, e) + " " + (e.roam ? "off" : "on"));
                        open(pl);
                    }));
                    if (e.roamStep > 0) b.add(Dialogs.button("<aqua>Call back to its first area", null, pl -> {
                        run(pl, "roam " + ref(pl, e) + " back");
                        open(pl);
                    }));
                }
            }
            case BUILDER -> {
                BuildOrder o = e.build;
                String hover;
                if (o == null) hover = "<gray>Nothing to build yet.";
                else {
                    String detail = e.worker == null ? null : e.worker.detail();
                    hover = "<gray>Blueprint: <white>" + Describe.esc(o.blueprint) + (detail == null ? "" : "\n<gray>" + Describe.esc(detail));
                }
                b.add(Dialogs.button("<aqua>Build", hover, pl -> new BuildMenu(plugin, e, this).open(pl)));
            }
            case RANCHER -> {
                int herd = e.herdSize > 0 ? e.herdSize : plugin.settings().herdSize;
                b.add(Dialogs.button("<aqua>Herd size: " + herd, "<gray>Keeps " + herd + " of each kind of animal: breeds up to"
                        + " it, culls the oldest above it.", pl -> {
                    run(pl, "herd " + ref(pl, e) + " " + Math.min(200, herd + 1));
                    open(pl);
                }));
                b.add(Dialogs.button("<aqua>Herd size -1", null, pl -> {
                    run(pl, "herd " + ref(pl, e) + " " + Math.max(2, herd - 1));
                    open(pl);
                }));
            }
            case TRADER -> b.add(Dialogs.button("<aqua>Trades: " + e.trades.size() + " picked", "<gray>Pick villagers' offers for "
                    + e.name + " to make whenever they have stock.", pl -> new TradesMenu(plugin, e, this).open(pl)));
            case COURIER -> {
                StringBuilder hover = new StringBuilder();
                if (e.routes.isEmpty()) hover.append("<gray>No routes yet.");
                for (int i = 0; i < e.routes.size() && i < 6; i++) {
                    if (i > 0) hover.append('\n');
                    hover.append("<white>").append(Describe.esc(e.routes.get(i).describe()));
                }
                b.add(Dialogs.button("<aqua>Routes: " + e.routes.size(), hover.toString(), pl -> new RoutesMenu(plugin, e, this).open(pl)));
            }
            case BODYGUARD -> b.add(Dialogs.button(e.following ? "<aqua>Following you" : "<aqua>Staying at its post",
                    e.following ? "<gray>Goes where you go and fights mobs within " + (int) plugin.settings().bodyguardRadius + " blocks of you."
                            : "<gray>Guards its post like a guard (its work area) until called.", pl -> {
                run(pl, (e.following ? "stay " : "follow ") + ref(pl, e));
                open(pl);
            }));
            default -> { }
        }
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
