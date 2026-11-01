package me.anticheat;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * Combat checks:
 *  - Reach: distance from your eyes to the target's hitbox must be about 3 blocks or less.
 *  - Aim:   a ray along where you're looking must actually pass through the target's hitbox.
 *  - Multi: you can't hit two different entities within the same tick or two.
 *  - KillAura: you can't hit something that is completely hidden behind solid blocks.
 *  - Aimbot: (statistics, alert only) hits that are suspiciously perfect, or that come right after a huge flick.
 *
 * When Reach, Aim, Multi or KillAura flag, the hit is also CANCELLED, so a kill aura stops doing damage.
 *  - Clicks: more than 24 swings in one second is not humanly sustainable. (Holding the mouse to break
 *            blocks swings 20+ times a second, so swings while mining are not counted.)
 *
 * Because players have ping, the target is never exactly where the attacker's screen shows it,
 * so Reach and Aim both widen their tolerance based on the attacker's ping.
 */
public class CombatCheck implements Listener {

    private static final double VANILLA_REACH = 3.0;
    private static final int MAX_CPS = 24;

    private final AntiCheatPlugin plugin;

    public CombatCheck(AntiCheatPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
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
        boolean cancel = false;   // set when a check flags, so the hit itself is blocked

        // ---------------- REACH ----------------
        // A fleeing target can be up to (speed * ping) further away on the server than on the
        // attacker's screen: ~6 blocks/sec * ping in seconds, plus a little slack.
        double distance = distanceToBox(eye.toVector(), box);
        double allowed = VANILLA_REACH + 0.3 + ping * 0.006;
        if (distance > allowed) {
            if (data.addBuffer("reach", 1.0) > 1.5) {
                plugin.flag(attacker, "Reach", String.format("%.2f > %.2f (ping %d)", distance, allowed, ping));
                cancel = true;
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
                cancel = true;
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
                cancel = true;
            }
        } else {
            data.addBuffer("multi", -0.2);
        }
        // ---------------- KILLAURA: HIT THROUGH A WALL ----------------
        // Vanilla blocks melee hits on something behind solid blocks; a kill aura doesn't care. We try 27 lines
        // from the eyes to the target and only count it as hidden if EVERY one of them is blocked.
        if (distance > 1.0 && hiddenBehindBlocks(eye, box)) {
            if (data.addBuffer("wallhit", 1.0) > 1.5) {
                plugin.flag(attacker, "KillAura", "hit a target through a wall");
                cancel = true;
            }
        } else {
            data.addBuffer("wallhit", -0.5);
        }

        // ---------------- AIMBOT (statistics, so alert only) ----------------
        if (victim instanceof LivingEntity && distance >= 2.0) {
            double error = Math.toDegrees(eye.getDirection().angle(box.getCenter().subtract(eye.toVector())));
            boolean turnedJustNow = tick - data.rotationTick <= 2;

            // Snap: a huge flick right before the hit that lands exactly on the target.
            if (turnedJustNow && data.lastYawDelta > 70 && error < 6.0) {
                data.snapHits.addLast(tick);
                while (!data.snapHits.isEmpty() && tick - data.snapHits.peekFirst() > 400) data.snapHits.pollFirst();
                if (data.snapHits.size() >= 3) {
                    plugin.flag(attacker, "Aimbot", "snapped onto targets 3 times", false);
                    data.snapHits.clear();
                }
            }

            // Lock: while turning, the crosshair is almost dead on the target's center on most hits.
            if (turnedJustNow && data.lastYawDelta >= 0.5) {
                data.aimErrors.addLast(error);
                if (data.aimErrors.size() > 8) data.aimErrors.pollFirst();
                if (data.aimErrors.size() == 8) {
                    int perfect = 0;
                    for (double sample : data.aimErrors) {
                        if (sample <= 1.5) perfect++;
                    }
                    if (perfect >= 6) {
                        plugin.flag(attacker, "Aimbot", perfect + " of the last 8 hits were dead on the target's center", false);
                        data.aimErrors.clear();
                    }
                }
            }
        }

        data.lastAttackTick = tick;
        data.lastVictimId = victim.getEntityId();

        if (cancel) e.setCancelled(true);
    }

    /** Remembers how far (and when) the player last turned, for the aimbot statistics. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTurn(PlayerMoveEvent e) {
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;

        float delta = Math.abs(to.getYaw() - from.getYaw()) % 360f;
        if (delta > 180f) delta = 360f - delta;
        if (delta < 0.01f) return;

        PlayerData data = plugin.getData(e.getPlayer());
        data.lastYawDelta = delta;
        data.rotationTick = Bukkit.getCurrentTick();
    }

    /**
     * Click speed. Counts arm swings in a sliding 20-tick (1 second) window.
     * Measured in server ticks rather than milliseconds, since the server handles
     * packets in tick-sized batches and millisecond timings would be misleading.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onStartDigging(BlockDamageEvent e) {
        plugin.getData(e.getPlayer()).lastDigTick = Bukkit.getCurrentTick();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFinishDigging(BlockBreakEvent e) {
        plugin.getData(e.getPlayer()).lastDigTick = Bukkit.getCurrentTick();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(PlayerAnimationEvent e) {
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING) return;

        Player player = e.getPlayer();
        PlayerData data = plugin.getData(player);
        int tick = Bukkit.getCurrentTick();

        // Holding the mouse while mining swings the arm every tick (sometimes twice). That's not clicking.
        if (tick - data.lastDigTick <= 10) return;

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

    /**
     * True if the target is completely hidden: all 27 lines from the eyes to points spread over its hitbox
     * run into solid blocks. One clear line means it could be seen (and hit) through a gap or around a corner.
     */
    private static boolean hiddenBehindBlocks(Location eye, BoundingBox box) {
        World world = eye.getWorld();
        if (eye.getBlock().getType().isSolid()) return false;   // eyes inside a block: can't judge

        Vector start = eye.toVector();
        double[] fractions = {0.1, 0.5, 0.9};
        for (double fx : fractions) {
            for (double fy : fractions) {
                for (double fz : fractions) {
                    Vector point = new Vector(
                            box.getMinX() + box.getWidthX() * fx,
                            box.getMinY() + box.getHeight() * fy,
                            box.getMinZ() + box.getWidthZ() * fz);
                    Vector direction = point.clone().subtract(start);
                    double length = direction.length();
                    if (length < 0.3) return false;   // practically touching
                    if (world.rayTraceBlocks(eye, direction.normalize(), length - 0.05,
                            FluidCollisionMode.NEVER, true) == null) {
                        return false;                  // one clear line is enough
                    }
                }
            }
        }
        return true;
    }

    /** Distance from a point to the closest point on a box (0 if the point is inside it). */
    private static double distanceToBox(Vector point, BoundingBox box) {
        double x = Math.max(box.getMinX(), Math.min(point.getX(), box.getMaxX()));
        double y = Math.max(box.getMinY(), Math.min(point.getY(), box.getMaxY()));
        double z = Math.max(box.getMinZ(), Math.min(point.getZ(), box.getMaxZ()));
        return point.distance(new Vector(x, y, z));
    }
}
