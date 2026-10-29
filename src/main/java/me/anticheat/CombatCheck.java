package me.anticheat;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * Combat checks:
 *  - Reach: distance from your eyes to the target's hitbox must be about 3 blocks or less.
 *  - Aim:   a ray along where you're looking must actually pass through the target's hitbox.
 *  - Multi: you can't hit two different entities within the same tick or two.
 *  - Clicks: more than 20 swings in one second is not humanly sustainable.
 *
 * Because players have ping, the target is never exactly where the attacker's screen shows it,
 * so Reach and Aim both widen their tolerance based on the attacker's ping.
 */
public class CombatCheck implements Listener {

    private static final double VANILLA_REACH = 3.0;
    private static final int MAX_CPS = 20;

    private final AntiCheatPlugin plugin;

    public CombatCheck(AntiCheatPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player attacker)) return;
        // Only direct melee hits (ignores sweeping, projectiles, thorns, etc.)
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (attacker.getGameMode() == GameMode.CREATIVE || attacker.getGameMode() == GameMode.SPECTATOR) return;

        Entity victim = e.getEntity();
        PlayerData data = plugin.getData(attacker);
        int tick = Bukkit.getCurrentTick();
        int ping = Math.min(attacker.getPing(), 400);

        Location eye = attacker.getEyeLocation();
        BoundingBox box = victim.getBoundingBox();

        // ---------------- REACH ----------------
        // A fleeing target can be up to (speed * ping) further away on the server than on the
        // attacker's screen: ~6 blocks/sec * ping in seconds, plus a little slack.
        double distance = distanceToBox(eye.toVector(), box);
        double allowed = VANILLA_REACH + 0.3 + ping * 0.006;
        if (distance > allowed) {
            if (data.addBuffer("reach", 1.0) > 1.5) {
                plugin.flag(attacker, "Reach", String.format("%.2f > %.2f (ping %d)", distance, allowed, ping));
            }
        } else {
            data.addBuffer("reach", -0.25);
        }

        // ---------------- AIM ----------------
        // Cast a ray from the eyes along the look direction. It should hit the (slightly
        // expanded) hitbox. If you hit someone you weren't looking at, that's aimbot/killaura.
        double expand = 0.3 + ping * 0.006;
        RayTraceResult ray = box.clone().expand(expand)
                .rayTrace(eye.toVector(), eye.getDirection(), VANILLA_REACH + 3.0);
        if (ray == null) {
            if (data.addBuffer("aim", 1.0) > 2.0) {
                plugin.flag(attacker, "Aim", "hit target outside line of sight (ping " + ping + ")");
            }
        } else {
            data.addBuffer("aim", -0.25);
        }

        // ---------------- MULTI-TARGET ----------------
        // One click = one attack packet = one target. Two different targets within
        // a tick or two is what multi-aura does.
        boolean differentVictim = data.lastVictimId != victim.getEntityId();
        if (differentVictim && tick - data.lastAttackTick <= 1) {
            if (data.addBuffer("multi", 1.0) > 2.0) {
                plugin.flag(attacker, "Multi", "hit 2 different entities within " + (tick - data.lastAttackTick) + " tick(s)");
            }
        } else {
            data.addBuffer("multi", -0.2);
        }
        data.lastAttackTick = tick;
        data.lastVictimId = victim.getEntityId();
    }

    /**
     * Click speed. Counts arm swings in a sliding 20-tick (1 second) window.
     * Measured in server ticks rather than milliseconds, since the server handles
     * packets in tick-sized batches and millisecond timings would be misleading.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(PlayerAnimationEvent e) {
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING) return;

        Player player = e.getPlayer();
        PlayerData data = plugin.getData(player);
        int tick = Bukkit.getCurrentTick();

        data.swingTicks.addLast(tick);
        while (!data.swingTicks.isEmpty() && tick - data.swingTicks.peekFirst() >= 20) {
            data.swingTicks.pollFirst();
        }

        int cps = data.swingTicks.size();
        if (cps > MAX_CPS) {
            if (data.addBuffer("clicks", 1.0) > 5.0) {
                plugin.flag(player, "Clicks", cps + " swings in 1s");
            }
        } else {
            data.addBuffer("clicks", -0.5);
        }
    }

    /** Distance from a point to the closest point on a box (0 if the point is inside it). */
    private static double distanceToBox(Vector point, BoundingBox box) {
        double x = Math.max(box.getMinX(), Math.min(point.getX(), box.getMaxX()));
        double y = Math.max(box.getMinY(), Math.min(point.getY(), box.getMaxY()));
        double z = Math.max(box.getMinZ(), Math.min(point.getZ(), box.getMaxZ()));
        return point.distance(new Vector(x, y, z));
    }
}
