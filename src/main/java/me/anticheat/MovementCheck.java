package me.anticheat;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.EnumSet;
import java.util.Set;

/**
 * Movement checks, all driven by PlayerMoveEvent:
 *  - Fly:   in the air you must follow vanilla gravity (also catches glide, spider, jetpack, high jump).
 *  - Speed: horizontal speed must be reachable with vanilla physics (friction + acceleration).
 *           This catches bunny-hop / strafe / long-jump speed cheats, not just blatant ones.
 *  - Jesus: staying at a constant height on top of water without swimming.
 *  - Step:  climbing more than 0.6 blocks in one step while on the ground.
 *  - Phase: standing inside solid blocks, or moving through them (no-clip).
 *
 * Limitation: PlayerMoveEvent only fires when the client sends a changed position/rotation.
 * Cheats that need raw packet timing (Timer, NoSlow...) need a packet library.
 */
public class MovementCheck implements Listener {

    // ---- vertical physics (blocks per tick) ----
    private static final double GRAVITY = 0.08;
    private static final double DRAG = 0.98;
    private static final double MAX_JUMP_DY = 0.42 + 0.05;
    private static final double GRAVITY_TOLERANCE = 0.03;

    // ---- horizontal physics (blocks per tick) ----
    // Each tick: velocity = lastVelocity * friction + acceleration (+ a 0.2 kick on the jump tick).
    private static final double GROUND_FRICTION = 0.546;      // 0.6 block slipperiness * 0.91
    private static final double AIR_FRICTION = 0.91;
    private static final double SPRINT_GROUND_ACCEL = 0.13;
    private static final double SPRINT_AIR_ACCEL = 0.026;
    private static final double SPRINT_JUMP_BOOST = 0.2;
    private static final double SPEED_TOLERANCE = 0.08;
    private static final int GROUND_STEADY_TICKS = 8;   // moves in a row on the ground before the strict ground limit applies
    private static final int AIR_STEADY_TICKS = 3;      // moves in a row in the air before the strict air limit applies

    // Hard ceilings in steady states (before Speed potion / walk-speed scaling). Vanilla can't beat these:
    // sprinting on flat ground tops out at ~0.286 b/tick, and 4+ ticks into a jump the speed has dropped to ~0.35.
    private static final double MAX_STEADY_GROUND_SPEED = 0.32;
    private static final double GROUND_TOLERANCE = 0.05;
    private static final double AIR_TOLERANCE = 0.012;

    // Air speed envelope. In vanilla, speed in the air always decays toward 0.289 b/tick (friction 0.91 against
    // an acceleration of 0.026), and the fastest possible jump leaves you 0.075 above that on the 2nd air tick.
    // So at air tick k the speed can never exceed 0.289 + 0.075 * 0.91^(k-2). Strafe / bunny-hop speed cheats
    // hold their speed up instead of letting it fall, so they end up above this curve.
    private static final double AIR_ASYMPTOTE = 0.2889;
    private static final double AIR_PEAK_EXCESS = 0.0751;
    private static final double AIR_MARGIN = 0.035;

    /** After a Lunge spear jab the player is flung forward by the client, so give them a moment. */
    private static final int LUNGE_GRACE_TICKS = 30;

    // Average speed over a window: sustained sprint-jumping averages ~0.36 b/tick (7.2 blocks/sec).
    private static final int AVG_WINDOW_TICKS = 40;
    private static final int AVG_EVAL_EVERY_TICKS = 10;
    private static final double MAX_AVERAGE_SPEED = 0.385;

    // ---- other ----
    private static final double MAX_STEP = 0.6 + 0.05;
    private static final int JESUS_TICKS = 8;

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

    /** Sand/gravel/anvils landing can end up inside a player's hitbox, which isn't the player's doing. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFallingBlock(EntityChangeBlockEvent e) {
        if (e.getEntity() instanceof FallingBlock) {
            exemptNearby(e.getBlock().getLocation(), 4, 40);
        }
    }

    /** Pistons can shove blocks (and players) around. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        exemptNearby(e.getBlock().getLocation(), 8, 30);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        exemptNearby(e.getBlock().getLocation(), 8, 30);
    }

    /**
     * Spear + Lunge: a jab attack flings the player forward (and slightly up). The client does this itself,
     * so the server sees a burst of speed that isn't a cheat. Only a spear that really has Lunge counts,
     * and only if the player has the hunger for it (Lunge needs at least 6 food points).
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSpearJab(PlayerAnimationEvent e) {
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        Player player = e.getPlayer();
        if (player.getFoodLevel() < 6) return;
        if (isLungeSpear(player.getInventory().getItemInMainHand())) {
            plugin.getData(player).exemptFor(Bukkit.getCurrentTick(), LUNGE_GRACE_TICKS);
        }
    }

    private boolean isLungeSpear(ItemStack item) {
        if (item == null || !item.getType().name().endsWith("_SPEAR")) return false;
        for (Enchantment enchantment : item.getEnchantments().keySet()) {
            if (enchantment.getKey().getKey().equals("lunge")) return true;
        }
        return false;
    }

    private void exemptNearby(Location where, double radius, int ticks) {
        int tick = Bukkit.getCurrentTick();
        double radiusSquared = radius * radius;
        for (Player player : where.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(where) <= radiusSquared) {
                plugin.getData(player).exemptFor(tick, ticks);
            }
        }
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
        boolean startedOnGround = data.lastOnGround;
        double lastHorizontal = data.lastHorizontal;
        boolean groundBeforeLast = data.groundBeforeLast;

        // Remember surfaces that break the normal rules for a while.
        Material below = to.clone().subtract(0, 0.5, 0).getBlock().getType();
        if (Tag.ICE.isTagged(below) || below == Material.SOUL_SAND || below == Material.SOUL_SOIL) {
            data.lastIceTick = tick;   // slippery or speed-boosting surfaces
        }
        if (below == Material.SLIME_BLOCK || Tag.BEDS.isTagged(below)) data.exemptFor(tick, 40); // bouncy

        // Coming out of an elytra glide or a riptide leaves a lot of leftover momentum for a while.
        if (player.isGliding() || player.isRiptiding()) data.exemptFor(tick, 40);

        boolean exempt = isExempt(player, data, tick);
        boolean inWater = player.isInWater();
        boolean odd = inOddBlock(to);

        // ---------------- JESUS + PHASE (run even next to water, so they sit before the early return) ----------------
        if (!exempt) {
            checkJesus(player, data, to, onGround, dy, horizontal, inWater);
            if (!odd) checkPhase(player, data, from, to);
        }

        if (exempt || inWater || odd) {
            data.speedSamples.clear();      // can't judge speed here, so restart the average-speed window
            data.speedWindowStart = -1;
            remember(data, dy, horizontal, onGround);
            data.airTicks = onGround ? 0 : 5;      // 5 = "already airborne", skips the first-tick jump check
            return;
        }

        // ---------------- FLY ----------------
        if (onGround) {
            data.airTicks = 0;
        } else {
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

        // ---------------- STEP ----------------
        // Vanilla players step up at most 0.6 blocks. Anything bigger while staying on the ground is step hacks.
        if (startedOnGround && onGround && dy > MAX_STEP) {
            if (data.addBuffer("step", 1.0) > 1.0) {
                plugin.flag(player, "Step", String.format("stepped up %.2f blocks", dy));
            }
        } else {
            data.addBuffer("step", -0.25);
        }

        // ---------------- SPEED ----------------
        boolean slippery = tick - data.lastIceTick < 20;   // ice and soul speed make you faster, skip them
        if (slippery) {
            data.speedSamples.clear();
            data.speedWindowStart = -1;
        } else {
            double multiplier = player.getWalkSpeed() / 0.2;
            PotionEffect speedEffect = player.getPotionEffect(PotionEffectType.SPEED);
            if (speedEffect != null) {
                multiplier *= 1.0 + 0.2 * (speedEffect.getAmplifier() + 1);
            }

            // (A) Per tick, in STEADY states only. The moves where you jump, land, or step up/down a block
            //     are skipped on purpose (friction and ground state change there, and an ordinary walk up
            //     or down a block must never look like speed). A real speed cheat keeps going, so it shows.
            //     Two limits, and exceeding EITHER one is a flag:
            //       - physics: the fastest vanilla physics could produce from last tick's speed
            //       - ceiling: the fixed top speed vanilla can reach in this state (catches steady speed)
            int streak = (onGround == startedOnGround) ? data.stateStreak + 1 : 1;
            boolean steady = onGround ? streak >= GROUND_STEADY_TICKS : streak >= AIR_STEADY_TICKS;
            if (lastHorizontal >= 0 && steady) {
                double friction = onGround ? GROUND_FRICTION : AIR_FRICTION;
                double accel = (onGround ? SPRINT_GROUND_ACCEL : SPRINT_AIR_ACCEL) * multiplier;
                double tolerance = onGround ? GROUND_TOLERANCE : AIR_TOLERANCE;
                double physicsLimit = lastHorizontal * friction + accel + tolerance;
                double ceiling = onGround
                        ? MAX_STEADY_GROUND_SPEED * multiplier
                        : (AIR_ASYMPTOTE + AIR_PEAK_EXCESS * Math.pow(AIR_FRICTION, streak - 2) + AIR_MARGIN) * multiplier;
                double limit = Math.min(physicsLimit, ceiling);

                if (horizontal > limit) {
                    if (data.addBuffer("speed", 1.0) > 3.0) {
                        plugin.flag(player, "Speed", String.format("%.3f b/tick, max %.3f (%s)",
                                horizontal, limit, onGround ? "on ground" : "in air"));
                    }
                } else {
                    data.addBuffer("speed", -0.1);
                }
            }

            // (B) Average over the last 2 seconds: catches steady speed and Timer-style cheats.
            checkAverageSpeed(player, data, tick, horizontal, multiplier);
        }

        remember(data, dy, horizontal, onGround);
    }

    // ------------------------------------------------------------------
    // Individual checks
    // ------------------------------------------------------------------

    /**
     * Jesus: standing/walking on top of water. The player isn't swimming (not "in water"),
     * has nothing solid under them, yet stays at a constant height right above a water block.
     * Anyone legitimately in that spot would sink and start swimming within a tick or two.
     */
    private void checkJesus(Player player, PlayerData data, Location to, boolean onGround,
                            double dy, double horizontal, boolean inWater) {
        boolean suspicious = false;
        if (!onGround && !inWater && Math.abs(dy) < 0.03 && horizontal > 0.05) {
            Block under = to.getWorld().getBlockAt(
                    to.getBlockX(), floor(to.getY() - 0.3), to.getBlockZ());
            suspicious = under.getType() == Material.WATER;
        }

        if (suspicious) {
            data.jesusTicks++;
            if (data.jesusTicks >= JESUS_TICKS) {
                plugin.flag(player, "Jesus",
                        String.format("on water for %d ticks (dy=%.3f)", data.jesusTicks, dy));
            }
        } else {
            data.jesusTicks = 0;
        }
    }

    /**
     * Phase / no-clip on foot: the player's hitbox must never overlap solid blocks, and the
     * straight path they just took must not cross one.
     */
    private void checkPhase(Player player, PlayerData data, Location from, Location to) {
        World world = to.getWorld();
        String info = null;

        double height = player.getHeight();

        // Already inside blocks BEFORE this move: a block landed on them (falling sand, a piston...) or
        // they were pushed in. That isn't phasing, so there's nothing to judge until they're back in free space.
        if (insideBlocks(player, from)) {
            data.addBuffer("phase", -1.0);
            return;
        }

        if (insideBlocks(player, to)) {
            info = String.format("inside blocks at %.1f %.1f %.1f", to.getX(), to.getY(), to.getZ());
        } else {
            Vector path = to.toVector().subtract(from.toVector());
            double distance = path.length();
            if (distance > 0.05 && world.isChunkLoaded(from.getBlockX() >> 4, from.getBlockZ() >> 4)) {
                Location start = from.clone().add(0, height / 2.0, 0);
                RayTraceResult hit = world.rayTraceBlocks(
                        start, path.normalize(), distance, FluidCollisionMode.NEVER, true);
                if (hit != null && hit.getHitBlock() != null) {
                    info = String.format("moved through %s (%.2f blocks)",
                            hit.getHitBlock().getType().name().toLowerCase(), distance);
                }
            }
        }

        if (info != null) {
            if (data.addBuffer("phase", 1.0) > 1.5) plugin.flag(player, "Phase", info);
        } else {
            data.addBuffer("phase", -0.5);
        }
    }

    /**
     * Average horizontal speed per server tick over the last 2 seconds of uninterrupted movement.
     * Vanilla can't sustain more than ~0.36 b/tick even bunny-hopping, so a higher average means
     * speed hacks. Because it's measured per SERVER tick, a client that sends movement packets
     * faster than 20 per second (Timer) shows up here too.
     */
    private void checkAverageSpeed(Player player, PlayerData data, int tick, double horizontal, double multiplier) {
        if (data.speedWindowStart < 0) data.speedWindowStart = tick;

        data.speedSamples.addLast(new double[]{tick, horizontal});
        while (!data.speedSamples.isEmpty() && tick - data.speedSamples.peekFirst()[0] >= AVG_WINDOW_TICKS) {
            data.speedSamples.pollFirst();
        }

        // Only judge after a full window of movement we could actually check, and only every few ticks.
        if (tick - data.speedWindowStart < AVG_WINDOW_TICKS) return;
        if (tick - data.lastAvgEval < AVG_EVAL_EVERY_TICKS) return;
        data.lastAvgEval = tick;

        // Server lag makes players look faster per server tick, so don't judge while it's lagging.
        if (Bukkit.getTPS()[0] < 19.0) return;

        double sum = 0;
        for (double[] sample : data.speedSamples) sum += sample[1];
        double average = sum / AVG_WINDOW_TICKS;
        double limit = MAX_AVERAGE_SPEED * multiplier;

        if (average > limit) {
            if (data.addBuffer("speedavg", 1.0) > 2.0) {
                plugin.flag(player, "Speed",
                        String.format("average %.3f b/tick over 2s, max %.3f", average, limit));
            }
        } else {
            data.addBuffer("speedavg", -1.0);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** True if the player's hitbox at this position overlaps solid blocks (shrunk a little so touching a wall doesn't count). */
    private boolean insideBlocks(Player player, Location loc) {
        double half = 0.25;   // the player is 0.6 wide
        double height = player.getHeight();
        BoundingBox box = new BoundingBox(
                loc.getX() - half, loc.getY() + 0.05, loc.getZ() - half,
                loc.getX() + half, loc.getY() + height - 0.05, loc.getZ() + half);
        return overlapsSolid(loc.getWorld(), box);
    }

    private void remember(PlayerData data, double dy, double horizontal, boolean onGround) {
        data.lastDy = dy;
        data.lastHorizontal = horizontal;
        data.stateStreak = (onGround == data.lastOnGround) ? data.stateStreak + 1 : 1;
        data.groundBeforeLast = data.lastOnGround;
        data.lastOnGround = onGround;
    }

    /** Situations where vanilla rules differ and we'd rather not judge the player. */
    private boolean isExempt(Player p, PlayerData data, int tick) {
        GameMode gm = p.getGameMode();
        return gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR
                || p.getAllowFlight() || p.isFlying() || p.isGliding() || p.isRiptiding()
                || p.isInsideVehicle() || p.isDead()
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
     * Server-side ground check: is there collision in a very thin slab directly under the
     * player's feet (player hitbox is 0.6 wide)? Uses real block collision shapes, so
     * stairs, slabs, fences, carpets and so on all behave correctly.
     *
     * The slab is deliberately razor thin (3/1000 of a block). Vanilla only counts you as
     * "on the ground" when your feet are exactly on the surface. A thicker slab would say
     * "on ground" while you're still a few hundredths of a block above it, which throws off
     * the friction maths in the speed check every time you drop down a step or stairs.
     */
    private boolean isOnGround(Location loc) {
        double x = loc.getX(), y = loc.getY(), z = loc.getZ();
        return overlapsSolid(loc.getWorld(), new BoundingBox(x - 0.3, y - 0.003, z - 0.3, x + 0.3, y, z + 0.3));
    }

    /** True if the box overlaps the real collision shape of any block. */
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
