package me.anticheat;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
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
 * Boat checks. While you ride a boat, the PLAYER's own movement isn't checked (the boat carries you),
 * which is exactly what boat cheats exploit. So the boat itself is checked:
 *  1. NoClip: a boat's hitbox should never overlap solid blocks, and its path must not cross one.
 *  2. Fly:    a boat in the air (no water around, nothing underneath) must fall with gravity.
 *             A boat that hovers, or goes up, is being held there by a cheat.
 */
public class VehicleCheck implements Listener {

    /** Shrink the hitbox slightly so a boat resting against a wall (touching) isn't counted as inside it. */
    private static final double SHRINK = 0.08;

    /** Vanilla: a boat in the air gains 0.04 blocks/tick of downward speed every tick. */
    private static final double BOAT_GRAVITY = 0.04;
    /** How much "less falling than gravity" we forgive (hovering is a full 0.04 off, so this is tight but safe). */
    private static final double BOAT_FLY_TOLERANCE = 0.02;
    /** The boat must have been in the air this many moves in a row before we judge it. */
    private static final int BOAT_AIR_TICKS = 4;

    private final AntiCheatPlugin plugin;

    public VehicleCheck(AntiCheatPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onEnter(VehicleEnterEvent e) {
        if (e.getEntered() instanceof Player player) {
            PlayerData data = plugin.getData(player);
            data.exemptFor(Bukkit.getCurrentTick(), 20);
            data.boatAirTicks = 0;
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
        if (tick < data.exemptUntilTick) {
            data.boatAirTicks = 0;   // start judging fresh once the grace period is over
            return;
        }

        Location from = e.getFrom();
        Location to = e.getTo();
        World world = to.getWorld();
        if (!world.equals(from.getWorld())) return;

        double dy = to.getY() - from.getY();

        // ---------- NOCLIP ----------
        boolean clipped = false;
        String clipInfo = "";

        // 1) boat hitbox overlapping solid blocks
        if (isInsideBlocks(boat, to)) {
            clipped = true;
            clipInfo = String.format("boat inside blocks at %.1f %.1f %.1f", to.getX(), to.getY(), to.getZ());
        } else {
            // 2) tunnelling: did the path from -> to cross a solid block?
            Vector path = to.toVector().subtract(from.toVector());
            double distance = path.length();
            if (distance > 0.05) {
                Location start = from.clone().add(0, boat.getHeight() / 2.0, 0);
                RayTraceResult hit = world.rayTraceBlocks(
                        start, path.normalize(), distance, FluidCollisionMode.NEVER, true);
                if (hit != null && hit.getHitBlock() != null) {
                    clipped = true;
                    clipInfo = String.format("boat passed through %s (moved %.2f)",
                            hit.getHitBlock().getType().name().toLowerCase(), distance);
                }
            }
        }

        if (clipped) {
            if (data.addBuffer("boatclip", 1.0) > 1.5) plugin.flag(rider, "BoatNoClip", clipInfo);
        } else {
            data.addBuffer("boatclip", -0.5);
        }

        // ---------- FLY ----------
        if (isAirborne(boat, to)) {
            data.boatAirTicks++;
            if (data.boatAirTicks >= BOAT_AIR_TICKS) {
                // In the air the boat's vertical speed drops by 0.04 every tick. If it fell any less than
                // that (hovering, rising), something is holding it up.
                double maxDy = data.boatLastDy - BOAT_GRAVITY + BOAT_FLY_TOLERANCE;
                if (dy > maxDy) {
                    if (data.addBuffer("boatfly", 1.0) > 2.0) {
                        plugin.flag(rider, "BoatFly",
                                String.format("dy=%.3f in the air, a falling boat is at most %.3f", dy, maxDy));
                    }
                } else {
                    data.addBuffer("boatfly", -0.25);
                }
            }
        } else {
            data.boatAirTicks = 0;
            data.addBuffer("boatfly", -0.5);
        }
        data.boatLastDy = dy;
    }

    /**
     * True only if the boat is clearly flying: no water/bubble column anywhere around it, and no solid
     * block right underneath. When unsure (unloaded chunk, touching water...) this says false.
     */
    private boolean isAirborne(Boat boat, Location loc) {
        World world = loc.getWorld();
        double half = boat.getWidth() / 2.0;
        double height = boat.getHeight();
        double x = loc.getX(), y = loc.getY(), z = loc.getZ();

        // Any water around the boat (with a little margin) means it's floating, not flying.
        BoundingBox around = new BoundingBox(x - half - 0.15, y - 0.15, z - half - 0.15,
                x + half + 0.15, y + height + 0.15, z + half + 0.15);
        for (int bx = floor(around.getMinX()); bx <= floor(around.getMaxX()); bx++) {
            for (int by = floor(around.getMinY()); by <= floor(around.getMaxY()); by++) {
                for (int bz = floor(around.getMinZ()); bz <= floor(around.getMaxZ()); bz++) {
                    if (!world.isChunkLoaded(bx >> 4, bz >> 4)) return false;
                    Block block = world.getBlockAt(bx, by, bz);
                    if (block.isLiquid() || block.getType() == Material.BUBBLE_COLUMN) return false;
                    if (block.getBlockData() instanceof Waterlogged wl && wl.isWaterlogged()) return false;
                }
            }
        }

        // Something solid right under the boat means it's resting on the ground (land, ice...).
        BoundingBox below = new BoundingBox(x - half, y - 0.15, z - half, x + half, y, z + half);
        return !overlapsSolid(world, below);
    }

    /** True if the boat's (slightly shrunk) hitbox at this position overlaps real block collision. */
    private boolean isInsideBlocks(Boat boat, Location loc) {
        double halfWidth = boat.getWidth() / 2.0;
        double height = boat.getHeight();

        BoundingBox box = new BoundingBox(
                loc.getX() - halfWidth + SHRINK, loc.getY() + SHRINK, loc.getZ() - halfWidth + SHRINK,
                loc.getX() + halfWidth - SHRINK, loc.getY() + height - SHRINK, loc.getZ() + halfWidth - SHRINK);
        return overlapsSolid(loc.getWorld(), box);
    }

    private boolean overlapsSolid(World world, BoundingBox box) {
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
