package dev.obsidian.core.ml;

/**
 * The canonical feature layout, shared by the extractor, the model and the
 * offline trainer so all three agree on order and meaning. One decision window
 * of a player's combat produces exactly these {@link #COUNT} numbers.
 *
 * <p>Each feature is engineered from the same signals the deterministic checks
 * use, which is what keeps the model interpretable: a flag can always be read
 * back as "these features, this far from normal".</p>
 */
public final class Features {

    private Features() {
    }

    // Indices — keep in sync with NAMES and the shipped model.dat.
    public static final int REACH_MEAN_EXCESS = 0;   // mean (reach - allowed limit), blocks
    public static final int REACH_VIOL_RATIO = 1;    // fraction of hits past limit+slop
    public static final int ANGLE_MEAN = 2;          // mean crosshair-to-target angle, deg
    public static final int ANGLE_VIOL_RATIO = 3;    // fraction of hits far off-target
    public static final int CLICK_MEAN_MS = 4;       // mean in-combat click interval
    public static final int CLICK_SIGMA_MS = 5;      // its std-dev (low = machine)
    public static final int CLICK_AUTOCORR = 6;      // lag-1 autocorrelation (high = fixed delay)
    public static final int AIM_MICRO_FRACTION = 7;  // fraction of moves that are human micro-jitter (low = cheat)
    public static final int MAX_DISTINCT_TARGETS = 8; // most players hit within the aura window
    public static final int MACE_COMBO_SIGMA_MS = 9; // std-dev of wind-charge->mace timing (low = macro)

    public static final int COUNT = 10;

    public static final String[] NAMES = {
            "reach_mean_excess",
            "reach_viol_ratio",
            "angle_mean",
            "angle_viol_ratio",
            "click_mean_ms",
            "click_sigma_ms",
            "click_autocorr",
            "aim_micro_fraction",
            "max_distinct_targets",
            "mace_combo_sigma_ms"
    };

    /**
     * Neutral defaults used when a window produced no evidence for a feature.
     * These mirror the bootstrap model's per-feature means, so a missing feature
     * standardizes to zero and contributes nothing rather than nudging the score.
     */
    public static final double[] NEUTRAL = {
            -0.2,  // reach excess (typical legit hit lands inside the limit)
            0.03,  // reach violations
            18.0,  // angle mean (typical legit)
            0.03,  // angle violations
            160.0, // click mean ms
            45.0,  // click sigma ms
            0.05,  // click autocorr
            0.35,  // micro-jitter fraction (humans jitter a lot)
            1.0,   // distinct targets
            150.0  // mace combo sigma
    };
}
