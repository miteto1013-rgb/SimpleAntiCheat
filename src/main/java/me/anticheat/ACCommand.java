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

/**
 * /ac alerts                      - turn your own anti-cheat alerts off/on
 * /ac freeze <player> [seconds]   - freeze a player (default 30s, max 600s)
 * /ac unfreeze <player>           - end a freeze early
 */
public class ACCommand implements CommandExecutor, TabCompleter {

    private static final int DEFAULT_FREEZE_SECONDS = 30;
    private static final int MAX_FREEZE_SECONDS = 600;

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
            case "freeze" -> freeze(sender, args);
            case "unfreeze" -> unfreeze(sender, args);
            default -> sendHelp(sender);
        }
        return true;
    }

    private void toggleAlerts(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            send(sender, "Only players can toggle alerts.", NamedTextColor.RED);
            return;
        }
        if (!player.hasPermission("anticheat.alerts")) {
            send(sender, "You don't have permission to do that.", NamedTextColor.RED);
            return;
        }
        boolean nowOn = plugin.toggleAlerts(player);
        send(sender, "Anti-cheat alerts are now " + (nowOn ? "ON" : "OFF") + ".",
                nowOn ? NamedTextColor.GREEN : NamedTextColor.YELLOW);
    }

    private void freeze(CommandSender sender, String[] args) {
        if (!sender.hasPermission("anticheat.admin")) {
            send(sender, "You don't have permission to do that.", NamedTextColor.RED);
            return;
        }
        if (args.length < 2) {
            send(sender, "Usage: /ac freeze <player> [seconds]", NamedTextColor.RED);
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            send(sender, "Player '" + args[1] + "' is not online.", NamedTextColor.RED);
            return;
        }

        int seconds = DEFAULT_FREEZE_SECONDS;
        if (args.length >= 3) {
            try {
                seconds = Integer.parseInt(args[2]);
            } catch (NumberFormatException ex) {
                send(sender, "'" + args[2] + "' is not a number of seconds.", NamedTextColor.RED);
                return;
            }
        }
        seconds = Math.max(1, Math.min(seconds, MAX_FREEZE_SECONDS));

        plugin.getFreezeManager().freeze(target, seconds);
        send(sender, "Froze " + target.getName() + " for " + seconds + " seconds.", NamedTextColor.GREEN);
    }

    private void unfreeze(CommandSender sender, String[] args) {
        if (!sender.hasPermission("anticheat.admin")) {
            send(sender, "You don't have permission to do that.", NamedTextColor.RED);
            return;
        }
        if (args.length < 2) {
            send(sender, "Usage: /ac unfreeze <player>", NamedTextColor.RED);
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            send(sender, "Player '" + args[1] + "' is not online.", NamedTextColor.RED);
            return;
        }

        if (plugin.getFreezeManager().unfreeze(target)) {
            send(sender, "Unfroze " + target.getName() + ".", NamedTextColor.GREEN);
        } else {
            send(sender, target.getName() + " isn't frozen.", NamedTextColor.YELLOW);
        }
    }

    private void sendHelp(CommandSender sender) {
        send(sender, "SimpleAntiCheat commands:", NamedTextColor.GOLD);
        send(sender, "/ac alerts - turn your alerts off/on", NamedTextColor.GRAY);
        send(sender, "/ac freeze <player> [seconds] - freeze a player", NamedTextColor.GRAY);
        send(sender, "/ac unfreeze <player> - unfreeze a player", NamedTextColor.GRAY);
    }

    private void send(CommandSender sender, String text, NamedTextColor color) {
        sender.sendMessage(Component.text(text, color));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();

        if (args.length == 1) {
            if (sender.hasPermission("anticheat.alerts")) options.add("alerts");
            if (sender.hasPermission("anticheat.admin")) {
                options.add("freeze");
                options.add("unfreeze");
            }
        } else if (args.length == 2 && sender.hasPermission("anticheat.admin")
                && (args[0].equalsIgnoreCase("freeze") || args[0].equalsIgnoreCase("unfreeze"))) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                options.add(online.getName());
            }
        }

        String typed = args[args.length - 1].toLowerCase();
        options.removeIf(option -> !option.toLowerCase().startsWith(typed));
        return options;
    }
}
