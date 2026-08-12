package dev.obsidian.core.tracker;

/**
 * Ring buffer of the player's last N rotations, stored as parallel primitive
 * arrays (no per-rotation objects; this is written on every flying packet).
 */
public final class RotationHistory {

    public static final int DEFAULT_CAPACITY = 40;

    private final float[] yaw;
    private final float[] pitch;
    private final float[] deltaYaw;
    private final float[] deltaPitch;
    private final long[] nanoTime;
    private int head;
    private int size;

    public RotationHistory(int capacity) {
        yaw = new float[capacity];
        pitch = new float[capacity];
        deltaYaw = new float[capacity];
        deltaPitch = new float[capacity];
        nanoTime = new long[capacity];
    }

    public void add(float newYaw, float newPitch, long nanos) {
        float dYaw = 0f;
        float dPitch = 0f;
        if (size > 0) {
            int prev = (head - 1 + yaw.length) % yaw.length;
            dYaw = wrapDegrees(newYaw - yaw[prev]);
            dPitch = newPitch - pitch[prev];
        }
        yaw[head] = newYaw;
        pitch[head] = newPitch;
        deltaYaw[head] = dYaw;
        deltaPitch[head] = dPitch;
        nanoTime[head] = nanos;
        head = (head + 1) % yaw.length;
        if (size < yaw.length) {
            size++;
        }
    }

    public int size() {
        return size;
    }

    private int physical(int index) {
        return (head - 1 - index + yaw.length * 2) % yaw.length;
    }

    /** index 0 = most recent. */
    public float yaw(int index) {
        return yaw[physical(index)];
    }

    public float pitch(int index) {
        return pitch[physical(index)];
    }

    public float deltaYaw(int index) {
        return deltaYaw[physical(index)];
    }

    public float deltaPitch(int index) {
        return deltaPitch[physical(index)];
    }

    public long nanos(int index) {
        return nanoTime[physical(index)];
    }

    public static float wrapDegrees(float deg) {
        deg = deg % 360.0f;
        if (deg >= 180.0f) {
            deg -= 360.0f;
        }
        if (deg < -180.0f) {
            deg += 360.0f;
        }
        return deg;
    }
}
