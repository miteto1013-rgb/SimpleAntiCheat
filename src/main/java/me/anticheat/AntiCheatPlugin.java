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

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class AntiCheatPlugin extends JavaPlugin implements Listener {

    // ------------------------------------------------------------------
    // Holding flagged players in place
    // ------------------------------------------------------------------

    /**
     * When a player is flagged by a MOVEMENT check they can't move. The hold is renewed by every movement flag
     * and lets go LOCK_SECONDS after the last one. Combat checks, AutoTotem, crystals, anchors, bows and X-ray
     * never hold anyone. Set LOCK_ON_FLAG to false to turn the hold off completely.
     */
    private static final boolean LOCK_ON_FLAG = true;
    private static final int LOCK_SECONDS = 3;
    private static final Set<String> MOVEMENT_CHECKS =
            Set.of("Fly", "Speed", "Jesus", "Step", "Phase", "BoatFly", "BoatNoClip");

    // ------------------------------------------------------------------
    // Automatic bans. Designed so one glitch can never get anyone banned.
    // ------------------------------------------------------------------

    /** Master switch. false = alerts only: nobody is ever banned. */
    private static final boolean BAN_ENABLED = true;
    /** How long an automatic ban lasts. It lifts by itself; /pardon <name> in the console lifts it early. */
    private static final int BAN_DAYS = 7;
    /** A player is banned once they have this many strikes on the SAME check. */
    private static final int STRIKES_TO_BAN = 5;
    /** A check can give a player at most one strike per this many seconds. */
    private static final int STRIKE_SPACING_SECONDS = 15;
    /** Strikes (and the /sus list) look back this far. Older ones are forgotten. */
    private static final int STRIKE_WINDOW_MINUTES = 10;
    /** Operators are never banned, so you can test cheats on yourself. */
    private static final boolean BAN_OPERATORS = false;

    private static final int STRIKE_WINDOW_TICKS = STRIKE_WINDOW_MINUTES * 60 * 20;

    private final Map<UUID, PlayerData> data = new HashMap<>();
    /** Players who ran /ac alerts to mute their alerts (resets on server restart). */
    private final Set<UUID> alertsOff = new HashSet<>();
    /** Players we've already sent a ban command for. */
    private final Set<UUID> bannedNow = new HashSet<>();

    private MovementLock lock;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new MovementCheck(this), this);
        getServer().getPluginManager().registerEvents(new CombatCheck(this), this);
        getServer().getPluginManager().registerEvents(new VehicleCheck(this), this);
        getServer().getPluginManager().registerEvents(new TotemCheck(this), this);
        getServer().getPluginManager().registerEvents(new XrayCheck(this), this);
        getServer().getPluginManager().registerEvents(new CrystalCheck(this), this);
        getServer().getPluginManager().registerEvents(new BowCheck(this), this);

        lock = new MovementLock(this);
        getServer().getPluginManager().registerEvents(lock, this);

        PluginCommand acCommand = getCommand("ac");
        if (acCommand != null) {
            ACCommand handler = new ACCommand(this);
            acCommand.setExecutor(handler);
            acCommand.setTabCompleter(handler);
        }

        SusCommand sus = new SusCommand(this);
        getServer().getPluginManager().registerEvents(sus, this);
        PluginCommand susCommand = getCommand("sus");
        if (susCommand != null) {
            susCommand.setExecutor(sus);
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

    /** A player's active strikes per check, for /ac strikes and /sus. */
    public Map<String, Integer> getStrikes(Player player) {
        return getData(player).currentStrikes(Bukkit.getCurrentTick(), STRIKE_WINDOW_TICKS);
    }

    public boolean isLocked(Player player) {
        return lock != null && lock.isLocked(player);
    }

    public int getStrikesToBan() {
        return STRIKES_TO_BAN;
    }

    public int getFlagWindowTicks() {
        return STRIKE_WINDOW_TICKS;
    }

    public int getFlagWindowMinutes() {
        return STRIKE_WINDOW_MINUTES;
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

    /** Called by the checks whenever a player fails one. Alerts staff, holds the player, counts a strike. */
    public void flag(Player player, String check, String info) {
        flag(player, check, info, true);
    }

    /**
     * Same, but takeAction = false makes it an alert only: no hold and no strike toward a ban
     * (used for statistical checks like X-ray, which are hints for staff and never proof).
     */
    public void flag(Player player, String check, String info, boolean takeAction) {
        PlayerData playerData = getData(player);
        int vl = playerData.addViolation(check);
        int tick = Bukkit.getCurrentTick();

        if (LOCK_ON_FLAG && takeAction && MOVEMENT_CHECKS.contains(check)) {
            lock.lock(player, LOCK_SECONDS);
        }

        int strikes = 0;
        boolean banRuleApplies = BAN_ENABLED && takeAction;
        if (banRuleApplies) {
            strikes = playerData.addStrike(check, tick, STRIKE_SPACING_SECONDS * 20, STRIKE_WINDOW_TICKS);
        }

        if (playerData.canAlert(check, tick, 10)) {
            int count = playerData.recordFlag(check, info, tick);   // what /sus shows

            // The console keeps the full details; players only see name, flag and how many times.
            String strikeText = banRuleApplies ? " | strike " + strikes + "/" + STRIKES_TO_BAN : "";
            getLogger().info(player.getName() + " failed " + check + " x" + count + " (" + info + ") VL " + vl + strikeText);

            Component message = Component.text("[AC] ", NamedTextColor.RED)
                    .append(Component.text(player.getName(), NamedTextColor.WHITE))
                    .append(Component.text(" - ", NamedTextColor.GRAY))
                    .append(Component.text(check, NamedTextColor.RED))
                    .append(Component.text(" x" + count, NamedTextColor.GRAY));

            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.isOp() && !alertsOff.contains(online.getUniqueId())) {
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
        getLogger().warning("Banning " + player.getName() + " for " + BAN_DAYS + " days - " + reason + " - " + info);

        Component notice = Component.text("[AC] ", NamedTextColor.RED)
                .append(Component.text(player.getName() + " was banned for " + BAN_DAYS + " days (" + check + ").",
                        NamedTextColor.GOLD));
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.isOp() && !alertsOff.contains(online.getUniqueId())) {
                online.sendMessage(notice);
            }
        }

        // Run on the next tick so we aren't kicking someone in the middle of an event.
        Bukkit.getScheduler().runTask(this, () -> {
            // Puts them on the server's ban list with an expiry date, so the ban lifts by itself.
            player.ban(reason, Duration.ofDays(BAN_DAYS), "SimpleAntiCheat");
            if (player.isOnline()) {
                player.kick(Component.text("You were banned for cheating (" + check + ") for " + BAN_DAYS + " days.",
                        NamedTextColor.RED));
            }
        });
    }
}
