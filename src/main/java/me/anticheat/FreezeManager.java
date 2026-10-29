package me.anticheat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Temporarily freezes players. A frozen player can still look around, but can't move,
 * hit anything, break blocks or use items. The freeze wears off by itself, so a false
 * positive can never lock someone out permanently.
 */
public class FreezeManager implements Listener {

    private final AntiCheatPlugin plugin;
    /** Player -> server tick at which their freeze ends. */
    private final Map<UUID, Integer> frozenUntil = new HashMap<>();

    public FreezeManager(AntiCheatPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isFrozen(Player player) {
        Integer until = frozenUntil.get(player.getUniqueId());
        return until != null && Bukkit.getCurrentTick() < until;
    }

    /** Freezes the player for the given time. Freezing someone who is already frozen just extends it. */
    public void freeze(Player player, int seconds) {
        boolean alreadyFrozen = isFrozen(player);
        frozenUntil.put(player.getUniqueId(), Bukkit.getCurrentTick() + seconds * 20);

        if (!alreadyFrozen) {
            player.sendMessage(Component.text(
                    "You have been frozen by the anti-cheat for " + seconds + " seconds.", NamedTextColor.RED));
        }

        // Someone in a boat would just keep being carried, so take them out of it.
        if (player.isInsideVehicle()) {
            Bukkit.getScheduler().runTask(plugin, player::leaveVehicle);
        }

        // When the time is up, remove the freeze (unless it was extended in the meantime).
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Integer until = frozenUntil.get(player.getUniqueId());
            if (until != null && Bukkit.getCurrentTick() >= until) {
                frozenUntil.remove(player.getUniqueId());
                if (player.isOnline()) {
                    player.sendMessage(Component.text("You have been unfrozen.", NamedTextColor.GREEN));
                }
            }
        }, seconds * 20L + 1);
    }

    // ------------------------------------------------------------------
    // What a frozen player can't do
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!isFrozen(e.getPlayer())) return;
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
    public void onAttack(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player player && isFrozen(player)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (isFrozen(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (isFrozen(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        frozenUntil.remove(e.getPlayer().getUniqueId());
    }
}
