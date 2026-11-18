package site.remotearc.employees.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
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
        super(plugin, 6, Component.text(e.name + " the " + e.job.display()));
        this.e = e;
        this.back = back;   // null: back to its card
    }

    @Override
    protected void draw() {
        if (!plugin.manager().all().contains(e)) return;   // fired while open
        for (int s = 0; s < 54; s++) button(s, filler(), null);

        List<String> info = new ArrayList<>(Describe.lore(plugin, e));
        info.addFirst("<gray>Skin: <white>" + Describe.esc(e.skin.describe()));
        if (!e.isLoaded()) info.add("<dark_gray>(not loaded: nobody is near and the site isn't kept loaded)");
        button(4, head(e, "<gold>" + e.name + " <gray>(" + e.job.display() + ")", info), null);

        // Row 2: where things go and where it works.
        StorageTarget st = e.storage;
        List<String> storeLore = new ArrayList<>();
        storeLore.add("<gray>Delivers to <white>" + Describe.esc(st.describe()));
        if (!e.overflow.isEmpty()) storeLore.add("<gray>Overflow: <white>" + "/chest " + e.overflow.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(", ")));
        storeLore.add("");
        if (st.kind() == StorageTarget.Kind.VIRTUAL) storeLore.add("<yellow>Left-click to open it");
        storeLore.add(st.kind() == StorageTarget.Kind.VIRTUAL ? "<yellow>Right-click to change" : "<yellow>Click to change");
        button(19, icon(st.kind() == StorageTarget.Kind.VIRTUAL ? Material.ENDER_CHEST : st.kind() == StorageTarget.Kind.CHEST
                ? Material.CHEST : Material.BARREL, "<aqua>Storage", storeLore), (p, c) -> {
            if (st.kind() == StorageTarget.Kind.VIRTUAL && !c.isRightClick()) {
                openThenReturn(p, () -> {
                    if (!plugin.vchests().open(p, e.ownerPlayer(), st.chestNumber())) Msg.error(p, "VirtualChests is not available right now.");
                });
            } else {
                new StorageMenu(plugin, e, this, 0).open(p);
            }
        });

        button(20, icon(Material.BUNDLE, "<aqua>Backpack", List.of(
                "<gray>What " + e.name + " is carrying. Seeds, saplings,",
                "<gray>fuel, feed and building blocks put here",
                "<gray>are used before anything from storage.",
                "", "<yellow>Click to open <gray>(close it to come back)")), (p, c) -> openThenReturn(p, () -> p.openInventory(e.backpack)));

        boolean builder = e.job == JobType.BUILDER;
        button(21, icon(Material.COMPASS, builder ? "<aqua>Build site" : "<aqua>Work area", builder ? List.of(
                e.build == null ? "<gray>None yet: it's wherever you place" : "<gray>Where you placed " + Describe.esc(e.build.blueprint) + ":",
                e.build == null ? "<gray>a blueprint (Build → Choose a blueprint)." : "<gray>" + e.area.describe() + ", y " + e.area.minY() + " to " + e.area.maxY(),
                "<gray>A builder needs no work area of its own.",
                "", e.area == null ? "" : "<yellow>Click to outline it", e.area == null ? "" : "<yellow>Shift-click to teleport there",
                e.build == null ? "" : "<yellow>Right-click to move the build to you") : List.of(
                e.area == null ? "<gray>No work area set." : "<gray>" + e.area.describe() + ", y " + e.area.minY() + " to " + e.area.maxY(),
                "", "<yellow>Click to change or show it",
                e.area == null ? "" : "<yellow>Shift-click to teleport there")),
                (p, c) -> {
                    if (c.isShiftClick() && e.area != null) {
                        p.closeInventory();
                        run(p, "area " + ref(p, e) + " tp");
                    } else if (e.job == JobType.BUILDER && c.isRightClick() && e.build != null) {
                        p.closeInventory();
                        run(p, "build " + ref(p, e) + " move");
                    } else if (e.job == JobType.BUILDER) {
                        if (e.area == null) return;
                        p.closeInventory();
                        AreaPreview.show(plugin, p, e.area, 400);
                    } else {
                        new AreaMenu(plugin, e, this).open(p);
                    }
                });

        jobButton();

        boolean away = e.worksAway;
        button(37, icon(away ? Material.CLOCK : Material.RED_BED, away ? "<yellow>Works while nobody's on" : "<aqua>Rests while nobody's on", List.of(
                away ? "<gray>Keeps working when the server is" : "<gray>Stops when the last player leaves",
                away ? "<gray>empty, and is paid every day." : "<gray>and isn't paid for days nobody",
                away ? "" : "<gray>comes on.",
                "", "<yellow>Click to " + (away ? "let them rest" : "keep them working"))), (p, c) -> {
            run(p, "away " + ref(p, e) + " " + (away ? "rest" : "work"));
            redraw();
        });

        int zoneCount = e.zones.size() + e.zonePresets.size();
        boolean guard = e.job == JobType.GUARD || e.job == JobType.BODYGUARD;
        button(28, icon(guard ? Material.SHIELD : Material.STRUCTURE_VOID, "<aqua>" + (guard ? "No-fight" : "No-work") + " zones: " + zoneCount, List.of(
                guard ? "<gray>Places where " + e.name + " leaves mobs" : "<gray>Places " + e.name + " leaves alone",
                guard ? "<gray>alone (a golem farm's zombie)." : "<gray>(no mining, building, harvesting there).",
                "<gray>From a WorldEdit selection or a preset.",
                "", "<yellow>Click to see or change")), (p, c) -> new ZonesMenu(plugin, e, this).open(p));

        if (e.job.usesSupply()) {
            button(23, icon(Material.CHEST_MINECART, "<aqua>Takes materials from", concat(wrap("<white>", Describe.esc(Supply.describe(plugin, e)), 40),
                    "<gray>Team storage: <white>" + (e.teamSupply ? "on" : "off"),
                    "", "<yellow>Click to change")), (p, c) -> new SupplyMenu(plugin, e, this).open(p));
        }

        button(24, icon(Material.LEATHER_CHESTPLATE, "<aqua>Skin", List.of(
                "<gray>Now: <white>" + Describe.esc(e.skin.describe()), "", "<yellow>Click to choose")), (p, c) -> new SkinMenu(plugin, e, this).open(p));

        button(25, icon(Material.NAME_TAG, "<aqua>Rename", List.of("", "<yellow>Click, then type the new name")), (p, c) -> {
            String who = ref(p, e);
            Prompt.ask(p, "New name for " + e.name + "?", name -> run(p, "rename " + who + " " + name));
        });

        // Row 4: day to day.
        if (e.paused) {
            button(29, icon(Material.LIME_DYE, "<green>Resume work", List.of("<gray>The rest of the paid period carries over.")), (p, c) -> {
                plugin.manager().setPaused(e, false);
                redraw();
            });
        } else {
            button(29, icon(Material.GRAY_DYE, "<gray>Pause work", List.of("<gray>Stops work and the wage clock", "<gray>until you resume.")), (p, c) -> {
                plugin.manager().setPaused(e, true);
                redraw();
            });
        }

        button(30, icon(e.food <= 0 ? Material.BOWL : Material.BREAD, "<aqua>Food and pantry", List.of(Describe.foodLine(plugin, e),
                "", "<yellow>Click to change the pantry")), (p, c) -> EmployeeCard.pantry(plugin, p, e));

        button(31, icon(Material.ENDER_PEARL, "<aqua>Go to " + e.name, List.of("", "<yellow>Click to teleport")), (p, c) -> {
            p.closeInventory();
            p.teleport(e.location.clone().add(0, 0.1, 0));
        });

        button(32, icon(Material.LEAD, "<aqua>Bring " + e.name + " here", List.of(
                e.job == JobType.BUILDER ? "<gray>Moves them to you (the build stays put)." : "<gray>Moves them and their work area to you,",
                e.job == JobType.BUILDER ? "" : "<gray>keeping its shape.", "", "<yellow>Click to move")), (p, c) ->
                new ConfirmMenu(plugin, "Bring " + e.name + " here?", "<green>Yes, move " + e.name + (e.job == JobType.BUILDER ? "" : " and the area"), pl -> {
                    pl.closeInventory();
                    run(pl, "move " + ref(pl, e));
                }, this::open).open(p));

        List<String> recent = new ArrayList<>();
        for (EventLog.Entry en : plugin.manager().events().recent(e.owner, 30)) {
            if (!en.employee().equals(e.name)) continue;
            recent.add((en.problem() ? "<gold>" : "<gray>") + EventLog.age(en.time()) + " ago: " + Describe.esc(cut(en.text(), 50)));
            if (recent.size() >= 8) break;
        }
        if (recent.isEmpty()) recent.add("<gray>Nothing to report.");
        button(33, icon(Material.WRITABLE_BOOK, "<aqua>Recent problems and events", recent), null);

        String selling = e.sellAll ? "everything" : e.sellRules.isEmpty() ? "nothing" : e.sellRules.size() + " kinds of item";
        double[] today = e.soldToday();
        List<String> sellLore = new ArrayList<>(List.of(
                "<gray>Last 24h: <white>" + (long) today[0] + "</white> items for <green>" + Msg.money(today[1]),
                "<gray>All time: <white>" + e.stats.itemsSold + "</white> items for <green>" + Msg.money(e.stats.earned)));
        int shown = 0;
        for (Employee.SaleRecord r : e.sales) {
            if (shown++ >= 4) break;
            sellLore.add("<dark_gray>" + EventLog.age(r.time()) + " ago: " + Describe.esc(r.what()) + " → " + Msg.money(r.money()));
        }
        sellLore.add("");
        sellLore.add("<yellow>Click to choose what it sells");
        button(34, icon(Material.EMERALD, "<aqua>Selling: <white>" + selling, sellLore), (p, c) -> new SellRulesMenu(plugin, e, this).open(p));

        if (e.job == JobType.GUARD || e.job == JobType.BODYGUARD) {
            List<String> lore = new ArrayList<>();
            if (plugin.manager().takesDamage(e)) {
                double hp = e.isLoaded() ? e.entity.getHealth() : 20;
                lore.add("<gray>Health: <red>" + "❤".repeat((int) Math.ceil(hp / 2)) + " <white>" + (int) Math.ceil(hp) + "/20");
                if (e.knockedOutUntil > System.currentTimeMillis()) lore.add("<yellow>Knocked out for "
                        + ((e.knockedOutUntil - System.currentTimeMillis()) / 1000 + 1) + "s more");
            } else {
                lore.add("<gray>Can't be hurt (jobs.guard.take-damage is off).");
            }
            String[] names = {"Boots", "Leggings", "Chestplate", "Helmet"};
            for (int i = 3; i >= 0; i--) {
                lore.add("<gray>" + names[i] + ": <white>" + (e.armor[i] == null ? "none" : site.remotearc.employees.util.Items.name(e.armor[i].getType())));
            }
            lore.add("");
            lore.add("<gray>Put armour, a bow and arrows in its");
            lore.add("<gray>backpack: it wears the best and uses");
            lore.add("<gray>the bow on mobs further off. Mobs can");
            lore.add("<gray>hurt it (creepers can't); knocked out,");
            lore.add("<gray>it's back up after " + plugin.settings().knockoutSeconds + "s.");
            lore.add("");
            lore.add("<yellow>Click to take its armour off");
            lore.add("<gray>(it goes into your inventory)");
            button(40, icon(e.armor[2] != null ? e.armor[2].getType() : Material.LEATHER_CHESTPLATE, "<aqua>Armour and health", lore), (p, c) -> {
                for (int i = 0; i < 4; i++) {
                    if (e.armor[i] == null) continue;
                    var left = p.getInventory().addItem(e.armor[i]);
                    for (var s : left.values()) p.getWorld().dropItem(p.getLocation(), s);
                    e.armor[i] = null;
                }
                if (e.isLoaded()) e.entity.getEquipment().setArmorContents(new org.bukkit.inventory.ItemStack[4]);
                plugin.manager().markDirty();
                redraw();
            });
        }


        if (plugin.settings().homesEnabled) {
            List<String> homeLore = new ArrayList<>();
            homeLore.add(site.remotearc.employees.HomeSites.describe(plugin, e));
            homeLore.add("");
            homeLore.add("<yellow>Click to pick a house from the catalog");
            button(42, icon(e.house != null ? Material.RED_BED : Material.WHITE_BED, "<aqua>Home", homeLore),
                    (p, c) -> new HomeMenu(plugin, e, this, 0).open(p));
        }

        button(44, icon(Material.OAK_SIGN, "<aqua>Board for " + e.name, List.of(
                "<gray>Everything about " + e.name + " on a big",
                "<gray>wall board: status, selling, sales, events.",
                "", "<yellow>Click to put it on the wall you look at")), (p, c) -> {
            p.closeInventory();
            run(p, "board employee " + e.name);
        });

        boolean hasAvatar = plugin.avatars().has(e.id);
        button(35, icon(Material.ARMOR_STAND, "<aqua>Avatar" + (hasAvatar ? " <gray>(placed)" : ""), List.of(
                "<gray>A life-size stand-in for your control",
                "<gray>room, showing their live status.",
                "<gray>Right-click it for this menu, shift-",
                "<gray>right-click to pause or resume.",
                "", "<yellow>Click to put it where you stand", hasAvatar ? "<yellow>Right-click to remove it" : "")), (p, c) -> {
            p.closeInventory();
            run(p, "avatar " + ref(p, e) + (c.isRightClick() ? " remove" : ""));
        });

        button(45, back(), (p, c) -> {
            if (back != null) back.open(p);
            else EmployeeCard.show(plugin, p, e);
        });
        button(49, icon(Material.BARRIER, "<red>Fire " + e.name, List.of(
                "<gray>Their backpack is handed to you.",
                "<gray>Wages already paid are not refunded.",
                "", "<yellow>Click to fire")), (p, c) -> new ConfirmMenu(plugin,
                "Fire " + e.name + "?", "<red>Yes, fire " + e.name, pl -> {
            plugin.manager().fire(e, pl);
            Msg.send(pl, "<gray>You fired " + e.name + ".");
            new EmployeeListMenu(plugin, e.owner, 0).open(pl);
        }, this::open).open(p));
    }

    /** The job's own button: filter, build, or herd size. */
    private void jobButton() {
        switch (e.job) {
            case MINER, QUARRY, SMELTER -> {
                button(22, icon(Material.HOPPER, "<aqua>" + Describe.filterLabel(e), List.of(
                        "<white>" + Describe.esc(Describe.filterText(e, 6)), "", "<yellow>Click to change")),
                        (p, c) -> new FilterMenu(plugin, e, this).open(p));
                if (e.job == JobType.MINER) {
                    ItemStack roam = icon(e.roam ? Material.FILLED_MAP : Material.MAP, "<aqua>Roaming: " + (e.roam ? "<green>on" : "<red>off"), List.of(
                            "<gray>When its area is mined out, it moves on",
                            "<gray>to the next patch of ground, spiralling",
                            "<gray>out up to " + plugin.settings().minerRoamDistance + " blocks from where it started.",
                            e.roamStep > 0 ? "<gray>Moved on " + e.roamStep + " time(s) so far." : "",
                            "", "<yellow>Click to switch", e.roamStep > 0 ? "<yellow>Right-click to call it back to its first area" : ""));
                    button(26, e.roam ? glow(roam) : roam, (p, c) -> {
                        run(p, "roam " + ref(p, e) + " " + (c.isRightClick() ? "back" : e.roam ? "off" : "on"));
                        redraw();
                    });
                }
            }
            case BUILDER -> {
                BuildOrder o = e.build;
                List<String> lore = new ArrayList<>();
                if (o == null) lore.add("<gray>Nothing to build yet.");
                else {
                    lore.add("<gray>Blueprint: <white>" + Describe.esc(o.blueprint));
                    String detail = e.worker == null ? null : e.worker.detail();
                    if (detail != null) lore.add("<gray>" + Describe.esc(detail));
                }
                lore.add("");
                lore.add("<yellow>Click for blueprints and build controls");
                button(22, icon(Material.BRICKS, "<aqua>Build", lore), (p, c) -> new BuildMenu(plugin, e, this).open(p));
            }
            case RANCHER -> {
                int herd = e.herdSize > 0 ? e.herdSize : plugin.settings().herdSize;
                button(22, icon(Material.WHEAT, "<aqua>Herd size: " + herd, List.of(
                        "<gray>Keeps " + herd + " of each kind of animal:",
                        "<gray>breeds up to it, culls the oldest above it.",
                        "", "<yellow>Left-click +1, right-click -1", "<yellow>Shift-click for ±5")), (p, c) -> {
                    int step = c.isShiftClick() ? 5 : 1;
                    int n = Math.max(2, Math.min(200, herd + (c.isRightClick() ? -step : step)));
                    run(p, "herd " + ref(p, e) + " " + n);
                    redraw();
                });
            }
            case TRADER -> button(22, icon(Material.EMERALD, "<aqua>Trades: " + e.trades.size() + " picked", List.of(
                    "<gray>Pick villagers' offers for " + e.name, "<gray>to make whenever they have stock.",
                    "", "<yellow>Click to choose")), (p, c) -> new TradesMenu(plugin, e, this).open(p));
            case COURIER -> {
                List<String> lore = new ArrayList<>();
                if (e.routes.isEmpty()) lore.add("<gray>No routes yet.");
                for (int i = 0; i < e.routes.size() && i < 6; i++) lore.add("<white>" + Describe.esc(e.routes.get(i).describe()));
                if (e.routes.size() > 6) lore.add("<gray>and " + (e.routes.size() - 6) + " more");
                lore.add("");
                lore.add("<yellow>Click to see or change");
                button(22, icon(Material.CHEST_MINECART, "<aqua>Routes: " + e.routes.size(), lore), (p, c) -> new RoutesMenu(plugin, e, this).open(p));
            }
            case BODYGUARD -> button(22, glow(icon(e.following ? Material.LEAD : Material.OAK_FENCE,
                    e.following ? "<aqua>Following you" : "<aqua>Staying at its post", List.of(
                            e.following ? "<gray>Goes where you go and fights mobs" : "<gray>Guards its post like a guard",
                            e.following ? "<gray>within " + (int) plugin.settings().bodyguardRadius + " blocks of you." : "<gray>(its work area) until called.",
                            "", e.following ? "<yellow>Click to send them back to their post" : "<yellow>Click to have them follow you"))), (p, c) -> {
                run(p, (e.following ? "stay " : "follow ") + ref(p, e));
                redraw();
            });
            default -> { }
        }
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
