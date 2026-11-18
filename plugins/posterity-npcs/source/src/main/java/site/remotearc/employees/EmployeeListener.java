package site.remotearc.employees;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import site.remotearc.employees.gui.EmployeeMenu;
import site.remotearc.employees.util.Msg;

/** Keeps employees unharmable and opens an employee's menu when its owner right-clicks it. */
final class EmployeeListener implements Listener {

    private final ArcEmployees plugin;

    EmployeeListener(ArcEmployees plugin) {
        this.plugin = plugin;
    }

    /** Employees in a world loaded later (a studio world made on demand) come back with it. */
    @EventHandler
    public void onWorldLoad(org.bukkit.event.world.WorldLoadEvent ev) {
        plugin.manager().worldLoaded(ev.getWorld());
    }

    /** Keeps track of when a player was last on the server. */
    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent ev) {
        plugin.manager().playerSeen();
        plugin.reports().joined(ev.getPlayer());
    }

    /** An employee's arrow never hurts a player, a pet, a villager or another employee: only hostile mobs. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArrowHit(org.bukkit.event.entity.ProjectileHitEvent ev) {
        if (ev.getHitEntity() == null || ev.getHitEntity() instanceof org.bukkit.entity.Enemy) return;
        if (ev.getEntity().getShooter() instanceof Entity shooter && plugin.manager().isEmployeeEntity(shooter)) ev.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArrowDamage(org.bukkit.event.entity.EntityDamageByEntityEvent ev) {
        if (ev.getEntity() instanceof org.bukkit.entity.Enemy) return;
        if (ev.getDamager() instanceof org.bukkit.entity.Projectile pr && pr.getShooter() instanceof Entity shooter
                && plugin.manager().isEmployeeEntity(shooter)) ev.setCancelled(true);
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent ev) {
        plugin.reports().quit(ev.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent ev) {
        if (isMerchant(ev.getEntity())) {
            ev.setCancelled(true);
            return;
        }
        if (!plugin.manager().isEmployeeEntity(ev.getEntity())) return;
        Employee e = plugin.manager().byEntity(ev.getEntity());
        // Only a fighter, only hurt by a mob (in melee or by its arrow) it would fight back, never by a creeper, never while down.
        Entity attacker = ev instanceof org.bukkit.event.entity.EntityDamageByEntityEvent hit ? mobOf(hit.getDamager()) : null;
        if (e == null || attacker == null || !plugin.manager().takesDamage(e) || e.knockedOutUntil > System.currentTimeMillis()
                || e.worker == null || !e.worker.fightsBack((org.bukkit.entity.LivingEntity) attacker)) {
            ev.setCancelled(true);
            return;
        }
        if (ev.isCancelled()) return;
        String by = mobName(attacker);
        e.lastHurt = System.currentTimeMillis();
        e.worker.attackedBy((org.bukkit.entity.LivingEntity) attacker);
        if (ev.getFinalDamage() >= e.entity.getHealth()) {
            ev.setCancelled(true);
            plugin.manager().knockOut(e, by);
        }
    }

    /** The hostile mob behind a hit (itself, or whoever shot the arrow); null for anything else, creepers included. */
    private static Entity mobOf(Entity damager) {
        Entity mob = damager instanceof org.bukkit.entity.Projectile p && p.getShooter() instanceof Entity shooter ? shooter : damager;
        if (!(mob instanceof org.bukkit.entity.Enemy) || !(mob instanceof org.bukkit.entity.LivingEntity) || mob instanceof org.bukkit.entity.Creeper) return null;
        return mob;
    }

    /** "a zombie", "an enderman". */
    private static String mobName(Entity mob) {
        String n = mob.getType().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return ("aeiou".indexOf(n.charAt(0)) >= 0 ? "an " : "a ") + n;
    }

    /** Merchants and avatars: our other NPCs, protected like employees. */
    private boolean isMerchant(Entity e) {
        return (plugin.market() != null && plugin.market().isMerchant(e)) || (plugin.avatars() != null && plugin.avatars().isAvatar(e));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCombust(EntityCombustEvent ev) {
        if (plugin.manager().isEmployeeEntity(ev.getEntity()) || isMerchant(ev.getEntity())) ev.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteractAt(PlayerInteractAtEntityEvent ev) {
        if (plugin.manager().isEmployeeEntity(ev.getRightClicked()) || isMerchant(ev.getRightClicked())) ev.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEntityEvent ev) {
        Entity clicked = ev.getRightClicked();
        if (plugin.avatars() != null && plugin.avatars().isAvatar(clicked)) {
            ev.setCancelled(true);
            if (ev.getHand() != EquipmentSlot.HAND) return;
            Employee e = plugin.avatars().employeeOf(clicked);
            Player p = ev.getPlayer();
            if (e == null) return;
            if (!plugin.manager().canManage(p, e)) {
                Msg.send(p, "<gold>" + e.name + "</gold> <gray>is " + e.ownerName() + "'s " + e.job.display().toLowerCase()
                        + ". <white>" + e.status.replace("<", "\\<"));
                return;
            }
            if (p.isSneaking()) {
                plugin.manager().setPaused(e, !e.paused);
                Msg.send(p, e.paused ? "<gray>" + e.name + " is paused." : "<green>" + e.name + " is back at work.");
            } else {
                site.remotearc.employees.gui.EmployeeCard.show(plugin, p, e);
            }
            return;
        }
        if (isMerchant(clicked)) {
            ev.setCancelled(true);
            if (ev.getHand() != EquipmentSlot.HAND) return;
            var merchant = plugin.market().byEntity(clicked);
            if (merchant != null && ev.getPlayer().isSneaking() && ev.getPlayer().hasPermission("employees.admin")) {
                new site.remotearc.employees.gui.MerchantMenu(plugin, merchant.id()).open(ev.getPlayer());
                return;
            }
            if (!plugin.settings().marketEnabled) {
                Msg.error(ev.getPlayer(), "The market is closed.");
                return;
            }
            String name = clicked.customName() == null ? "The merchant"
                    : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(clicked.customName());
            new site.remotearc.employees.market.SellMenu(plugin, ev.getPlayer(), name).open();
            return;
        }
        if (!plugin.manager().isEmployeeEntity(clicked)) return;
        ev.setCancelled(true);
        if (ev.getHand() != EquipmentSlot.HAND) return;
        Player p = ev.getPlayer();
        Employee e = plugin.manager().byEntity(clicked);
        if (e == null) return;
        if (plugin.manager().canManage(p, e)) {
            site.remotearc.employees.gui.EmployeeCard.show(plugin, p, e);
        } else {
            Msg.send(p, "<gold>" + e.name + "</gold> <gray>is " + e.ownerName() + "'s " + e.job.display().toLowerCase()
                    + ". " + Activity.mini(plugin, e));
        }
    }

    /**
     * Mobs never go after employees: a zombie would follow one around for
     * ever, and a creeper hit by a guard would turn on it and blow up the base.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(org.bukkit.event.entity.EntityTargetLivingEntityEvent ev) {
        if (ev.getTarget() == null) return;
        if (isMerchant(ev.getTarget())) {
            ev.setCancelled(true);
            return;
        }
        if (!plugin.manager().isEmployeeEntity(ev.getTarget())) return;
        // Mobs may go for guards and bodyguards that can be hurt (not creepers: nothing gets blown up),
        // and only mobs they'd fight back: not one kept in a boat, named, leashed or out of reach.
        Employee e = plugin.manager().byEntity(ev.getTarget());
        boolean fair = e != null && plugin.manager().takesDamage(e) && e.knockedOutUntil <= System.currentTimeMillis()
                && mobOf(ev.getEntity()) != null && e.worker != null && e.worker.fightsBack((org.bukkit.entity.LivingEntity) ev.getEntity());
        if (!fair) ev.setCancelled(true);
    }

    /** With market.merchants-only, buying and selling goes through merchants: /shop and /sell send players to one. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(org.bukkit.event.player.PlayerCommandPreprocessEvent ev) {
        if (!plugin.settings().merchantsOnly || !plugin.settings().marketEnabled) return;
        String msg = ev.getMessage();
        String label = msg.substring(1).split(" ", 2)[0].toLowerCase(java.util.Locale.ROOT);
        if (!plugin.settings().blockedCommands.contains(label)) return;
        ev.setCancelled(true);
        Player p = ev.getPlayer();
        var market = plugin.market();
        if (!market.hasMerchants()) {
            Msg.error(p, "Trading happens at merchants, and there isn't one yet (an admin places one with /employees market place).");
            return;
        }
        if (market.nearMerchant(p)) {
            new site.remotearc.employees.market.BuyMenu(plugin, "The merchant", 0).open(p);
            return;
        }
        StringBuilder where = new StringBuilder();
        for (var m : market.merchants()) {
            where.append(" ").append(m.name()).append(" at ").append((int) m.x()).append(", ").append((int) m.z())
                    .append(m.world().equals("world") ? "" : " in " + m.world()).append(";");
        }
        Msg.send(p, "<yellow>Buying and selling happens at a merchant:<gray><where> <gray>Right-click them to trade.",
                Msg.p("where", where.toString()));
    }

    /** Employees and merchants never go through nether or end portals (they'd vanish to another world). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPortal(org.bukkit.event.entity.EntityPortalEvent ev) {
        if (plugin.manager().isEmployeeEntity(ev.getEntity()) || isMerchant(ev.getEntity())) ev.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPortalReady(io.papermc.paper.event.entity.EntityPortalReadyEvent ev) {
        if (plugin.manager().isEmployeeEntity(ev.getEntity()) || isMerchant(ev.getEntity())) ev.setCancelled(true);
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent ev) {
        for (Entity entity : ev.getEntities()) {
            plugin.manager().removeIfStray(entity);
            if (plugin.boards() != null) plugin.boards().removeIfStray(entity);
            if (plugin.market() != null) plugin.market().removeIfStray(entity);
            if (plugin.avatars() != null) plugin.avatars().removeIfStray(entity);
        }
    }
}
