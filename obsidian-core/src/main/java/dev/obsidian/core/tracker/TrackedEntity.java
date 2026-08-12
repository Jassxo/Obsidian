package dev.obsidian.core.tracker;

/**
 * One entity as a single client sees it: its hitbox and a short history of
 * positions, each stamped with the nanotime the movement packet was sent to
 * that client. Preallocated arrays only; nothing is allocated as positions flow.
 *
 * <p>The history exists for one reason: <b>lag compensation without false
 * positives</b>. When a player attacks, the target may have been anywhere along
 * its recent path from the attacker's point of view, so reach is measured as the
 * <i>minimum</i> distance across every retained position inside the plausible
 * latency window. That always gives the player the benefit of the doubt — a
 * genuinely long reach still reads long, but a laggy legit hit never does.</p>
 */
public final class TrackedEntity {

    /** Positions kept per entity. Covers well over a second of movement at 20 tps. */
    private static final int HISTORY = 24;

    public enum Kind { PLAYER, OTHER }

    private final int entityId;
    private final Kind kind;
    private final double halfWidth;
    private final double height;

    private final long[] nanos = new long[HISTORY];
    private final double[] x = new double[HISTORY];
    private final double[] y = new double[HISTORY];
    private final double[] z = new double[HISTORY];
    private int head;
    private int size;

    // Latest absolute position, the anchor that relative-move deltas add onto.
    private double lastX;
    private double lastY;
    private double lastZ;

    public TrackedEntity(int entityId, Kind kind, double width, double height,
                         double x, double y, double z, long nanos) {
        this.entityId = entityId;
        this.kind = kind;
        this.halfWidth = width / 2.0;
        this.height = height;
        teleport(x, y, z, nanos);
    }

    public int entityId() {
        return entityId;
    }

    public Kind kind() {
        return kind;
    }

    /** Absolute reposition (spawn / teleport / position-sync). */
    public void teleport(double newX, double newY, double newZ, long now) {
        lastX = newX;
        lastY = newY;
        lastZ = newZ;
        push(newX, newY, newZ, now);
    }

    /** Relative move: server deltas are already decoded to blocks by the caller. */
    public void move(double dx, double dy, double dz, long now) {
        teleport(lastX + dx, lastY + dy, lastZ + dz, now);
    }

    private void push(double px, double py, double pz, long now) {
        nanos[head] = now;
        x[head] = px;
        y[head] = py;
        z[head] = pz;
        head = (head + 1) % HISTORY;
        if (size < HISTORY) {
            size++;
        }
    }

    /**
     * Minimum eye→hitbox distance across every retained position not older than
     * {@code sinceNanos}. The newest position is always considered even if the
     * window would otherwise exclude everything.
     *
     * @return distance in blocks, or {@link Double#MAX_VALUE} if no history
     */
    public double minReachDistance(double eyeX, double eyeY, double eyeZ, long sinceNanos) {
        double best = Double.MAX_VALUE;
        boolean any = false;
        for (int i = 0; i < size; i++) {
            int idx = (head - 1 - i + HISTORY * 2) % HISTORY;
            if (i > 0 && nanos[idx] < sinceNanos) {
                break; // history is newest-first; older samples are out of the window
            }
            any = true;
            best = Math.min(best, distanceToHitbox(eyeX, eyeY, eyeZ, x[idx], y[idx], z[idx]));
        }
        return any ? best : Double.MAX_VALUE;
    }

    /** Distance from a point to this entity's axis-aligned hitbox at (px,py,pz). */
    private double distanceToHitbox(double eyeX, double eyeY, double eyeZ,
                                    double px, double py, double pz) {
        double dx = axisGap(eyeX, px - halfWidth, px + halfWidth);
        double dy = axisGap(eyeY, py, py + height);
        double dz = axisGap(eyeZ, pz - halfWidth, pz + halfWidth);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double axisGap(double v, double min, double max) {
        if (v < min) {
            return min - v;
        }
        if (v > max) {
            return v - max;
        }
        return 0.0;
    }

    /** Most recent center-of-hitbox coordinates, for angle-to-target. */
    public double centerX() {
        return lastX;
    }

    public double centerY() {
        return lastY + height / 2.0;
    }

    public double centerZ() {
        return lastZ;
    }
}
