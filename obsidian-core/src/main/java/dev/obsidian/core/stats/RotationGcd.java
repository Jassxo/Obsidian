package dev.obsidian.core.stats;

/**
 * Running GCD of rotation deltas, Grim-style.
 *
 * <p>Vanilla mouse input quantizes camera movement: every yaw/pitch delta is an
 * integer multiple of a per-sensitivity constant {@code f^3 * 0.15} (with
 * {@code f = sensitivity * 0.6 + 0.2}). We work in fixed-point: deltas are scaled
 * by {@code 2^24} and truncated to longs, then a running GCD is maintained. A
 * rotation that is not a multiple of the established GCD did not come from the
 * mouse — silent aim and rotation spoofs produce these.</p>
 *
 * <p>Known legit GCD breakers: sensitivity change mid-fight, cinematic camera,
 * some controller mods. That is why the consuming check is experimental and
 * only cares about breaks that coincide with crystal actions.</p>
 */
public final class RotationGcd {

    /** Fixed-point scale; matches the precision Grim found workable. */
    public static final double SCALE = 1 << 24;

    /** Deltas below this (in degrees) are float noise, not real input. */
    private static final double NOISE_FLOOR = 0.0001;

    private long runningGcd;
    private int samples;

    /**
     * Feed one absolute rotation delta (degrees). Returns true if the delta was
     * usable (above noise floor).
     */
    public boolean add(double delta) {
        delta = Math.abs(delta);
        if (delta < NOISE_FLOOR) {
            return false;
        }
        long fixed = (long) (delta * SCALE);
        if (fixed <= 0) {
            return false;
        }
        runningGcd = runningGcd == 0 ? fixed : gcd(runningGcd, fixed);
        samples++;
        return true;
    }

    /**
     * Whether a delta is consistent with the established quantum. Only meaningful
     * once enough samples exist ({@link #samples()}).
     *
     * @param toleranceUnits allowed remainder in fixed-point units, absorbs float error
     */
    public boolean isConsistent(double delta, long toleranceUnits) {
        delta = Math.abs(delta);
        if (delta < NOISE_FLOOR || runningGcd <= 0) {
            return true;
        }
        long fixed = (long) (delta * SCALE);
        long remainder = fixed % runningGcd;
        long distance = Math.min(remainder, runningGcd - remainder);
        return distance <= toleranceUnits;
    }

    public long gcdValue() {
        return runningGcd;
    }

    public int samples() {
        return samples;
    }

    /**
     * A GCD collapsing toward zero means the "quantum" is below real mouse
     * precision; treat the estimate as unusable (e.g. after sensitivity changes).
     */
    public boolean isEstimateUsable() {
        // 0.001 degrees in fixed point; real sensitivities sit well above this.
        return runningGcd > (long) (0.001 * SCALE);
    }

    public void reset() {
        runningGcd = 0;
        samples = 0;
    }

    public static long gcd(long a, long b) {
        while (b != 0) {
            long t = b;
            b = a % b;
            a = t;
        }
        return a;
    }
}
