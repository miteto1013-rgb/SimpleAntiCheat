package me.anticheat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class AntiCheatPlugin extends JavaPlugin implements Listener {

    /** While testing: the flagged player also sees their own alerts, even without the permission. */
    private static final boolean ALERT_FLAGGED_PLAYER = true;

    // ------------------------------------------------------------------
    // Automatic bans. Designed so one glitch can never get anyone banned.
    // ------------------------------------------------------------------

    /** Master switch. Set to false for alerts only: nobody is ever banned. */
    private static final boolean BAN_ENABLED = true;
    /** A player is banned once they have this many strikes on the SAME check. */
    private static final int STRIKES_TO_BAN = 5;
    /** A check can give a player at most one strike per this many seconds. */
    private static final int STRIKE_SPACING_SECONDS = 15;
    /** Strikes older than this are forgotten. */
    private static final int STRIKE_WINDOW_MINUTES = 10;
    /** Operators are never banned, so you can test cheats on yourself. */
    private static final boolean BAN_OPERATORS = false;

    private static final int STRIKE_WINDOW_TICKS = STRIKE_WINDOW_MINUTES * 60 * 20;

    private final Map<UUID, PlayerData> data = new HashMap<>();
    /** Players who ran /ac alerts to mute their alerts (resets on server restart). */
    private final Set<UUID> alertsOff = new HashSet<>();
    /** Players we've already sent a ban command for. */
    private final Set<UUID> bannedNow = new HashSet<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new MovementCheck(this), this);
        getServer().getPluginManager().registerEvents(new CombatCheck(this), this);
        getServer().getPluginManager().registerEvents(new VehicleCheck(this), this);
        getServer().getPluginManager().registerEvents(new TotemCheck(this), this);
        getServer().getPluginManager().registerEvents(new XrayCheck(this), this);

        PluginCommand command = getCommand("ac");
        if (command != null) {
            ACCommand handler = new ACCommand(this);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }

        // Every minute: knock 1 off every violation level, and forget players who left with no strikes.
        getServer().getScheduler().runTaskTimer(this, () -> {
            data.values().forEach(PlayerData::decayViolations);
            int now = Bukkit.getCurrentTick();
            data.entrySet().removeIf(entry -> Bukkit.getPlayer(entry.getKey()) == null
                    && entry.getValue().currentStrikes(now, STRIKE_WINDOW_TICKS).isEmpty());
        }, 20L * 60, 20L * 60);
    }

    public PlayerData getData(Player player) {
        return data.computeIfAbsent(player.getUniqueId(), id -> new PlayerData());
    }

    /** A player's active strikes per check, for /ac strikes. */
    public Map<String, Integer> getStrikes(Player player) {
        return getData(player).currentStrikes(Bukkit.getCurrentTick(), STRIKE_WINDOW_TICKS);
    }

    public int getStrikesToBan() {
        return STRIKES_TO_BAN;
    }

    /** Turns this player's alerts off or on. Returns true if alerts are now ON. */
    public boolean toggleAlerts(Player player) {
        if (alertsOff.remove(player.getUniqueId())) return true;
        alertsOff.add(player.getUniqueId());
        return false;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        bannedNow.remove(id);
        PlayerData playerData = data.get(id);
        // Keep players who still have strikes, so logging out and back in doesn't wipe them.
        if (playerData != null && playerData.currentStrikes(Bukkit.getCurrentTick(), STRIKE_WINDOW_TICKS).isEmpty()) {
            data.remove(id);
        }
    }

    /** Called by the checks whenever a player fails one. Alerts staff and counts a strike toward a ban. */
    public void flag(Player player, String check, String info) {
        flag(player, check, info, true);
    }

    /**
     * Same, but countsTowardBan = false makes it an alert only (used for statistical checks like X-ray,
     * which are hints for staff and never a reason to ban automatically).
     */
    public void flag(Player player, String check, String info, boolean countsTowardBan) {
        PlayerData playerData = getData(player);
        int vl = playerData.addViolation(check);
        int tick = Bukkit.getCurrentTick();

        int strikes = 0;
        boolean banRuleApplies = BAN_ENABLED && countsTowardBan;
        if (banRuleApplies) {
            strikes = playerData.addStrike(check, tick, STRIKE_SPACING_SECONDS * 20, STRIKE_WINDOW_TICKS);
        }

        if (playerData.canAlert(check, tick, 10)) {
            String strikeText = banRuleApplies ? " | strike " + strikes + "/" + STRIKES_TO_BAN : "";
            getLogger().info(player.getName() + " failed " + check + " (" + info + ") VL " + vl + strikeText);

            Component message = Component.text("[AC] ", NamedTextColor.RED)
                    .append(Component.text(player.getName(), NamedTextColor.WHITE))
                    .append(Component.text(" failed ", NamedTextColor.GRAY))
                    .append(Component.text(check, NamedTextColor.RED))
                    .append(Component.text(" (" + info + ") VL " + vl + strikeText, NamedTextColor.GRAY));

            for (Player online : Bukkit.getOnlinePlayers()) {
                if (alertsOff.contains(online.getUniqueId())) continue;
                boolean isStaff = online.hasPermission("anticheat.alerts");
                boolean isSelf = ALERT_FLAGGED_PLAYER && online.equals(player);
                if (isStaff || isSelf) {
                    online.sendMessage(message);
                }
            }
        }

        if (banRuleApplies && strikes >= STRIKES_TO_BAN) {
            if (player.isOp() && !BAN_OPERATORS) return;   // operators are never banned
            ban(player, check, info);
        }
    }

    private void ban(Player player, String check, String info) {
        if (!bannedNow.add(player.getUniqueId())) return;   // already being banned

        String reason = "Cheating detected (" + check + ")";
        getLogger().warning("Banning " + player.getName() + " - " + reason + " - " + info);

        Component notice = Component.text("[AC] ", NamedTextColor.RED)
                .append(Component.text(player.getName() + " was banned for " + check + ".", NamedTextColor.GOLD));
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("anticheat.alerts") && !alertsOff.contains(online.getUniqueId())) {
                online.sendMessage(notice);
            }
        }

        // Use the server's normal ban command (works the same on every Paper version).
        // Run it on the next tick so we aren't kicking someone in the middle of an event.
        String command = "ban " + player.getName() + " " + reason;
        Bukkit.getScheduler().runTask(this, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
    }
}
