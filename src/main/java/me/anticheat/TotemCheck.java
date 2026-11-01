package me.anticheat;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * AutoTotem detection.
 *
 * When a totem pops, the offhand becomes empty. A human has to notice the pop and then
 * press the swap key or click the inventory, which takes a reaction time on top of their
 * ping. AutoTotem cheats put a new totem back almost instantly (limited only by ping).
 *
 * How it works: remember the tick of each pop, then watch the offhand every tick until a
 * totem appears again. That covers every way of equipping it (swap key, inventory click,
 * hotbar swap...) without having to listen for each one. If the totem is back faster than
 * ping + ~100ms, that's suspicious; three in a row triggers an alert.
 *
 * Fast humans who keep their inventory open and swap totems with a hotkey look almost identical to the
 * cheat, so this only ever ALERTS staff (and shows in /sus). It never holds a player or counts toward a ban.
 *
 * Limits: server ticks are 50ms steps, so timing is approximate, and a cheat configured
 * with a deliberate human-like delay can slip under this check.
 */
public class TotemCheck implements Listener {

    /** Time on top of the player's ping that even a very fast human needs to re-equip. */
    private static final int FAST_MS_ABOVE_PING = 100;
    /** Stop watching after 5 seconds (the player simply didn't re-equip). */
    private static final int GIVE_UP_TICKS = 100;

    private final AntiCheatPlugin plugin;
    private final Map<UUID, Integer> popTicks = new HashMap<>();

    public TotemCheck(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPop(EntityResurrectEvent e) {
        // ignoreCancelled: the event is fired cancelled when there was no totem to use.
        if (e.getEntity() instanceof Player player) {
            popTicks.put(player.getUniqueId(), Bukkit.getCurrentTick());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        popTicks.remove(e.getPlayer().getUniqueId());
    }

    /** Runs every tick: has anyone who popped a totem put a new one in their offhand yet? */
    private void tick() {
        if (popTicks.isEmpty()) return;

        int now = Bukkit.getCurrentTick();
        Iterator<Map.Entry<UUID, Integer>> it = popTicks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> entry = it.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            int elapsed = now - entry.getValue();

            if (player == null || player.isDead() || elapsed > GIVE_UP_TICKS) {
                it.remove();
                continue;
            }
            if (elapsed < 1) continue;   // same tick as the pop, the old totem is still being used up

            if (player.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING) {
                it.remove();
                evaluate(player, elapsed);
            }
        }
    }

    private void evaluate(Player player, int elapsedTicks) {
        PlayerData data = plugin.getData(player);
        int ping = Math.min(player.getPing(), 500);
        long elapsedMs = elapsedTicks * 50L;

        if (elapsedMs <= ping + FAST_MS_ABOVE_PING) {
            if (data.addBuffer("autototem", 1.0) > 2.5) {
                plugin.flag(player, "AutoTotem",
                        "totem back in offhand " + elapsedMs + "ms after pop (ping " + ping + "ms)", false);
            }
        } else {
            data.addBuffer("autototem", -0.5);
        }
    }
}
