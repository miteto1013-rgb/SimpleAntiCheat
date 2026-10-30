package me.anticheat;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * X-ray detection by statistics. Someone who can see ores through walls digs straight to them,
 * so they hit rare ores far more often per block of stone mined than a normal miner does.
 *
 * It counts ordinary stone mined versus rare-ore "finds" (diamond, emerald, ancient debris).
 * Several blocks of the same vein mined within seconds count as ONE find, so mining a big vein
 * isn't suspicious. Counts fade over time (halved every 5 minutes), so only recent mining matters.
 *
 * This is a hint for staff to look at, not proof, so it only sends an alert and never freezes anyone.
 * The real protection against x-ray is Paper's built-in anti-xray setting (see the chat notes).
 */
public class XrayCheck implements Listener {

    private static final double MIN_STONE = 100;          // need this much ordinary mining before judging
    private static final int MIN_FINDS = 3;               // and at least this many separate ore finds
    private static final double MAX_FIND_RATIO = 0.035;   // finds / (finds + stone). Normal mining is well under 1%.
    private static final int ALERT_COOLDOWN_TICKS = 20 * 60 * 5;

    private static class Stats {
        double stone;
        double finds;
        Location lastRare;
        int lastRareTick = -100000;
        int lastAlertTick = -100000;
    }

    private final AntiCheatPlugin plugin;
    private final Map<UUID, Stats> stats = new HashMap<>();

    public XrayCheck(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        // Every 5 minutes halve everyone's counts, so old mining fades out.
        Bukkit.getScheduler().runTaskTimer(plugin, () -> stats.values().forEach(s -> {
            s.stone *= 0.5;
            s.finds *= 0.5;
        }), 20L * 60 * 5, 20L * 60 * 5);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player player = e.getPlayer();
        if (player.getGameMode() != GameMode.SURVIVAL) return;

        Material type = e.getBlock().getType();
        boolean rareOre = Tag.DIAMOND_ORES.isTagged(type) || Tag.EMERALD_ORES.isTagged(type)
                || type == Material.ANCIENT_DEBRIS;
        boolean stone = Tag.BASE_STONE_OVERWORLD.isTagged(type) || Tag.BASE_STONE_NETHER.isTagged(type);
        if (!rareOre && !stone) return;

        Stats s = stats.computeIfAbsent(player.getUniqueId(), id -> new Stats());
        int tick = Bukkit.getCurrentTick();

        if (stone) {
            s.stone++;
            return;
        }

        // Rare ore. Another block of the same vein (close by, within 15 seconds) isn't a new find.
        Location loc = e.getBlock().getLocation();
        boolean sameVein = s.lastRare != null
                && s.lastRare.getWorld().equals(loc.getWorld())
                && tick - s.lastRareTick <= 20 * 15
                && s.lastRare.distanceSquared(loc) <= 6 * 6;
        s.lastRare = loc;
        s.lastRareTick = tick;
        if (sameVein) return;

        s.finds++;

        double ratio = s.finds / (s.finds + s.stone);
        if (s.stone >= MIN_STONE && s.finds >= MIN_FINDS && ratio > MAX_FIND_RATIO
                && tick - s.lastAlertTick > ALERT_COOLDOWN_TICKS) {
            s.lastAlertTick = tick;
            // false = alert staff only, don't freeze: this is a statistical hint, not proof
            plugin.flag(player, "Xray",
                    String.format("%.0f ore finds vs %.0f stone mined recently (%.1f%%)",
                            s.finds, s.stone, ratio * 100),
                    false);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        stats.remove(e.getPlayer().getUniqueId());
    }
}
