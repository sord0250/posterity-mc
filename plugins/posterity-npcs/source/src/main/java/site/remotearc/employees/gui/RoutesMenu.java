package site.remotearc.employees.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.Employee;
import site.remotearc.employees.storage.StorageTarget;
import site.remotearc.employees.util.Items;
import site.remotearc.employees.util.Msg;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** A courier's routes: what it carries from where to where. */
final class RoutesMenu extends Menu {

    private final Employee e;
    private final Menu back;

    RoutesMenu(ArcEmployees plugin, Employee e, Menu back) {
        super(plugin, 6, Component.text(e.name + "'s routes"));
        this.e = e;
        this.back = back;
    }

    static Material iconFor(StorageTarget t) {
        return t.kind() == StorageTarget.Kind.VIRTUAL ? Material.ENDER_CHEST : t.kind() == StorageTarget.Kind.NONE ? Material.EMERALD : Material.CHEST;
    }

    @Override
    protected void draw() {
        for (int s = 0; s < 54; s++) button(s, filler(), null);
        button(4, icon(Material.CHEST_MINECART, "<gold>Routes", List.of(
                "<gray>" + e.name + " takes turns on these: picks up",
                "<gray>at the first place and unloads it all at",
                "<gray>the second. Placed chests nearby are",
                "<gray>walked to; far ones it takes a shortcut to.",
                "<gray>/chest numbers work from anywhere. A route",
                "<gray>can also end at a merchant: it walks the",
                "<gray>load over and sells it.")), null);
        int slot = 9;
        for (int i = 0; i < e.routes.size() && slot < 45; i++) {
            Employee.Route r = e.routes.get(i);
            int index = i;
            button(slot++, icon(iconFor(r.from()), "<white>" + (i + 1) + ". " + Describe.esc(r.describe()), concat(
                    wrap("<gray>", Describe.esc(r.rules()), 40), "", "<yellow>Click to change", "<yellow>Shift-click to remove")), (p, c) -> {
                if (c.isShiftClick()) {
                    run(p, "route " + ref(p, e) + " remove " + (index + 1));
                    redraw();
                } else {
                    new RouteEditMenu(plugin, e, index, r.from(), r.to(), EnumSet.copyOf(withDummy(r.items())), r.keep(), r.stock(), this).open(p);
                }
            });
        }
        button(49, icon(Material.LIME_DYE, "<green>Add a route", List.of("", "<yellow>Click to set one up")), (p, c) ->
                new RouteEditMenu(plugin, e, -1, null, null, EnumSet.noneOf(Material.class), 0, 0, this).open(p));
        button(45, back(), (p, c) -> back.open(p));
    }

    /** EnumSet.copyOf needs a non-empty collection or an EnumSet. */
    private static Set<Material> withDummy(Set<Material> s) {
        return s.isEmpty() ? EnumSet.noneOf(Material.class) : s;
    }

    /** One route being set up or changed; saved with Save. */
    static final class RouteEditMenu extends Menu {

        private static final int[] KEEPS = {0, 16, 64, 256, 1024};
        private static final int[] STOCKS = {0, 64, 128, 256, 512, 1024};

        private final Employee e;
        private final int index;
        private StorageTarget from, to;
        private final Set<Material> items;
        private int keep, stock;
        private final Menu back;

        RouteEditMenu(ArcEmployees plugin, Employee e, int index, StorageTarget from, StorageTarget to, Set<Material> items,
                      int keep, int stock, Menu back) {
            super(plugin, 5, Component.text(index < 0 ? "New route" : "Route " + (index + 1)));
            this.e = e;
            this.index = index;
            this.from = from;
            this.to = to;
            this.items = items;
            this.keep = keep;
            this.stock = stock;
            this.back = back;
        }

        @Override
        protected void draw() {
            for (int s = 0; s < 45; s++) button(s, filler(), null);
            button(11, end("Take from", from), (p, c) -> pick(p, c.isRightClick(), true));
            button(13, icon(Material.MINECART, "<gray>→", List.of()), null);
            button(15, end("Bring to", to), (p, c) -> {
                if (c.isShiftClick()) {
                    if (!plugin.market().hasMerchants()) {
                        Msg.error(p, "There's no merchant yet (an admin places one with /employees market place).");
                        return;
                    }
                    to = StorageTarget.NONE;   // carry it to a merchant and sell it
                    redraw();
                    return;
                }
                pick(p, c.isRightClick(), false);
            });

            List<String> itemLore = new ArrayList<>();
            if (items.isEmpty()) itemLore.add("<gray>Everything (plain items).");
            else for (Material m : items.stream().limit(12).toList()) itemLore.add("<white>" + Items.name(m));
            if (items.size() > 12) itemLore.add("<gray>and " + (items.size() - 12) + " more");
            itemLore.add("");
            itemLore.add("<yellow>Click to pick items");
            if (!items.isEmpty()) itemLore.add("<yellow>Right-click for everything again");
            button(29, icon(Material.HOPPER, "<aqua>Carries: " + (items.isEmpty() ? "everything" : items.size() + " kinds"), itemLore), (p, c) -> {
                if (c.isRightClick()) {
                    items.clear();
                    redraw();
                    return;
                }
                new ItemPickerMenu(plugin, "Carries", suggestions(), null, 0, items, items::add, this).open(p);
            });
            button(31, icon(Material.BARREL, "<aqua>Leave behind: " + (keep == 0 ? "nothing" : keep + " of each"), List.of(
                    "<gray>How many of each item stay at the",
                    "<gray>place it takes from.", "", "<yellow>Click to change")), (p, c) -> {
                keep = cycle(KEEPS, keep, c.isRightClick());
                redraw();
            });
            button(33, icon(Material.COMPARATOR, "<aqua>Top up to: " + (stock == 0 ? "no limit" : stock + " of each"), List.of(
                    "<gray>Only bring enough to keep this many",
                    "<gray>of each item at the destination (e.g.",
                    "<gray>keep a furnace room stocked with 128",
                    "<gray>coal). No limit = bring it all.", "", "<yellow>Click to change")), (p, c) -> {
                stock = cycle(STOCKS, stock, c.isRightClick());
                redraw();
            });

            boolean ready = from != null && to != null && !from.equals(to);
            button(40, icon(ready ? Material.LIME_CONCRETE : Material.GRAY_CONCRETE, ready ? "<green>Save" : "<gray>Save",
                    List.of(ready ? "<gray>" + Describe.esc(from.describe() + " → " + to.describe()) : "<gray>Pick both ends first.")), (p, c) -> {
                if (!ready) return;
                Employee.Route r = new Employee.Route(from, to, EnumSet.copyOf(RoutesMenu.withDummy(items)), keep, stock);
                if (index >= 0 && index < e.routes.size()) e.routes.set(index, r);
                else e.routes.add(r);
                plugin.manager().markDirty();
                plugin.manager().refreshTickets();
                e.worker.interrupt();
                Msg.send(p, "<green>" + e.name + " carries " + Describe.esc(r.rules()) + " from " + Describe.esc(r.describe()) + ".");
                back.open(p);
            });
            button(36, back(), (p, c) -> back.open(p));
        }

        private ItemStack end(String label, StorageTarget t) {
            boolean merchant = t != null && t.kind() == StorageTarget.Kind.NONE;
            List<String> lore = new ArrayList<>();
            if (merchant) {
                lore.add("<gray>Walks the load to the nearest merchant");
                lore.add("<gray>and sells it (up to the day's limit);");
                lore.add("<gray>anything left over comes back.");
            }
            lore.add("");
            lore.add("<yellow>Click, then click a chest in the world");
            lore.add("<yellow>Right-click to type a /chest number");
            if (label.equals("Bring to")) lore.add("<yellow>Shift-click to sell it at a merchant");
            return icon(t == null ? Material.GRAY_STAINED_GLASS : merchant ? Material.EMERALD : iconFor(t), "<aqua>" + label + ": "
                    + (t == null ? "<gray>not set" : merchant ? "<white>a merchant" : "<white>" + Describe.esc(t.describe())), lore);
        }

        private void pick(org.bukkit.entity.Player p, boolean number, boolean isFrom) {
            if (number) {
                Prompt.ask(p, "Which /chest number?", text -> {
                    int n;
                    try {
                        n = Integer.parseInt(text.replace("/chest", "").trim());
                    } catch (NumberFormatException ex) {
                        Msg.error(p, "That isn't a number.");
                        open(p);
                        return;
                    }
                    set(p, StorageTarget.virtual(Math.max(1, n)), isFrom);
                });
            } else {
                ChestPick.ask(p, isFrom ? "the container " + e.name + " should take from" : "the container " + e.name + " should bring things to",
                        b -> set(p, StorageTarget.chest(b), isFrom));
            }
        }

        private void set(org.bukkit.entity.Player p, StorageTarget t, boolean isFrom) {
            String why = t.problemFor(p);
            if (why != null) Msg.error(p, why);
            else if (isFrom) from = t;
            else to = t;
            open(p);
        }

        private List<Material> suggestions() {
            java.util.Map<Material, Integer> have = new java.util.EnumMap<>(Material.class);
            if (from != null && from.kind() == StorageTarget.Kind.VIRTUAL && plugin.vchests().available()) {
                Items.countPlain(plugin.vchests().contents(e.owner, from.chestNumber()), have);
            } else if (from != null && from.container() != null) {
                Items.countPlain(from.container().getInventory().getStorageContents(), have);
            }
            List<Material> out = new ArrayList<>(have.keySet());
            out.sort((a, b) -> have.get(b) - have.get(a));
            for (Material m : List.of(Material.COBBLESTONE, Material.OAK_LOG, Material.COAL, Material.RAW_IRON, Material.IRON_INGOT,
                    Material.WHEAT, Material.CARROT, Material.POTATO, Material.STICK, Material.EMERALD)) if (!out.contains(m)) out.add(m);
            return out;
        }

        private static int cycle(int[] options, int now, boolean down) {
            int i = 0;
            while (i < options.length && options[i] != now) i++;
            if (i == options.length) return options[0];
            return options[Math.floorMod(i + (down ? -1 : 1), options.length)];
        }
    }
}
