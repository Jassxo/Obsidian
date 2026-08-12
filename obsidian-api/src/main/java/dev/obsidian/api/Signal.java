package dev.obsidian.api;

/**
 * A single piece of evidence emitted by a check.
 *
 * <p>A signal on its own never means a player is cheating. Signals are combined by the
 * confidence engine, weighted by each check's configured likelihood ratio, and decay
 * over time. Consumers should treat {@link #strength()} as "how unusual was this one
 * observation", not as a verdict.</p>
 *
 * @param checkId  stable identifier of the emitting check, e.g. {@code spawn-reaction}
 * @param strength normalized signal strength in {@code [0.0, 1.0]}
 * @param evidence human-readable description of the raw measurement behind the signal,
 *                 e.g. {@code "reaction 41ms comp (ping 23ms), sigma 6.2ms over 15"}
 * @param timestampMillis wall-clock time the signal was produced
 */
public record Signal(String checkId, double strength, String evidence, long timestampMillis) {

    public Signal {
        if (strength < 0.0 || strength > 1.0) {
            throw new IllegalArgumentException("strength out of range: " + strength);
        }
    }
}
