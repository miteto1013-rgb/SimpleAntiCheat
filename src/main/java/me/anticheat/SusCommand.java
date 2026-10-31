package me.anticheat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /sus opens a menu of online players the anti-cheat has flagged recently (newest first).
 * Hover a head to see what they were flagged for, click it to teleport to them and check
 * whether they're really cheating or it was a false positive.
 */
public class SusCommand implements CommandExecutor, Listener {

    private static final int MAX_SLOTS = 54;

    private final AntiCheatPlugin plugin;

    public SusCommand(AntiCheatPlugin plugin) {
        this.plugin = plugin;
    }

    /** The menu itself; remembers which player each slot belongs to. */
    public static class Menu implements InventoryHolder {
        private Inventory inventory;
        private final Map<Integer, UUID> slots = new HashMap<>();

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static class Flagged {
        final Player player;
        final Map<String, PlayerData.FlagRecord> flags;
        final int lastTick;

        Flagged(Player player, Map<String, PlayerData.FlagRecord> flags) {
            this.player = player;
            this.flags = flags;
            int latest = 0;
            for (PlayerData.FlagRecord record : flags.values()) latest = Math.max(latest, record.lastTick);
            this.lastTick = latest;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player viewer)) {
            sender.sendMessage(Component.text("Only players can use /sus.", NamedTextColor.RED));
            return true;
        }
        if (!viewer.hasPermission("anticheat.admin")) {
            viewer.sendMessage(Component.text("You don't have permission to do that.", NamedTextColor.RED));
            return true;
        }

        int now = Bukkit.getCurrentTick();
        List<Flagged> flagged = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            Map<String, PlayerData.FlagRecord> flags =
                    plugin.getData(online).recentFlags(now, plugin.getFlagWindowTicks());
            if (!flags.isEmpty()) flagged.add(new Flagged(online, flags));
        }

        if (flagged.isEmpty()) {
            viewer.sendMessage(Component.text(
                    "Nobody has been flagged in the last " + plugin.getFlagWindowMinutes() + " minutes.",
                    NamedTextColor.GREEN));
            return true;
        }

        flagged.sort((a, b) -> Integer.compare(b.lastTick, a.lastTick));   // most recently flagged first
        int shown = Math.min(flagged.size(), MAX_SLOTS);
        int size = Math.max(9, ((shown + 8) / 9) * 9);

        Menu menu = new Menu();
        Inventory inventory = Bukkit.createInventory(menu, size, Component.text("Flagged players", NamedTextColor.DARK_RED));
        menu.inventory = inventory;

        for (int slot = 0; slot < shown; slot++) {
            Flagged entry = flagged.get(slot);
            inventory.setItem(slot, buildHead(entry, now));
            menu.slots.put(slot, entry.player.getUniqueId());
        }

        viewer.openInventory(inventory);
        return true;
    }

    private ItemStack buildHead(Flagged entry, int now) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setOwningPlayer(entry.player);
        meta.displayName(line(entry.player.getName(), NamedTextColor.YELLOW));

        List<Component> lore = new ArrayList<>();
        entry.flags.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().lastTick, a.getValue().lastTick))
                .limit(5)
                .forEach(flag -> {
                    PlayerData.FlagRecord record = flag.getValue();
                    lore.add(line(flag.getKey() + ": " + record.count + (record.count == 1 ? " flag, " : " flags, ")
                            + ago(now - record.lastTick), NamedTextColor.RED));
                    lore.add(line("  " + shorten(record.lastInfo, 44), NamedTextColor.DARK_GRAY));
                });

        Map<String, Integer> strikes = plugin.getStrikes(entry.player);
        if (!strikes.isEmpty()) {
            StringBuilder text = new StringBuilder("Strikes: ");
            strikes.forEach((check, count) -> text.append(check).append(' ')
                    .append(count).append('/').append(plugin.getStrikesToBan()).append("  "));
            lore.add(line(text.toString().trim(), NamedTextColor.GRAY));
        }

        lore.add(Component.empty());
        lore.add(line("Click to teleport to them", NamedTextColor.GREEN));
        meta.lore(lore);
        head.setItemMeta(meta);
        return head;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu menu)) return;
        e.setCancelled(true);   // nothing can be taken out of or put into this menu

        if (!(e.getWhoClicked() instanceof Player viewer)) return;
        if (e.getClickedInventory() == null || !e.getClickedInventory().equals(e.getInventory())) return;

        UUID id = menu.slots.get(e.getSlot());
        if (id == null) return;

        Player target = Bukkit.getPlayer(id);
        viewer.closeInventory();
        if (target == null || !target.isOnline()) {
            viewer.sendMessage(Component.text("That player has left the server.", NamedTextColor.RED));
            return;
        }

        viewer.teleport(target.getLocation());
        viewer.sendMessage(Component.text("Teleported to " + target.getName() + ". Use /sus to go back to the list.",
                NamedTextColor.GREEN));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }

    // ------------------------------------------------------------------

    /** Item text defaults to italic in Minecraft; turn that off. */
    private static Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private static String ago(int ticks) {
        int seconds = Math.max(0, ticks / 20);
        return seconds < 60 ? seconds + "s ago" : (seconds / 60) + "m ago";
    }

    private static String shorten(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
