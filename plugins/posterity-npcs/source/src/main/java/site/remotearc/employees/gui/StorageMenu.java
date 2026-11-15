package site.remotearc.employees.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.Employee;
import site.remotearc.employees.storage.StorageTarget;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Choose where an employee delivers: a /chest number, the chest you look at, or its backpack. */
final class StorageMenu extends Menu {

    private static final int PER_PAGE = 36;

    private final Employee e;
    private final Menu back;
    private final int page;

    StorageMenu(ArcEmployees plugin, Employee e, Menu back, int page) {
        super(plugin, 6, Component.text("Where " + e.name + " delivers"));
        this.e = e;
        this.back = back;
        this.page = page;
    }

    @Override
    protected void draw() {
        for (int s = 0; s < 54; s++) button(s, filler(), null);
        StorageTarget st = e.storage;
        button(1, icon(Material.BARREL, "<aqua>Keep everything in the backpack", List.of(
                "<gray>Nothing is delivered; 36 slots.", "", "<yellow>Click to choose")), (p, c) -> {
            run(p, "storage " + ref(p, e) + " backpack");
            back.open(p);
        });
        if (plugin.settings().chestsEnabled) {
            button(4, icon(Material.CHEST, "<aqua>The chest you're looking at", List.of(
                    "<gray>Close this menu, look at a chest or",
                    "<gray>barrel (within 6 blocks), reopen it",
                    "<gray>and click here. Or use",
                    "<white>/employees storage " + e.name + " chest", "", "<yellow>Click to choose")), (p, c) -> {
                run(p, "storage " + ref(p, e) + " chest");
                back.open(p);
            });
        }
        if (st.kind() == StorageTarget.Kind.VIRTUAL) {
            button(7, icon(Material.ENDER_EYE, "<aqua>Open /chest " + st.chestNumber(), List.of("", "<yellow>Click to open")),
                    (p, c) -> openThenReturn(p, () -> plugin.vchests().open(p, e.ownerPlayer(), st.chestNumber())));
        }

        if (plugin.settings().vcEnabled && plugin.vchests().available()) {
            Map<Integer, String> users = new HashMap<>();
            for (Employee o : plugin.manager().ownedBy(e.owner)) {
                if (o.storage.kind() == StorageTarget.Kind.VIRTUAL) users.merge(o.storage.chestNumber(), o.name, (a, b) -> a + ", " + b);
                for (int n : o.overflow) users.merge(n, o.name + " (overflow)", (a, b) -> a + ", " + b);
            }
            int first = page * PER_PAGE + 1;
            for (int i = 0; i < PER_PAGE; i++) {
                int n = first + i;
                boolean current = st.kind() == StorageTarget.Kind.VIRTUAL && st.chestNumber() == n;
                String user = users.get(n);
                ItemStack it;
                if (current) {
                    it = glow(icon(Material.ENDER_CHEST, "<green>/chest " + n + " <gray>(current)", List.of("<gray>" + e.name + " delivers here.")));
                } else if (user != null) {
                    it = icon(Material.CHEST, "<yellow>/chest " + n, List.of("<gray>Used by <white>" + user,
                            "<gray>Sharing works, but their items mix.", "", "<yellow>Click to deliver here too"));
                } else {
                    it = icon(Material.ENDER_CHEST, "<aqua>/chest " + n, List.of("<gray>No employee uses it.", "", "<yellow>Click to deliver here"));
                }
                it.setAmount(Math.min(99, n));
                button(9 + i, it, (p, c) -> {
                    run(p, "storage " + ref(p, e) + " vc " + n);
                    back.open(p);
                });
            }
        }
        button(45, back(), (p, c) -> back.open(p));
        if (page > 0) button(48, icon(Material.ARROW, "<yellow>Lower numbers", List.of()), (p, c) -> new StorageMenu(plugin, e, back, page - 1).open(p));
        if (page < 5) button(50, icon(Material.ARROW, "<yellow>Higher numbers", List.of()), (p, c) -> new StorageMenu(plugin, e, back, page + 1).open(p));
    }
}
