package me.anticheat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * /ac alerts            - turn your own anti-cheat alerts off/on
 * /ac strikes <player>  - see a player's active strikes (5 on the same check = ban)
 */
public class ACCommand implements CommandExecutor, TabCompleter {

    private final AntiCheatPlugin plugin;

    public ACCommand(AntiCheatPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "alerts" -> toggleAlerts(sender);
            case "strikes" -> strikes(sender, args);
            default -> sendHelp(sender);
        }
        return true;
    }

    private void toggleAlerts(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            send(sender, "Only players can toggle alerts.", NamedTextColor.RED);
            return;
        }
        if (!player.isOp()) {
            send(sender, "Only operators can see anti-cheat alerts.", NamedTextColor.RED);
            return;
        }
        boolean nowOn = plugin.toggleAlerts(player);
        send(sender, "Anti-cheat alerts are now " + (nowOn ? "ON" : "OFF") + ".",
                nowOn ? NamedTextColor.GREEN : NamedTextColor.YELLOW);
    }

    private void strikes(CommandSender sender, String[] args) {
        if (!sender.hasPermission("anticheat.admin")) {
            send(sender, "You don't have permission to do that.", NamedTextColor.RED);
            return;
        }
        if (args.length < 2) {
            send(sender, "Usage: /ac strikes <player>", NamedTextColor.RED);
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            send(sender, "Player '" + args[1] + "' is not online.", NamedTextColor.RED);
            return;
        }

        Map<String, Integer> strikes = plugin.getStrikes(target);
        if (strikes.isEmpty()) {
            send(sender, target.getName() + " has no active strikes.", NamedTextColor.GREEN);
            return;
        }
        send(sender, target.getName() + "'s active strikes (" + plugin.getStrikesToBan() + " on one check = ban):",
                NamedTextColor.GOLD);
        strikes.forEach((check, count) ->
                send(sender, " - " + check + ": " + count + "/" + plugin.getStrikesToBan(), NamedTextColor.GRAY));
    }

    private void sendHelp(CommandSender sender) {
        send(sender, "SimpleAntiCheat commands:", NamedTextColor.GOLD);
        send(sender, "/ac alerts - turn your alerts off/on", NamedTextColor.GRAY);
        send(sender, "/ac strikes <player> - see a player's strikes", NamedTextColor.GRAY);
    }

    private void send(CommandSender sender, String text, NamedTextColor color) {
        sender.sendMessage(Component.text(text, color));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();

        if (args.length == 1) {
            if (sender.isOp()) options.add("alerts");
            if (sender.hasPermission("anticheat.admin")) options.add("strikes");
        } else if (args.length == 2 && sender.hasPermission("anticheat.admin")
                && args[0].equalsIgnoreCase("strikes")) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                options.add(online.getName());
            }
        }

        String typed = args[args.length - 1].toLowerCase();
        options.removeIf(option -> !option.toLowerCase().startsWith(typed));
        return options;
    }
}
