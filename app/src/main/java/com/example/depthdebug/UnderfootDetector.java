package com.example.depthdebug;

import android.media.Image;

import com.google.ar.core.Frame;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.NotYetAvailableException;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class UnderfootDetector {

    public enum Level { NONE, FAR, NEAR }

    public static class Result {
        public final boolean alert;
        public final Level level;
        public final int[] hotCells;     // индексы 0..8 (3x3)
        public final float minDistanceM; // минимальный p10 среди hot cells (в метрах)
        public final float pitchDeg;
        public final float rollDeg;
        public final float quality;

        // Ожидаемый pitch для "телефон на груди, смотрит вдаль"
        private final float pitchCenter = -70f;   // твой "ноль" позы
        private final float pitchTol = 20f;       // допуск +/- 15°

        public Result(boolean alert, Level level, int[] hotCells, float minDistanceM,
                      float pitchDeg, float rollDeg, float quality) {
            this.alert = alert;
            this.level = level;
            this.hotCells = hotCells;
            this.minDistanceM = minDistanceM;
            this.pitchDeg = pitchDeg;
            this.rollDeg = rollDeg;
            this.quality = quality;
        }
    }
    private final float farM = 2.2f;   // beep если опасность уже близко
    private final float nearM = 1.0f;  // вибро если совсем близко

    private final float obstacleDeltaM = 0.35f;

    // Drop: порог неизвестности в нижнем ряду
    private final float dropUnknownThr = 0.65f;

    // Depth range
    private final int minMm = 200;
    private final int maxMm = 6000;
    private final float minQuality = 0.05f;

    private final int M = 15;
    private final int N = 4;

    private final int[] cells = new int[]{3,4,5,6,7,8};

    private final int[][] hist = new int[6][M];
    private int histIdx = 0;
    private boolean histFilled = false;

    public Result update(Frame frame, OrientationHelper.Orientation ori) {
        if (frame.getCamera().getTrackingState() != TrackingState.TRACKING) {
            return new Result(false, Level.NONE, new int[0], Float.POSITIVE_INFINITY,
                    ori.pitchDeg, ori.rollDeg, 0f);
        }

        if (ori.pitchDeg < 0 && Math.abs(ori.pitchDeg) > 70 ) {
            return new Result(false, Level.NONE, new int[0], Float.POSITIVE_INFINITY,
                    ori.pitchDeg, ori.rollDeg, 0f);
        }

        Image depth = null;
        try {
            depth = frame.acquireDepthImage16Bits();

            CellStats[] stats = compute6Cells(depth);

            int total = 0, valid = 0;
            for (CellStats s : stats) { total += s.total; valid += s.valid; }
            float quality = (total == 0) ? 0f : (valid / (float) total);

            if (quality < minQuality) {
                return new Result(false, Level.NONE, new int[0], Float.POSITIVE_INFINITY,
                        ori.pitchDeg, ori.rollDeg, quality);
            }
            float zFloor = estimateFloor(stats);

            int[] activeThisFrame = new int[6];
            float bestP10 = Float.POSITIVE_INFINITY;
            Level bestLevel = Level.NONE;

            for (int i = 0; i < 6; i++) {
                CellStats s = stats[i];
                int cellId = cells[i];

                boolean obstacle = false;
                boolean drop = false;

                // OBSTACLE: объект должен быть заметно ближе, чем baseline пола
                if (Float.isFinite(zFloor) && Float.isFinite(s.p10M)) {
                    if (s.p10M < (zFloor - obstacleDeltaM)) obstacle = true;
                }

                // DROP: в нижнем ряду неизвестность высокая
                if (cellId >= 6) {
                    if (s.unknownRatio > dropUnknownThr) drop = true;
                }

                boolean cellAlert = obstacle || drop;
                activeThisFrame[i] = cellAlert ? 1 : 0;

                if (cellAlert && s.p10M < bestP10) {
                    bestP10 = s.p10M;
                }
            }

            pushHistory(activeThisFrame);

            boolean[] confirmed = new boolean[6];
            int confirmedCount = 0;

            int K = histFilled ? M : histIdx;
            for (int i = 0; i < 6; i++) {
                int sum = 0;
                for (int k = 0; k < K; k++) sum += hist[i][k];
                confirmed[i] = (sum >= N);
                if (confirmed[i]) confirmedCount++;
            }

            if (confirmedCount == 0) {
                return new Result(false, Level.NONE, new int[0], Float.POSITIVE_INFINITY,
                        ori.pitchDeg, ori.rollDeg, quality);
            }

            // 5) Hot cells + level
            int[] hot = new int[confirmedCount];
            int j = 0;

            float minDist = Float.POSITIVE_INFINITY;
            for (int i = 0; i < 6; i++) {
                if (!confirmed[i]) continue;
                hot[j++] = cells[i];
                minDist = Math.min(minDist, stats[i].p10M);
            }

            Level level = Level.FAR;
            if (minDist < nearM) level = Level.NEAR;
            else if (minDist < farM) level = Level.FAR;
            else {
                if (!Float.isFinite(minDist)) level = Level.FAR;
                else level = Level.NONE;
            }

            return new Result(true, level, hot, minDist, ori.pitchDeg, ori.rollDeg, quality);

        } catch (NotYetAvailableException e) {
            return new Result(false, Level.NONE, new int[0], Float.POSITIVE_INFINITY,
                    ori.pitchDeg, ori.rollDeg, 0f);
        } finally {
            if (depth != null) depth.close();
        }
    }

    private float estimateFloor(CellStats[] stats) {
        float z7 = stats[4].p50M;
        if (Float.isFinite(z7)) return z7;

        // fallback: медиана медиан по 6/7/8
        float[] v = new float[]{stats[3].p50M, stats[4].p50M, stats[5].p50M};
        int n = 0;
        for (float x : v) if (Float.isFinite(x)) v[n++] = x;
        if (n == 0) return Float.POSITIVE_INFINITY;

        Arrays.sort(v, 0, n);
        return v[n / 2];
    }

    private void pushHistory(int[] activeThisFrame6) {
        for (int i = 0; i < 6; i++) {
            hist[i][histIdx] = activeThisFrame6[i];
        }
        histIdx++;
        if (histIdx >= M) {
            histIdx = 0;
            histFilled = true;
        }
    }

    private static class CellStats {
        float p10M;
        float p50M;
        float unknownRatio;
        int total;
        int valid;
    }

    private CellStats[] compute6Cells(Image depth) {
        int w = depth.getWidth();
        int h = depth.getHeight();

        Image.Plane dp = depth.getPlanes()[0];
        ByteBuffer db = dp.getBuffer();
        int dRow = dp.getRowStride();
        int dPix = dp.getPixelStride();

        CellStats[] out = new CellStats[6];
        for (int i = 0; i < 6; i++) out[i] = new CellStats();

        final int stride = 4;

        int maxPerCell = (w/3/stride + 2) * (h/3/stride + 2);
        int[][] buf = new int[6][maxPerCell];
        int[] n = new int[6];

        for (int y = h/3; y < h; y += stride) { // rows 1 and 2
            int row = (y * 3) / h; // 0..2
            if (row == 0) continue;

            int dRowStart = y * dRow;
            for (int x = 0; x < w; x += stride) {
                int col = (x * 3) / w; // 0..2
                int cellIndex = row * 3 + col; // 0..8

                int idx6 = mapCellTo6(cellIndex);
                if (idx6 < 0) continue;

                CellStats cs = out[idx6];
                cs.total++;

                int dOff = dRowStart + x * dPix;
                int lo = db.get(dOff) & 0xFF;
                int hi = db.get(dOff + 1) & 0xFF;
                int dmm = (hi << 8) | lo;

                boolean known = (dmm != 0 && dmm >= minMm && dmm <= maxMm);
                if (!known) continue;

                cs.valid++;
                buf[idx6][n[idx6]++] = dmm;
            }
        }

        for (int i = 0; i < 6; i++) {
            CellStats cs = out[i];
            cs.unknownRatio = (cs.total == 0) ? 1f : (1f - (cs.valid / (float) cs.total));
            cs.p10M = percentileMeters(buf[i], n[i], 0.10f);
            cs.p50M = percentileMeters(buf[i], n[i], 0.50f);
        }

        return out;
    }

    private float percentileMeters(int[] arr, int n, float p) {
        if (n <= 0) return Float.POSITIVE_INFINITY;
        Arrays.sort(arr, 0, n);
        int idx = (int) Math.floor(p * (n - 1));
        int mm = arr[Math.max(0, Math.min(n - 1, idx))];
        return mm / 1000f;
    }

    private int mapCellTo6(int cell) {
        switch (cell) {
            case 3: return 0;
            case 4: return 1;
            case 5: return 2;
            case 6: return 3;
            case 7: return 4;
            case 8: return 5;
            default: return -1;
        }
    }
}