package dev.ad.tv;

// shortcut: translation only, no rotation or rolling shutter; upgrade to feature tracking if a mast twists.
final class Shake {
    static final int W = 256, H = 144;
    private static final int RANGE = 12;
    private static final float FOLLOW = 0.08f;

    private float[] cols, rows;
    private float rawX, rawY, smoothX, smoothY;

    float[] next(int[] argb) {
        float[] c = new float[W], r = new float[H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int p = argb[y * W + x];
                float luma = ((p >> 16 & 255) * 2 + (p >> 8 & 255) * 5 + (p & 255)) / 8f;
                c[x] += luma;
                r[y] += luma;
            }
        }
        if (cols != null) {
            rawX += shift(cols, c, RANGE);
            rawY += shift(rows, r, RANGE);
        }
        cols = c;
        rows = r;
        smoothX += FOLLOW * (rawX - smoothX);
        smoothY += FOLLOW * (rawY - smoothY);
        return new float[] {smoothX - rawX, smoothY - rawY};
    }

    // Profiles lose their mean so a camera exposure change does not read as motion.
    static float shift(float[] prev, float[] cur, int range) {
        float mp = mean(prev), mc = mean(cur);
        float[] cost = new float[2 * range + 1];
        for (int d = -range; d <= range; d++) {
            float sum = 0;
            int n = 0;
            for (int i = Math.max(0, d); i < Math.min(cur.length, prev.length + d); i++, n++) {
                sum += Math.abs(cur[i] - mc - prev[i - d] + mp);
            }
            cost[d + range] = sum / n;
        }
        // Ties keep zero, so a flat or dark frame does not drift.
        int best = 0;
        for (int d = -range; d <= range; d++) if (cost[d + range] < cost[best + range]) best = d;
        if (best == -range || best == range) return best;
        float a = cost[best + range - 1], b = cost[best + range], c = cost[best + range + 1];
        float den = a - 2 * b + c;
        return den > 0 ? best + (a - c) / (2 * den) : best;
    }

    static float clamp(float shift, float zoom, float size) {
        float max = (zoom - 1) * size / 2;
        return Math.max(-max, Math.min(max, shift));
    }

    private static float mean(float[] v) {
        float s = 0;
        for (float x : v) s += x;
        return s / v.length;
    }
}
