package me.anticheat;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * SafeWalk detection (alert only).
 *
 * SafeWalk stops you from walking off an edge without sneaking. The giveaway is that you keep "sprinting"
 * while standing perfectly still right on an edge: in vanilla, sprinting stops the moment you stop moving or
 * run into something, but a SafeWalk cheat just holds your position, so the sprint never gets cancelled.
 *
 * Every 5 ticks we look for players who are sprinting but haven't moved, aren't sneaking, are hanging half
 * over an edge (nothing under the centre of their feet), and have no wall in front of them. Four checks in a
 * row (about a second) raise an alert.
 *
 * Because it's inferred rather than measured, this only alerts staff. It never holds anyone or counts toward a ban.
 */
public class SafeWalkCheck {

    private static class Last {
        final World world;
        final double x;
        final double y;
        final double z;

        Last(Location loc) {
            this.world = loc.getWorld();
            this.x = loc.getX();
            this.y = loc.getY();
            this.z = loc.getZ();
        }
    }

    private final AntiCheatPlugin plugin;
    private final Map<UUID, Last> lastSeen = new HashMap<>();

    public SafeWalkCheck(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::scan, 5L, 5L);
    }

    private void scan() {
        int now = Bukkit.getCurrentTick();
        lastSeen.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);

        for (Player player : Bukkit.getOnlinePlayers()) {
            Location loc = player.getLocation();
            Last before = lastSeen.put(player.getUniqueId(), new Last(loc));
            PlayerData data = plugin.getData(player);

            boolean stationary = before != null && before.world.equals(loc.getWorld())
                    && Math.abs(loc.getX() - before.x) < 0.001
                    && Math.abs(loc.getY() - before.y) < 0.001
                    && Math.abs(loc.getZ() - before.z) < 0.001;

            GameMode gm = player.getGameMode();
            boolean skip = gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR
                    || player.getAllowFlight() || player.isFlying() || player.isGliding()
                    || player.isInsideVehicle() || player.isDead() || player.isInWater()
                    || now < data.exemptUntilTick || plugin.isLocked(player);

            if (!skip && stationary && player.isSprinting() && !player.isSneaking() && hangingOverEdge(loc)) {
                if (data.addBuffer("safewalk", 1.0) > 3.0) {
                    plugin.flag(player, "SafeWalk", "sprinting in place, hanging over an edge", false);
                }
            } else {
                data.addBuffer("safewalk", -0.5);
            }
        }
    }

    /** On the ground, but with nothing under the centre of the feet and no wall in the way. */
    private boolean hangingOverEdge(Location loc) {
        World world = loc.getWorld();
        double x = loc.getX(), y = loc.getY(), z = loc.getZ();

        boolean onGround = overlapsSolid(world, new BoundingBox(x - 0.3, y - 0.003, z - 0.3, x + 0.3, y, z + 0.3));
        if (!onGround) return false;

        boolean centreSupported = overlapsSolid(world, new BoundingBox(x - 0.01, y - 0.003, z - 0.01, x + 0.01, y, z + 0.01));
        if (centreSupported) return false;

        Vector ahead = loc.getDirection().setY(0);
        if (ahead.lengthSquared() < 1.0E-6) return false;
        ahead.normalize().multiply(0.5);
        double cx = x + ahead.getX(), cz = z + ahead.getZ();
        boolean wallAhead = overlapsSolid(world, new BoundingBox(cx - 0.3, y + 0.1, cz - 0.3, cx + 0.3, y + 1.7, cz + 0.3));
        return !wallAhead;
    }

    private static boolean overlapsSolid(World world, BoundingBox box) {
        for (int bx = floor(box.getMinX()); bx <= floor(box.getMaxX()); bx++) {
            for (int by = floor(box.getMinY()); by <= floor(box.getMaxY()); by++) {
                for (int bz = floor(box.getMinZ()); bz <= floor(box.getMaxZ()); bz++) {
                    if (!world.isChunkLoaded(bx >> 4, bz >> 4)) continue;
                    Block block = world.getBlockAt(bx, by, bz);
                    if (block.getCollisionShape().overlaps(box.clone().shift(-bx, -by, -bz))) return true;
                }
            }
        }
        return false;
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }
}
