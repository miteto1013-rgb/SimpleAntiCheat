package me.anticheat;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.RespawnAnchor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Crystal aura / anchor aura protection. Besides flagging, these checks BLOCK the action once a player
 * has clearly crossed the line, so the cheat stops working instead of just being logged.
 *
 * Crystal aura (auto place + auto explode end crystals):
 *  - CrystalAura:  a crystal can't be placed and broken faster than a person can see it appear and react.
 *                  The player's own ping is added, since they have to receive the crystal before hitting it.
 *                  Breaking it in the same tick is only possible by guessing its entity ID.
 *  - CrystalSpam:  more than 15 crystals placed or broken in one second.
 *  - CrystalReach: placing a crystal on a block further away than you can reach.
 *
 * Anchor aura (auto place, charge and explode respawn anchors):
 *  - AnchorAura:   an anchor can't be placed, charged with glowstone and set off within a few ticks (that
 *                  takes several hotbar swaps), and anchors can't be clicked more than 14 times a second.
 *
 * Every check uses a buffer, so a single odd event never flags anyone.
 */
public class CrystalCheck implements Listener {

    private static final int RATE_WINDOW_TICKS = 20;
    private static final int MAX_CRYSTAL_PLACES = 15;
    private static final int MAX_CRYSTAL_BREAKS = 15;
    private static final int MAX_ANCHOR_USES = 14;
    private static final double MAX_PLACE_REACH = 5.2;     // vanilla block reach is 4.5
    private static final int ANCHOR_MIN_TICKS = 4;         // place -> detonate in under this many ticks is impossible by hand

    private static class Placed {
        final UUID placer;
        final int tick;

        Placed(UUID placer, int tick) {
            this.placer = placer;
            this.tick = tick;
        }
    }

    private static class Pending {
        final int tick;
        final Location block;

        Pending(int tick, Location block) {
            this.tick = tick;
            this.block = block;
        }
    }

    private static class AnchorPlaced {
        final Location where;
        final int tick;

        AnchorPlaced(Location where, int tick) {
            this.where = where;
            this.tick = tick;
        }
    }

    private final AntiCheatPlugin plugin;
    /** Crystal entity -> who placed it and when. */
    private final Map<UUID, Placed> crystals = new HashMap<>();
    /** Player -> the crystal placement they just attempted (matched to the crystal that spawns). */
    private final Map<UUID, Pending> pendingPlace = new HashMap<>();
    /** Player -> the last anchor they placed. */
    private final Map<UUID, AnchorPlaced> anchors = new HashMap<>();
    private final Map<UUID, Deque<Integer>> placeAttempts = new HashMap<>();
    private final Map<UUID, Deque<Integer>> breakTicks = new HashMap<>();
    private final Map<UUID, Deque<Integer>> anchorUses = new HashMap<>();

    public CrystalCheck(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        // Every 30 seconds, forget crystals and placements that are long gone.
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int now = Bukkit.getCurrentTick();
            crystals.values().removeIf(placed -> now - placed.tick > 600);
            pendingPlace.values().removeIf(pending -> now - pending.tick > 40);
        }, 20L * 30, 20L * 30);
    }

    // ------------------------------------------------------------------
    // Placing crystals / using anchors
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block clicked = e.getClickedBlock();
        if (clicked == null) return;

        Player player = e.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;

        ItemStack item = e.getItem();
        if (item != null && item.getType() == Material.END_CRYSTAL) {
            onCrystalPlace(e, player, clicked);
        } else if (clicked.getType() == Material.RESPAWN_ANCHOR) {
            onAnchorUse(e, player, clicked, item);
        }
    }

    private void onCrystalPlace(PlayerInteractEvent e, Player player, Block clicked) {
        PlayerData data = plugin.getData(player);
        UUID id = player.getUniqueId();
        int tick = Bukkit.getCurrentTick();

        // Reach: the block they're placing it on must be within reach.
        double reach = distanceToBox(player.getEyeLocation().toVector(), clicked.getBoundingBox());
        if (reach > MAX_PLACE_REACH) {
            if (data.addBuffer("crystalreach", 1.0) > 1.5) {
                plugin.flag(player, "CrystalReach",
                        String.format("placed a crystal %.2f blocks away (max %.1f)", reach, MAX_PLACE_REACH));
                e.setCancelled(true);
                return;
            }
        } else {
            data.addBuffer("crystalreach", -0.5);
        }

        // Rate: crystal placements in the last second.
        int attempts = record(placeAttempts, id, tick);
        if (attempts > MAX_CRYSTAL_PLACES) {
            if (data.addBuffer("crystalplace", 1.0) > 1.5) {
                plugin.flag(player, "CrystalSpam", attempts + " crystal placements in 1 second");
                e.setCancelled(true);
                return;
            }
        } else {
            data.addBuffer("crystalplace", -0.25);
        }

        pendingPlace.put(id, new Pending(tick, clicked.getLocation()));
    }

    /** Matches a freshly spawned crystal to the player who just placed it. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(EntitySpawnEvent e) {
        Entity crystal = e.getEntity();
        if (crystal.getType() != EntityType.END_CRYSTAL) return;

        int tick = Bukkit.getCurrentTick();
        Location at = crystal.getLocation();
        for (Map.Entry<UUID, Pending> entry : pendingPlace.entrySet()) {
            Pending pending = entry.getValue();
            if (tick - pending.tick <= 1
                    && pending.block.getWorld().equals(at.getWorld())
                    && pending.block.distanceSquared(at) <= 4.0) {
                crystals.put(crystal.getUniqueId(), new Placed(entry.getKey(), tick));
                break;
            }
        }
    }

    // ------------------------------------------------------------------
    // Breaking crystals
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreakCrystal(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player player)) return;
        if (e.getEntity().getType() != EntityType.END_CRYSTAL) return;
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;

        PlayerData data = plugin.getData(player);
        UUID id = player.getUniqueId();
        int tick = Bukkit.getCurrentTick();

        // Rate: crystals broken in the last second.
        int breaks = record(breakTicks, id, tick);
        if (breaks > MAX_CRYSTAL_BREAKS) {
            if (data.addBuffer("crystalbreak", 1.0) > 1.5) {
                plugin.flag(player, "CrystalSpam", breaks + " crystals broken in 1 second");
                e.setCancelled(true);
                return;
            }
        } else {
            data.addBuffer("crystalbreak", -0.25);
        }

        // Timing: how long after placing THEIR crystal did they break it?
        Placed placed = crystals.get(e.getEntity().getUniqueId());
        if (placed == null || !placed.placer.equals(id)) return;

        int delayTicks = tick - placed.tick;
        int ping = Math.min(player.getPing(), 500);
        // They have to receive the crystal before they can hit it, so a person needs at least their ping
        // (plus reaction time). Anything quicker than 80% of their ping, or under one tick, is a cheat.
        double fastestHumanMs = Math.max(50.0, ping * 0.8);
        if (delayTicks * 50.0 < fastestHumanMs) {
            if (data.addBuffer("crystalaura", delayTicks == 0 ? 2.0 : 1.0) > 3.0) {
                plugin.flag(player, "CrystalAura",
                        String.format("broke their crystal %dms after placing it (ping %dms)", delayTicks * 50, ping));
                e.setCancelled(true);
            }
        } else {
            data.addBuffer("crystalaura", -0.5);
        }
    }

    // ------------------------------------------------------------------
    // Anchors
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnchorPlace(BlockPlaceEvent e) {
        if (e.getBlockPlaced().getType() == Material.RESPAWN_ANCHOR) {
            anchors.put(e.getPlayer().getUniqueId(),
                    new AnchorPlaced(e.getBlockPlaced().getLocation(), Bukkit.getCurrentTick()));
        }
    }

    private void onAnchorUse(PlayerInteractEvent e, Player player, Block anchorBlock, ItemStack item) {
        PlayerData data = plugin.getData(player);
        UUID id = player.getUniqueId();
        int tick = Bukkit.getCurrentTick();

        // Rate: anchor clicks in the last second. (Main hand only, so one click isn't counted twice.)
        if (e.getHand() == EquipmentSlot.HAND) {
            int uses = record(anchorUses, id, tick);
            if (uses > MAX_ANCHOR_USES) {
                if (data.addBuffer("anchorrate", 1.0) > 1.5) {
                    plugin.flag(player, "AnchorAura", uses + " anchor clicks in 1 second");
                    e.setCancelled(true);
                    return;
                }
            } else {
                data.addBuffer("anchorrate", -0.25);
            }
        }

        if (!(anchorBlock.getBlockData() instanceof RespawnAnchor anchor)) return;

        // Is this click charging the anchor (glowstone, in either hand) or setting it off?
        boolean holdingFuel = item != null && item.getType() == Material.GLOWSTONE;
        boolean offhandFuel = e.getHand() == EquipmentSlot.HAND
                && player.getInventory().getItemInOffHand().getType() == Material.GLOWSTONE;
        boolean charging = (holdingFuel || offhandFuel) && anchor.getCharges() < anchor.getMaximumCharges();
        boolean explodes = !charging && anchor.getCharges() > 0
                && anchorBlock.getWorld().getEnvironment() != World.Environment.NETHER;
        if (!explodes) return;

        // Timing: set off an anchor they only just placed?
        AnchorPlaced placed = anchors.get(id);
        if (placed == null || !placed.where.equals(anchorBlock.getLocation())) return;

        int sincePlace = tick - placed.tick;
        if (sincePlace < ANCHOR_MIN_TICKS) {
            if (data.addBuffer("anchoraura", 1.0) > 1.5) {
                plugin.flag(player, "AnchorAura",
                        "set off an anchor " + (sincePlace * 50) + "ms after placing it (place, charge and fire)");
                e.setCancelled(true);
            }
        } else {
            data.addBuffer("anchoraura", -0.5);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        pendingPlace.remove(id);
        anchors.remove(id);
        placeAttempts.remove(id);
        breakTicks.remove(id);
        anchorUses.remove(id);
    }

    // ------------------------------------------------------------------

    /** Adds this tick to the player's list and returns how many entries fall in the last second. */
    private int record(Map<UUID, Deque<Integer>> map, UUID id, int tick) {
        Deque<Integer> ticks = map.computeIfAbsent(id, k -> new ArrayDeque<>());
        ticks.addLast(tick);
        while (!ticks.isEmpty() && tick - ticks.peekFirst() >= RATE_WINDOW_TICKS) ticks.pollFirst();
        return ticks.size();
    }

    /** Distance from a point to the closest point on a box (0 if the point is inside it). */
    private static double distanceToBox(Vector point, BoundingBox box) {
        double x = Math.max(box.getMinX(), Math.min(point.getX(), box.getMaxX()));
        double y = Math.max(box.getMinY(), Math.min(point.getY(), box.getMaxY()));
        double z = Math.max(box.getMinZ(), Math.min(point.getZ(), box.getMaxZ()));
        return point.distance(new Vector(x, y, z));
    }
}
