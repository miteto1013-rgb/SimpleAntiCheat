package me.anticheat;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Anti-knockback / velocity cheat detection.
 *
 * When a player is hit, the server pushes them. The player's own game applies that push right away, so within
 * a few ticks they MUST visibly move in the direction they were pushed. Even someone sprinting straight into the
 * push still gets moved most of it on the first tick (they can only cancel about 0.13 blocks of it by walking
 * the other way). A velocity cheat throws the push away, so the player barely moves, or doesn't move at all.
 *
 * We note the push, watch the player's movement for ping + 6 ticks, and check that at least one tick of it went
 * in the push direction by close to the push strength. Pushes into a wall are skipped (they can't show up as
 * movement), and three misses in a row are needed before anything is flagged.
 */
public class KnockbackCheck implements Listener {

    /** Ignore weak pushes (blocks per tick); they're too small to tell apart from normal walking. */
    private static final double MIN_PUSH = 0.25;
    /** A player walking straight into the push can cancel at most this much of it on the first tick. */
    private static final double MAX_COUNTER = 0.2;

    private static class Track {
        final double dirX;
        final double dirZ;
        final double strength;
        final int endTick;
        double best = 0;   // the biggest single-tick move in the push direction seen so far

        Track(double dirX, double dirZ, double strength, int endTick) {
            this.dirX = dirX;
            this.dirZ = dirZ;
            this.strength = strength;
            this.endTick = endTick;
        }
    }

    private final AntiCheatPlugin plugin;
    private final Map<UUID, Track> tracks = new HashMap<>();

    public KnockbackCheck(AntiCheatPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent e) {
        Player player = e.getPlayer();
        Vector velocity = e.getVelocity();
        double strength = Math.hypot(velocity.getX(), velocity.getZ());
        if (strength < MIN_PUSH) return;

        // Situations where the push doesn't show up as normal movement.
        GameMode gm = player.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return;
        if (player.getAllowFlight() || player.isFlying() || player.isGliding() || player.isInsideVehicle()
                || player.isInWater() || player.isDead() || plugin.isLocked(player)) return;
        Material feet = player.getLocation().getBlock().getType();
        if (feet == Material.COBWEB || feet == Material.POWDER_SNOW || feet == Material.SWEET_BERRY_BUSH) return;

        // Pushed into a wall right in front of them? Then there's nowhere to go.
        Vector direction = new Vector(velocity.getX() / strength, 0, velocity.getZ() / strength);
        Location chest = player.getLocation().add(0, 0.9, 0);
        if (player.getWorld().rayTraceBlocks(chest, direction, 1.0, FluidCollisionMode.NEVER, true) != null) return;

        int tick = Bukkit.getCurrentTick();
        int ping = Math.min(player.getPing(), 400);
        int window = ping / 50 + 6;   // the push has to travel to them, they move, and the move travels back

        Track track = new Track(direction.getX(), direction.getZ(), strength, tick + window);
        tracks.put(player.getUniqueId(), track);
        Bukkit.getScheduler().runTaskLater(plugin, () -> evaluate(player, track), window + 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Track track = tracks.get(e.getPlayer().getUniqueId());
        if (track == null || Bukkit.getCurrentTick() > track.endTick) return;

        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;

        double projected = (to.getX() - from.getX()) * track.dirX + (to.getZ() - from.getZ()) * track.dirZ;
        if (projected > track.best) track.best = projected;
    }

    private void evaluate(Player player, Track track) {
        UUID id = player.getUniqueId();
        if (tracks.get(id) != track) return;   // a newer hit replaced this one
        tracks.remove(id);
        if (!player.isOnline() || player.isDead() || plugin.isLocked(player)) return;

        PlayerData data = plugin.getData(player);
        // A player who didn't move at all (no movement events) has best = 0, which is exactly what a cheat looks like.
        double needed = track.strength - MAX_COUNTER;
        if (track.best < needed) {
            if (data.addBuffer("antikb", 1.0) > 2.5) {
                plugin.flag(player, "AntiKnockback",
                        String.format("pushed %.2f blocks/tick but only moved %.2f", track.strength, track.best));
            }
        } else {
            data.addBuffer("antikb", -1.0);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        tracks.remove(e.getPlayer().getUniqueId());
    }
}
