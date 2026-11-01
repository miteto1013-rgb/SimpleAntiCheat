package me.anticheat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Holds a player in place after a MOVEMENT check flags them. Every movement flag renews the hold, and it
 * lets go a few seconds after the LAST one. Since a held player can't move, movement flags stop coming, so
 * the hold runs out by itself.
 *
 * Safety nets so nobody can ever get stuck:
 *  - a hold can be renewed for at most 8 seconds in a row
 *  - after a hold ends there are 2 seconds in which nobody can be held again
 *  - when a hold ends, everything we remembered about how the player was moving is wiped, because while
 *    they were held their position was frozen but their game kept falling, so the old numbers are stale
 */
public class MovementLock implements Listener {

    private static final int MAX_HOLD_TICKS = 20 * 8;
    private static final int COOLDOWN_TICKS = 20 * 2;

    private final AntiCheatPlugin plugin;
    /** Player -> server tick at which the hold ends. */
    private final Map<UUID, Integer> lockedUntil = new HashMap<>();
    /** Player -> when their current hold started (to cap how long it can be renewed). */
    private final Map<UUID, Integer> holdStart = new HashMap<>();
    /** Player -> they can't be held again before this tick. */
    private final Map<UUID, Integer> cooldownUntil = new HashMap<>();

    public MovementLock(AntiCheatPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isLocked(Player player) {
        Integer until = lockedUntil.get(player.getUniqueId());
        return until != null && Bukkit.getCurrentTick() < until;
    }

    public void lock(Player player, int seconds) {
        UUID id = player.getUniqueId();
        int now = Bukkit.getCurrentTick();
        boolean alreadyLocked = isLocked(player);

        if (!alreadyLocked) {
            Integer cooldown = cooldownUntil.get(id);
            if (cooldown != null && now < cooldown) return;   // only just released: give them a moment
            holdStart.put(id, now);
            player.sendMessage(Component.text(
                    "[AC] You were stopped by the anti-cheat. You can move again in a moment.", NamedTextColor.RED));
        } else if (now - holdStart.getOrDefault(id, now) > MAX_HOLD_TICKS) {
            return;   // held long enough already: let it run out
        }

        lockedUntil.put(id, now + seconds * 20);

        // Someone in a boat would just keep being carried, so take them out of it.
        if (player.isInsideVehicle()) {
            Bukkit.getScheduler().runTask(plugin, player::leaveVehicle);
        }

        // Release once the time is up (unless another flag renewed the hold in the meantime).
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Integer until = lockedUntil.get(id);
            if (until != null && Bukkit.getCurrentTick() >= until) {
                lockedUntil.remove(id);
                holdStart.remove(id);
                cooldownUntil.put(id, Bukkit.getCurrentTick() + COOLDOWN_TICKS);
                plugin.getData(player).resetMovementBaseline();
                if (player.isOnline()) {
                    player.sendMessage(Component.text("[AC] You can move again.", NamedTextColor.GREEN));
                }
            }
        }, seconds * 20L + 1);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!isLocked(e.getPlayer())) return;
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;

        // Keep the new head rotation, but put the position back where it was.
        to.setX(from.getX());
        to.setY(from.getY());
        to.setZ(from.getZ());
        e.setTo(to);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent e) {
        if (e.getEntered() instanceof Player player && isLocked(player)) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        lockedUntil.remove(id);
        holdStart.remove(id);
        cooldownUntil.remove(id);
    }
}
