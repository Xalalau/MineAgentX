package com.mineagent.engine.behavior;

import java.util.Random;

/**
 * Behavioral noise layer — makes AI actions feel more human and less mechanical.
 *
 * <p>Key differences between human players and bots (from anti-cheat research):
 * <ul>
 *   <li>Action intervals vary (machines are precise to the millisecond)</li>
 *   <li>Turning follows smooth curves rather than linear steps (bots snap instantly)</li>
 *   <li>Complex situations cause hesitation (bots maintain constant speed)</li>
 *   <li>Small mistakes are sometimes made and then corrected (bots execute perfectly)</li>
 * </ul>
 *
 * <p>This wrapper adds human-like variation to higher-level decisions:
 * <ul>
 *   <li>{@link #jitterInterval} — add Gaussian jitter to action intervals</li>
 *   <li>{@link #easeInOutTurn} — smooth turns with an ease-in-out curve</li>
 *   <li>{@link #maybeHesitate} — probabilistically introduce a thinking pause</li>
 *   <li>{@link #maybeMistake} — probabilistically introduce a small mistake and correction</li>
 * </ul>
 *
 * <p>All methods are stateless static utilities and safe for concurrent calls.
 */
public final class HumanLikeNoise {

    private HumanLikeNoise() {}

    private static final Random RNG = new Random();

    /** Standard deviation of Gaussian jitter (±15% of the interval). */
    private static final double INTERVAL_JITTER_STD = 0.15;

    /** Probability of a thinking pause (3%). */
    private static final double HESITATE_PROB = 0.03;

    /** Probability of a small mistake (1.5%). */
    private static final double MISTAKE_PROB = 0.015;

    /**
     * Add ±15% Gaussian jitter to a base interval.
     *
     * @param baseIntervalMs base interval in milliseconds
     * @return interval with noise applied
     */
    public static long jitterInterval(long baseIntervalMs) {
        double noise = RNG.nextGaussian() * INTERVAL_JITTER_STD;
        double adjusted = baseIntervalMs * (1.0 + noise);
        return Math.max(1, (long) adjusted);
    }

    /**
     * Ease-in-out curve for smooth turning.
     *
     * <p>Movement currently turns linearly at 15°/tick, which looks robotic.
     * Human turns start quickly and slow as they approach the target angle.
     * This method maps linear progress (0→1) onto an ease-in-out curve,
     * making the beginning and end of a turn smoother.
     *
     * @param linearProgress linear progress [0, 1]
     * @return ease-in-out adjusted progress [0, 1]
     */
    public static float easeInOutTurn(float linearProgress) {
        // Classic ease-in-out: 3*t^2 - 2*t^3 (smoothstep)
        float p = clamp(linearProgress, 0f, 1f);
        return p * p * (3f - 2f * p);
    }

    /**
     * Calculate how many degrees to turn this tick from the remaining angle.
     *
     * <p>Slow down near the target (ease-out), turn quickly farther away (ease-in).
     * This simulates a mouse flick: turn quickly toward the general direction, then fine-tune.
     *
     * @param remainingDiff remaining angle in degrees, wrapped to -180~180
     * @param maxPerTick maximum turn angle per tick
     * @return actual turn angle for this tick
     */
    public static float adaptiveTurn(float remainingDiff, float maxPerTick) {
        float absDiff = Math.abs(remainingDiff);
        if (absDiff < 0.5f) return 0;

        // Turn quickly at large differences (full speed), slow down near the target
        // Use full speed when the angle difference is > 45°
        // Ease out when the angle difference is < 45°
        float speedRatio;
        if (absDiff > 45f) {
            speedRatio = 1.0f;
        } else {
            // ease-out: (diff/45)^0.5; the square root makes deceleration more natural
            float t = absDiff / 45f;
            speedRatio = (float) Math.sqrt(t);
        }

        float turn = Math.signum(remainingDiff) * Math.min(maxPerTick, absDiff * speedRatio);
        // Add tiny jitter (human hands are never perfectly steady)
        turn += (RNG.nextFloat() - 0.5f) * 0.8f;
        return turn;
    }

    /**
     * Probabilistically decide whether a hesitation pause is needed.
     *
     * <p>Human players briefly pause to think in complex situations.
     * Returns true about 3% of the time; callers should inject a 200-500ms delay.
     *
     * @return true when a brief hesitation should occur
     */
    public static boolean maybeHesitate() {
        return RNG.nextDouble() < HESITATE_PROB;
    }

    /**
     * Duration of a hesitation pause (200-500ms).
     */
    public static int hesitationDurationMs() {
        return 200 + RNG.nextInt(301); // 200-500ms
    }

    /**
     * Probabilistically decide whether to make a small mistake and correction.
     *
     * <p>Returns true about 1.5% of the time; callers should inject a small mistake
     * (such as mining the wrong block or walking one step too far), then correct it immediately.
     * This helps avoid the robotic impression of perfect execution.
     *
     * @return true when a small mistake should occur
     */
    public static boolean maybeMistake() {
        return RNG.nextDouble() < MISTAKE_PROB;
    }

    /**
     * Add tiny movement-speed jitter to simulate human hand tremor.
     *
     * @param baseSpeed base speed [0, 1]
     * @return speed with noise applied (±5%)
     */
    public static float jitterSpeed(float baseSpeed) {
        // Zero is an intentional stop while pathing turns in place. Adding
        // positive noise to zero makes the companion walk in its old facing
        // direction and can push it off an edge before alignment is safe.
        if (baseSpeed <= 0.0f) return 0.0f;
        float noise = (float) (RNG.nextGaussian() * 0.05);
        return clamp(baseSpeed + noise, 0f, 1f);
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
