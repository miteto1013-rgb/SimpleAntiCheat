package me.anticheat;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Bow / crossbow aimbot detection by statistics.
 *
 * An aimbot hits almost every shot, even at long range on targets that are moving. Nobody does that by hand.
 * We look at fully drawn arrows fired from at least 15 blocks away at the end of their flight:
 *  - a block or the ground means a miss
 *  - hitting a player who is MOVING means a hit
 *  - hitting a player who is standing still is ignored (that proves nothing), as are mobs
 *
 * Two things raise an alert: 7 long-range hits in a row, or 85% hits over the last 8-10 shots.
 *
 * Accuracy can be skill, so this is only a hint for staff: it alerts (and shows in /sus) but never holds
 * anyone in place or counts toward a ban.
 */
public class BowCheck implements Listener {

    private static final double MIN_DISTANCE = 15.0;
    private static final int WINDOW_SHOTS = 10;
    private static final int MIN_SHOTS = 8;
    private static final double MAX_HIT_RATIO = 0.85;
    private static final int MAX_STREAK = 7;

    private static class Shot {
        final Location origin;
        final int tick;

        Shot(Location origin, int tick) {
            this.origin = origin;
            this.tick = tick;
        }
    }

    private static class Stats {
        final Deque<Boolean> results = new ArrayDeque<>();
        int streak;
    }

    private final AntiCheatPlugin plugin;
    /** Arrow -> where it was shot from. */
    private final Map<UUID, Shot> arrows = new HashMap<>();
    private final Map<UUID, Stats> stats = new HashMap<>();

    public BowCheck(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        // Arrows that despawned without hitting anything never report back, so forget them after a while.
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int now = Bukkit.getCurrentTick();
            arrows.values().removeIf(shot -> now - shot.tick > 600);
        }, 20L * 30, 20L * 30);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent e) {
        if (!(e.getEntity() instanceof AbstractArrow arrow)) return;
        if (!(arrow.getShooter() instanceof Player shooter)) return;
        if (!arrow.isCritical()) return;   // only fully drawn bow shots and crossbow bolts
        if (shooter.getGameMode() == GameMode.CREATIVE || shooter.getGameMode() == GameMode.SPECTATOR) return;

        arrows.put(arrow.getUniqueId(), new Shot(shooter.getEyeLocation(), Bukkit.getCurrentTick()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHit(ProjectileHitEvent e) {
        Shot shot = arrows.remove(e.getEntity().getUniqueId());
        if (shot == null) return;
        if (!(e.getEntity() instanceof AbstractArrow arrow)) return;
        if (!(arrow.getShooter() instanceof Player shooter) || !shooter.isOnline()) return;

        Location landing = arrow.getLocation();
        if (!landing.getWorld().equals(shot.origin.getWorld())) return;
        if (shot.origin.distance(landing) < MIN_DISTANCE) return;   // close shots are easy, they prove nothing

        boolean hit;
        Entity target = e.getHitEntity();
        if (target == null) {
            hit = false;                                   // hit a block, or ran out of flight
        } else if (target instanceof Player victim && !victim.equals(shooter)) {
            if (plugin.getData(victim).lastHorizontal <= 0.12) return;   // standing still: leave this shot out
            hit = true;
        } else {
            return;                                        // mobs and other entities aren't counted
        }

        Stats s = stats.computeIfAbsent(shooter.getUniqueId(), id -> new Stats());
        s.results.addLast(hit);
        if (s.results.size() > WINDOW_SHOTS) s.results.pollFirst();
        s.streak = hit ? s.streak + 1 : 0;

        if (s.streak >= MAX_STREAK) {
            plugin.flag(shooter, "BowAimbot", s.streak + " long-range hits in a row on moving players", false);
            s.streak = 0;
            s.results.clear();
            return;
        }

        if (s.results.size() >= MIN_SHOTS) {
            int hits = 0;
            for (boolean result : s.results) {
                if (result) hits++;
            }
            double ratio = hits / (double) s.results.size();
            if (ratio >= MAX_HIT_RATIO) {
                plugin.flag(shooter, "BowAimbot",
                        hits + " of the last " + s.results.size() + " long-range shots hit", false);
                s.streak = 0;
                s.results.clear();
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        stats.remove(e.getPlayer().getUniqueId());
    }
}
