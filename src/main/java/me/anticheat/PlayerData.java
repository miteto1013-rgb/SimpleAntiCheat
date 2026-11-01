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
    public int stateStreak = 1;               // how many moves in a row ended in the current ground state
    public int lastDigTick = -1000;           // last time the player started or finished breaking a block
    public double lastGroundY = Double.NaN;   // height of the last spot the player stood on
    public double lastYawDelta;               // how far the player turned on their last move event (degrees)
    public int rotationTick = -1000;          // when that happened
    public final Deque<Double> aimErrors = new ArrayDeque<>();   // angle between crosshair and target on recent hits
    public final Deque<Integer> snapHits = new ArrayDeque<>();   // recent hits that came right after a huge flick
    public int boatAirTicks;                  // consecutive boat moves with the boat in the air
    public double boatLastDy;                 // the boat's vertical movement on its previous move
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

    // ---------- flag log (what /sus shows) ----------
    public static class FlagRecord {
        public int count;
        public int lastTick;
        public String lastInfo = "";
    }

    private final Map<String, FlagRecord> flagLog = new HashMap<>();

    /** Records a flag and returns how many times this check has flagged the player recently. */
    public int recordFlag(String check, String info, int nowTick) {
        FlagRecord record = flagLog.computeIfAbsent(check, k -> new FlagRecord());
        record.count++;
        record.lastTick = nowTick;
        record.lastInfo = info;
        return record.count;
    }

    /** Checks this player was flagged for within the window (a copy, safe to loop over). */
    public Map<String, FlagRecord> recentFlags(int nowTick, int windowTicks) {
        flagLog.values().removeIf(record -> nowTick - record.lastTick > windowTicks);
        return new HashMap<>(flagLog);
    }

    /** Rate-limits chat alerts so a single cheater doesn't flood the chat. */
    public boolean canAlert(String check, int nowTick, int cooldownTicks) {
        Integer last = lastAlert.get(check);
        if (last != null && nowTick - last < cooldownTicks) return false;
        lastAlert.put(check, nowTick);
        return true;
    }
}
