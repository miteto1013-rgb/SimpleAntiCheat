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

    /** Freeze players when they flag: they can still look around, but can't move, hit or interact. */
    private static final boolean FREEZE_ON_FLAG = true;
    private static final int FREEZE_SECONDS = 5;

    private FreezeManager freeze;

    private final Map<UUID, PlayerData> data = new HashMap<>();
    /** Players who ran /ac alerts to mute their alerts (resets on server restart). */
    private final Set<UUID> alertsOff = new HashSet<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new MovementCheck(this), this);
        getServer().getPluginManager().registerEvents(new CombatCheck(this), this);
        getServer().getPluginManager().registerEvents(new VehicleCheck(this), this);
        getServer().getPluginManager().registerEvents(new TotemCheck(this), this);
        getServer().getPluginManager().registerEvents(new XrayCheck(this), this);

        freeze = new FreezeManager(this);
        getServer().getPluginManager().registerEvents(freeze, this);

        PluginCommand command = getCommand("ac");
        if (command != null) {
            ACCommand handler = new ACCommand(this);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }

        // Every minute, knock 1 off every violation level so old flags fade away.
        getServer().getScheduler().runTaskTimer(this,
                () -> data.values().forEach(PlayerData::decayViolations),
                20L * 60, 20L * 60);
    }

    public PlayerData getData(Player player) {
        return data.computeIfAbsent(player.getUniqueId(), id -> new PlayerData());
    }

    public FreezeManager getFreezeManager() {
        return freeze;
    }

    /** Turns this player's alerts off or on. Returns true if alerts are now ON. */
    public boolean toggleAlerts(Player player) {
        if (alertsOff.remove(player.getUniqueId())) return true;
        alertsOff.add(player.getUniqueId());
        return false;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        data.remove(event.getPlayer().getUniqueId());
    }

    /** Called by the checks whenever a player fails one. Alerts staff and freezes the player. */
    public void flag(Player player, String check, String info) {
        flag(player, check, info, FREEZE_ON_FLAG);
    }

    /** Same, but the caller decides about freezing (statistical checks like X-ray shouldn't freeze). */
    public void flag(Player player, String check, String info, boolean freezePlayer) {
        PlayerData playerData = getData(player);
        int vl = playerData.addViolation(check);

        if (freezePlayer) freeze.freeze(player, FREEZE_SECONDS);

        if (!playerData.canAlert(check, Bukkit.getCurrentTick(), 10)) return;

        String plain = player.getName() + " failed " + check + " (" + info + ") VL " + vl;
        getLogger().info(plain);

        Component message = Component.text("[AC] ", NamedTextColor.RED)
                .append(Component.text(player.getName(), NamedTextColor.WHITE))
                .append(Component.text(" failed ", NamedTextColor.GRAY))
                .append(Component.text(check, NamedTextColor.RED))
                .append(Component.text(" (" + info + ") VL " + vl, NamedTextColor.GRAY));

        for (Player online : Bukkit.getOnlinePlayers()) {
            if (alertsOff.contains(online.getUniqueId())) continue;
            boolean isStaff = online.hasPermission("anticheat.alerts");
            boolean isSelf = ALERT_FLAGGED_PLAYER && online.equals(player);
            if (isStaff || isSelf) {
                online.sendMessage(message);
            }
        }
    }
}
