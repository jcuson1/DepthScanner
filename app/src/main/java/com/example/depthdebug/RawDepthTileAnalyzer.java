package com.example.depthdebug;

import android.media.Image;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;

public final class RawDepthTileAnalyzer {

    public static final class Grid {
        public final int cols, rows;
        public final float[] p20m;        // robust near distance per tile
        public final float[] p50m;        // median
        public final float[] validRatio;  // 0..1
        public final float[] unknownRatio;// 0..1
        public final float quality;       // global valid ratio over ROI

        Grid(int cols, int rows, float[] p20m, float[] p50m, float[] validRatio, float[] unknownRatio, float quality) {
            this.cols = cols; this.rows = rows;
            this.p20m = p20m; this.p50m = p50m;
            this.validRatio = validRatio; this.unknownRatio = unknownRatio;
            this.quality = quality;
        }

        public int idx(int c, int r) { return r * cols + c; }
    }

    private final int cols;
    private final int rows;

    // ROI normalized
    private final float roiX0, roiX1, roiY0, roiY1;

    // Filters
    private final int confMin;   // 0..255
    private final int minMm;
    private final int maxMm;

    // Sampling stride
    private final int stride;

    // Per-tile sample reservoir
    private static final int MAX_SAMPLES_PER_TILE = 48;
    private final int[][] samples; // [tile][k]
    private final int[] counts;
    private final Random rng = new Random(1);

    // Output buffers (reused)
    private final float[] p20m;
    private final float[] p50m;
    private final float[] validRatio;
    private final float[] unknownRatio;

    public RawDepthTileAnalyzer(int cols, int rows) {
        this(cols, rows,
                0.06f, 0.94f, 0.12f, 0.98f,
                120, 250, 6000,
                4);
    }

    public RawDepthTileAnalyzer(int cols, int rows,
                                float roiX0, float roiX1, float roiY0, float roiY1,
                                int confMin, int minMm, int maxMm,
                                int stride) {
        this.cols = cols;
        this.rows = rows;
        this.roiX0 = roiX0; this.roiX1 = roiX1;
        this.roiY0 = roiY0; this.roiY1 = roiY1;
        this.confMin = confMin;
        this.minMm = minMm;
        this.maxMm = maxMm;
        this.stride = Math.max(1, stride);

        int tiles = cols * rows;
        samples = new int[tiles][MAX_SAMPLES_PER_TILE];
        counts = new int[tiles];

        p20m = new float[tiles];
        p50m = new float[tiles];
        validRatio = new float[tiles];
        unknownRatio = new float[tiles];
    }

    public Grid analyze(Image rawDepth16, Image confidence8) {
        final int w = rawDepth16.getWidth();
        final int h = rawDepth16.getHeight();

        // Depth
        Image.Plane dp = rawDepth16.getPlanes()[0];
        ByteBuffer db = dp.getBuffer();
        int dRowStride = dp.getRowStride();
        int dPixelStride = dp.getPixelStride(); // usually 2

        // Conf
        Image.Plane cp = confidence8.getPlanes()[0];
        ByteBuffer cb = cp.getBuffer();
        int cRowStride = cp.getRowStride();
        int cPixelStride = cp.getPixelStride(); // usually 1

        int x0 = clamp((int)(roiX0 * w), 0, w - 1);
        int x1 = clamp((int)(roiX1 * w), x0 + 1, w);
        int y0 = clamp((int)(roiY0 * h), 0, h - 1);
        int y1 = clamp((int)(roiY1 * h), y0 + 1, h);

        Arrays.fill(counts, 0);

        int tiles = cols * rows;
        int[] total = new int[tiles];
        int[] valid = new int[tiles];

        int totalAll = 0;
        int validAll = 0;

        for (int y = y0; y < y1; y += stride) {
            float ny = (y - y0) / (float)(y1 - y0);
            int tr = Math.min(rows - 1, (int)(ny * rows));

            int dRow = y * dRowStride;
            int cRow = y * cRowStride;

            for (int x = x0; x < x1; x += stride) {
                float nx = (x - x0) / (float)(x1 - x0);
                int tc = Math.min(cols - 1, (int)(nx * cols));
                int ti = tr * cols + tc;

                total[ti]++; totalAll++;

                // read confidence
                int cOff = cRow + x * cPixelStride;
                int conf = cb.get(cOff) & 0xFF;

                // read depth mm (LE)
                int dOff = dRow + x * dPixelStride;
                int lo = db.get(dOff) & 0xFF;
                int hi = db.get(dOff + 1) & 0xFF;
                int dmm = (hi << 8) | lo;

                if (conf < confMin || dmm == 0 || dmm < minMm || dmm > maxMm) {
                    continue;
                }

                valid[ti]++; validAll++;

                // Reservoir into small sample buffer
                int n = counts[ti];
                if (n < MAX_SAMPLES_PER_TILE) {
                    samples[ti][n] = dmm;
                    counts[ti] = n + 1;
                } else {
                    int j = rng.nextInt(n + 1);
                    if (j < MAX_SAMPLES_PER_TILE) samples[ti][j] = dmm;
                    counts[ti] = n + 1;
                }
            }
        }

        for (int i = 0; i < tiles; i++) {
            int t = total[i];
            int v = valid[i];
            validRatio[i] = (t == 0) ? 0f : (v / (float)t);
            unknownRatio[i] = (t == 0) ? 1f : (1f - validRatio[i]);

            int n = Math.min(counts[i], MAX_SAMPLES_PER_TILE);
            if (n <= 0) {
                p20m[i] = Float.POSITIVE_INFINITY;
                p50m[i] = Float.POSITIVE_INFINITY;
            } else {
                Arrays.sort(samples[i], 0, n);
                p20m[i] = samples[i][(int)Math.floor(0.20f * (n - 1))] / 1000f;
                p50m[i] = samples[i][(int)Math.floor(0.50f * (n - 1))] / 1000f;
            }
        }

        float quality = (totalAll == 0) ? 0f : (validAll / (float)totalAll);
        return new Grid(cols, rows, p20m, p50m, validRatio, unknownRatio, quality);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}