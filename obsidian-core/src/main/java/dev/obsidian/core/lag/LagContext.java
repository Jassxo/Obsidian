package dev.obsidian.core.lag;

/**
 * Snapshot of everything a check needs to compensate for lag. One mutable
 * instance lives inside each PlayerData and is refreshed before check dispatch;
 * checks must never do a timing comparison without going through this.
 */
public final class LagContext {

    private int pingMillis;
    private double mspt;
    private boolean recentTeleport;
    private boolean recentRespawn;
    private boolean serverLagging;

    public void refresh(int pingMillis, double mspt, boolean recentTeleport,
                        boolean recentRespawn, double maxMspt) {
        this.pingMillis = pingMillis;
        this.mspt = mspt;
        this.recentTeleport = recentTeleport;
        this.recentRespawn = recentRespawn;
        this.serverLagging = mspt > maxMspt;
    }

    /** Checks must skip evaluation entirely when this is true. */
    public boolean shouldSkip() {
        return serverLagging || recentTeleport || recentRespawn;
    }

    public int pingMillis() {
        return pingMillis;
    }

    public double mspt() {
        return mspt;
    }

    /**
     * Converts a raw wall-clock delta into a lag-compensated one: subtracts the
     * player's median ping and normalizes for server tick stretch. When the
     * server runs slow, packets bunch up and intervals shrink artificially, so
     * we scale measurements back up toward what a 50 MSPT server would see.
     */
    public double compensateMillis(double rawDeltaMillis) {
        double pingAdjusted = rawDeltaMillis - pingMillis;
        double tickScale = mspt > 50.0 ? mspt / 50.0 : 1.0;
        return pingAdjusted * tickScale;
    }

    /**
     * Compensation for intervals between two packets from the SAME player.
     * Ping delays both endpoints equally and cancels out, so subtracting it
     * would over-correct; only tick stretch is normalized here.
     */
    public double compensateIntervalMillis(double rawDeltaMillis) {
        double tickScale = mspt > 50.0 ? mspt / 50.0 : 1.0;
        return rawDeltaMillis * tickScale;
    }
}
