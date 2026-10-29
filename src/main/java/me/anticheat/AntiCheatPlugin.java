package me.anticheat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class AntiCheatPlugin extends JavaPlugin implements Listener {

    /** While testing: the flagged player also sees their own alerts, even without the permission. */
    private static final boolean ALERT_FLAGGED_PLAYER = true;

    private final Map<UUID, PlayerData> data = new HashMap<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new MovementCheck(this), this);
        getServer().getPluginManager().registerEvents(new CombatCheck(this), this);

        // Every minute, knock 1 off every violation level so old flags fade away.
        getServer().getScheduler().runTaskTimer(this,
                () -> data.values().forEach(PlayerData::decayViolations),
                20L * 60, 20L * 60);
    }

    public PlayerData getData(Player player) {
        return data.computeIfAbsent(player.getUniqueId(), id -> new PlayerData());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        data.remove(event.getPlayer().getUniqueId());
    }

    /** Called by the checks whenever a player fails one. Currently only alerts, no punishment. */
    public void flag(Player player, String check, String info) {
        PlayerData playerData = getData(player);
        int vl = playerData.addViolation(check);

        if (!playerData.canAlert(check, Bukkit.getCurrentTick(), 10)) return;

        String plain = player.getName() + " failed " + check + " (" + info + ") VL " + vl;
        getLogger().info(plain);

        Component message = Component.text("[AC] ", NamedTextColor.RED)
                .append(Component.text(player.getName(), NamedTextColor.WHITE))
                .append(Component.text(" failed ", NamedTextColor.GRAY))
                .append(Component.text(check, NamedTextColor.RED))
                .append(Component.text(" (" + info + ") VL " + vl, NamedTextColor.GRAY));

        for (Player online : Bukkit.getOnlinePlayers()) {
            boolean isStaff = online.hasPermission("anticheat.alerts");
            boolean isSelf = ALERT_FLAGGED_PLAYER && online.equals(player);
            if (isStaff || isSelf) {
                online.sendMessage(message);
            }
        }
    }
}
