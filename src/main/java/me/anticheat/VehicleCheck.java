package me.anticheat;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * Boat no-clip / phase detection.
 *
 * While you ride a boat, the PLAYER's own movement is not checked (the boat carries you),
 * which is exactly what boat no-clip exploits. Two checks close that gap:
 *  1. Inside blocks: a boat's hitbox should never overlap solid block collision.
 *  2. Tunnelling:    the straight path the boat took this move must not pass through a solid block.
 */
public class VehicleCheck implements Listener {

    /** Shrink the hitbox slightly so a boat resting against a wall (touching) isn't counted as inside it. */
    private static final double SHRINK = 0.08;

    private final AntiCheatPlugin plugin;

    public VehicleCheck(AntiCheatPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onEnter(VehicleEnterEvent e) {
        if (e.getEntered() instanceof Player player) {
            plugin.getData(player).exemptFor(Bukkit.getCurrentTick(), 20);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleMove(VehicleMoveEvent e) {
        if (!(e.getVehicle() instanceof Boat boat)) return;

        Player rider = null;
        for (Entity passenger : boat.getPassengers()) {
            if (passenger instanceof Player p) {
                rider = p;
                break;
            }
        }
        if (rider == null) return;   // empty boats can't be driven by a cheater

        PlayerData data = plugin.getData(rider);
        int tick = Bukkit.getCurrentTick();
        if (tick < data.exemptUntilTick) return;

        Location from = e.getFrom();
        Location to = e.getTo();
        World world = to.getWorld();
        if (!world.equals(from.getWorld())) return;

        boolean violation = false;
        String info = "";

        // ---------- 1) boat hitbox overlapping solid blocks ----------
        if (isInsideBlocks(boat, to)) {
            violation = true;
            info = String.format("boat inside blocks at %.1f %.1f %.1f", to.getX(), to.getY(), to.getZ());
        } else {
            // ---------- 2) tunnelling: did the path from -> to cross a solid block? ----------
            Vector path = to.toVector().subtract(from.toVector());
            double distance = path.length();
            if (distance > 0.05) {
                Location start = from.clone().add(0, boat.getHeight() / 2.0, 0);
                RayTraceResult hit = world.rayTraceBlocks(
                        start, path.normalize(), distance, FluidCollisionMode.NEVER, true);
                if (hit != null && hit.getHitBlock() != null) {
                    violation = true;
                    info = String.format("boat passed through %s (moved %.2f)",
                            hit.getHitBlock().getType().name().toLowerCase(), distance);
                }
            }
        }

        if (violation) {
            if (data.addBuffer("boatclip", 1.0) > 1.5) plugin.flag(rider, "BoatNoClip", info);
        } else {
            data.addBuffer("boatclip", -0.5);
        }
    }

    /** True if the boat's (slightly shrunk) hitbox at this position overlaps real block collision. */
    private boolean isInsideBlocks(Boat boat, Location loc) {
        World world = loc.getWorld();
        double halfWidth = boat.getWidth() / 2.0;
        double height = boat.getHeight();

        BoundingBox box = new BoundingBox(
                loc.getX() - halfWidth + SHRINK, loc.getY() + SHRINK, loc.getZ() - halfWidth + SHRINK,
                loc.getX() + halfWidth - SHRINK, loc.getY() + height - SHRINK, loc.getZ() + halfWidth - SHRINK);

        for (int bx = floor(box.getMinX()); bx <= floor(box.getMaxX()); bx++) {
            for (int by = floor(box.getMinY()); by <= floor(box.getMaxY()); by++) {
                for (int bz = floor(box.getMinZ()); bz <= floor(box.getMaxZ()); bz++) {
                    if (!world.isChunkLoaded(bx >> 4, bz >> 4)) continue;
                    Block block = world.getBlockAt(bx, by, bz);
                    // Collision shapes are relative to the block, so shift the box to match.
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
