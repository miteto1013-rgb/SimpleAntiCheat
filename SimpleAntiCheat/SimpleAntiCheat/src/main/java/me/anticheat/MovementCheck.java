package me.anticheat;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;

import java.util.EnumSet;
import java.util.Set;

/**
 * Movement checks, all driven by PlayerMoveEvent:
 *  - Fly:   in the air, you can only follow vanilla gravity (dy = (lastDy - 0.08) * 0.98)
 *           and a jump can only lift you 0.42 blocks on the first tick.
 *  - Speed: horizontal distance per move must stay under what vanilla can produce.
 *
 * Limitation: PlayerMoveEvent only fires when the client sends a changed position/rotation,
 * so this is a good first version, not a bulletproof one (see notes in the chat reply).
 */
public class MovementCheck implements Listener {

    // Vanilla physics constants (blocks per tick)
    private static final double GRAVITY = 0.08;
    private static final double DRAG = 0.98;
    private static final double MAX_JUMP_DY = 0.42 + 0.05;
    private static final double GRAVITY_TOLERANCE = 0.03;

    // Sprint-jumping peaks around 0.5 b/tick; sprinting on flat ground is ~0.29 b/tick.
    private static final double MAX_SPEED_AIR = 0.62;
    private static final double MAX_SPEED_GROUND = 0.34;

    /** Blocks that change movement rules, so we don't judge players inside/on them. */
    private static final Set<Material> ODD_BLOCKS = EnumSet.of(
            Material.COBWEB, Material.POWDER_SNOW, Material.SWEET_BERRY_BUSH,
            Material.HONEY_BLOCK, Material.SLIME_BLOCK, Material.BUBBLE_COLUMN);

    private final AntiCheatPlugin plugin;

    public MovementCheck(AntiCheatPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // Things the server does TO the player: ignore movement briefly after
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent e) {      // knockback, explosions, wind charges...
        plugin.getData(e.getPlayer()).exemptFor(Bukkit.getCurrentTick(), 30);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        plugin.getData(e.getPlayer()).exemptFor(Bukkit.getCurrentTick(), 20);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        plugin.getData(e.getPlayer()).exemptFor(Bukkit.getCurrentTick(), 40);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        plugin.getData(e.getPlayer()).exemptFor(Bukkit.getCurrentTick(), 40);
    }

    // ------------------------------------------------------------------
    // The actual checks
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null || !to.getWorld().equals(from.getWorld())) return;

        // Rotation-only packets don't tell us anything about movement.
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) return;

        Player player = e.getPlayer();
        PlayerData data = plugin.getData(player);
        int tick = Bukkit.getCurrentTick();

        double dy = to.getY() - from.getY();
        double horizontal = Math.hypot(to.getX() - from.getX(), to.getZ() - from.getZ());

        // NOTE: we work out "on ground" ourselves from the world. The client's own
        // onGround flag is exactly what cheaters spoof, so we never trust it.
        boolean onGround = isOnGround(to);

        // Remember surfaces that break the normal rules for a while.
        Material below = to.clone().subtract(0, 0.5, 0).getBlock().getType();
        if (Tag.ICE.isTagged(below)) data.lastIceTick = tick;
        if (below == Material.SLIME_BLOCK || Tag.BEDS.isTagged(below)) data.exemptFor(tick, 40); // bouncy

        if (isExempt(player, data, tick) || inOddBlock(to)) {
            data.lastDy = dy;
            data.airTicks = onGround ? 0 : 5;      // 5 = "already airborne", skips the first-tick jump check
            data.groundTicks = onGround ? data.groundTicks + 1 : 0;
            return;
        }

        // ---------------- FLY ----------------
        if (onGround) {
            data.airTicks = 0;
            data.groundTicks++;
        } else {
            data.groundTicks = 0;
            data.airTicks++;

            boolean violation = false;
            String info = "";

            if (data.airTicks == 1) {
                // First tick off the ground: a jump gives +0.42, nothing more.
                if (dy > MAX_JUMP_DY) {
                    violation = true;
                    info = String.format("jump dy=%.3f max=%.3f", dy, MAX_JUMP_DY);
                }
            } else {
                // Later ticks: vertical movement must follow gravity. Moving UP more than
                // physics allows (or falling much slower) means something is holding you up.
                double expected = (data.lastDy - GRAVITY) * DRAG;
                if (dy > expected + GRAVITY_TOLERANCE) {
                    violation = true;
                    info = String.format("dy=%.3f max=%.3f", dy, expected + GRAVITY_TOLERANCE);
                }
            }

            if (violation) {
                if (data.addBuffer("fly", 1.0) > 3.0) plugin.flag(player, "Fly", info);
            } else {
                data.addBuffer("fly", -0.1);
            }
        }

        // ---------------- SPEED ----------------
        boolean slippery = tick - data.lastIceTick < 20;   // ice makes you much faster, skip it
        if (!slippery) {
            double multiplier = player.getWalkSpeed() / 0.2;
            PotionEffect speedEffect = player.getPotionEffect(PotionEffectType.SPEED);
            if (speedEffect != null) {
                multiplier *= 1.0 + 0.2 * (speedEffect.getAmplifier() + 1);
            }

            // After a few ticks on the ground, momentum from jumping is gone, so be stricter.
            double limit = (data.groundTicks >= 5 ? MAX_SPEED_GROUND : MAX_SPEED_AIR) * multiplier;

            if (horizontal > limit) {
                if (data.addBuffer("speed", 1.0) > 3.0) {
                    plugin.flag(player, "Speed", String.format("%.3f b/tick max=%.3f", horizontal, limit));
                }
            } else {
                data.addBuffer("speed", -0.25);
            }
        }

        data.lastDy = dy;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Situations where vanilla rules differ and we'd rather not judge the player. */
    private boolean isExempt(Player p, PlayerData data, int tick) {
        GameMode gm = p.getGameMode();
        return gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR
                || p.getAllowFlight() || p.isFlying() || p.isGliding() || p.isRiptiding()
                || p.isInsideVehicle() || p.isDead() || p.isInWater()
                || tick < data.exemptUntilTick
                || p.hasPotionEffect(PotionEffectType.LEVITATION)
                || p.hasPotionEffect(PotionEffectType.SLOW_FALLING)
                || p.hasPotionEffect(PotionEffectType.JUMP_BOOST);
    }

    /** True if the player's feet are in/next to liquid, ladders, vines, webs, honey, etc. */
    private boolean inOddBlock(Location loc) {
        World world = loc.getWorld();
        int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return true;

        for (int offset = -1; offset <= 1; offset++) {
            Block block = world.getBlockAt(x, y + offset, z);
            Material type = block.getType();
            if (block.isLiquid() || ODD_BLOCKS.contains(type) || Tag.CLIMBABLE.isTagged(type)) return true;
            if (block.getBlockData() instanceof Waterlogged wl && wl.isWaterlogged()) return true;
        }
        return false;
    }

    /**
     * Server-side ground check: is there collision in a thin slab directly under the
     * player's feet (player hitbox is 0.6 wide)? Uses real block collision shapes, so
     * stairs, slabs, fences, carpets and so on all behave correctly.
     */
    private boolean isOnGround(Location loc) {
        World world = loc.getWorld();
        double x = loc.getX(), y = loc.getY(), z = loc.getZ();
        BoundingBox probe = new BoundingBox(x - 0.3, y - 0.1, z - 0.3, x + 0.3, y, z + 0.3);

        for (int bx = floor(probe.getMinX()); bx <= floor(probe.getMaxX()); bx++) {
            for (int by = floor(probe.getMinY()); by <= floor(probe.getMaxY()); by++) {
                for (int bz = floor(probe.getMinZ()); bz <= floor(probe.getMaxZ()); bz++) {
                    if (!world.isChunkLoaded(bx >> 4, bz >> 4)) continue;
                    Block block = world.getBlockAt(bx, by, bz);
                    // Collision shapes are relative to the block, so shift the probe to match.
                    BoundingBox relative = probe.clone().shift(-bx, -by, -bz);
                    if (block.getCollisionShape().overlaps(relative)) return true;
                }
            }
        }
        return false;
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }
}
