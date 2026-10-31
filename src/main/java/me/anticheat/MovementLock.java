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
 * Holds a flagged player in place. Every flag renews the lock, and it lets go a few seconds after the
 * LAST flag. Since a locked player can't move, movement flags stop coming, so the lock runs out by itself.
 * A cheater who starts again after being released just gets flagged and held again.
 */
public class MovementLock implements Listener {

    private final AntiCheatPlugin plugin;
    /** Player -> server tick at which the lock ends. */
    private final Map<UUID, Integer> lockedUntil = new HashMap<>();

    public MovementLock(AntiCheatPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isLocked(Player player) {
        Integer until = lockedUntil.get(player.getUniqueId());
        return until != null && Bukkit.getCurrentTick() < until;
    }

    public void lock(Player player, int seconds) {
        boolean alreadyLocked = isLocked(player);
        lockedUntil.put(player.getUniqueId(), Bukkit.getCurrentTick() + seconds * 20);

        if (!alreadyLocked) {
            player.sendMessage(Component.text(
                    "[AC] You were stopped by the anti-cheat. You can move again in a moment.", NamedTextColor.RED));
        }

        // Someone in a boat would just keep being carried, so take them out of it.
        if (player.isInsideVehicle()) {
            Bukkit.getScheduler().runTask(plugin, player::leaveVehicle);
        }

        // Release once the time is up (unless another flag renewed the lock in the meantime).
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Integer until = lockedUntil.get(player.getUniqueId());
            if (until != null && Bukkit.getCurrentTick() >= until) {
                lockedUntil.remove(player.getUniqueId());
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
        lockedUntil.remove(e.getPlayer().getUniqueId());
    }
}
