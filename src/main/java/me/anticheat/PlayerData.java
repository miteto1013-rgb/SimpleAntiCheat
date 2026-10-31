package me.anticheat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Everything we remember about one player between events.
 * Only ever touched from the main server thread, so no locking is needed.
 */
public class PlayerData {

    // ---------- movement state ----------
    public double lastDy;
    public int airTicks;
    public int groundTicks;
    public int exemptUntilTick;
    public int lastIceTick = -1000;
    public double lastHorizontal = -1;      // -1 = unknown (no previous move yet)
    public boolean lastOnGround = true;
    public boolean groundBeforeLast = true;   // was the previous tick started on the ground?
    public int jesusTicks;
    public final Deque<double[]> speedSamples = new ArrayDeque<>();   // {tick, horizontal distance}
    public int speedWindowStart = -1;
    public int lastAvgEval = -1000;

    // ---------- combat state ----------
    public final Deque<Integer> swingTicks = new ArrayDeque<>();
    public int lastAttackTick = -1000;
    public int lastVictimId = -1;

    // ---------- bookkeeping ----------
    private final Map<String, Double> buffers = new HashMap<>();
    private final Map<String, Integer> violations = new HashMap<>();
    private final Map<String, Integer> lastAlert = new HashMap<>();

    /** Skip movement checks for a few ticks (teleports, knockback, bounces...). */
    public void exemptFor(int nowTick, int ticks) {
        exemptUntilTick = Math.max(exemptUntilTick, nowTick + ticks);
    }

    /**
     * Buffers soak up one-off oddities (lag spikes, edge cases).
     * Add to it on a suspicious event, subtract on a clean one, and only flag
     * when it climbs past a threshold. It never goes below zero.
     */
    public double addBuffer(String check, double amount) {
        double value = Math.max(0, buffers.getOrDefault(check, 0.0) + amount);
        buffers.put(check, value);
        return value;
    }

    /** Increments and returns the violation level (VL) for a check. */
    public int addViolation(String check) {
        return violations.merge(check, 1, Integer::sum);
    }

    /** Called periodically so old flags fade away. */
    public void decayViolations() {
        violations.replaceAll((check, vl) -> vl - 1);
        violations.values().removeIf(vl -> vl <= 0);
    }

    // ---------- strikes (the ban rule counts these) ----------
    private final Map<String, Deque<Integer>> strikes = new HashMap<>();

    /**
     * Adds a strike for this check, but only if at least spacingTicks have passed since the previous
     * one. That way a single glitch that keeps flagging every tick still only earns ONE strike.
     * Strikes older than windowTicks are forgotten. Returns how many strikes are active now.
     */
    public int addStrike(String check, int nowTick, int spacingTicks, int windowTicks) {
        Deque<Integer> list = strikes.computeIfAbsent(check, k -> new ArrayDeque<>());
        while (!list.isEmpty() && nowTick - list.peekFirst() > windowTicks) list.pollFirst();
        if (list.isEmpty() || nowTick - list.peekLast() >= spacingTicks) list.addLast(nowTick);
        return list.size();
    }

    /** Active strikes per check (checks with none are left out). */
    public Map<String, Integer> currentStrikes(int nowTick, int windowTicks) {
        Map<String, Integer> result = new HashMap<>();
        for (Map.Entry<String, Deque<Integer>> entry : strikes.entrySet()) {
            Deque<Integer> list = entry.getValue();
            while (!list.isEmpty() && nowTick - list.peekFirst() > windowTicks) list.pollFirst();
            if (!list.isEmpty()) result.put(entry.getKey(), list.size());
        }
        return result;
    }

    /** Rate-limits chat alerts so a single cheater doesn't flood the chat. */
    public boolean canAlert(String check, int nowTick, int cooldownTicks) {
        Integer last = lastAlert.get(check);
        if (last != null && nowTick - last < cooldownTicks) return false;
        lastAlert.put(check, nowTick);
        return true;
    }
}
