package dev.obsidian.core.ml;

/**
 * A scorer over a fixed-length feature vector. The only thing the rest of the
 * plugin needs from the ML layer: turn features into a cheat probability in
 * [0, 1]. Kept as an interface so the logistic model can later be swapped for a
 * tree ensemble without touching the check that consumes it.
 */
public interface Model {

    /** Cheat probability in [0, 1] for one feature vector of length {@link Features#COUNT}. */
    double score(double[] features);

    /** Short identifier for logging ("logistic v1"). */
    String id();
}
