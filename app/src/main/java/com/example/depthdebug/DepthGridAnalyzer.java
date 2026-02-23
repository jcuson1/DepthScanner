package com.example.depthdebug;

import android.media.Image;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Fast depth-frame analyzer: splits depth image into a fixed grid and computes robust distance stats.
 *
 * - Uses p10 (10th percentile) as an устойчивый "почти минимум".
 * - Also outputs valid/unknown ratios for drop detection and data-quality gating.
 */
public final class DepthGridAnalyzer {

    public static final class Grid {
        public final int cols;
        public final int rows;

        public final float[] p10m;

        public final float[] p50m;

        public final float[] unknown;

        public final int[] total;

        public final int[] valid;

        public final float quality;

        private Grid(int cols, int rows,
                     float[] p10m, float[] p50m, float[] unknown,
                     int[] total, int[] valid, float quality) {
            this.cols = cols;
            this.rows = rows;
            this.p10m = p10m;
            this.p50m = p50m;
            this.unknown = unknown;
            this.total = total;
            this.valid = valid;
            this.quality = quality;
        }

        public int idx(int col, int row) { return row * cols + col; }
    }

    private final int cols;
    private final int rows;

    // Depth filtering
    private final int minMm;
    private final int maxMm;
    private final int stride;

    // ROI in normalized coords [0..1]
    private final float roiX0, roiX1, roiY0, roiY1;

    // Percentiles
    private final float pNearMin; // e.g. 0.10
    private final float pMedian;  // e.g. 0.50

    public DepthGridAnalyzer(int cols, int rows) {
        this(cols, rows,
                200, 6000,
                4,
                // ROI: cut top sky/ceiling and very edges
                0.06f, 0.94f, 0.12f, 0.98f,
                0.10f, 0.50f);
    }

    public DepthGridAnalyzer(int cols, int rows,
                             int minMm, int maxMm,
                             int stride,
                             float roiX0, float roiX1, float roiY0, float roiY1,
                             float pNearMin, float pMedian) {
        this.cols = cols;
        this.rows = rows;
        this.minMm = minMm;
        this.maxMm = maxMm;
        this.stride = Math.max(1, stride);
        this.roiX0 = clamp01(roiX0);
        this.roiX1 = clamp01(roiX1);
        this.roiY0 = clamp01(roiY0);
        this.roiY1 = clamp01(roiY1);
        this.pNearMin = clamp01(pNearMin);
        this.pMedian = clamp01(pMedian);
    }

    public Grid analyze(Image depth16) {
        int w = depth16.getWidth();
        int h = depth16.getHeight();

        Image.Plane plane = depth16.getPlanes()[0];
        ByteBuffer buf = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();

        int x0 = (int) (roiX0 * w);
        int x1 = (int) (roiX1 * w);
        int y0 = (int) (roiY0 * h);
        int y1 = (int) (roiY1 * h);

        x0 = clampInt(x0, 0, w - 1);
        x1 = clampInt(x1, x0 + 1, w);
        y0 = clampInt(y0, 0, h - 1);
        y1 = clampInt(y1, y0 + 1, h);

        int cells = cols * rows;
        int[] total = new int[cells];
        int[] valid = new int[cells];
        float[] unknown = new float[cells];
        float[] p10m = new float[cells];
        float[] p50m = new float[cells];

        // Temporary buffers for each cell (mm)
        int maxPerCell = ((x1 - x0) / stride + 2) * ((y1 - y0) / stride + 2);
        int[][] samples = new int[cells][maxPerCell];
        int[] counts = new int[cells];

        int totalAll = 0;
        int validAll = 0;

        for (int y = y0; y < y1; y += stride) {
            int rowStart = y * rowStride;

            float ny = (y - y0) / (float) (y1 - y0);
            int gr = (int) (ny * rows);
            if (gr >= rows) gr = rows - 1;

            for (int x = x0; x < x1; x += stride) {
                float nx = (x - x0) / (float) (x1 - x0);
                int gc = (int) (nx * cols);
                if (gc >= cols) gc = cols - 1;
                int cell = gr * cols + gc;

                total[cell]++;
                totalAll++;

                int off = rowStart + x * pixelStride;
                // little-endian 16-bit
                int lo = buf.get(off) & 0xFF;
                int hi = buf.get(off + 1) & 0xFF;
                int dmm = (hi << 8) | lo;

                if (dmm == 0 || dmm < minMm || dmm > maxMm) continue;
                valid[cell]++;
                validAll++;

                int n = counts[cell];
                if (n < maxPerCell) {
                    samples[cell][n] = dmm;
                    counts[cell] = n + 1;
                }
            }
        }

        for (int i = 0; i < cells; i++) {
            int t = total[i];
            int v = valid[i];
            unknown[i] = (t == 0) ? 1f : (1f - (v / (float) t));
            p10m[i] = percentileMeters(samples[i], counts[i], pNearMin);
            p50m[i] = percentileMeters(samples[i], counts[i], pMedian);
        }

        float quality = (totalAll == 0) ? 0f : (validAll / (float) totalAll);
        return new Grid(cols, rows, p10m, p50m, unknown, total, valid, quality);
    }

    private static float percentileMeters(int[] arr, int n, float p) {
        if (n <= 0) return Float.POSITIVE_INFINITY;
        Arrays.sort(arr, 0, n);
        int idx = (int) Math.floor(p * (n - 1));
        idx = Math.max(0, Math.min(n - 1, idx));
        return arr[idx] / 1000f;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}